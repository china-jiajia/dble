package com.actiontech.dble.meta;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.datasource.PhysicalDBNode;
import com.actiontech.dble.backend.datasource.PhysicalDBPool;
import com.actiontech.dble.backend.mysql.view.FileSystemRepository;
import com.actiontech.dble.backend.mysql.view.KVStoreRepository;
import com.actiontech.dble.backend.mysql.view.Repository;
import com.actiontech.dble.config.ServerConfig;
import com.actiontech.dble.config.loader.zkprocess.comm.ZkConfig;
import com.actiontech.dble.config.loader.zkprocess.comm.ZkParamCfg;
import com.actiontech.dble.config.loader.zkprocess.zookeeper.process.DDLInfo;
import com.actiontech.dble.config.model.DBHostConfig;
import com.actiontech.dble.config.model.SchemaConfig;
import com.actiontech.dble.config.model.SystemConfig;
import com.actiontech.dble.config.model.TableConfig;
import com.actiontech.dble.log.alarm.AlarmCode;
import com.actiontech.dble.meta.protocol.StructureMeta;
import com.actiontech.dble.meta.table.AbstractTableMetaHandler;
import com.actiontech.dble.meta.table.MetaHelper;
import com.actiontech.dble.meta.table.MetaHelper.IndexType;
import com.actiontech.dble.meta.table.SchemaMetaHandler;
import com.actiontech.dble.meta.table.TableMetaCheckHandler;
import com.actiontech.dble.plan.node.QueryNode;
import com.actiontech.dble.server.util.SchemaUtil;
import com.actiontech.dble.server.util.SchemaUtil.SchemaInfo;
import com.actiontech.dble.util.KVPathUtil;
import com.actiontech.dble.util.StringUtil;
import com.actiontech.dble.util.ZKUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlPrimaryKey;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableChangeColumn;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableModifyColumn;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ProxyMetaManager {
    protected static final Logger LOGGER = LoggerFactory.getLogger(ProxyMetaManager.class);
    /* catalog,table,tablemeta */
    private final Map<String, SchemaMeta> catalogs;
    private final Set<String> lockTables;
    private ReentrantLock metaLock = new ReentrantLock();
    private Condition condRelease = metaLock.newCondition();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> checkTaskHandler;
    private AtomicInteger metaCount = new AtomicInteger(0);
    private Repository repository = null;
    private AtomicInteger version = new AtomicInteger(0);

    public ProxyMetaManager() {
        this.catalogs = new ConcurrentHashMap<>();
        this.lockTables = new HashSet<>();
    }

    private String genLockKey(String schema, String tbName) {
        return schema + "." + tbName;
    }

    public int getMetaCount() {
        return metaCount.get();
    }

    public ReentrantLock getMetaLock() {
        return metaLock;
    }

    public void addMetaLock(String schema, String tbName) throws InterruptedException {
        metaLock.lock();
        try {
            metaCount.incrementAndGet();
            version.incrementAndGet();
            String lockKey = genLockKey(schema, tbName);
            while (lockTables.contains(lockKey)) {
                condRelease.await();
            }
            lockTables.add(lockKey);
        } finally {
            metaLock.unlock();
        }
    }

    public boolean isMetaLocked(String schema, String tbName) {
        metaLock.lock();
        try {
            String lockKey = genLockKey(schema, tbName);
            return lockTables.contains(lockKey);
        } finally {
            metaLock.unlock();
        }
    }

    public void removeMetaLock(String schema, String tbName) {
        metaLock.lock();
        try {
            lockTables.remove(genLockKey(schema, tbName));
            metaCount.decrementAndGet();
            condRelease.signalAll();
        } finally {
            metaLock.unlock();
        }
    }

    public Map<String, SchemaMeta> getCatalogs() {
        return catalogs;
    }


    public boolean createDatabase(String schema) {
        SchemaMeta schemaMeta = catalogs.get(schema);
        if (schemaMeta == null) {
            schemaMeta = new SchemaMeta();
            catalogs.put(schema, schemaMeta);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Checking the existence of the database in which the view is to be created
     */
    private boolean checkDbExists(String schema) {
        return schema != null && this.catalogs.containsKey(schema);
    }

    private boolean checkTableExists(String schema, String strTable) {
        return checkDbExists(schema) && strTable != null && this.catalogs.get(schema).getTableMetas().containsKey(strTable);
    }

    public void addTable(String schema, StructureMeta.TableMeta tm) {
        String tbName = tm.getTableName();
        SchemaMeta schemaMeta = catalogs.get(schema);
        if (schemaMeta != null) {
            schemaMeta.addTableMeta(tbName, tm);
        }
    }


    private void dropTable(String schema, String tbName) {
        SchemaMeta schemaMeta = catalogs.get(schema);
        if (schemaMeta != null)
            schemaMeta.dropTable(tbName);
    }

    /**
     * In fact, it only have single table
     */
    private void dropTable(String schema, String sql, SQLDropTableStatement statement, boolean isSuccess, boolean needNotifyOther) {
        for (SQLExprTableSource table : statement.getTableSources()) {
            SchemaInfo schemaInfo = getSchemaInfo(schema, table);
            try {
                if (!isSuccess) {
                    return;
                }
                dropTable(schemaInfo.getSchema(), schemaInfo.getTable());
            } catch (Exception e) {
                LOGGER.info("updateMetaData failed,sql is" + statement.toString(), e);
            } finally {
                removeMetaLock(schemaInfo.getSchema(), schemaInfo.getTable());
                if (DbleServer.getInstance().isUseZK()) {
                    try {
                        notifyClusterDDL(schemaInfo.getSchema(), schemaInfo.getTable(), sql, isSuccess ? DDLInfo.DDLStatus.SUCCESS : DDLInfo.DDLStatus.FAILED, needNotifyOther);
                    } catch (Exception e) {
                        LOGGER.warn(AlarmCode.CORE_CLUSTER_WARN + "notifyClusterDDL error", e);
                    }
                }
            }
        }
    }

    public StructureMeta.TableMeta getSyncTableMeta(String schema, String tbName) {
        while (true) {
            int oldVersion = version.get();
            if (metaCount.get() == 0) {
                StructureMeta.TableMeta meta = getTableMeta(schema, tbName);
                if (version.get() == oldVersion) {
                    return meta;
                }
            } else {
                metaLock.lock();
                try {
                    if (lockTables.contains(genLockKey(schema, tbName))) {
                        LOGGER.warn("schema:" + schema + ", table:" + tbName + " is doing ddl,Waiting for table metadata lock");
                        condRelease.await();
                    } else {
                        return getTableMeta(schema, tbName);
                    }
                } catch (InterruptedException e) {
                    return null;
                } finally {
                    metaLock.unlock();
                }
            }
        }
    }


    public QueryNode getSyncView(String schema, String vName) {
        while (true) {
            metaLock.lock();
            try {
                if (lockTables.contains(genLockKey(schema, vName))) {
                    LOGGER.info("schema:" + schema + ", view:" + vName + " is doing ddl,Waiting for table metadata lock");
                    condRelease.await();
                } else {
                    return catalogs.get(schema).getView(vName);
                }
            } catch (InterruptedException e) {
                return null;
            } finally {
                metaLock.unlock();
            }
        }
    }

    private StructureMeta.TableMeta getTableMeta(String schema, String tbName) {
        return catalogs.get(schema).getTableMeta(tbName);
    }


    private Set<String> getSelfNodes(ServerConfig config) {
        Set<String> selfNode = null;
        for (Map.Entry<String, PhysicalDBPool> entry : config.getDataHosts().entrySet()) {
            PhysicalDBPool host = entry.getValue();
            DBHostConfig wHost = host.getSource().getConfig();
            if (("localhost".equalsIgnoreCase(wHost.getIp()) || "127.0.0.1".equalsIgnoreCase(wHost.getIp())) && wHost.getPort() == config.getSystem().getServerPort()) {
                for (Map.Entry<String, PhysicalDBNode> nodeEntry : config.getDataNodes().entrySet()) {
                    if (nodeEntry.getValue().getDbPool().getHostName().equals(host.getHostName())) {
                        if (selfNode == null) {
                            selfNode = new HashSet<>(2);
                        }
                        selfNode.add(nodeEntry.getKey());
                    }
                }
                break;
            }
        }
        return selfNode;
    }

    public void init(ServerConfig config) throws Exception {
        if (DbleServer.getInstance().isUseZK()) {
            //add syncMeta.lock the other DDL will wait
            boolean createSuccess = false;
            int times = 0;
            while (!createSuccess) {
                try {
                    //syncMeta LOCK ,if another server start, it may failed
                    ZKUtils.createTempNode(KVPathUtil.getSyncMetaLockPath());
                    createSuccess = true;
                } catch (Exception e) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
                    if (times % 60 == 0) {
                        LOGGER.info("createTempNode syncMeta.lock failed", e);
                        times = 0;
                    }
                    times++;
                }
            }
            String ddlPath = KVPathUtil.getDDLPath();
            CuratorFramework zkConn = ZKUtils.getConnection();
            //WAIT DDL PATH HAS NOT CHILD
            times = 0;
            while (zkConn.getChildren().forPath(ddlPath).size() > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
                if (times % 60 == 0) {
                    LOGGER.info("waiting for DDL in " + ddlPath);
                    times = 0;
                }
                times++;
            }


            initMeta(config);
            // online
            ZKUtils.createTempNode(KVPathUtil.getOnlinePath(), ZkConfig.getInstance().getValue(ZkParamCfg.ZK_CFG_MYID));
            //add watcher
            ZKUtils.addChildPathCache(ddlPath, new DDLChildListener());
            //add watcher
            ZKUtils.addViewPathCache(KVPathUtil.getViewPath(), new ViewChildListener());
            // syncMeta UNLOCK
            zkConn.delete().forPath(KVPathUtil.getSyncMetaLockPath());

        } else {
            initMeta(config);
        }
    }


    /**
     * recovery all the view info from KV system
     */
    private void loadViewFromKV() {
        repository = new KVStoreRepository();
        Map<String, Map<String, String>> viewCreateSqlMap = repository.getViewCreateSqlMap();
        loadViewMeta(viewCreateSqlMap);
    }

    /**
     * recovery all the view info for file system
     */
    private void loadViewFromFile() {
        repository = new FileSystemRepository();
        Map<String, Map<String, String>> viewCreateSqlMap = repository.getViewCreateSqlMap();
        loadViewMeta(viewCreateSqlMap);
    }

    /**
     * put the create sql in & parse all the tableNode & put into schema-viewMeta
     *
     * @param viewCreateSqlMap
     */
    private void loadViewMeta(Map<String, Map<String, String>> viewCreateSqlMap) {

        for (Map.Entry<String, Map<String, String>> schemaName : viewCreateSqlMap.entrySet()) {
            for (Map.Entry<String, String> view : schemaName.getValue().entrySet()) {
                ViewMeta vm = new ViewMeta(view.getValue(), schemaName.getKey());
                vm.init(true);
                DbleServer.getInstance().getTmManager().getCatalogs().get(schemaName.getKey()).getViewMetas().put(vm.getViewName(), vm);
            }
        }
    }


    public void initMeta(ServerConfig config) {
        Set<String> selfNode = getSelfNodes(config);
        SchemaMetaHandler handler = new SchemaMetaHandler(this, config, selfNode);
        handler.execute();
        if (DbleServer.getInstance().isUseZK()) {
            loadViewFromKV();
        } else {
            loadViewFromFile();
        }
        SystemConfig system = config.getSystem();
        if (system.getCheckTableConsistency() == 1) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("MetaDataChecker-%d").build());
            checkTaskHandler = scheduler.scheduleWithFixedDelay(tableStructureCheckTask(selfNode), 0L, system.getCheckTableConsistencyPeriod(), TimeUnit.MILLISECONDS);
        }
    }

    public void terminate() {
        if (checkTaskHandler != null) {
            checkTaskHandler.cancel(false);
            scheduler.shutdown();
        }
    }
    //Check the Consistency of table Structure

    private Runnable tableStructureCheckTask(final Set<String> selfNode) {
        return new Runnable() {
            @Override
            public void run() {
                tableStructureCheck(selfNode);
            }
        };
    }

    private void tableStructureCheck(Set<String> selfNode) {
        for (SchemaConfig schema : DbleServer.getInstance().getConfig().getSchemas().values()) {
            if (!checkDbExists(schema.getName())) {
                continue;
            }
            for (TableConfig table : schema.getTables().values()) {
                if (!checkTableExists(schema.getName(), table.getName())) {
                    continue;
                }
                AbstractTableMetaHandler handler = new TableMetaCheckHandler(this, schema.getName(), table, selfNode);
                handler.execute();
            }
        }
    }

    public void updateMetaData(String schema, String sql, boolean isSuccess, boolean needNotifyOther) {
        SQLStatementParser parser = new MySqlStatementParser(sql);
        SQLStatement statement = parser.parseStatement();
        if (statement instanceof MySqlCreateTableStatement) {
            createTable(schema, sql, (MySqlCreateTableStatement) statement, isSuccess, needNotifyOther);
        } else if (statement instanceof SQLDropTableStatement) {
            dropTable(schema, sql, (SQLDropTableStatement) statement, isSuccess, needNotifyOther);
        } else if (statement instanceof SQLAlterTableStatement) {
            alterTable(schema, sql, (SQLAlterTableStatement) statement, isSuccess, needNotifyOther);
        } else if (statement instanceof SQLTruncateStatement) {
            truncateTable(schema, sql, (SQLTruncateStatement) statement, isSuccess, needNotifyOther);
        } else if (statement instanceof SQLCreateIndexStatement) {
            createIndex(schema, sql, (SQLCreateIndexStatement) statement, isSuccess, needNotifyOther);
        } else if (statement instanceof SQLDropIndexStatement) {
            dropIndex(schema, sql, (SQLDropIndexStatement) statement, isSuccess, needNotifyOther);
        } else {
            // TODO: further
        }
    }

    public void notifyClusterDDL(String schema, String table, String sql, DDLInfo.DDLStatus ddlStatus, boolean needNotifyOther) throws Exception {
        CuratorFramework zkConn = ZKUtils.getConnection();
        DDLInfo ddlInfo = new DDLInfo(schema, sql, ZkConfig.getInstance().getValue(ZkParamCfg.ZK_CFG_MYID), ddlStatus);
        String nodeName = StringUtil.getFullName(schema, table);
        String nodePath = ZKPaths.makePath(KVPathUtil.getDDLPath(), nodeName);
        if (zkConn.checkExists().forPath(nodePath) == null) {
            zkConn.create().forPath(nodePath, ddlInfo.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            String instancePath = ZKPaths.makePath(nodePath, KVPathUtil.DDL_INSTANCE);
            String thisNode = ZkConfig.getInstance().getValue(ZkParamCfg.ZK_CFG_MYID);
            ZKUtils.createTempNode(instancePath, thisNode);
            if (needNotifyOther) {
                //this node is ddl sender
                zkConn.setData().forPath(nodePath, ddlInfo.toString().getBytes(StandardCharsets.UTF_8));
                while (true) {
                    List<String> preparedList = zkConn.getChildren().forPath(instancePath);
                    List<String> onlineList = zkConn.getChildren().forPath(KVPathUtil.getOnlinePath());
                    if (preparedList.size() >= onlineList.size()) {
                        zkConn.delete().deletingChildrenIfNeeded().forPath(nodePath);
                        break;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                }
            }
        }
    }

    //no need to check user
    private static SchemaInfo getSchemaInfo(String schema, SQLExprTableSource tableSource) {
        try {
            return SchemaUtil.getSchemaInfo(null, schema, tableSource);
        } catch (SQLException e) { // is should not happen
            LOGGER.info("getSchemaInfo error", e);
            return null;
        }
    }


    private void createTable(String schema, String sql, MySqlCreateTableStatement statement, boolean isSuccess, boolean needNotifyOther) {
        SchemaInfo schemaInfo = getSchemaInfo(schema, statement.getTableSource());
        try {
            if (!isSuccess) {
                return;
            }
            StructureMeta.TableMeta tblMeta = MetaHelper.initTableMeta(schemaInfo.getTable(), statement, System.currentTimeMillis());
            addTable(schemaInfo.getSchema(), tblMeta);
        } catch (Exception e) {
            LOGGER.info("updateMetaData failed,sql is" + statement.toString(), e);
        } finally {
            removeMetaLock(schemaInfo.getSchema(), schemaInfo.getTable());
            if (DbleServer.getInstance().isUseZK()) {
                try {
                    notifyClusterDDL(schemaInfo.getSchema(), schemaInfo.getTable(), sql, isSuccess ? DDLInfo.DDLStatus.SUCCESS : DDLInfo.DDLStatus.FAILED, needNotifyOther);
                } catch (Exception e) {
                    LOGGER.warn(AlarmCode.CORE_CLUSTER_WARN + "notifyClusterDDL error", e);
                }
            }
        }
    }

    private void alterTable(String schema, String sql, SQLAlterTableStatement alterStatement, boolean isSuccess, boolean needNotifyOther) {
        SchemaInfo schemaInfo = getSchemaInfo(schema, alterStatement.getTableSource());
        try {
            if (!isSuccess) {
                return;
            }
            StructureMeta.TableMeta orgTbMeta = getTableMeta(schemaInfo.getSchema(), schemaInfo.getTable());
            if (orgTbMeta == null)
                return;
            StructureMeta.TableMeta.Builder tmBuilder = orgTbMeta.toBuilder();
            List<StructureMeta.ColumnMeta> cols = new ArrayList<>();
            cols.addAll(orgTbMeta.getColumnsList());
            int autoColumnIndex = -1;
            Set<String> indexNames = new HashSet<>();
            for (StructureMeta.IndexMeta index : tmBuilder.getIndexList()) {
                indexNames.add(index.getName());
            }
            for (SQLAlterTableItem alterItem : alterStatement.getItems()) {
                if (alterItem instanceof SQLAlterTableAddColumn) {
                    autoColumnIndex = addColumn(tmBuilder, cols, (SQLAlterTableAddColumn) alterItem, indexNames);
                } else if (alterItem instanceof SQLAlterTableAddIndex) {
                    addIndex(tmBuilder, (SQLAlterTableAddIndex) alterItem, indexNames);
                } else if (alterItem instanceof SQLAlterTableAddConstraint) {
                    SQLAlterTableAddConstraint addConstraint = (SQLAlterTableAddConstraint) alterItem;
                    SQLConstraint constraint = addConstraint.getConstraint();
                    if (constraint instanceof MySqlPrimaryKey) {
                        MySqlPrimaryKey primaryKey = (MySqlPrimaryKey) constraint;
                        StructureMeta.IndexMeta indexMeta = MetaHelper.makeIndexMeta(MetaHelper.PRIMARY, IndexType.PRI, primaryKey.getColumns());
                        tmBuilder.setPrimary(indexMeta);
                    } else { // NOT SUPPORT
                    }
                } else if (alterItem instanceof SQLAlterTableDropIndex) {
                    SQLAlterTableDropIndex dropIndex = (SQLAlterTableDropIndex) alterItem;
                    String dropName = StringUtil.removeBackQuote(dropIndex.getIndexName().getSimpleName());
                    dropIndex(tmBuilder, dropName);
                } else if (alterItem instanceof SQLAlterTableDropKey) {
                    SQLAlterTableDropKey dropIndex = (SQLAlterTableDropKey) alterItem;
                    String dropName = StringUtil.removeBackQuote(dropIndex.getKeyName().getSimpleName());
                    dropIndex(tmBuilder, dropName);
                } else if (alterItem instanceof MySqlAlterTableChangeColumn) {
                    autoColumnIndex = changeColumn(tmBuilder, cols, (MySqlAlterTableChangeColumn) alterItem, indexNames);
                } else if (alterItem instanceof MySqlAlterTableModifyColumn) {
                    autoColumnIndex = modifyColumn(tmBuilder, cols, (MySqlAlterTableModifyColumn) alterItem, indexNames);
                } else if (alterItem instanceof SQLAlterTableDropColumnItem) {
                    dropColumn(cols, (SQLAlterTableDropColumnItem) alterItem);
                } else if (alterItem instanceof SQLAlterTableDropPrimaryKey) {
                    tmBuilder.clearPrimary();
                } else {
                    // TODO: further
                }
            }
            tmBuilder.clearColumns().addAllColumns(cols);
            if (autoColumnIndex != -1) {
                tmBuilder.setAiColPos(autoColumnIndex);
            }
            tmBuilder.setVersion(System.currentTimeMillis());
            StructureMeta.TableMeta newTblMeta = tmBuilder.build();
            addTable(schemaInfo.getSchema(), newTblMeta);
        } catch (Exception e) {
            LOGGER.info("updateMetaData alterTable failed,sql is" + alterStatement.toString(), e);
        } finally {
            removeMetaLock(schemaInfo.getSchema(), schemaInfo.getTable());
            if (DbleServer.getInstance().isUseZK()) {
                try {
                    notifyClusterDDL(schemaInfo.getSchema(), schemaInfo.getTable(), sql, isSuccess ? DDLInfo.DDLStatus.SUCCESS : DDLInfo.DDLStatus.FAILED, needNotifyOther);
                } catch (Exception e) {
                    LOGGER.warn(AlarmCode.CORE_CLUSTER_WARN + "notifyClusterDDL error", e);
                }
            }
        }
    }

    private void truncateTable(String schema, String sql, SQLTruncateStatement statement, boolean isSuccess, boolean needNotifyOther) {
        //TODO:reset Sequence?
        SQLExprTableSource exprTableSource = statement.getTableSources().get(0);
        SchemaInfo schemaInfo = getSchemaInfo(schema, exprTableSource);
        removeMetaLock(schemaInfo.getSchema(), schemaInfo.getTable());
        if (DbleServer.getInstance().isUseZK()) {
            try {
                notifyClusterDDL(schemaInfo.getSchema(), schemaInfo.getTable(), sql, isSuccess ? DDLInfo.DDLStatus.SUCCESS : DDLInfo.DDLStatus.FAILED, needNotifyOther);
            } catch (Exception e) {
                LOGGER.warn(AlarmCode.CORE_CLUSTER_WARN + "notifyClusterDDL error", e);
            }
        }
    }

    private void createIndex(String schema, String sql, SQLCreateIndexStatement statement, boolean isSuccess, boolean needNotifyOther) {
        SQLTableSource tableSource = statement.getTable();
        if (tableSource instanceof SQLExprTableSource) {
            SQLExprTableSource exprTableSource = (SQLExprTableSource) tableSource;
            SchemaInfo schemaInfo = getSchemaInfo(schema, exprTableSource);
            try {
                if (!isSuccess) {
                    return;
                }
                StructureMeta.TableMeta orgTbMeta = getTableMeta(schemaInfo.getSchema(), schemaInfo.getTable());
                if (orgTbMeta == null)
                    return;
                String indexName = StringUtil.removeBackQuote(statement.getName().getSimpleName());
                StructureMeta.TableMeta.Builder tmBuilder = orgTbMeta.toBuilder();
                if (statement.getType() == null) {
                    addIndex(indexName, tmBuilder, IndexType.MUL, itemsToColumns(statement.getItems()));
                } else if (statement.getType().equals("UNIQUE")) {
                    addIndex(indexName, tmBuilder, IndexType.UNI, itemsToColumns(statement.getItems()));
                }
            } catch (Exception e) {
                LOGGER.info("updateMetaData failed,sql is" + statement.toString(), e);
            } finally {
                removeMetaLock(schemaInfo.getSchema(), schemaInfo.getTable());
                if (DbleServer.getInstance().isUseZK()) {
                    try {
                        notifyClusterDDL(schemaInfo.getSchema(), schemaInfo.getTable(), sql, isSuccess ? DDLInfo.DDLStatus.SUCCESS : DDLInfo.DDLStatus.FAILED, needNotifyOther);
                    } catch (Exception e) {
                        LOGGER.warn(AlarmCode.CORE_CLUSTER_WARN + "notifyClusterDDL error", e);
                    }
                }
            }
        }
    }

    private void addIndex(StructureMeta.TableMeta.Builder tmBuilder, SQLAlterTableAddIndex addIndex, Set<String> indexNames) {
        List<SQLExpr> columnExprs = itemsToColumns(addIndex.getItems());
        String indexName = MetaHelper.genIndexName(addIndex.getName(), columnExprs, indexNames);
        if (addIndex.isUnique()) {
            addIndex(indexName, tmBuilder, IndexType.UNI, columnExprs);
        } else {
            addIndex(indexName, tmBuilder, IndexType.MUL, columnExprs);
        }
    }

    private void addIndex(String indexName, StructureMeta.TableMeta.Builder tmBuilder, IndexType indexType, List<SQLExpr> columnExprs) {

        StructureMeta.IndexMeta indexMeta = MetaHelper.makeIndexMeta(indexName, indexType, columnExprs);
        tmBuilder.addIndex(indexMeta);
    }

    private List<SQLExpr> itemsToColumns(List<SQLSelectOrderByItem> items) {
        List<SQLExpr> columnExprs = new ArrayList<>();
        for (SQLSelectOrderByItem item : items) {
            columnExprs.add(item.getExpr());
        }
        return columnExprs;
    }

    private void dropIndex(String schema, String sql, SQLDropIndexStatement dropIndexStatement, boolean isSuccess, boolean needNotifyOther) {
        SchemaInfo schemaInfo = getSchemaInfo(schema, dropIndexStatement.getTableName());
        StructureMeta.TableMeta orgTbMeta = getTableMeta(schemaInfo.getSchema(), schemaInfo.getTable());
        try {
            if (!isSuccess) {
                return;
            }
            if (orgTbMeta != null) {
                StructureMeta.TableMeta.Builder tmBuilder = orgTbMeta.toBuilder();
                String dropName = StringUtil.removeBackQuote(((SQLIdentifierExpr) dropIndexStatement.getIndexName()).getName());
                dropIndex(tmBuilder, dropName);
            }
        } catch (Exception e) {
            LOGGER.info("updateMetaData failed,sql is" + dropIndexStatement.toString(), e);
        } finally {
            removeMetaLock(schemaInfo.getSchema(), schemaInfo.getTable());
            if (DbleServer.getInstance().isUseZK()) {
                try {
                    notifyClusterDDL(schemaInfo.getSchema(), schemaInfo.getTable(), sql, isSuccess ? DDLInfo.DDLStatus.SUCCESS : DDLInfo.DDLStatus.FAILED, needNotifyOther);
                } catch (Exception e) {
                    LOGGER.warn(AlarmCode.CORE_CLUSTER_WARN + "notifyClusterDDL error", e);
                }
            }
        }
    }

    private void dropIndex(StructureMeta.TableMeta.Builder tmBuilder, String dropName) {
        List<StructureMeta.IndexMeta> indexes = new ArrayList<>();
        indexes.addAll(tmBuilder.getIndexList());
        if (dropIndex(indexes, dropName)) {
            tmBuilder.clearIndex().addAllIndex(indexes);
        } else {
            List<StructureMeta.IndexMeta> uniques = new ArrayList<>();
            uniques.addAll(tmBuilder.getUniIndexList());
            dropIndex(uniques, dropName);
            tmBuilder.clearUniIndex().addAllUniIndex(uniques);
        }
    }

    private boolean dropIndex(List<StructureMeta.IndexMeta> indexes, String dropName) {
        int index = -1;
        for (int i = 0; i < indexes.size(); i++) {
            String indexName = indexes.get(i).getName();
            if (indexName.equalsIgnoreCase(dropName)) {
                index = i;
                break;
            }
        }
        if (index != -1) {
            indexes.remove(index);
            return true;
        }
        return false;
    }

    private int addColumn(StructureMeta.TableMeta.Builder tmBuilder, List<StructureMeta.ColumnMeta> columnMetas, SQLAlterTableAddColumn addColumn, Set<String> indexNames) {
        int autoColumnIndex = -1;
        boolean isFirst = addColumn.isFirst();
        SQLName afterColumn = addColumn.getAfterColumn();
        if (afterColumn != null || isFirst) {
            int addIndex = -1;
            if (isFirst) {
                addIndex = 0;
            } else {
                String afterColName = StringUtil.removeBackQuote(afterColumn.getSimpleName());
                for (int i = 0; i < columnMetas.size(); i++) {
                    String colName = columnMetas.get(i).getName();
                    if (afterColName.equalsIgnoreCase(colName)) {
                        addIndex = i + 1;
                        break;
                    }
                }
            }
            StructureMeta.ColumnMeta.Builder cmBuilder = MetaHelper.makeColumnMeta(tmBuilder, addColumn.getColumns().get(0), indexNames);
            columnMetas.add(addIndex, cmBuilder.build());
            if (cmBuilder.getAutoIncre()) {
                autoColumnIndex = addIndex;
            }
        } else {
            int addIndex = columnMetas.size();
            for (SQLColumnDefinition columnDef : addColumn.getColumns()) {
                StructureMeta.ColumnMeta.Builder cmBuilder = MetaHelper.makeColumnMeta(tmBuilder, columnDef, indexNames);
                columnMetas.add(addIndex, cmBuilder.build());
                if (cmBuilder.getAutoIncre()) {
                    autoColumnIndex = addIndex;
                }
            }
        }
        return autoColumnIndex;
    }

    private int changeColumn(StructureMeta.TableMeta.Builder tmBuilder, List<StructureMeta.ColumnMeta> columnMetas, MySqlAlterTableChangeColumn changeColumn, Set<String> indexNames) {
        int autoColumnIndex = -1;
        String changeColName = StringUtil.removeBackQuote(changeColumn.getColumnName().getSimpleName());
        for (int i = 0; i < columnMetas.size(); i++) {
            String colName = columnMetas.get(i).getName();
            if (changeColName.equalsIgnoreCase(colName)) {
                columnMetas.remove(i);
                break;
            }
        }
        boolean isFirst = changeColumn.isFirst();
        SQLExpr afterColumn = changeColumn.getAfterColumn();
        int changeIndex = getChangeIndex(isFirst, afterColumn, columnMetas);
        StructureMeta.ColumnMeta.Builder cmBuilder = MetaHelper.makeColumnMeta(tmBuilder, changeColumn.getNewColumnDefinition(), indexNames);
        columnMetas.add(changeIndex, cmBuilder.build());
        if (cmBuilder.getAutoIncre()) {
            autoColumnIndex = changeIndex;
        }
        return autoColumnIndex;
    }

    private void dropColumn(List<StructureMeta.ColumnMeta> columnMetas, SQLAlterTableDropColumnItem dropColumn) {
        for (SQLName dropName : dropColumn.getColumns()) {
            String dropColName = StringUtil.removeBackQuote(dropName.getSimpleName());
            for (int i = 0; i < columnMetas.size(); i++) {
                String colName = columnMetas.get(i).getName();
                if (dropColName.equalsIgnoreCase(colName)) {
                    columnMetas.remove(i);
                    break;
                }
            }
        }
    }

    private int modifyColumn(StructureMeta.TableMeta.Builder tmBuilder, List<StructureMeta.ColumnMeta> columnMetas, MySqlAlterTableModifyColumn modifyColumn, Set<String> indexNames) {
        int autoColumnIndex = -1;
        SQLColumnDefinition modifyColDef = modifyColumn.getNewColumnDefinition();
        String modifyColName = StringUtil.removeBackQuote(modifyColDef.getName().getSimpleName());
        for (int i = 0; i < columnMetas.size(); i++) {
            String colName = columnMetas.get(i).getName();
            if (modifyColName.equalsIgnoreCase(colName)) {
                columnMetas.remove(i);
                break;
            }
        }
        boolean isFirst = modifyColumn.isFirst();
        SQLExpr afterColumn = modifyColumn.getAfterColumn();
        int modifyIndex = getChangeIndex(isFirst, afterColumn, columnMetas);
        StructureMeta.ColumnMeta.Builder cmBuilder = MetaHelper.makeColumnMeta(tmBuilder, modifyColDef, indexNames);
        columnMetas.add(modifyIndex, cmBuilder.build());
        if (cmBuilder.getAutoIncre()) {
            autoColumnIndex = modifyIndex;
        }
        return autoColumnIndex;
    }

    private int getChangeIndex(boolean isFirst, SQLExpr afterColumn, List<StructureMeta.ColumnMeta> columnMetas) {
        int changeIndex = -1;
        if (isFirst) {
            changeIndex = 0;
        } else if (afterColumn != null) {
            String afterColName = StringUtil.removeBackQuote(((SQLIdentifierExpr) afterColumn).getName());
            for (int i = 0; i < columnMetas.size(); i++) {
                String colName = columnMetas.get(i).getName();
                if (afterColName.equalsIgnoreCase(colName)) {
                    changeIndex = i + 1;
                    break;
                }
            }
        } else {
            changeIndex = columnMetas.size();
        }
        return changeIndex;
    }


    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

}

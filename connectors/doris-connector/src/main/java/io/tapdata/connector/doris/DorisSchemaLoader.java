package io.tapdata.connector.doris;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.tapdata.connector.doris.bean.DorisConfig;
import io.tapdata.entity.conversion.TableFieldTypesGenerator;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.mapping.DefaultExpressionMatchingMap;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.entity.utils.InstanceFactory;
import io.tapdata.kit.DbKit;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @Author dayun
 * @Date 7/14/22
 */
public class DorisSchemaLoader {
    private static final String TAG = DorisSchemaLoader.class.getSimpleName();
    private static final String SELECT_TABLES = "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '%s' AND TABLE_TYPE='BASE TABLE'";
    private static final String TABLE_NAME_IN = " AND TABLE_NAME IN(%s)";
    private static final String SELECT_COLUMNS = "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME %s";

    private static final String SELECT_ALL_INDEX_SQL_JOIN = "select i.TABLE_NAME,\n" +
            "i.INDEX_NAME,\n" +
            "i.INDEX_TYPE,\n" +
            "i.COLLATION,\n" +
            "i.NON_UNIQUE,\n" +
            "i.COLUMN_NAME,\n" +
            "i.SEQ_IN_INDEX,\n" +
            "k.CONSTRAINT_NAME\n"+
            "from INFORMATION_SCHEMA.STATISTICS i\n" +
            "inner join INFORMATION_SCHEMA.KEY_COLUMN_USAGE k\n"+
            "on k.TABLE_NAME = i.TABLE_NAME and i.COLUMN_NAME = k.COLUMN_NAME\n"+
            "where k.TABLE_SCHEMA = '%s'\n" +
            "and i.TABLE_SCHEMA = '%s'\n" +
            "and i.TABLE_NAME %s\n" +
            "and i.INDEX_NAME <> 'PRIMARY'";
    private static final DorisDDLInstance DDLInstance = DorisDDLInstance.getInstance();

    private TapConnectionContext tapConnectionContext;
    private DorisContext dorisContext;

    public DorisSchemaLoader(DorisContext dorisContext) {
        this.dorisContext = dorisContext;
        this.tapConnectionContext = dorisContext.getTapConnectionContext();
    }

    public void discoverSchema(final TapConnectionContext tapConnectionContext, final DorisConfig dorisConfig, List<String> filterTable, Consumer<List<TapTable>> consumer, int tableSize) throws Throwable {
        if (null == consumer) {
            throw new IllegalArgumentException("Consumer cannot be null");
        }

        String database = dorisConfig.getDatabase();
        List<String> allTables = queryAllTables(database, filterTable);
        if (CollectionUtils.isEmpty(allTables)) {
            consumer.accept(null);
            return;
        }

        TableFieldTypesGenerator instance = InstanceFactory.instance(TableFieldTypesGenerator.class);
        DefaultExpressionMatchingMap dataTypesMap = tapConnectionContext.getSpecification().getDataTypesMap();

        try {
            List<List<String>> partition = Lists.partition(allTables, tableSize);
            partition.forEach(tables -> {
                String tableNames = StringUtils.join(tables, "','");
                List<DataMap> columnList = queryAllColumns(database, tableNames);
                List<DataMap> indexList = queryAllIndexes(database, tableNames);

                Map<String, List<DataMap>> columnMap = Maps.newHashMap();
                if (CollectionUtils.isNotEmpty(columnList)) {
                    columnMap = columnList.stream().collect(Collectors.groupingBy(t -> t.getString("TABLE_NAME")));
                }
                Map<String, List<DataMap>> indexMap = Maps.newHashMap();
                if (CollectionUtils.isNotEmpty(indexList)) {
                    indexMap = indexList.stream().collect(Collectors.groupingBy(t -> t.getString("TABLE_NAME")));
                }

                Map<String, List<DataMap>> finalColumnMap = columnMap;
                Map<String, List<DataMap>> finalIndexMap = indexMap;

                List<TapTable> tempList = new ArrayList<>();
                tables.forEach(table -> {
                    TapTable tapTable = TapSimplify.table(table);

                    discoverFields(finalColumnMap.get(table), tapTable, instance, dataTypesMap);
                    discoverIndexes(finalIndexMap.get(table), tapTable);
                    tempList.add(tapTable);
                });

                if (CollectionUtils.isNotEmpty(columnList)) {
                    consumer.accept(tempList);
                    tempList.clear();
                }
            });
        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    private List<DataMap> queryAllIndexes(String database, String tableNames) {
        TapLogger.debug(TAG, "Query all indexes, database: {}, tableNames:{}", database, tableNames);
        List<DataMap> indexList = TapSimplify.list();

        String inTableName = new StringJoiner(tableNames).add("IN ('").add("')").toString();
        String sql = String.format(SELECT_ALL_INDEX_SQL_JOIN, database, database, inTableName);

        try (Statement statement = dorisContext.getConnection().createStatement()) {
            ResultSet resultSet = dorisContext.executeQuery(statement, sql);
            List<String> columnNames = DbKit.getColumnsFromResultSet(resultSet);
            while (resultSet.next()) {
                if (StringUtils.equals("null", resultSet.getString("CONSTRAINT_NAME"))) {
                    indexList.add(DbKit.getRowFromResultSet(resultSet, columnNames));
                }
            }
        } catch (Throwable e) {
            TapLogger.error(TAG, "Execute queryAllIndexes failed, error: " + e.getMessage(), e);
        }
        return indexList;
    }

    public List<String> queryAllTables(String database, final List<String> filterTables) {
        final List<String> tableList = TapSimplify.list();
        final Connection connection = dorisContext.getConnection();
        try (final Statement statement = connection.createStatement();
             final ResultSet resultSet = queryTables(statement, database, filterTables)) {
            while (resultSet.next()) {
                tableList.add(resultSet.getString("TABLE_NAME"));
            }
        } catch (final Exception e) {
            TapLogger.error(TAG, "Execute queryAllTables failed, error: " + e.getMessage(), e);
        }

        return tableList;
    }

    private List<DataMap> queryAllColumns(String database, String tableNames) {
        TapLogger.debug(TAG, "Query all columns, database: {}, tableNames:{}", database, tableNames);

        String inTableName = new StringJoiner(tableNames).add("IN ('").add("')").toString();
        String sql = String.format(SELECT_COLUMNS, database, inTableName);
        List<DataMap> columnList = TapSimplify.list();
        try (Statement statement = dorisContext.getConnection().createStatement();
             final ResultSet resultSet = dorisContext.executeQuery(statement, sql)) {
            List<String> columnNames = DbKit.getColumnsFromResultSet(resultSet);
            while (resultSet.next()) {
                columnList.add(DbKit.getRowFromResultSet(resultSet, columnNames));
            }
        } catch (Throwable e) {
            TapLogger.error(TAG, "Execute queryAllColumns failed, error: " + e.getMessage(), e);
        }
        return columnList;
    }

    private void discoverIndexes(List<DataMap> indexList, TapTable tapTable) {
        List<TapIndex> indexes = new ArrayList<>();

        if (CollectionUtils.isEmpty(indexList)) {
            return;
        }
        indexList.forEach(dataMap -> {
            String indexName = dataMap.getString("INDEX_NAME");
            TapIndex tapIndex = indexes.stream().filter(i -> i.getName().equals(indexName)).findFirst().orElse(null);
            if (null == tapIndex) {
                tapIndex = new TapIndex();
                tapIndex.setName(indexName);
                int nonUnique = Integer.parseInt(dataMap.getString("NON_UNIQUE"));
                tapIndex.setUnique(nonUnique == 1);
                tapIndex.setPrimary(false);
                indexes.add(tapIndex);
            }
            List<TapIndexField> indexFields = tapIndex.getIndexFields();
            if (null == indexFields) {
                indexFields = new ArrayList<>();
                tapIndex.setIndexFields(indexFields);
            }
            TapIndexField tapIndexField = new TapIndexField();
            tapIndexField.setName(dataMap.getString("COLUMN_NAME"));
            String collation = dataMap.getString("COLLATION");
            tapIndexField.setFieldAsc("A".equals(collation));
            indexFields.add(tapIndexField);
        });
        tapTable.setIndexList(indexes);
    }

    private void discoverFields(List<DataMap> columnList, TapTable tapTable, TableFieldTypesGenerator tableFieldTypesGenerator,
                                DefaultExpressionMatchingMap dataTypesMap) {
        AtomicInteger primaryPos = new AtomicInteger(1);

        if (CollectionUtils.isEmpty(columnList)) {
            return;
        }
        columnList.forEach( dataMap -> {
            String columnName = dataMap.getString("COLUMN_NAME");
            String columnType = dataMap.getString("COLUMN_TYPE");
            TapField field = TapSimplify.field(columnName, columnType);
            tableFieldTypesGenerator.autoFill(field, dataTypesMap);

            int ordinalPosition = Integer.parseInt(dataMap.getString("ORDINAL_POSITION"));
            field.pos(ordinalPosition);

            String isNullable = dataMap.getString("IS_NULLABLE");
            field.nullable(isNullable.equals("YES"));

            Object columnKey = dataMap.getObject("COLUMN_KEY");
            if (columnKey instanceof String && columnKey.equals("PRI")) {
                field.primaryKeyPos(primaryPos.getAndIncrement());
            }

            tapTable.add(field);
        });
    }

    public void createTable(final TapTable tapTable) {
        final String tableName = tapTable.getName();
        Collection<String> primaryKeys = tapTable.primaryKeys();
        String sql;
        if (CollectionUtils.isEmpty(primaryKeys)) {
            String firstColumn = tapTable.getNameFieldMap().values().stream().findFirst().orElseGet(TapField::new).getName();
            sql = "CREATE TABLE IF NOT EXISTS " + tableName +
                    "(" + DDLInstance.buildColumnDefinition(tapTable) + ") " +
                    "DUPLICATE KEY (" + firstColumn + " ) " +
                    "DISTRIBUTED BY HASH(" + firstColumn + " ) BUCKETS 10 " +
                    "PROPERTIES(\"replication_num\" = \"1\")";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS " + tableName +
                    "(" + DDLInstance.buildColumnDefinition(tapTable) + ") " +
                    "UNIQUE KEY (" + DDLInstance.buildDistributedKey(primaryKeys) + " ) " +
                    "DISTRIBUTED BY HASH(" + DDLInstance.buildDistributedKey(primaryKeys) + " ) BUCKETS 10 " +
                    "PROPERTIES(\"replication_num\" = \"1\")";
        }

        try {
            dorisContext.execute(sql);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Create Table " + tableName + " Failed! " + e.getMessage());
        }
    }

    public void dropTable(String dataName, final String tableName) {
        Connection connection = dorisContext.getConnection();
        try (Statement statement = connection.createStatement();
             ResultSet table = queryOneTable(statement, dataName, tableName)){
            if (table.next()) {
                String sql = "DROP TABLE " + tableName;
                dorisContext.execute(sql);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Drop Table " + tableName + " Failed! \n ");
        }
    }

    public void clearTable(String dataName, final String tableName) {
        final Connection connection = dorisContext.getConnection();
        try (Statement statement = connection.createStatement();
             ResultSet table = queryOneTable(statement, dataName, tableName)){
            if (table.next()) {
                String sql = "TRUNCATE TABLE " + tableName;
                dorisContext.execute(sql);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("TRUNCATE TABLE " + tableName + " Failed! \n ");
        }
    }

    public ResultSet queryOneTable(final Statement statement, String database, String tableName) throws Exception {
        return queryTables(statement, database, Lists.newArrayList(tableName));
    }

    public ResultSet queryTables(final Statement statement, String database, final List<String> filterTables) throws Exception {
        String sql = String.format(SELECT_TABLES, database);
        if (CollectionUtils.isNotEmpty(filterTables)) {
            final List<String> wrappedTables = filterTables.stream().map(t -> "'" + t + "'").collect(Collectors.toList());
            String tableNameIn = String.join(",", wrappedTables);
            sql += String.format(TABLE_NAME_IN, tableNameIn);
        }
        TapLogger.debug(TAG, "Execute sql: " + sql);
        return dorisContext.executeQuery(statement, sql);
    }
}

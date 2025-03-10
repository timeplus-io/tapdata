package io.tapdata.pdk.tdd.tests.v2;

import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.pdk.apis.entity.WriteListResult;
import io.tapdata.pdk.apis.functions.connector.target.WriteRecordFunction;
import io.tapdata.pdk.cli.commands.TapSummary;
import io.tapdata.pdk.core.api.ConnectorNode;
import io.tapdata.pdk.core.tapnode.TapNodeInfo;
import io.tapdata.pdk.core.workflow.engine.DataFlowWorker;
import io.tapdata.pdk.tdd.core.PDKTestBase;
import io.tapdata.pdk.tdd.core.SupportFunction;
import io.tapdata.pdk.tdd.tests.support.Record;
import io.tapdata.pdk.tdd.tests.support.TapAssert;
import io.tapdata.pdk.tdd.tests.support.TapGo;
import io.tapdata.pdk.tdd.tests.support.TapTestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.tapdata.entity.simplify.TapSimplify.*;
import static io.tapdata.entity.utils.JavaTypesToTapTypes.JAVA_Long;
import static io.tapdata.entity.utils.JavaTypesToTapTypes.JAVA_String;


@DisplayName("test.writeRecordTest")
@TapGo(sort = 1)
/**
 * 都需使用随机ID建表， 如果有DropTableFunction实现， 测试用例应该自动删除创建的临时表（无论成功或是失败）
 * */
public class WriteRecordTest extends PDKTestBase {
    private static final String TAG = WriteRecordTest.class.getSimpleName();
    protected ConnectorNode tddTargetNode;
    protected ConnectorNode sourceNode;
    protected DataFlowWorker dataFlowWorker;
    protected String targetNodeId = "t2";
    protected String testSourceNodeId = "ts1";
    protected String originToSourceId;
    protected TapNodeInfo tapNodeInfo;
    protected String testTableId;
    private void targetTable(){
        this.targetTable = table(UUID.randomUUID().toString())
                .add(field("id", JAVA_Long).isPrimaryKey(true).primaryKeyPos(1).tapType(tapNumber().maxValue(BigDecimal.valueOf(Long.MAX_VALUE)).minValue(BigDecimal.valueOf(Long.MIN_VALUE))))
                .add(field("name", JAVA_String).tapType(tapString().bytes(100L)))
                .add(field("text", JAVA_String).tapType(tapString().bytes(100L)));
    }
    @Test
    @DisplayName("test.writeRecordTest.case.sourceTest1")//增删改数量返回正确
    @TapTestCase(sort = 1)
    /**
     * 插入2条数据， 修改插入的2条数据， 删除插入的2条数据 ，验证插入的数量， 修改的数量， 删除的数量是否正确。
     * */
    void sourceTest1() throws Throwable {
        consumeQualifiedTapNodeInfo(nodeInfo -> {
            TestNode prepare = prepare(nodeInfo);
            RecordEventExecute execute = prepare.recordEventExecute();
            targetTable();
            boolean isCreatedTable = false;
            try {
                super.connectorOnStart(prepare);
                execute.testCase(this.getMethod("sourceTest1"));
                isCreatedTable = super.createTable(prepare);
                writeRecorde(execute);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }finally {
                if (isCreatedTable) execute.dropTable();
                super.connectorOnStop(prepare);
            }
        });
        //waitCompleted(5000000);
    }
    long insertRecordNeed = 2;
    private void writeRecorde(RecordEventExecute recordEventExecute) throws Throwable {
       Record[] records = Record.testStart((int)insertRecordNeed);
       int recLen = records.length;
       recordEventExecute.builderRecord(records);
        Method testCase = recordEventExecute.testCase();

        WriteListResult<TapRecordEvent> insert = recordEventExecute.insert();
        long insertRecord = insert.getInsertedCount();
        TapAssert.asserts(()->
            Assertions.assertEquals(
                recLen, insertRecord,
                TapSummary.format("recordEventExecute.insert.assert.error", recLen))
        ).acceptAsWarn(testCase,TapSummary.format("recordEventExecute.insert.assert.succeed",recLen) );

       for (Record record : records) {
           record.builder("name","Gavin pro").builder("text","Gavin pro max-modify");
       }
       WriteListResult<TapRecordEvent> update = recordEventExecute.update();
       long updateRecord = update.getModifiedCount();
       TapAssert.asserts(()->Assertions.assertEquals(
           recLen, updateRecord,
           TapSummary.format("recordEventExecute.update.assert.error",recLen))
       ).acceptAsError(testCase,TapSummary.format("recordEventExecute.update.assert.succeed",recLen));


       WriteListResult<TapRecordEvent> delete = recordEventExecute.delete();
       long deleteRecord = delete.getRemovedCount();
       TapAssert.asserts(()->Assertions.assertEquals(
           recLen, deleteRecord,
           TapSummary.format("recordEventExecute.delete.assert.error",recLen))
       ).acceptAsError(testCase,TapSummary.format("recordEventExecute.delete.assert.succeed",recLen));

    }

    @Test
    @DisplayName("test.writeRecordTest.case.sourceTest2")// 多次插入相同主键的数据， 插入修改数量应该正确
    @TapTestCase(sort = 2)
    /**
     * 支持默认行为就是合格的， 默认以外的按警告处理
     * 插入2条数据， 再次插入相同主键的2条数据， 内容略有不同， 插入策略是update_on_exists（默认行为），
     *      此时验证新插入应该是插入2个， 后再插入的相同主键的2条数据应该是修改2个，
     *      假如是插入2个就应该是一个警告， 代表可观测性数据可能不准确。如果是其他情况都是错误的。
     * 插入2条数据， 再次插入相同主键的2条数据， 内容略有不同，
     *      插入策略是ignore_on_exists， 此时验证新插入应该是插入2个，
     *      后再插入的相同主键的2条数据应该是新增， 修改， 删除都没有数量。
     *      由于这个不是默认策略， 因此此处的错误都按警告处理并且提示用当策略是ignore_on_exists时， 需要怎么做。
     * */
    void sourceTest2() throws Throwable {
        consumeQualifiedTapNodeInfo(nodeInfo -> {
            TestNode prepare = prepare(nodeInfo);
            RecordEventExecute execute = prepare.recordEventExecute();
            targetTable();
            boolean isCreatedTable = false;
            try {
                super.connectorOnStart(prepare);
                execute.testCase(this.getMethod("sourceTest2"));
                isCreatedTable = super.createTable(prepare);
                sourceTest2Fun(execute, prepare.connectorNode());
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }finally {
                if ( isCreatedTable )execute.dropTable();
                super.connectorOnStop(prepare);
            }
        });
        //waitCompleted(5000000);
    }
    private void sourceTest2Fun( RecordEventExecute recordEventExecute,ConnectorNode connectorNode) throws Throwable {
        Record[] records = Record.testStart((int)insertRecordNeed);
        final int recLen = records.length;
        recordEventExecute.builderRecord(records);
        Method testCase = recordEventExecute.testCase();
        //插入2条数据， 再次插入相同主键的2条数据， 内容略有不同， 插入策略是update_on_exists（默认行为），
        WriteListResult<TapRecordEvent> insertBefore = recordEventExecute.insert();
        long firstInsert = insertBefore.getInsertedCount();
        long firstUpdate = insertBefore.getModifiedCount();
        long firstDelete = insertBefore.getRemovedCount();
        //此时验证新插入应该是插入2个
        String firstInsertMsgError = TapSummary.format("writeRecordTest.sourceTest2.verify.firstInsert", recLen, firstInsert,firstUpdate,firstDelete);
        String firstInsertMsgSucceed = TapSummary.format("writeRecordTest.sourceTest2.verify.firstInsert.succeed", recLen, firstInsert,firstUpdate,firstDelete);
        TapAssert.asserts(()-> Assertions.assertEquals(recLen, firstInsert,firstInsertMsgError ))
                .acceptAsError(testCase,firstInsertMsgSucceed);

        for (int index = 0; index < insertRecordNeed; index++) {
            records[index].builder("name","yes please update_on_exists.");
        }
        WriteListResult<TapRecordEvent> insertAfter = recordEventExecute.insert();
        long lastInsert = insertAfter.getInsertedCount();
        long lastUpdate = insertAfter.getModifiedCount();
        long lastDelete = insertAfter.getRemovedCount();

        //新增和修改之和等于操作数，否则失败
        //插入2条数据， 再次插入相同主键的2条数据， 内容略有不同，
        TapAssert asserts = TapAssert.asserts(() -> Assertions.assertTrue(
        lastUpdate+lastInsert==recLen
                && lastUpdate == recLen
                && lastInsert == 0 ,
            lastUpdate+lastInsert!=recLen?
                TapSummary.format("wr.test2.insertAfter.notEquals",recLen,0,2,0,2,lastInsert,lastUpdate,lastDelete): lastInsert == recLen && lastUpdate == 0?
                TapSummary.format("wr.test2.insertAfter.warnInsert",recLen,0,2,0,lastInsert,lastUpdate,lastDelete):lastUpdate != recLen?
                TapSummary.format("wr.test2.insertAfter.warnUpdate",recLen,0,2,0,lastInsert,lastUpdate,lastDelete)
                :TapSummary.format("wr.test2.insertAfter.errorOther",recLen,0,2,0,lastInsert,lastUpdate,lastDelete))
        );
        String succeed = TapSummary.format("wr.test2.insertAfter.succeed",recLen,0,2,0,lastInsert,lastUpdate,lastDelete);
        if (lastUpdate == recLen && lastUpdate+lastInsert==recLen) {
            //后再插入的相同主键的2条数据应该是修改2个
            asserts.acceptAsWarn(testCase,succeed);
        }else if(lastInsert == recLen && lastUpdate+lastInsert==recLen){
            //假如是插入2个就应该是一个警告， 代表可观测性数据可能不准确。
            asserts.acceptAsWarn(testCase,succeed);
        }else {
            //如果是其他情况都是错误的。@TODO acceptAsError
            asserts.acceptAsWarn(testCase,succeed);
        }


//            final String insertPolicy = "dml_insert_policy";
//            ConnectorCapabilities connectorCapabilities = connectorNode.getConnectorContext().getConnectorCapabilities();
//            Map<String, String> capabilityAlternativeMap = connectorCapabilities.getCapabilityAlternativeMap();
//            if (null == capabilityAlternativeMap){
//                capabilityAlternativeMap = new HashMap<>();
//                connectorCapabilities.setCapabilityAlternativeMap(capabilityAlternativeMap);
//            }
//            capabilityAlternativeMap.put(insertPolicy,"ignore_on_exists");
        super.ignoreOnExistsWhenInsert(connectorNode.getConnectorContext());
        //插入策略是ignore_on_exists
        //插入2条数据， 再次插入相同主键的2条数据， 内容略有不同， 插入策略是ignore_on_exists，
        for (int index = 0; index < insertRecordNeed; index++) {
            records[index].builder("name","yes please ignore_on_exists.");
        }

        WriteListResult<TapRecordEvent> insertAfter2 = recordEventExecute.insert();
        long lastInsert2 = insertAfter2.getInsertedCount();
        long lastUpdate2 = insertAfter2.getModifiedCount();
        long lastDelete2 = insertAfter2.getRemovedCount();

        //新增和修改之和等于操作数，否则失败
        //插入2条数据， 再次插入相同主键的2条数据， 内容略有不同，
        TapAssert.asserts(() -> Assertions.assertTrue(
            lastUpdate2 == 0 && lastInsert2 == 0 ,
                    TapSummary.format("wr.test2.IOE.insertAfter.error",0,0,0,lastInsert2,lastUpdate2,lastDelete2))
        ).acceptAsWarn(testCase,TapSummary.format("wr.test2.IOE.insertAfter.succeed",0,0,0,lastInsert2,lastUpdate2,lastDelete2));
    }

    private void sourceTest2FunV2( RecordEventExecute recordEventExecute,ConnectorNode connectorNode) throws Throwable {
        Record[] records = Record.testStart((int)insertRecordNeed);
        final int recLen = records.length;
        recordEventExecute.builderRecord(records);
        Method testCase = recordEventExecute.testCase();
        //插入2条数据， 再次插入相同主键的2条数据， 内容略有不同， 插入策略是update_on_exists（默认行为），
        WriteListResult<TapRecordEvent> insertBefore = recordEventExecute.insert();
        long firstInsert = insertBefore.getInsertedCount();
        long firstUpdate = insertBefore.getModifiedCount();
        long firstDelete = insertBefore.getRemovedCount();
        //此时验证新插入应该是插入2个
        String firstInsertMsgError = TapSummary.format("writeRecordTest.sourceTest2.verify.firstInsert", recLen, firstInsert,firstUpdate,firstDelete);
        String firstInsertMsgSucceed = TapSummary.format("writeRecordTest.sourceTest2.verify.firstInsert.succeed", recLen, firstInsert,firstUpdate,firstDelete);
        TapAssert.asserts(()-> Assertions.assertEquals(recLen, firstInsert,firstInsertMsgError ))
                .acceptAsError(testCase,firstInsertMsgSucceed);
        AtomicInteger count = new AtomicInteger();
        Runnable run = ()->{
            count.getAndIncrement();
            for (int index = 0; index < insertRecordNeed; index++) {
                records[index].builder("name","yes please update_on_exists."+UUID.randomUUID());
            }
            try {
                WriteListResult<TapRecordEvent> insertAfter = recordEventExecute.insert();
                super.ignoreOnExistsWhenInsert(connectorNode.getConnectorContext());
                //插入策略是ignore_on_exists
                //插入2条数据， 再次插入相同主键的2条数据， 内容略有不同， 插入策略是ignore_on_exists，
                for (int index = 0; index < insertRecordNeed; index++) {
                    records[index].builder("name","yes please ignore_on_exists."+UUID.randomUUID());
                }

                WriteListResult<TapRecordEvent> insertAfter2 = recordEventExecute.insert();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }finally {
                count.decrementAndGet();
            }
        };
        Thread[] th = new Thread[1000];
        for (int i = 0; i < 1000; i++) {
            th[i] = new Thread(run);
        }
        for (int i = 0; i < 1000; i++) {
            th[i].start();
        }

        while (count.get()!=0){
            //count.wait(100);
        }
    }

    @Test
    @DisplayName("test.writeRecordTest.case.sourceTest3")// 删除不存在的数据时，删除数量应该正确
    @TapTestCase(sort = 3)
    /**
     * 删除1条不存在的数据， 此时不应该报错， 且返回给引擎的插入， 修改和删除都应该为0.
     * */
    void sourceTest3() throws Throwable {
        consumeQualifiedTapNodeInfo(nodeInfo -> {
            TestNode prepare = prepare(nodeInfo);
            RecordEventExecute execute = prepare.recordEventExecute();
            targetTable();
            boolean isCreatedTable = false;
            try {
                super.connectorOnStart(prepare);
                execute.testCase(this.getMethod("sourceTest3"));
                isCreatedTable = super.createTable(prepare);
                sourceTest3Fun(execute, prepare.connectorNode());
            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                if (isCreatedTable) execute.dropTable();
                super.connectorOnStop(prepare);
            }
        });
        //waitCompleted(5000000);
    }
    void sourceTest3Fun( RecordEventExecute recordEventExecute,ConnectorNode connectorNode){
        Record[] records = Record.testStart(1);
        final int recLen = records.length;
        recordEventExecute.builderRecord(records);
        try {
            WriteListResult<TapRecordEvent> delete = recordEventExecute.delete();
            TapAssert.asserts(()->Assertions.assertTrue(
                null != delete &&
                    delete.getRemovedCount() ==0 &&
                    delete.getModifiedCount() ==0 &&
                    delete.getInsertedCount() ==0,
                TapSummary.format("wr.test3.deleteNotExist.error",
                    recLen,
                    delete.getInsertedCount(),
                    delete.getModifiedCount(),
                    delete.getRemovedCount()
                ))
            ).acceptAsWarn(
                recordEventExecute.testCase(),
                TapSummary.format("wr.test3.deleteNotExist.succeed",recLen)
            );
        }catch (Throwable throwable) {
            TapAssert.asserts(()->Assertions.assertDoesNotThrow(
                recordEventExecute::delete,
                TapSummary.format("wr.test3.deleteNotExist.catchThrowable",recLen))
            ).acceptAsError(
                 recordEventExecute.testCase(),
                TapSummary.format("wr.test3.deleteNotExist.notThrowable",recLen)
            );
        }
    }

    @Test
    @DisplayName("test.writeRecordTest.case.sourceTest4")//修改不存在的数据， 插入修改数量应该正确
    @TapTestCase(sort = 4)
    /**
     * 修改1条不存在的数据， 如果修改策略是insert_on_nonexists， 此时验证新插入应该是1个
     * 修改1条不存在的数据， 如果修改策略是 ignore_on_nonexists， 此时验证插入和修改都应该为0个
     * */
    void sourceTest4() throws Throwable {
        consumeQualifiedTapNodeInfo(nodeInfo -> {
            TestNode prepare = prepare(nodeInfo);
            boolean tableIsCreated = false;
            targetTable();
            RecordEventExecute execute = prepare.recordEventExecute();
            try {
                Method testCase = this.getMethod("sourceTest4");
                execute.testCase(testCase);
                super.connectorOnStart(prepare);
                tableIsCreated = super.createTable(prepare);
                sourceTest4Fun(execute, prepare.connectorNode());
            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                if (tableIsCreated) {
                    execute.dropTable();
                }
                super.connectorOnStop(prepare);
            }
        });
    }
    void sourceTest4Fun(RecordEventExecute recordEventExecute, ConnectorNode connectorNode){
        Method testCase = recordEventExecute.testCase();
        insertOnNotExists(recordEventExecute,connectorNode,testCase);
        ignoreOnNotExists(recordEventExecute,connectorNode,testCase);
    }
    private void insertOnNotExists(RecordEventExecute recordEventExecute, ConnectorNode connectorNode,Method testCase){
        Record[] records = Record.testStart(1);
        final int recLen = records.length;
        recordEventExecute.builderRecord(records);
        //修改1条不存在的数据， 如果修改策略是insert_on_nonexists， 此时验证新插入应该是1个
        super.insertOnExistsWhenUpdate(connectorNode.getConnectorContext());
        WriteListResult<TapRecordEvent> update1 = null;
        try {
            update1 = recordEventExecute.update();
        } catch (Throwable throwable) {
            TapAssert.asserts(()-> Assertions.fail(TapSummary.format("wr.test4.insertOnNotExists.throwable",recLen, throwable.getMessage()))).acceptAsError(testCase, null);
            return;
        }
        WriteListResult<TapRecordEvent> updateFinal1 = update1;
        long insert = null==update1?0: update1.getInsertedCount();
        long update = null==update1?0: update1.getModifiedCount();
        long delete = null==update1?0: update1.getRemovedCount();
        TapAssert.asserts(()->
            Assertions.assertTrue(
                null != updateFinal1 && insert == recLen && update == 0,
                TapSummary.format("wr.test4.insertOnNotExists.error",recLen,recLen,0,0,insert,update,delete))
        ).acceptAsWarn(
            testCase,
            TapSummary.format("wr.test4.insertOnNotExists.succeed",recLen,recLen,0,0,insert,update,delete)
        );
    }
    private void ignoreOnNotExists(RecordEventExecute recordEventExecute, ConnectorNode connectorNode,Method testCase){
        Record[] records = Record.testStart(1);
        final int recLen2 = records.length;
        recordEventExecute.resetRecords();
        recordEventExecute.builderRecord(records);
        //修改1条不存在的数据， 如果修改策略是 ignore_on_nonexists， 此时验证插入和修改都应该为0个
        super.ignoreOnExistsWhenUpdate(connectorNode.getConnectorContext());
        WriteListResult<TapRecordEvent> update2 = null;
        try {
            update2 = recordEventExecute.update();
        }catch (Throwable throwable){
            TapAssert.asserts(()-> Assertions.fail(TapSummary.format("wr.test4.ignoreOnNotExists.throwable",recLen2, throwable.getMessage()))).error(testCase);
            return;
        }
        final WriteListResult<TapRecordEvent> updateFinal2 = update2;
        long insert = null==update2?0: update2.getInsertedCount();
        long update = null==update2?0: update2.getModifiedCount();
        long delete = null==update2?0: update2.getRemovedCount();
        TapAssert.asserts(()->
            Assertions.assertTrue(
                null != updateFinal2 && insert == 0 && update == 0,
                TapSummary.format("wr.test4.ignoreOnNotExists.error",recLen2,0,0,0,insert,update,delete))
        ).acceptAsWarn(
            testCase,
            TapSummary.format("wr.test4.ignoreOnNotExists.succeed",recLen2,0,0,0,insert,update,delete)
        );
    }

    public static List<SupportFunction> testFunctions() {
        return list(
                support(WriteRecordFunction.class, TapSummary.format(inNeedFunFormat,"WriteRecordFunction"))
//                support(CreateTableFunction.class,"Create table is must to verify ,please implement CreateTableFunction in registerCapabilities method."),
                //support(QueryByAdvanceFilterFunction.class, "QueryByAdvanceFilterFunction is a must for database which is schema free to sample some record to generate the field data types.")
//                support(DropTableFunction.class, TapSummary.format(inNeedFunFormat,"DropTableFunction"))
        );
    }
}

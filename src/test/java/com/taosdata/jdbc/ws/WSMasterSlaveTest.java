package com.taosdata.jdbc.ws;

import com.taosdata.jdbc.TSDBDriver;
import com.taosdata.jdbc.annotation.CatalogRunner;
import com.taosdata.jdbc.annotation.Description;
import com.taosdata.jdbc.annotation.TestTarget;
import com.taosdata.jdbc.tmq.*;
import com.taosdata.jdbc.utils.SpecifyAddress;
import com.taosdata.jdbc.utils.TestUtils;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.sql.*;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(CatalogRunner.class)
@TestTarget(alias = "websocket query test", author = "yjshe", version = "2.0.38")
@FixMethodOrder
public class WSMasterSlaveTest {
    private static final String host = "localhost";
    private static final String hostB = "vm95";
    private static final int portB = 6041;
    private static final String dbName = TestUtils.camelToSnake(WSMasterSlaveTest.class);
    private static final String superTable = "st";
    private static Connection connection;
    private static Statement statement;
    private static String[] topics = {"topic_ws_map" + dbName};
    @Description("consumer")
    @Test
    public void consumerException() {
        Properties properties = new Properties();
        properties.setProperty(TMQConstants.CONNECT_USER, "root");
        properties.setProperty(TMQConstants.CONNECT_PASS, "taosdata");
        properties.setProperty(TMQConstants.BOOTSTRAP_SERVERS, "127.0.0.1:6041");
        properties.setProperty(TSDBDriver.PROPERTY_KEY_SLAVE_CLUSTER_HOST, hostB);
        properties.setProperty(TSDBDriver.PROPERTY_KEY_SLAVE_CLUSTER_PORT, String.valueOf(portB));

        properties.setProperty(TMQConstants.MSG_WITH_TABLE_NAME, "true");
        properties.setProperty(TMQConstants.ENABLE_AUTO_COMMIT, "true");
        properties.setProperty(TMQConstants.GROUP_ID, "ws_bean");
        properties.setProperty(TMQConstants.VALUE_DESERIALIZER, "com.taosdata.jdbc.tmq.ResultDeserializer");
        properties.setProperty(TMQConstants.CONNECT_TYPE, "ws");

        try {
            TaosConsumer<ResultBean> consumer = new TaosConsumer<>(properties);
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            return;
        }
        Assert.fail();
    }

    @Description("consumer")
    @Test
    public void consumerLoadBlance() throws IOException, SQLException, InterruptedException {
        System.setProperty("ENV_TAOS_JDBC_TEST", "test");

        TaosAdapterMock mock = new TaosAdapterMock(0);
        mock.start();
        int port = mock.getListenPort();

        AtomicInteger a = new AtomicInteger(1);
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("topic-thread-" + t.getId());
            return t;
        });
        scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                statement.executeUpdate(
                        "insert into ct0 values(now, " + a.getAndIncrement() + ", 0.2, 'a','一', true)" +
                                "(now+1s," + a.getAndIncrement() + ",0.4,'b','二', false)" +
                                "(now+2s," + a.getAndIncrement() + ",0.6,'c','三', false)");
            } catch (SQLException e) {
                // ignore
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        TimeUnit.MILLISECONDS.sleep(11);

        String topic = "topic_ws_map" + dbName;
        // create topic
        statement.executeUpdate("create topic if not exists " + topic + " as select ts, c1, c2, c3, c4, c5, t1 from ct0");


        Properties properties = new Properties();
        properties.setProperty(TMQConstants.CONNECT_USER, "root");
        properties.setProperty(TMQConstants.CONNECT_PASS, "taosdata");
        properties.setProperty(TMQConstants.BOOTSTRAP_SERVERS, "127.0.0.1:" + port + ",127.0.0.1:6041");

        properties.setProperty(TMQConstants.MSG_WITH_TABLE_NAME, "true");
        properties.setProperty(TMQConstants.ENABLE_AUTO_COMMIT, "true");
        properties.setProperty(TSDBDriver.PROPERTY_KEY_ENABLE_AUTO_RECONNECT, "true");
        properties.setProperty(TMQConstants.GROUP_ID, "ws_bean");
        properties.setProperty(TMQConstants.CONNECT_TYPE, "ws");

        try (TaosConsumer<Map<String, Object>> consumer = new TaosConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(topic));
            for (int i = 0; i < 10; i++) {
                ConsumerRecords<Map<String, Object>> consumerRecords = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<Map<String, Object>> r : consumerRecords) {
                    Map<String, Object> map = r.value();
                    Assert.assertEquals(7, map.size());
                    Assert.assertTrue(map.get("ts") instanceof Timestamp);
                }
                if (i == 2) {
                    mock.stop();
                    TimeUnit.SECONDS.sleep(1);
                }
            }
            consumer.unsubscribe();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        scheduledExecutorService.shutdown();
    }



    @BeforeClass
    public static void before() throws SQLException {
        String url = SpecifyAddress.getInstance().getRestUrl();
        if (url == null) {
            url = "jdbc:TAOS-RS://" + host + ":6041/?user=root&password=taosdata";
        }
        connection = DriverManager.getConnection(url);
        statement = connection.createStatement();
        for (String topic : topics) {
            statement.executeUpdate("drop topic if exists " + topic);
        }
        statement.execute("drop database if exists " + dbName);
        statement.execute("create database if not exists " + dbName + " WAL_RETENTION_PERIOD 3650");
        statement.execute("use " + dbName);
        statement.execute("create stable if not exists " + superTable
                + " (ts timestamp, c1 int, c2 float, c3 nchar(10), c4 binary(10), c5 bool) tags(t1 int)");


        statement.execute("create table if not exists ct0 using " + superTable + " tags(1000)");
        statement.execute("create table if not exists ct1 using " + superTable + " tags(2000)");
    }

    @AfterClass
    public static void after() throws InterruptedException {
        try {
            if (connection != null) {
                if (statement != null) {
                    for (String topic : topics) {
                        TimeUnit.SECONDS.sleep(3);
                        statement.executeUpdate("drop topic if exists " + topic);
                    }
                    statement.executeUpdate("drop database if exists " + dbName);
                    statement.close();
                }
                connection.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }
}

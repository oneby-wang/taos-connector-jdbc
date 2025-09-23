package com.taosdata.jdbc.ws.stmt;

import com.taosdata.jdbc.TSDBDriver;
import com.taosdata.jdbc.utils.SpecifyAddress;
import com.taosdata.jdbc.utils.TestUtils;
import com.taosdata.jdbc.utils.Utils;
import com.taosdata.jdbc.ws.TaosAdapterMock;
import io.netty.util.ResourceLeakDetector;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.sql.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
@FixMethodOrder
public class WsPstmtReconnectInsertTest {
    static String host = "localhost";
    static String db_name = TestUtils.camelToSnake(WsPstmtReconnectInsertTest.class);
    static String tableName = "wpt";
    static Connection connection;
    private final String actionStr;
    private final String mode;

    public WsPstmtReconnectInsertTest(String actionStr, String mode) {
        this.actionStr = actionStr;
        this.mode = mode;
    }
    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                { "\"action\":\"stmt2_init\"", "" },
                { "\"action\":\"stmt2_prepare\"", "" },
                { "\"action\":\"stmt2_bind\"", "" },
                { "\"action\":\"stmt2_exec\"", "" },

                { "\"action\":\"stmt2_init\"", "line" },
                { "\"action\":\"stmt2_prepare\"", "line" },
                { "\"action\":\"stmt2_bind\"", "line" },
                { "\"action\":\"stmt2_exec\"", "line" }
        });
    }

    private void stmt2Write(String url, Properties properties) throws SQLException {

        String sql = "INSERT INTO " + db_name + "." + tableName + "(tbname, groupId, location, ts, current, voltage, phase) VALUES (?,?,?,?,?,?,?)";

        try (Connection connection = DriverManager.getConnection(url, properties);
             PreparedStatement pstmt = connection.prepareStatement(sql)) {

            for (int i = 1; i <= 100; i++) {
                // set columns
                long current = System.currentTimeMillis();
                for (int j = 0; j < 1; j++) {
                    pstmt.setString(1, "d_bind_中国人" + i);
                    pstmt.setInt(2, i);
                    pstmt.setString(3, "location_" + i);

                    pstmt.setTimestamp(4, new Timestamp(current + i));
                    pstmt.setFloat(5, 1.0f);
                    pstmt.setInt(6, 2);
                    pstmt.setFloat(7, 3.0f);
                    pstmt.addBatch();
                }
                int[] exeResult = pstmt.executeBatch();

                for (int ele : exeResult){
                    Assert.assertEquals(ele, Statement.SUCCESS_NO_INFO);
                }
            }
            Assert.assertEquals((100), Utils.getSqlRows(connection, db_name + "." + tableName));
            Assert.assertEquals((1), Utils.getSqlRows(connection, db_name + "." + "`d_bind_中国人1`"));
            pstmt.execute("delete from " + db_name + "." + tableName);
        }
    }
    @Test
    public void testStmt2Reconnect() throws SQLException, IOException {
        TaosAdapterMock mockB = new TaosAdapterMock(this.actionStr, 1);
        mockB.start();

        Properties properties = new Properties();
        String url = "jdbc:TAOS-WS://"
                + host + ":" + mockB.getListenPort() + ","
                + host + ":" + 6041 + "/?user=root&password=taosdata";

        properties.setProperty(TSDBDriver.PROPERTY_KEY_ENABLE_AUTO_RECONNECT, "true");
        properties.setProperty(TSDBDriver.PROPERTY_KEY_RECONNECT_INTERVAL_MS, "2000");
        properties.setProperty(TSDBDriver.PROPERTY_KEY_RECONNECT_RETRY_COUNT, "3");
        properties.setProperty(TSDBDriver.PROPERTY_KEY_MESSAGE_WAIT_TIMEOUT, "5000");

        if ("line".equalsIgnoreCase(this.mode)) {
            properties.setProperty(TSDBDriver.PROPERTY_KEY_PBS_MODE, this.mode);
        }

        stmt2Write(url, properties);
        mockB.stop();
    }
    @BeforeClass
    public static void setUp() throws SQLException {
        System.setProperty("ENV_TAOS_JDBC_TEST", "test");
        TestUtils.runInMain();

        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        String url = SpecifyAddress.getInstance().getWebSocketWithoutUrl();
        if (url == null) {
            url = "jdbc:TAOS-WS://" + host + ":6041/?user=root&password=taosdata";
        } else {
            url += "?user=root&password=taosdata&batchfetch=true";
        }
        Properties properties = new Properties();
        connection = DriverManager.getConnection(url, properties);
        Statement statement = connection.createStatement();
        statement.execute("drop database if exists " + db_name);
        statement.execute("create database " + db_name);
        statement.execute("use " + db_name);
        statement.execute("create stable if not exists " + db_name + "." + tableName + " (ts TIMESTAMP, current FLOAT, voltage INT, phase FLOAT) TAGS (groupId INT, location BINARY(24))");
        statement.close();
    }

    @AfterClass
    public static void tearDown() throws SQLException {
        if (connection == null) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + db_name);
        }
        connection.close();
        System.gc();
    }
}

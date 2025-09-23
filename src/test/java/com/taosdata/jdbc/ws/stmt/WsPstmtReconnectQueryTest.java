package com.taosdata.jdbc.ws.stmt;

import com.taosdata.jdbc.TSDBDriver;
import com.taosdata.jdbc.utils.SpecifyAddress;
import com.taosdata.jdbc.utils.TestUtils;
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
public class WsPstmtReconnectQueryTest {
    static String host = "localhost";
    static String db_name = TestUtils.camelToSnake(WsPstmtReconnectQueryTest.class);
    static String tableName = "wpt";
    static Connection connection;
    private final String actionStr;
    private final String mode;

    public WsPstmtReconnectQueryTest(String actionStr, String mode) {
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
                { "\"action\":\"stmt2_result\"", "" },

                { "\"action\":\"stmt2_init\"", "line" },
                { "\"action\":\"stmt2_prepare\"", "line" },
                { "\"action\":\"stmt2_bind\"", "line" },
                { "\"action\":\"stmt2_exec\"", "line" },
                { "\"action\":\"stmt2_result\"", "line" }
        });
    }

    private void stmt2Query(String url, Properties properties) throws SQLException {

        String sql = "select * from " + db_name + "." + tableName + " where ts > ? and ts < ?";
        int resultCount = 0;

        try (Connection connection = DriverManager.getConnection(url, properties);
             PreparedStatement pstmt = connection.prepareStatement(sql)) {

            pstmt.setTimestamp(1, Timestamp.valueOf("2018-10-03 14:38:00"));
            pstmt.setTimestamp(2, Timestamp.valueOf("2018-10-03 14:39:00"));

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                resultCount++;
            }
            rs.close();
        }
        Assert.assertTrue(resultCount > 0);
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

        stmt2Query(url, properties);
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
        statement.execute("insert into d0 using " + db_name + "." + tableName + " " +
                "TAGS (2, \"California.SanFrancisco\") VALUES " +
                "    (\"2018-10-03 14:38:05\", 10.2, 220, 0.23),\n" +
                "    (\"2018-10-03 14:38:15\", 12.6, 218, 0.33),\n" +
                "    (\"2018-10-03 14:38:25\", 12.3, 221, 0.31) ");
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

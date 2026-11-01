package database;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import vcservlet.VCForkControl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The workbench sends {@code islocked}. Creating or updating a node or edge
 * must store that flag instead of leaving every row unlocked.
 */
public class LockFlagTest {

    private static final String MEMORY_URL = "jdbc:derby:memory:cispaceslock";

    @BeforeClass
    public static void startDatabase() throws Exception {
        System.setProperty("derby.stream.error.file", "target/derby.log");
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        DBConnect.setConnectionFactory(new DBConnect.ConnectionFactory() {
            @Override
            public Connection open() throws SQLException {
                return DriverManager.getConnection(MEMORY_URL);
            }
        });
        try (Connection connection = DriverManager.getConnection(MEMORY_URL + ";create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE CISPACES_NODE ("
                            + "NODEID varchar(255) PRIMARY KEY NOT NULL, "
                            + "SOURCE varchar(255), UNCERT varchar(20), EVAL varchar(5000), "
                            + "TXT varchar(5000), INP varchar(20), DTG timestamp, CMT varchar(50), "
                            + "TYPE varchar(10), ANNOT varchar(5000), GRAPHID varchar(255), ISLOCKED boolean)");
            statement.executeUpdate(
                    "CREATE TABLE CISPACES_EDGE ("
                            + "EDGEID varchar(255) PRIMARY KEY NOT NULL, "
                            + "TARGET varchar(255), SOURCE varchar(255), FORMEDGEID varchar(255), "
                            + "GRAPHID varchar(255), ISLOCKED boolean)");
        }
    }

    @AfterClass
    public static void stopDatabase() {
        DBConnect.setConnectionFactory(null);
        try {
            DriverManager.getConnection(MEMORY_URL + ";drop=true");
        } catch (SQLException expected) {
            // Derby reports a successful drop as SQLState 08006.
        }
    }

    @Test
    public void clientLockFlagIsStoredOnNodesAndEdges() throws Exception {
        VCForkControl control = new VCForkControl();
        control.evalJSONNode(nodeJson("node-locked", "true"));
        control.evalJSONNode(nodeJson("node-open", "false"));
        control.evalJSONEdge(edgeJson("edge-locked", "true"));
        control.evalJSONEdge(edgeJson("edge-open", "false"));

        assertTrue(storedLock("CISPACES_NODE", "nodeid", "node-locked"));
        assertFalse(storedLock("CISPACES_NODE", "nodeid", "node-open"));
        assertTrue(storedLock("CISPACES_EDGE", "edgeid", "edge-locked"));
        assertFalse(storedLock("CISPACES_EDGE", "edgeid", "edge-open"));
    }

    @Test
    public void updatingANodePersistsTheClientLockFlag() throws Exception {
        VCForkControl control = new VCForkControl();
        control.evalJSONNode(nodeJson("node-relock", "false"));
        assertFalse(storedLock("CISPACES_NODE", "nodeid", "node-relock"));

        control.evalJSONNode(nodeJson("node-relock", "true"));
        assertTrue(storedLock("CISPACES_NODE", "nodeid", "node-relock"));
    }

    @Test
    public void updateWithoutFlagLeavesLockAndExplicitFalseClearsIt() throws Exception {
        VCForkControl control = new VCForkControl();
        control.evalJSONNode(nodeJson("node-keep", "true"));
        assertTrue(storedLock("CISPACES_NODE", "nodeid", "node-keep"));

        control.evalJSONNode(nodeJsonWithoutLock("node-keep", "edited claim"));
        assertEquals("edited claim", storedText("node-keep"));
        assertTrue(storedLock("CISPACES_NODE", "nodeid", "node-keep"));

        String unlock = nodeJson("node-keep", "false").replace("\"islocked\":\"false\"", "\"islocked\":false");
        control.evalJSONNode(unlock);
        assertFalse(storedLock("CISPACES_NODE", "nodeid", "node-keep"));
    }

    @Test
    public void camelCaseKeyIsIgnored() throws Exception {
        VCForkControl control = new VCForkControl();
        String json = nodeJson("node-camel", "false").replace(
                "\"islocked\":\"false\"", "\"isLocked\":\"true\"");
        control.evalJSONNode(json);
        assertFalse(storedLock("CISPACES_NODE", "nodeid", "node-camel"));
    }

    private static String nodeJson(String nodeId, String locked) {
        return "{"
                + "\"nodeID\":\"" + nodeId + "\","
                + "\"source\":\"analyst\","
                + "\"uncert\":\"Confirmed\","
                + "\"eval\":\"N/A\","
                + "\"text\":\"claim\","
                + "\"input\":\"manual\","
                + "\"dtg\":\"2017-08-25 06:44:34\","
                + "\"commit\":\"N/A\","
                + "\"type\":\"I\","
                + "\"graphID\":\"graph-1\","
                + "\"islocked\":\"" + locked + "\""
                + "}";
    }

    private static String nodeJsonWithoutLock(String nodeId, String text) {
        return "{"
                + "\"nodeID\":\"" + nodeId + "\","
                + "\"source\":\"analyst\","
                + "\"uncert\":\"Confirmed\","
                + "\"eval\":\"N/A\","
                + "\"text\":\"" + text + "\","
                + "\"input\":\"manual\","
                + "\"dtg\":\"2017-08-25 06:44:34\","
                + "\"commit\":\"N/A\","
                + "\"type\":\"I\","
                + "\"graphID\":\"graph-1\""
                + "}";
    }

    private static String edgeJson(String edgeId, String locked) {
        return "{"
                + "\"target\":\"n-to\","
                + "\"source\":\"n-from\","
                + "\"formEdgeID\":\"form-1\","
                + "\"edgeID\":\"" + edgeId + "\","
                + "\"graphID\":\"graph-1\","
                + "\"islocked\":\"" + locked + "\""
                + "}";
    }

    private static String storedText(String nodeId) throws SQLException {
        ArrayList<HashMap<String, Object>> rows = new DBConnect().execSQL(
                "SELECT txt FROM CISPACES_NODE WHERE nodeid = '" + nodeId + "'");
        assertNotNull(rows);
        assertEquals(1, rows.size());
        return String.valueOf(rows.get(0).get("txt"));
    }

    private static boolean storedLock(String table, String idColumn, String id) throws SQLException {
        ArrayList<HashMap<String, Object>> rows = new DBConnect().execSQL(
                "SELECT islocked FROM " + table + " WHERE " + idColumn + " = '" + id + "'");
        assertNotNull(table + " " + id, rows);
        assertEquals(table + " " + id, 1, rows.size());
        return asBoolean(rows.get(0).get("islocked"));
    }

    private static boolean asBoolean(Object value) {
        String text = String.valueOf(value);
        if ("1".equals(text)) {
            return true;
        }
        if ("0".equals(text)) {
            return false;
        }
        return Boolean.parseBoolean(text);
    }
}

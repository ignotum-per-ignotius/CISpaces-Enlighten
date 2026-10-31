package database;

import filters.JwtSubjects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import utils.JsonHelper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Derby-backed check that a saved graph can be imported from its history snapshot
 * and that a failed import does not delete the live nodes.
 */
public class AnalysisImportTest {

    private static final String MEMORY_URL = "jdbc:derby:memory:cispacesit";

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
            createSchema(statement);
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
    public void saveImportAndReadRestoresNodes() throws Exception {
        String graphId = "graph-import";
        String userId = "user-1";
        DBQuery query = new DBQuery();
        assertTrue(query.insertGraph(graphId, userId, Timestamp.valueOf("2017-11-15 14:28:40"),
                "Sample", "A small graph", false, null));
        insertClaim(query, "node-1", graphId, "it's a claim");
        query.insertNode("node-2", "analyst", "Confirmed", "N/A", "second claim", "manual",
                Timestamp.valueOf("2017-11-15 14:28:40"), "note", "I", "scheme=expert", graphId, false);
        query.insertEdge("node-2", "node-1", null, "edge-1", graphId, false);

        String saved = query.saveLatestAnalysis(graphId, userId, "snapshot");
        assertTrue(saved.contains("\"status\":true"));

        JSONObject history = new JSONObject(query.getHistoryOfAnalysis(graphId));
        JSONArray snapshots = history.getJSONArray("history");
        assertEquals(1, snapshots.length());
        String analysis = snapshots.getJSONObject(0).getString("analysis");
        assertTrue(analysis.contains("it's a claim"));

        insertClaim(query, "node-1", graphId, "mutated");

        @SuppressWarnings("rawtypes")
        java.util.HashMap snapshot = new JsonHelper().convertInputMap(analysis);
        assertTrue(query.updateAnalysis(snapshot));

        JSONObject restored = new JSONObject(query.getAnalysis(graphId));
        Set<String> texts = nodeTexts(restored.getJSONArray("nodes"));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("it's a claim", "second claim")), texts);
        boolean sawScheme = false;
        JSONArray restoredNodes = restored.getJSONArray("nodes");
        for (int i = 0; i < restoredNodes.length(); i++) {
            JSONObject node = restoredNodes.getJSONObject(i);
            if ("second claim".equals(node.getString("text"))) {
                assertEquals("expert", node.getJSONObject("annot").getString("scheme"));
                sawScheme = true;
            }
        }
        assertTrue(sawScheme);
        JSONArray edges = restored.getJSONArray("edges");
        assertEquals(1, edges.length());
        assertEquals("edge-1", edges.getJSONObject(0).getString("edgeID"));
    }

    @Test
    public void failedImportRollsBackDeletes() throws Exception {
        String graphId = "graph-rollback";
        DBQuery query = new DBQuery();
        assertTrue(query.insertGraph(graphId, "user-1", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Rollback", "keep the original", false, null));
        insertClaim(query, "original-node", graphId, "original claim");

        java.util.HashMap<String, Object> snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("graphID", graphId);
        java.util.List<java.util.Map<String, String>> nodes = new java.util.ArrayList<java.util.Map<String, String>>();
        nodes.add(node("dup-node", graphId, "replacement"));
        nodes.add(node("dup-node", graphId, "duplicate"));
        snapshot.put("nodes", nodes);
        snapshot.put("edges", new java.util.ArrayList<Object>());

        assertFalse(query.updateAnalysis(snapshot));

        JSONObject live = new JSONObject(query.getAnalysis(graphId));
        Set<String> texts = nodeTexts(live.getJSONArray("nodes"));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("original claim")), texts);
    }

    @Test
    public void mismatchedNodeGraphIdDoesNotCrossWriteOrEmptySource() throws Exception {
        DBQuery query = new DBQuery();
        assertTrue(query.insertGraph("owned-graph", "user-1", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Owned", "stays", false, null));
        assertTrue(query.insertGraph("other-graph", "user-2", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Other", "not ours", false, null));
        insertClaim(query, "owned-node", "owned-graph", "owned claim");
        insertClaim(query, "other-node", "other-graph", "other claim");

        java.util.HashMap<String, Object> snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("graphID", "owned-graph");
        java.util.List<java.util.Map<String, String>> nodes = new java.util.ArrayList<java.util.Map<String, String>>();
        nodes.add(node("injected-node", "other-graph", "stolen"));
        snapshot.put("nodes", nodes);
        snapshot.put("edges", new java.util.ArrayList<Object>());

        assertFalse(query.updateAnalysis(snapshot));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("owned claim")),
                nodeTexts(new JSONObject(query.getAnalysis("owned-graph")).getJSONArray("nodes")));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("other claim")),
                nodeTexts(new JSONObject(query.getAnalysis("other-graph")).getJSONArray("nodes")));
    }

    @Test
    public void mismatchedEdgeGraphIdDoesNotCrossWriteOrEmptySource() throws Exception {
        DBQuery query = new DBQuery();
        assertTrue(query.insertGraph("owned-for-edge", "user-1", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Owned", "stays", false, null));
        assertTrue(query.insertGraph("victim-graph", "user-2", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Victim", "not ours", false, null));
        insertClaim(query, "owned-edge-node", "owned-for-edge", "still here");
        insertClaim(query, "victim-node", "victim-graph", "victim claim");

        java.util.HashMap<String, Object> snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("graphID", "owned-for-edge");
        java.util.List<java.util.Map<String, String>> nodes = new java.util.ArrayList<java.util.Map<String, String>>();
        nodes.add(node("owned-edge-node", "owned-for-edge", "still here"));
        snapshot.put("nodes", nodes);
        java.util.List<java.util.Map<String, String>> edges = new java.util.ArrayList<java.util.Map<String, String>>();
        java.util.Map<String, String> edge = new java.util.HashMap<String, String>();
        edge.put("edgeID", "injected-edge");
        edge.put("source", "victim-node");
        edge.put("target", "victim-node");
        edge.put("graphID", "victim-graph");
        edges.add(edge);
        snapshot.put("edges", edges);

        assertFalse(query.updateAnalysis(snapshot));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("still here")),
                nodeTexts(new JSONObject(query.getAnalysis("owned-for-edge")).getJSONArray("nodes")));
        assertEquals(0, new JSONObject(query.getAnalysis("owned-for-edge")).getJSONArray("edges").length());
        assertEquals(new HashSet<String>(java.util.Arrays.asList("victim claim")),
                nodeTexts(new JSONObject(query.getAnalysis("victim-graph")).getJSONArray("nodes")));
        assertEquals(0, new JSONObject(query.getAnalysis("victim-graph")).getJSONArray("edges").length());
    }

    @Test
    public void quotedIdentifierDoesNotMatchOtherGraphs() throws Exception {
        DBQuery query = new DBQuery();
        assertTrue(query.insertGraph("owned", "user-1", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Owned", "stays", false, null));
        insertClaim(query, "quote-owned-node", "owned", "keep me");

        String injected = "owned' OR '1'='1";
        assertNull(query.getAnalysis(injected));
        assertTrue(query.deleteAnalysis(injected));
        JSONArray remaining = new JSONObject(query.getAnalysis("owned")).getJSONArray("nodes");
        assertEquals(1, remaining.length());
        assertEquals("keep me", remaining.getJSONObject(0).getString("text"));
    }

    @Test
    public void secondUserCannotReadUnsharedGraph() throws Exception {
        DBQuery query = new DBQuery();
        assertTrue(query.insertGraph("private-read-graph", "user-1", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Private", "owner only", false, null));
        insertClaim(query, "secret-read-node", "private-read-graph", "secret claim");
        assertTrue(query.saveLatestAnalysis("private-read-graph", "user-1", "snapshot").contains("\"status\":true"));

        assertNull(query.getAnalysis("private-read-graph", "user-2"));
        assertNull(query.getAnalysisMeta("private-read-graph", "user-2"));
        assertNull(query.getHistoryOfAnalysis("private-read-graph", "user-2"));
        assertNull(query.getAnalysis("private-read-graph", "  "));

        JSONObject owned = new JSONObject(query.getAnalysis("private-read-graph", "user-1"));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("secret claim")),
                nodeTexts(owned.getJSONArray("nodes")));
        assertEquals("user-1", new JSONObject(query.getAnalysisMeta("private-read-graph", "user-1")).getString("userID"));
        assertTrue(query.getHistoryOfAnalysis("private-read-graph", "user-1").contains("secret claim"));

        assertTrue(query.insertGraph("shared-read-graph", "user-1", Timestamp.valueOf("2017-11-15 14:28:40"),
                "Shared", "anyone signed in", true, null));
        insertClaim(query, "shared-read-node", "shared-read-graph", "shared claim");
        assertTrue(query.saveLatestAnalysis("shared-read-graph", "user-1", "snapshot").contains("\"status\":true"));

        JSONObject shared = new JSONObject(query.getAnalysis("shared-read-graph", "user-2"));
        assertEquals(new HashSet<String>(java.util.Arrays.asList("shared claim")),
                nodeTexts(shared.getJSONArray("nodes")));
        assertNotNull(query.getAnalysisMeta("shared-read-graph", "user-2"));
        assertTrue(query.getHistoryOfAnalysis("shared-read-graph", "user-2").contains("shared claim"));
    }

    @Test
    public void tokenSubjectYieldsUserId() {
        assertEquals("user-1", JwtSubjects.userId("user-1"));
        assertEquals("user-1", JwtSubjects.userId("{\"user_id\":\"user-1\",\"username\":\"Ella\"}"));
        assertNull(JwtSubjects.userId("{\"username\":\"Ella\"}"));
        assertNull(JwtSubjects.userId("  "));
    }

    private static void insertClaim(DBQuery query, String nodeId, String graphId, String text) {
        query.insertNode(nodeId, "analyst", "Confirmed", "N/A", text, "manual",
                Timestamp.valueOf("2017-11-15 14:28:40"), "note", "I", "{}", graphId, false);
    }

    private static java.util.Map<String, String> node(String nodeId, String graphId, String text) {
        java.util.Map<String, String> node = new java.util.HashMap<String, String>();
        node.put("nodeID", nodeId);
        node.put("graphID", graphId);
        node.put("text", text);
        node.put("dtg", "2017-11-15 14:28:40");
        return node;
    }

    private static Set<String> nodeTexts(JSONArray nodes) {
        Set<String> texts = new HashSet<String>();
        for (int i = 0; i < nodes.length(); i++) {
            texts.add(nodes.getJSONObject(i).getString("text"));
        }
        return texts;
    }

    private static void createSchema(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE CISPACES_GRAPH ("
                + "GRAPHID varchar(255) PRIMARY KEY NOT NULL, USERID varchar(255), TIMEST timestamp, "
                + "TITLE varchar(255), DESCRIPTION varchar(5000), ISSHARED boolean, PARENTGRAPHID varchar(255))");
        statement.execute("CREATE TABLE CISPACES_GRAPH_HISTORY ("
                + "SNAPID varchar(255) PRIMARY KEY NOT NULL, GRAPHID varchar(255), USERID varchar(255), "
                + "TIMEST timestamp, ANALYSIS CLOB, TITLE varchar(255))");
        statement.execute("CREATE TABLE CISPACES_NODE ("
                + "NODEID varchar(255) PRIMARY KEY NOT NULL, SOURCE varchar(255), UNCERT varchar(20), "
                + "EVAL long varchar, TXT long varchar, INP varchar(20), DTG timestamp, CMT varchar(50), "
                + "TYPE varchar(10), ANNOT long varchar, GRAPHID varchar(255), ISLOCKED boolean)");
        statement.execute("CREATE TABLE CISPACES_EDGE ("
                + "EDGEID varchar(255) PRIMARY KEY NOT NULL, TARGET varchar(255), SOURCE varchar(255), "
                + "FORMEDGEID varchar(255), GRAPHID varchar(255), ISLOCKED boolean)");
        statement.execute("CREATE TABLE CISPACES_NODE_HISTORY ("
                + "revisionid varchar(255) PRIMARY KEY NOT NULL, nodeid varchar(255), graphid varchar(255))");
        statement.execute("CREATE TABLE CISPACES_EDGE_HISTORY ("
                + "revisionid varchar(255) PRIMARY KEY NOT NULL, edgeid varchar(255), graphid varchar(255))");
    }
}

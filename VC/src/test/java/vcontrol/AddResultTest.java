package vcontrol;

import database.DBConnect;
import database.DBQuery;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;

public class AddResultTest {

    @Test
    public void failedNodeInsertIsNotSuccess() throws Exception {
        HashMap result = control(false).onAddNodes(node());
        assertEquals("fail", result.get("response"));
    }

    @Test
    public void storedNodeInsertIsSuccess() throws Exception {
        HashMap result = control(true).onAddNodes(node());
        assertEquals("success", result.get("response"));
    }

    @Test
    public void failedEdgeInsertIsNotSuccess() throws Exception {
        HashMap result = control(false).onAddEdges(edge());
        assertEquals("fail", result.get("response"));
    }

    @Test
    public void storedEdgeInsertIsSuccess() throws Exception {
        HashMap result = control(true).onAddEdges(edge());
        assertEquals("success", result.get("response"));
    }

    @Test
    public void unreachableDatabaseIsNotSuccess() throws Exception {
        HashMap result = new VControl().onAddNodes(node());
        assertEquals("fail", result.get("response"));
    }

    private static VControl control(final boolean stored) {
        DBConnect db = new DBConnect() {
            @Override
            public synchronized ArrayList<HashMap<String, Object>> executeQuery(String sql, Object... params) {
                return new ArrayList<HashMap<String, Object>>();
            }

            @Override
            public synchronized boolean executeUpdate(String sql, Object... params) {
                return stored;
            }
        };
        return new VControl(new DBQuery(db));
    }

    private static HashMap node() {
        HashMap map = new HashMap();
        map.put("nodeID", "n1");
        map.put("text", "a claim");
        map.put("graphID", "g1");
        return map;
    }

    private static HashMap edge() {
        HashMap map = new HashMap();
        map.put("target", "n2");
        map.put("source", "n1");
        map.put("edgeID", "e1");
        map.put("graphID", "g1");
        return map;
    }
}

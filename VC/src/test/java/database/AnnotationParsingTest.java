package database;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AnnotationParsingTest {

    @Test
    public void missingNaAndEmptyObjectAreNoAnnotations() {
        assertFalse(row(null).containsKey("annot"));
        assertFalse(row("N/A").containsKey("annot"));
        assertFalse(row("{}").containsKey("annot"));
        assertFalse(row("  N/A  ").containsKey("annot"));
        assertFalse(row("").containsKey("annot"));
    }

    @Test
    public void rowWithoutAnnotColumnIsLeftAlone() {
        HashMap<String, Object> entry = new HashMap<String, Object>();
        entry.put("nodeid", "n1");
        entry.put("txt", "a claim");
        JSONObject node = first(entries(entry));
        assertEquals("n1", node.get("nodeID"));
        assertEquals("a claim", node.get("text"));
        assertFalse(node.containsKey("annot"));
    }

    @Test
    public void nonKeyValueDoesNotThrow() {
        assertFalse(row("not-a-pair").containsKey("annot"));
        assertFalse(row("{\"k\":\"v\"}").containsKey("annot"));
        JSONObject mixed = (JSONObject) row("scheme=expert,bogus,role=analyst").get("annot");
        assertEquals("expert", mixed.get("scheme"));
        assertEquals("analyst", mixed.get("role"));
        assertFalse(mixed.containsKey("bogus"));
    }

    @Test
    public void keyValuePairsAreKept() {
        JSONObject annot = (JSONObject) row("{scheme=expert}").get("annot");
        assertEquals("expert", annot.get("scheme"));
        JSONObject repeatedEquals = (JSONObject) row("a=b=c").get("annot");
        assertEquals("b", repeatedEquals.get("a"));
    }

    private static JSONObject row(String annot) {
        HashMap<String, Object> entry = new HashMap<String, Object>();
        entry.put("nodeid", "n1");
        entry.put("annot", annot);
        return first(entries(entry));
    }

    private static JSONObject first(ArrayList<HashMap<String, Object>> rows) {
        JSONArray parsed = new DBQuery().getResultListJSON(rows);
        return (JSONObject) parsed.get(0);
    }

    private static ArrayList<HashMap<String, Object>> entries(HashMap<String, Object> entry) {
        ArrayList<HashMap<String, Object>> rows = new ArrayList<HashMap<String, Object>>();
        rows.add(entry);
        return rows;
    }
}

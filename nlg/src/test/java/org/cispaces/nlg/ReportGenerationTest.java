package org.cispaces.nlg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Exercises the original explanation service on one supported claim.
 */
public class ReportGenerationTest {

    @Test
    public void reportNamesTheSupportedClaim() {
        String request = "{"
                + "\"graph\":{"
                + "\"nodes\":["
                + "{\"type\":\"I\",\"input\":\"INFO\",\"nodeID\":\"i1\",\"text\":\"A witness reported the blockage\",\"dtg\":\"2017-07-11 15:31:32\",\"source\":\"Miles\"},"
                + "{\"type\":\"RA\",\"nodeID\":\"r1\",\"text\":\"Pro\",\"dtg\":\"2017-07-11 15:32:02\",\"source\":\"Miles\"},"
                + "{\"type\":\"I\",\"input\":\"CLAIM\",\"nodeID\":\"c1\",\"text\":\"The route is blocked\",\"dtg\":\"2017-07-11 15:31:57\",\"source\":\"Miles\"}"
                + "],"
                + "\"edges\":["
                + "{\"source\":\"i1\",\"target\":\"r1\"},"
                + "{\"source\":\"r1\",\"target\":\"c1\"}"
                + "]"
                + "},"
                + "\"eval\":{\"colors\":{\"Skeptical\":{\"c1\":\"V\",\"i1\":\"V\"}}}"
                + "}";
        JSONObject report = new JSONObject(new ExtensionsBulletPoints(request).getText());
        assertFalse(report.getBoolean("fail"));
        String text = report.getString("text");
        assertTrue(text, text.contains("The route is blocked"));
        assertTrue(text, text.contains("A witness reported the blockage"));
    }
}

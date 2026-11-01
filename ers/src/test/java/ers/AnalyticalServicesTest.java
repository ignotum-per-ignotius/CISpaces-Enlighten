package ers;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.internal.LinkedTreeMap;

import ersdata.Schemes;
import utils.JsonHelper;

/**
 * Exercises the original argumentation schemes and the evidential reasoner.
 * The graph is the same shape the workbench posts to /ers/rest/WriteRules
 * after AIFDBConverter renames source/target to fromID/toID.
 */
public class AnalyticalServicesTest {

    @Test
    public void expertOpinionCriticalQuestionIsLoaded() {
        Schemes schemes = new Schemes(null);
        // DefCQs keeps critical questions attached to scheme assumptions.
        assertTrue(schemes.getCQs().contains("CQEO4"));
        assertTrue(schemes.getCQs().contains("CQCE5"));
    }

    @Test
    public void hypothesisEvaluationLabelsTheClaim() {
        String graph = "{"
                + "\"nodes\":["
                + "{\"nodeID\":\"info1\",\"type\":\"I\",\"input\":\"INFO\",\"text\":\"A witness saw the blockage\"},"
                + "{\"nodeID\":\"ra1\",\"type\":\"RA\",\"text\":\"Pro\"},"
                + "{\"nodeID\":\"claim1\",\"type\":\"I\",\"input\":\"CLAIM\",\"text\":\"The route is blocked\"}"
                + "],"
                + "\"edges\":["
                + "{\"fromID\":\"info1\",\"toID\":\"ra1\"},"
                + "{\"fromID\":\"ra1\",\"toID\":\"claim1\"}"
                + "]}";
        JsonHelper helper = new JsonHelper(false, true);
        LinkedTreeMap parsed = helper.convertInputMapG(graph);
        String response = new ERSControl(false, null).evaluateJsonString(parsed);
        assertTrue(response, response.contains("colors"));
        assertTrue(response, response.contains("claim1"));
    }
}

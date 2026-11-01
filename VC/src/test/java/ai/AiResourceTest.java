package ai;

import org.json.JSONObject;
import org.junit.Test;

import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;

public class AiResourceTest {

    @Test
    public void missingApiKeyReturns503() {
        AiResource resource = new AiResource(new LlmClient("https://example.invalid/v1", "test-model", null));
        Response response = resource.complete("{\"prompt\":\"summarise the claim\"}");

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        JSONObject body = new JSONObject(response.getEntity().toString());
        assertEquals(LlmClient.MISSING_KEY_MESSAGE, body.getString("error"));
    }

    @Test
    public void stubbedClientReturnsStubText() {
        AiResource resource = new AiResource(new LlmClient("https://example.invalid/v1", "test-model", "test-key") {
            @Override
            public String complete(String prompt, String graphJson) {
                return "stub text";
            }
        });

        Response response = resource.complete("{\"prompt\":\"hello\",\"graph\":{\"nodes\":[]}}");

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        JSONObject body = new JSONObject(response.getEntity().toString());
        assertEquals("stub text", body.getString("text"));
    }
}

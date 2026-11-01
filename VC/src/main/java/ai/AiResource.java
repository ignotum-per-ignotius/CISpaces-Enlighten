package ai;

import filters.JWTTokenNeeded;
import org.json.JSONObject;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.logging.Logger;

/**
 * Analyst completion endpoint.
 * URL: http://localhost:8080/VC/ai/complete
 *
 * The container applies {@link filters.JWTTokenNeededFilter}, the same signature
 * check as the other services. This class does not log the prompt, the API key,
 * or the bearer token. The servlet that publishes this resource does not install
 * Jersey's entity logging filter.
 */
@Path("/complete")
@JWTTokenNeeded
public class AiResource {

    static final int MAX_PROMPT_CHARS = 4000;
    static final int MAX_GRAPH_CHARS = 200000;

    private static final Logger log = Logger.getLogger(AiResource.class.getName());

    private final LlmClient client;

    public AiResource() {
        this(new LlmClient());
    }

    public AiResource(LlmClient client) {
        this.client = client;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response complete(String body) {
        String prompt;
        String graph;
        try {
            JSONObject json = new JSONObject(body == null ? "" : body);
            prompt = json.getString("prompt");
            graph = graphJson(json);
        } catch (RuntimeException e) {
            return error(Response.Status.BAD_REQUEST, "Request body must be a JSON object with a prompt string");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "prompt is required");
        }
        prompt = prompt.trim();
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return error(Response.Status.BAD_REQUEST, "prompt is too long");
        }
        if (graph != null && graph.length() > MAX_GRAPH_CHARS) {
            return error(Response.Status.BAD_REQUEST, "graph is too long");
        }

        try {
            String text = client.complete(prompt, graph);
            JSONObject ok = new JSONObject();
            ok.put("text", text == null ? "" : text);
            return Response.ok(ok.toString(), MediaType.APPLICATION_JSON).build();
        } catch (LlmClient.NotConfiguredException e) {
            return error(Response.Status.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (LlmClient.ModelRequestException e) {
            log.warning("AI completion failed");
            return error(Response.Status.BAD_GATEWAY, e.getMessage());
        }
    }

    private static String graphJson(JSONObject json) {
        if (!json.has("graph") || json.isNull("graph")) {
            return null;
        }
        Object graph = json.get("graph");
        if (graph instanceof String) {
            String text = ((String) graph).trim();
            return text.isEmpty() ? null : text;
        }
        return graph.toString();
    }

    private static Response error(Response.Status status, String message) {
        JSONObject body = new JSONObject();
        body.put("error", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body.toString()).build();
    }
}

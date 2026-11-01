package ai;

import filters.JWTTokenNeeded;
import filters.JWTTokenNeededFilter;
import filters.JwtSubjects;
import org.json.JSONObject;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Analyst completion and owner key setup.
 * URLs: http://localhost:8080/VC/ai/complete and http://localhost:8080/VC/ai/keys/{provider}
 *
 * The container applies {@link filters.JWTTokenNeededFilter}. This class does not log
 * the prompt, the API key, or the bearer token.
 */
@Path("/")
@JWTTokenNeeded
public class AiResource {

    static final int MAX_PROMPT_CHARS = 4000;
    static final int MAX_GRAPH_CHARS = 200000;

    private static final Logger log = Logger.getLogger(AiResource.class.getName());

    private final LlmClient client;
    private final ProviderKeyStore keys;
    private final OwnerGate owners;
    private final String callerOverride;

    @Context
    private ContainerRequestContext requestContext;

    @Inject
    public AiResource() {
        this(new LlmClient());
    }

    public AiResource(LlmClient client) {
        this(client, ProviderKeyStore.fromEnvironment(), new AdminOwnerGate(), null);
    }

    public AiResource(LlmClient client, ProviderKeyStore keys, OwnerGate owners, String callerOverride) {
        this.client = client;
        this.keys = keys;
        this.owners = owners;
        this.callerOverride = callerOverride;
    }

    @POST
    @Path("/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response complete(String body) {
        String prompt;
        String graph;
        String provider;
        try {
            JSONObject json = new JSONObject(body == null ? "" : body);
            prompt = json.getString("prompt");
            graph = graphJson(json);
            provider = providerField(json);
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, "provider must be openai, anthropic, or google");
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
            String storedKey = provider == null ? null : keys.get(provider);
            String text = client.complete(prompt, graph, provider, storedKey);
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

    /**
     * Owner-only. The key is written to the server-side store and is not returned.
     */
    @POST
    @Path("/keys/{provider}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setProviderKey(@PathParam("provider") String provider, String body) {
        String caller = callerId();
        if (caller == null || caller.isEmpty()) {
            return error(Response.Status.UNAUTHORIZED, "Authorization is required");
        }
        if (!owners.isOwner(caller)) {
            return error(Response.Status.FORBIDDEN, "Only an owner can set a provider API key");
        }
        String name = ProviderKeyStore.canonical(provider);
        if (name == null) {
            return error(Response.Status.BAD_REQUEST, "provider must be openai, anthropic, or google");
        }
        String apiKey;
        try {
            JSONObject json = new JSONObject(body == null ? "" : body);
            apiKey = json.getString("apiKey");
        } catch (RuntimeException e) {
            return error(Response.Status.BAD_REQUEST, "Request body must be a JSON object with an apiKey string");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "apiKey is required");
        }
        if (LlmClient.usableSecret(apiKey) == null) {
            return error(Response.Status.BAD_REQUEST, "apiKey is not a usable secret");
        }
        try {
            keys.put(name, apiKey);
        } catch (IOException e) {
            log.warning("Failed to store a provider API key");
            return error(Response.Status.INTERNAL_SERVER_ERROR, "The provider API key could not be stored");
        }
        log.log(java.util.logging.Level.INFO, "Stored an API key for provider {0}", name);
        JSONObject ok = new JSONObject();
        ok.put("provider", name);
        ok.put("stored", true);
        return Response.ok(ok.toString(), MediaType.APPLICATION_JSON).build();
    }

    private String callerId() {
        if (callerOverride != null) {
            return callerOverride;
        }
        if (requestContext == null) {
            return null;
        }
        Object subject = requestContext.getProperty(JWTTokenNeededFilter.JWT_SUBJECT);
        if (subject == null) {
            return null;
        }
        return JwtSubjects.userId(subject.toString());
    }

    private static String providerField(JSONObject json) {
        if (!json.has("provider") || json.isNull("provider")) {
            return null;
        }
        String name = ProviderKeyStore.canonical(json.getString("provider"));
        if (name == null) {
            throw new IllegalArgumentException("provider");
        }
        return name;
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

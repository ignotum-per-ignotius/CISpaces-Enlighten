package ai;

import org.json.JSONObject;
import org.junit.Test;

import javax.ws.rs.core.Response;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AiResourceTest {

    private static final String STORED_KEY = "sk-stored-provider-key";
    private static final String ENV_KEY = "sk-env-override-key";

    @Test
    public void missingApiKeyReturns503() {
        AiResource resource = new AiResource(new LlmClient("https://example.invalid/v1", "test-model", null));
        Response response = resource.complete("{\"prompt\":\"summarise the claim\"}");

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        JSONObject body = new JSONObject(response.getEntity().toString());
        assertEquals(LlmClient.MISSING_KEY_MESSAGE, body.getString("error"));
    }

    @Test
    public void unparseableBaseUrlReturns503() {
        AiResource resource = new AiResource(new LlmClient("not a url", "test-model", "test-key"));
        Response response = resource.complete("{\"prompt\":\"hello\"}");

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        JSONObject body = new JSONObject(response.getEntity().toString());
        assertEquals(LlmClient.MISSING_BASE_URL_MESSAGE, body.getString("error"));
    }

    @Test
    public void stubbedClientReturnsStubText() {
        AiResource resource = new AiResource(new LlmClient("https://example.invalid/v1", "test-model", "test-key") {
            @Override
            public String complete(String prompt, String graphJson, String provider, String storedKey) {
                return "stub text";
            }
        });

        Response response = resource.complete("{\"prompt\":\"hello\",\"graph\":{\"nodes\":[]}}");

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        JSONObject body = new JSONObject(response.getEntity().toString());
        assertEquals("stub text", body.getString("text"));
    }

    @Test
    public void ownerStoresKeyOnDiskAndResponseOmitsIt() throws Exception {
        File dir = newStoreDir();
        try {
            ProviderKeyStore store = new ProviderKeyStore(dir.toPath());
            AiResource resource = new AiResource(new LlmClient(null, null, null), store, owner("owner-1"), "owner-1");

            Response response = resource.setProviderKey("OpenAI", "{\"apiKey\":\"" + STORED_KEY + "\"}");

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            String entity = response.getEntity().toString();
            assertFalse(entity.contains(STORED_KEY));
            JSONObject body = new JSONObject(entity);
            assertEquals("openai", body.getString("provider"));
            assertTrue(body.getBoolean("stored"));
            assertEquals(STORED_KEY, new String(Files.readAllBytes(new File(dir, "openai.key").toPath()), StandardCharsets.UTF_8));
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(new File(dir, "openai.key").toPath());
            assertTrue(perms.contains(OWNER_READ));
            assertTrue(perms.contains(OWNER_WRITE));
            assertEquals(2, perms.size());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void nonOwnerCannotStoreAKey() throws Exception {
        File dir = newStoreDir();
        try {
            ProviderKeyStore store = new ProviderKeyStore(dir.toPath());
            AiResource resource = new AiResource(new LlmClient(null, null, null), store, owner("owner-1"), "analyst-2");

            Response response = resource.setProviderKey("anthropic", "{\"apiKey\":\"" + STORED_KEY + "\"}");

            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
            assertFalse(response.getEntity().toString().contains(STORED_KEY));
            assertFalse(new File(dir, "anthropic.key").exists());
            assertNull(store.get("anthropic"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void completeUsesStoredKeyWhenEnvKeyIsUnset() throws Exception {
        File dir = newStoreDir();
        try {
            ProviderKeyStore store = new ProviderKeyStore(dir.toPath());
            store.put(ProviderKeyStore.ANTHROPIC, STORED_KEY);
            RecordingExchange exchange = new RecordingExchange("{\"content\":[{\"text\":\"from-store\"}]}");
            LlmClient client = new LlmClient(null, null, null, exchange);
            AiResource resource = new AiResource(client, store, owner("owner-1"), "owner-1");

            Response response = resource.complete("{\"prompt\":\"hello\",\"provider\":\"anthropic\"}");

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            String entity = response.getEntity().toString();
            assertFalse(entity.contains(STORED_KEY));
            assertEquals("from-store", new JSONObject(entity).getString("text"));
            assertEquals("https://api.anthropic.com/v1/messages", exchange.url);
            assertEquals(STORED_KEY, exchange.headers.get("x-api-key"));
            assertNull(exchange.headers.get("Authorization"));
            assertFalse(new String(exchange.body, StandardCharsets.UTF_8).contains(STORED_KEY));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void envKeyOverridesStoredKey() throws Exception {
        File dir = newStoreDir();
        try {
            ProviderKeyStore store = new ProviderKeyStore(dir.toPath());
            store.put(ProviderKeyStore.OPENAI, STORED_KEY);
            RecordingExchange exchange = new RecordingExchange(
                    "{\"choices\":[{\"message\":{\"content\":\"from-env\"}}]}");
            LlmClient client = new LlmClient(null, null, ENV_KEY, exchange);
            AiResource resource = new AiResource(client, store, owner("owner-1"), "analyst-2");

            Response response = resource.complete("{\"prompt\":\"hello\",\"provider\":\"openai\"}");

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            String entity = response.getEntity().toString();
            assertFalse(entity.contains(STORED_KEY));
            assertFalse(entity.contains(ENV_KEY));
            assertEquals("from-env", new JSONObject(entity).getString("text"));
            assertEquals("Bearer " + ENV_KEY, exchange.headers.get("Authorization"));
            assertFalse(exchange.headers.get("Authorization").contains(STORED_KEY));
            assertFalse(new String(exchange.body, StandardCharsets.UTF_8).contains(ENV_KEY));
            assertFalse(new String(exchange.body, StandardCharsets.UTF_8).contains(STORED_KEY));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void googleStoredKeyUsesGoogleHeader() throws Exception {
        File dir = newStoreDir();
        try {
            ProviderKeyStore store = new ProviderKeyStore(dir.toPath());
            store.put(ProviderKeyStore.GOOGLE, STORED_KEY);
            RecordingExchange exchange = new RecordingExchange(
                    "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"from-google\"}]}}]}");
            AiResource resource = new AiResource(
                    new LlmClient(null, null, null, exchange), store, owner("owner-1"), "owner-1");

            Response response = resource.complete("{\"prompt\":\"hello\",\"provider\":\"google\"}");

            assertEquals("from-google", new JSONObject(response.getEntity().toString()).getString("text"));
            assertEquals(STORED_KEY, exchange.headers.get("x-goog-api-key"));
            assertFalse(exchange.url.contains(STORED_KEY));
            assertTrue(exchange.url.startsWith("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:"));
        } finally {
            deleteTree(dir);
        }
    }

    private static OwnerGate owner(final String userId) {
        return new OwnerGate() {
            @Override
            public boolean isOwner(String candidate) {
                return userId.equals(candidate);
            }
        };
    }

    private static File newStoreDir() {
        File dir = new File("target/ai-keys-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteTree(children[i]);
            }
        }
        file.delete();
    }

    private static final class RecordingExchange implements LlmClient.Exchange {
        private final String response;
        private String url;
        private Map<String, String> headers = new HashMap<String, String>();
        private byte[] body = new byte[0];

        private RecordingExchange(String response) {
            this.response = response;
        }

        @Override
        public String post(String url, Map<String, String> headers, byte[] body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
            return response;
        }
    }
}

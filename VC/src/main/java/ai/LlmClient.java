package ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Calls OpenAI, Anthropic, or Google. {@code CISPACES_AI_API_KEY} overrides a stored
 * provider key. {@code CISPACES_AI_BASE_URL} forces the OpenAI-compatible protocol.
 * The prompt, the API key, and tokens are never written to the log.
 */
public class LlmClient {

    public static final String ENV_BASE_URL = "CISPACES_AI_BASE_URL";
    public static final String ENV_MODEL = "CISPACES_AI_MODEL";
    public static final String ENV_API_KEY = "CISPACES_AI_API_KEY";
    public static final String MISSING_KEY_MESSAGE = "AI API key is not configured";

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Logger log = Logger.getLogger(LlmClient.class.getName());

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Exchange exchange;

    public interface Exchange {
        String post(String url, Map<String, String> headers, byte[] body);
    }

    public LlmClient() {
        this(System.getenv(ENV_BASE_URL), System.getenv(ENV_MODEL), System.getenv(ENV_API_KEY), null);
    }

    public LlmClient(String baseUrl, String model, String apiKey) {
        this(baseUrl, model, apiKey, null);
    }

    public LlmClient(String baseUrl, String model, String apiKey, Exchange exchange) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.exchange = exchange == null ? new HttpExchange() : exchange;
    }

    public String complete(String prompt, String graphJson) {
        return complete(prompt, graphJson, null, null);
    }

    /**
     * @param storedKey key loaded for {@code provider}, ignored when the env key is set
     */
    public String complete(String prompt, String graphJson, String provider, String storedKey) {
        String key = usableSecret(apiKey);
        if (key == null) {
            key = usableSecret(storedKey);
        }
        if (key == null) {
            log.warning("AI API key is not configured");
            throw new NotConfiguredException(MISSING_KEY_MESSAGE);
        }
        String content = userContent(prompt, graphJson);
        if (!isBlank(baseUrl)) {
            String modelName = modelName(provider);
            return messageText(exchange.post(
                    chatCompletionsUrl(baseUrl),
                    bearerHeaders(key),
                    openaiBody(modelName, content)));
        }
        if (provider == null) {
            log.warning("AI model base URL is not configured");
            throw new NotConfiguredException("AI model base URL is not configured");
        }
        String modelName = modelName(provider);
        if (ProviderKeyStore.ANTHROPIC.equals(provider)) {
            return anthropicText(exchange.post(
                    ProviderKeyStore.defaultBaseUrl(provider) + "/v1/messages",
                    anthropicHeaders(key),
                    anthropicBody(modelName, content)));
        }
        if (ProviderKeyStore.GOOGLE.equals(provider)) {
            return googleText(exchange.post(
                    ProviderKeyStore.defaultBaseUrl(provider) + "/models/" + modelName + ":generateContent",
                    googleHeaders(key),
                    googleBody(content)));
        }
        return messageText(exchange.post(
                chatCompletionsUrl(ProviderKeyStore.defaultBaseUrl(ProviderKeyStore.OPENAI)),
                bearerHeaders(key),
                openaiBody(modelName, content)));
    }

    private String modelName(String provider) {
        if (!isBlank(model)) {
            return safeModel(model);
        }
        String fallback = ProviderKeyStore.defaultModel(provider);
        if (fallback == null) {
            log.warning("AI model name is not configured");
            throw new NotConfiguredException("AI model name is not configured");
        }
        return fallback;
    }

    static String userContent(String prompt, String graphJson) {
        if (graphJson == null || graphJson.isEmpty()) {
            return prompt;
        }
        return prompt + "\n\nAnalysis graph JSON:\n" + graphJson;
    }

    static String chatCompletionsUrl(String baseUrl) {
        String root = baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/chat/completions";
    }

    /**
     * A usable secret has no whitespace that could break an HTTP header.
     * A missing or blank value is treated as not configured.
     */
    static String usableSecret(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 4096) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= ' ' || c == '\u007f') {
                return null;
            }
        }
        return trimmed;
    }

    private static String safeModel(String modelName) {
        String trimmed = modelName.trim();
        if (trimmed.isEmpty()
                || trimmed.indexOf('/') >= 0
                || trimmed.indexOf('\\') >= 0
                || trimmed.indexOf('?') >= 0
                || trimmed.indexOf('#') >= 0
                || trimmed.indexOf(':') >= 0
                || trimmed.indexOf(' ') >= 0) {
            throw new NotConfiguredException("AI model name is not configured");
        }
        return trimmed;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json");
        return headers;
    }

    private static Map<String, String> bearerHeaders(String key) {
        Map<String, String> headers = jsonHeaders();
        headers.put("Authorization", "Bearer " + key);
        return headers;
    }

    private static Map<String, String> anthropicHeaders(String key) {
        Map<String, String> headers = jsonHeaders();
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");
        return headers;
    }

    private static Map<String, String> googleHeaders(String key) {
        Map<String, String> headers = jsonHeaders();
        headers.put("x-goog-api-key", key);
        return headers;
    }

    private static byte[] openaiBody(String modelName, String content) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        JSONArray messages = new JSONArray();
        messages.put(message);
        JSONObject payload = new JSONObject();
        payload.put("model", modelName);
        payload.put("messages", messages);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] anthropicBody(String modelName, String content) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        JSONArray messages = new JSONArray();
        messages.put(message);
        JSONObject payload = new JSONObject();
        payload.put("model", modelName);
        payload.put("max_tokens", 1024);
        payload.put("messages", messages);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] googleBody(String content) {
        JSONObject part = new JSONObject();
        part.put("text", content);
        JSONArray parts = new JSONArray();
        parts.put(part);
        JSONObject contentItem = new JSONObject();
        contentItem.put("parts", parts);
        JSONArray contents = new JSONArray();
        contents.put(contentItem);
        JSONObject payload = new JSONObject();
        payload.put("contents", contents);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String messageText(String responseBody) {
        try {
            String text = new JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (text == null) {
                throw new ModelRequestException("The model request failed");
            }
            return text;
        } catch (ModelRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warning("AI model response did not contain message text");
            throw new ModelRequestException("The model request failed");
        }
    }

    private static String anthropicText(String responseBody) {
        try {
            String text = new JSONObject(responseBody)
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text");
            if (text == null) {
                throw new ModelRequestException("The model request failed");
            }
            return text;
        } catch (ModelRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warning("AI model response did not contain message text");
            throw new ModelRequestException("The model request failed");
        }
    }

    private static String googleText(String responseBody) {
        try {
            String text = new JSONObject(responseBody)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text");
            if (text == null) {
                throw new ModelRequestException("The model request failed");
            }
            return text;
        } catch (ModelRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warning("AI model response did not contain message text");
            throw new ModelRequestException("The model request failed");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class HttpExchange implements Exchange {
        @Override
        public String post(String url, Map<String, String> headers, byte[] body) {
            HttpURLConnection connection = null;
            try {
                URL target = new URL(url);
                if (!"https".equalsIgnoreCase(target.getProtocol()) && !"http".equalsIgnoreCase(target.getProtocol())) {
                    log.warning("AI model base URL is not an http(s) URL");
                    throw new NotConfiguredException("AI model base URL is not configured");
                }
                connection = (HttpURLConnection) target.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                // The upstream body can echo the prompt. Read it only to parse or discard it.
                String responseBody = stream == null ? "" : readLimited(stream);
                if (status < 200 || status >= 300) {
                    log.log(Level.WARNING, "AI model request failed, status {0}", status);
                    throw new ModelRequestException("The model request failed");
                }
                return responseBody;
            } catch (NotConfiguredException e) {
                throw e;
            } catch (ModelRequestException e) {
                throw e;
            } catch (Exception e) {
                log.log(Level.WARNING, "AI model request failed: {0}", e.getClass().getSimpleName());
                throw new ModelRequestException("The model request failed");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private static String readLimited(InputStream stream) throws java.io.IOException {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int allowed = MAX_RESPONSE_BYTES - out.size();
                    if (allowed <= 0) {
                        break;
                    }
                    out.write(buffer, 0, Math.min(read, allowed));
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                stream.close();
            }
        }
    }

    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    public static class ModelRequestException extends RuntimeException {
        public ModelRequestException(String message) {
            super(message);
        }
    }
}

package ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI-compatible chat client. The base URL, model name, and API key come from
 * the environment. Nothing here is a vendor key, and the prompt, key, and token
 * are never written to the log.
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

    public LlmClient() {
        this(System.getenv(ENV_BASE_URL), System.getenv(ENV_MODEL), System.getenv(ENV_API_KEY));
    }

    public LlmClient(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    /**
     * @param prompt analyst instruction, already checked by the resource
     * @param graphJson optional analysis graph, or null
     * @return the model message text
     */
    public String complete(String prompt, String graphJson) {
        String key = configuredSecret(apiKey);
        if (key == null) {
            log.warning("AI API key is not configured");
            throw new NotConfiguredException(MISSING_KEY_MESSAGE);
        }
        if (isBlank(baseUrl)) {
            log.warning("AI model base URL is not configured");
            throw new NotConfiguredException("AI model base URL is not configured");
        }
        if (isBlank(model)) {
            log.warning("AI model name is not configured");
            throw new NotConfiguredException("AI model name is not configured");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(chatCompletionsUrl(baseUrl));
            if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
                log.warning("AI model base URL is not an http(s) URL");
                throw new NotConfiguredException("AI model base URL is not configured");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key);

            byte[] payload = requestBody(model.trim(), userContent(prompt, graphJson));
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
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
            return messageText(responseBody);
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

    private static byte[] requestBody(String modelName, String content) {
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

    private static String messageText(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            String text = json.getJSONArray("choices")
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

    private static String readLimited(InputStream stream) throws IOException {
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

    /**
     * A usable secret has no whitespace that could break an HTTP header.
     * A missing or blank value is treated as not configured.
     */
    private static String configuredSecret(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

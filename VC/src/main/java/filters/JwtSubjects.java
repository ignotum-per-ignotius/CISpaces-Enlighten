package filters;

import org.json.JSONObject;

/**
 * The login token's subject is the user id. Tokens issued before that correction
 * stored the whole user JSON as the subject; both forms are accepted.
 */
public final class JwtSubjects {

    private JwtSubjects() {
    }

    public static String userId(String subject) {
        if (subject == null) {
            return null;
        }
        String trimmed = subject.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.charAt(0) == '{') {
            try {
                JSONObject user = new JSONObject(trimmed);
                if (user.has("user_id") && !user.isNull("user_id")) {
                    return user.getString("user_id").trim();
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            return null;
        }
        return trimmed;
    }
}

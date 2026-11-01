package ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

/**
 * Server-side provider keys. Each key is one file outside the source tree.
 * The file contents are never logged and are never copied into an HTTP response.
 */
public final class ProviderKeyStore {

    public static final String ENV_DIR = "CISPACES_AI_KEY_DIR";
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String GOOGLE = "google";

    private static final Logger log = Logger.getLogger(ProviderKeyStore.class.getName());

    private final Path directory;

    public static ProviderKeyStore fromEnvironment() {
        String configured = System.getenv(ENV_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            return new ProviderKeyStore(new java.io.File(configured.trim()).toPath());
        }
        String catalina = System.getProperty("catalina.base");
        if (catalina != null && !catalina.trim().isEmpty()) {
            return new ProviderKeyStore(new java.io.File(catalina.trim(), "conf/cispaces-ai-keys").toPath());
        }
        return new ProviderKeyStore(new java.io.File(System.getProperty("user.home"), ".cispaces/ai-keys").toPath());
    }

    public ProviderKeyStore(Path directory) {
        this.directory = directory;
    }

    public static String canonical(String provider) {
        if (provider == null) {
            return null;
        }
        String name = provider.trim().toLowerCase(Locale.ROOT);
        if (OPENAI.equals(name) || ANTHROPIC.equals(name) || GOOGLE.equals(name)) {
            return name;
        }
        return null;
    }

    public static String defaultModel(String provider) {
        if (OPENAI.equals(provider)) {
            return "gpt-4o";
        }
        if (ANTHROPIC.equals(provider)) {
            return "claude-sonnet-4-5";
        }
        if (GOOGLE.equals(provider)) {
            return "gemini-2.5-flash";
        }
        return null;
    }

    public static String defaultBaseUrl(String provider) {
        if (OPENAI.equals(provider)) {
            return "https://api.openai.com/v1";
        }
        if (ANTHROPIC.equals(provider)) {
            return "https://api.anthropic.com";
        }
        if (GOOGLE.equals(provider)) {
            return "https://generativelanguage.googleapis.com/v1beta";
        }
        return null;
    }

    public synchronized void put(String provider, String apiKey) throws IOException {
        String name = canonical(provider);
        String secret = LlmClient.usableSecret(apiKey);
        if (name == null || secret == null) {
            throw new IOException("provider key was rejected");
        }
        Files.createDirectories(directory);
        ownerOnly(directory, true);
        Path dest = directory.resolve(name + ".key");
        Path tmp = directory.resolve(name + ".key.tmp");
        try {
            Files.write(tmp, secret.getBytes(StandardCharsets.UTF_8), CREATE, TRUNCATE_EXISTING, WRITE);
            ownerOnly(tmp, false);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            ownerOnly(dest, false);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // The temp file is removed on the next successful write.
            }
        }
    }

    /** In-process read for the model call. Do not place the result in a response or a log. */
    public synchronized String get(String provider) {
        String name = canonical(provider);
        if (name == null) {
            return null;
        }
        Path dest = directory.resolve(name + ".key");
        if (!Files.isRegularFile(dest)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(dest);
            return LlmClient.usableSecret(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("Failed to read a stored provider API key");
            return null;
        }
    }

    private static void ownerOnly(Path path, boolean directory) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms = new HashSet<java.nio.file.attribute.PosixFilePermission>();
            perms.add(OWNER_READ);
            perms.add(OWNER_WRITE);
            if (directory) {
                perms.add(OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems have no owner-only mode.
        } catch (IOException e) {
            log.warning("Could not restrict permissions on the provider key store");
        }
    }
}

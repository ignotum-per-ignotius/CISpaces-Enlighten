package filters;

import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * nlg does not depend on the common module. This copy reads the same shared
 * file as common's KeyGenerator: CISPACES_JWT_KEY_FILE, or
 * ${CISPACES}/config/key.properties. Classpath copies are not read.
 */
public class KeyGenerator {

    private static final Logger log = Logger.getLogger(KeyGenerator.class.getName());
    private static final int MIN_KEY_LENGTH = 32;

    public static Key generateKey() {
        String keyString = readSharedKey();
        if (keyString == null) {
            return null;
        }
        byte[] keyBytes = keyString.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, "DES");
    }

    private static String readSharedKey() {
        String path = System.getenv("CISPACES_JWT_KEY_FILE");
        if (path == null || path.trim().isEmpty()) {
            String home = System.getenv("CISPACES");
            if (home != null && !home.trim().isEmpty()) {
                path = home.trim() + "/config/key.properties";
            }
        }
        if (path == null || path.trim().isEmpty()) {
            log.severe("#### Set CISPACES_JWT_KEY_FILE to the shared JWT key file");
            return null;
        }
        path = path.trim();
        Properties prop = new Properties();
        InputStream input = null;
        try {
            input = new FileInputStream(path);
            prop.load(input);
            String keyString = prop.getProperty("keyString");
            if (keyString != null) {
                keyString = keyString.trim();
            }
            if (keyString == null || keyString.length() < MIN_KEY_LENGTH) {
                log.severe("#### Shared JWT key is missing or too short");
                return null;
            }
            log.info("#### Read JWT authentication key from shared file");
            return keyString;
        } catch (IOException exc) {
            log.log(Level.SEVERE, "#### Failed to load shared JWT key", exc);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException exc) {
                    log.log(Level.SEVERE, "#### Failed to close JWT key file", exc);
                }
            }
        }
    }
}

package database;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import vcservlet.VCForkControl;
import vcontrol.VControl;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * New password hashes use the current PBKDF2 iteration count, a freshly
 * created user can still log in, and a successful login rewrites an older hash.
 */
public class PasswordHashTest {

    private static final String MEMORY_URL = "jdbc:derby:memory:cispacespw";

    @BeforeClass
    public static void startDatabase() throws Exception {
        System.setProperty("derby.stream.error.file", "target/derby.log");
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        DBConnect.setConnectionFactory(new DBConnect.ConnectionFactory() {
            @Override
            public Connection open() throws SQLException {
                return DriverManager.getConnection(MEMORY_URL);
            }
        });
        try (Connection connection = DriverManager.getConnection(MEMORY_URL + ";create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE CISPACES_USERS ("
                            + "USER_ID char(36), USERNAME varchar(255), PASSWORD varchar(255), "
                            + "AFFILIATION varchar(10), IS_ADMIN int)");
        }
    }

    @AfterClass
    public static void stopDatabase() {
        DBConnect.setConnectionFactory(null);
        try {
            DriverManager.getConnection(MEMORY_URL + ";drop=true");
        } catch (SQLException expected) {
            // Derby reports a successful drop as SQLState 08006.
        }
    }

    @Test
    public void newHashUsesCurrentIterationCountAndLoginWorks() throws Exception {
        assertEquals(1400000, VControl.PBKDF2_ITERATIONS);
        assertTrue(VControl.PBKDF2_ITERATIONS >= 210000);

        VCForkControl control = new VCForkControl();
        String username = "fresh-user";
        String secret = "fresh-secret";
        String added = control.evalJSONAddUser(
                "{\"username\":\"" + username + "\",\"password\":\"" + secret + "\",\"affiliation\":\"UK\"}");
        assertNotNull(added);

        String stored = storedPassword(username);
        assertTrue(stored.startsWith(VControl.PBKDF2_ITERATIONS + ":"));

        String login = control.evalJSONLoginUser(
                "{\"username\":\"" + username + "\",\"password\":\"" + secret + "\"}");
        assertNotNull(login);
        assertTrue(login.contains(username));
        assertFalse(login.contains(secret));
        assertEquals(stored, storedPassword(username));
    }

    @Test
    public void loginRewritesAnOlderIterationCount() throws Exception {
        String username = "legacy-user";
        String secret = "legacy-pass";
        String legacy = legacyHash(secret);
        assertTrue(legacy.startsWith("1000:"));
        try (Connection connection = DriverManager.getConnection(MEMORY_URL);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO CISPACES_USERS (USER_ID, USERNAME, PASSWORD, AFFILIATION, IS_ADMIN) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, "11111111-1111-1111-1111-111111111111");
            statement.setString(2, username);
            statement.setString(3, legacy);
            statement.setString(4, "UK");
            statement.setInt(5, 0);
            statement.executeUpdate();
        }

        VCForkControl control = new VCForkControl();
        String login = control.evalJSONLoginUser(
                "{\"username\":\"" + username + "\",\"password\":\"" + secret + "\"}");
        assertNotNull(login);
        assertFalse(login.contains(secret));

        String upgraded = storedPassword(username);
        assertTrue(upgraded.startsWith(VControl.PBKDF2_ITERATIONS + ":"));
        assertFalse(legacy.equals(upgraded));

        assertNotNull(control.evalJSONLoginUser(
                "{\"username\":\"" + username + "\",\"password\":\"" + secret + "\"}"));
    }

    private static String storedPassword(String username) throws SQLException {
        try (Connection connection = DriverManager.getConnection(MEMORY_URL);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT PASSWORD FROM CISPACES_USERS WHERE USERNAME = ?")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static String legacyHash(String secret) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, 1000, 64 * 8);
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        return "1000:" + toHex(salt) + ":" + toHex(hash);
    }

    private static String toHex(byte[] array) {
        BigInteger bi = new BigInteger(1, array);
        String hex = bi.toString(16);
        int paddingLength = (array.length * 2) - hex.length();
        if (paddingLength > 0) {
            return String.format("%0" + paddingLength + "d", 0) + hex;
        }
        return hex;
    }
}

package ai;

import database.DBConnect;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * An owner is a row in {@code CISPACES_USERS} whose {@code IS_ADMIN} flag is set.
 * The query does not read the password column.
 */
public final class AdminOwnerGate implements OwnerGate {

    private final DBConnect database;

    public AdminOwnerGate() {
        this(new DBConnect(DBConnect.ColumnValues.TEXT, false));
    }

    AdminOwnerGate(DBConnect database) {
        this.database = database;
    }

    @Override
    public boolean isOwner(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        ArrayList<HashMap<String, Object>> rows = database.executeQuery(
                "SELECT IS_ADMIN FROM CISPACES_USERS WHERE USER_ID = ?", userId.trim());
        if (rows.isEmpty() || rows.get(0).get("is_admin") == null) {
            return false;
        }
        String flag = rows.get(0).get("is_admin").toString().trim();
        return "1".equals(flag) || "true".equalsIgnoreCase(flag);
    }
}

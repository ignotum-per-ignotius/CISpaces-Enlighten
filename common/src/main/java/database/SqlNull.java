package database;

/**
 * Explicit SQL NULL so a prepared-statement binder can pick the JDBC type.
 * A bare Java null is bound as VARCHAR, which Derby rejects for TIMESTAMP columns.
 */
final class SqlNull {
    final int jdbcType;

    private SqlNull(int jdbcType) {
        this.jdbcType = jdbcType;
    }

    static SqlNull varchar() {
        return new SqlNull(java.sql.Types.VARCHAR);
    }

    static SqlNull timestamp() {
        return new SqlNull(java.sql.Types.TIMESTAMP);
    }
}

package database;

import org.junit.Test;

import java.sql.SQLException;

import static org.junit.Assert.fail;

public class DBConnectExecSQLTest {

    @Test
    public void failedQueryThrowsInsteadOfReturningNull() {
        DBConnect dbConnect = new DBConnect();
        try {
            dbConnect.execSQL("SELECT 1 FROM CISPACES_GRAPH");
            fail("a failed Derby query must throw instead of returning null");
        } catch (SQLException expected) {
            // No datasource is configured here, so the query cannot run.
        }
    }
}

package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class D1ConnectionConfigTest {
    private static final String UUID = "3f1b6aab-97b8-4db6-8df4-1ace68f716a0";

    private static Properties creds(String user, String password) {
        Properties p = new Properties();
        if (user != null) p.setProperty("user", user);
        if (password != null) p.setProperty("password", password);
        return p;
    }

    @Test
    void parsesUuidUrlWithUserAndPassword() throws SQLException {
        D1ConnectionConfig c = D1ConnectionConfig.parse("jdbc:d1://" + UUID, creds("acc", "tok"));
        assertEquals("acc", c.getAccountId());
        assertEquals("tok", c.getToken());
        assertEquals(UUID, c.getDatabase());
        assertTrue(c.isDatabaseUuid());
        assertEquals(D1ConnectionConfig.DEFAULT_API_BASE, c.getApiBase());
        assertEquals(30, c.getTimeoutSeconds());
    }

    @Test
    void acceptsNameWithoutSlashesAndTrailingSlash() throws SQLException {
        assertEquals("my-db", D1ConnectionConfig.parse("jdbc:d1:my-db", creds("a", "t")).getDatabase());
        D1ConnectionConfig c = D1ConnectionConfig.parse("jdbc:d1://my-db/", creds("a", "t"));
        assertEquals("my-db", c.getDatabase());
        assertFalse(c.isDatabaseUuid());
    }

    @Test
    void urlQueryParametersAreReadAndInfoOverridesThem() throws SQLException {
        Properties info = creds("acc", "tok");
        info.setProperty("timeoutSeconds", "5");
        D1ConnectionConfig c = D1ConnectionConfig.parse(
                "jdbc:d1://db?apiBase=http%3A%2F%2F127.0.0.1%3A8080%2Fv4%2F&timeoutSeconds=9", info);
        assertEquals("http://127.0.0.1:8080/v4", c.getApiBase());
        assertEquals(5, c.getTimeoutSeconds());
    }

    @Test
    void explicitAccountIdAndTokenWinOverUserAndPassword() throws SQLException {
        Properties info = creds("user-acc", "pw-token");
        info.setProperty("accountId", "acc2");
        info.setProperty("token", "tok2");
        D1ConnectionConfig c = D1ConnectionConfig.parse("jdbc:d1://db", info);
        assertEquals("acc2", c.getAccountId());
        assertEquals("tok2", c.getToken());
    }

    @Test
    void missingCredentialsFailWithSqlState28000() {
        SQLException noUser = assertThrows(SQLException.class,
                () -> D1ConnectionConfig.parse("jdbc:d1://db", creds(null, "tok")));
        assertEquals("28000", noUser.getSQLState());
        SQLException noToken = assertThrows(SQLException.class,
                () -> D1ConnectionConfig.parse("jdbc:d1://db", creds("acc", " ")));
        assertEquals("28000", noToken.getSQLState());
    }

    @Test
    void missingDatabaseFails() {
        SQLException e = assertThrows(SQLException.class,
                () -> D1ConnectionConfig.parse("jdbc:d1://", creds("a", "t")));
        assertEquals("08001", e.getSQLState());
    }

    @Test
    void invalidTimeoutFails() {
        Properties info = creds("a", "t");
        info.setProperty("timeoutSeconds", "abc");
        assertThrows(SQLException.class, () -> D1ConnectionConfig.parse("jdbc:d1://db", info));
    }

    @Test
    void acceptsOnlyD1Urls() {
        assertTrue(D1ConnectionConfig.acceptsUrl("jdbc:d1://x"));
        assertFalse(D1ConnectionConfig.acceptsUrl("jdbc:sqlite:x"));
        assertFalse(D1ConnectionConfig.acceptsUrl(null));
    }
}

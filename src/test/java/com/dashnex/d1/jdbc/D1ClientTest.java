package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.dashnex.d1.jdbc.StubD1Server.*;
import static org.junit.jupiter.api.Assertions.*;

class D1ClientTest {
    private StubD1Server stub;
    private D1Client client;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubD1Server();
        client = new D1Client(stub.config(), new long[]{1, 1, 1});
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void executeSendsAuthorizedJsonAndParsesResults() throws SQLException {
        stub.enqueue(ok(result(new String[]{"i", "r", "s", "b", "n"},
                new Object[][]{{1, 1.5, "x", new int[]{1, 2, 255}, null}})));
        List<D1Result> results = client.execute("SELECT ?, ?, ?, ?, ?",
                Arrays.asList(true, 7L, 2.5, new byte[]{1, (byte) 255}, null));

        Request req = stub.lastRequest();
        assertEquals("POST", req.method);
        assertEquals("/client/v4/accounts/" + ACCOUNT + "/d1/database/" + DB_UUID + "/raw", req.path);
        assertEquals("Bearer " + TOKEN, req.authorization);
        assertEquals("SELECT ?, ?, ?, ?, ?", req.sql());
        JsonNode p = req.params();
        assertEquals(1, p.get(0).asInt());
        assertEquals(7, p.get(1).asLong());
        assertEquals(2.5, p.get(2).asDouble());
        assertTrue(p.get(3).isArray());
        assertEquals(255, p.get(3).get(1).asInt());
        assertTrue(p.get(4).isNull());

        assertEquals(1, results.size());
        D1Result r = results.get(0);
        assertEquals(List.of("i", "r", "s", "b", "n"), r.getColumns());
        Object[] row = r.getRows().get(0);
        assertEquals(1L, row[0]);
        assertEquals(1.5, row[1]);
        assertEquals("x", row[2]);
        assertArrayEquals(new byte[]{1, 2, (byte) 255}, (byte[]) row[3]);
        assertNull(row[4]);
        assertTrue(r.hasColumns());
    }

    @Test
    void parsesChangesAndLastRowIdForEachStatement() throws SQLException {
        stub.enqueue(ok(empty(1, 42), empty(0, 42)));
        List<D1Result> results = client.execute("INSERT INTO t VALUES (1); CREATE INDEX i ON t(a)", List.of());
        assertEquals(2, results.size());
        assertEquals(1, results.get(0).getChanges());
        assertEquals(42, results.get(0).getLastRowId());
        assertFalse(results.get(1).hasColumns());
    }

    @Test
    void batchSendsAllStatements() throws SQLException {
        stub.enqueue(ok(empty(1, 1), empty(1, 2)));
        List<D1Result> results = client.batch(List.of(
                new D1Client.Stmt("INSERT INTO t(a) VALUES (?)", List.of("x")),
                new D1Client.Stmt("INSERT INTO t(a) VALUES (?)", List.of("y"))));
        JsonNode batch = stub.lastRequest().body.get("batch");
        assertEquals(2, batch.size());
        assertEquals("y", batch.get(1).get("params").get(0).asText());
        assertEquals(2, results.size());
    }

    @Test
    void mapsErrorsToSqlStates() {
        stub.enqueue(error(400, 7500, "UNIQUE constraint failed: t.e: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_UNIQUE)"));
        SQLException constraint = assertThrows(SQLException.class, () -> client.execute("INSERT INTO t VALUES (1)", List.of()));
        assertEquals("23000", constraint.getSQLState());
        assertEquals(7500, constraint.getErrorCode());
        assertTrue(constraint.getMessage().contains("UNIQUE constraint failed"));

        stub.enqueue(error(400, 7500, "near \"selec\": syntax error at offset 0: SQLITE_ERROR"));
        assertEquals("42000", assertThrows(SQLException.class, () -> client.execute("selec 1", List.of())).getSQLState());

        stub.enqueue(error(401, 10000, "Authentication error"));
        SQLException auth = assertThrows(SQLException.class, () -> client.execute("SELECT 1", List.of()));
        assertEquals("28000", auth.getSQLState());
        assertTrue(auth.getMessage().contains("D1"));

        stub.enqueue(error(404, 7404, "The database x could not be found"));
        assertEquals("08001", assertThrows(SQLException.class, () -> client.execute("SELECT 1", List.of())).getSQLState());

        stub.enqueue(new StubD1Server.Response(502, "<html>bad gateway</html>"));
        stub.enqueue(new StubD1Server.Response(502, "<html>bad gateway</html>"));
        stub.enqueue(new StubD1Server.Response(502, "<html>bad gateway</html>"));
        stub.enqueue(new StubD1Server.Response(502, "<html>bad gateway</html>"));
        SQLException gateway = assertThrows(SQLException.class, () -> client.execute("SELECT 1", List.of()));
        assertTrue(gateway.getMessage().contains("502"));
    }

    @Test
    void sqlErrorWithHttp403IsMappedByMessageNotTreatedAsAuthFailure() {
        // F9: D1 can return a SQL error (code 7500) on an HTTP 403 response; it must still be classified
        // by its message (constraint/syntax/etc.), not swallowed by the 401/403 "bad token" branch.
        stub.enqueue(error(403, 7500, "UNIQUE constraint failed: t.e: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_UNIQUE)"));
        SQLException e = assertThrows(SQLException.class, () -> client.execute("INSERT INTO t VALUES (1)", List.of()));
        assertEquals("23000", e.getSQLState());
        assertEquals(7500, e.getErrorCode());
        assertTrue(e.getMessage().contains("UNIQUE constraint failed"));
    }

    @Test
    void retries429ThenSucceeds() throws SQLException {
        stub.enqueue(error(429, 971, "rate limited"));
        stub.enqueue(ok(empty(1, 5)));
        List<D1Result> results = client.execute("INSERT INTO t VALUES (1)", List.of());
        assertEquals(1, results.get(0).getChanges());
        assertEquals(2, stub.requests().size());
    }

    @Test
    void retries5xxOnlyForReadOnlySql() throws SQLException {
        stub.enqueue(error(503, 0, "unavailable"));
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));
        assertEquals(1, client.execute("SELECT 1", List.of()).size());
        assertEquals(2, stub.requests().size());

        stub.enqueue(error(503, 0, "unavailable"));
        stub.enqueue(ok(empty(1, 1)));
        assertThrows(SQLException.class, () -> client.execute("DELETE FROM t", List.of()));
        assertEquals(3, stub.requests().size());
    }

    @Test
    void requestTimeoutIsNotRetried() throws Exception {
        // F5: HttpTimeoutException is an IOException but must never be retried, even for read-only SQL
        // (retrying a 30s-timeout query 3x would make a slow-but-alive D1 look hung for 2 minutes).
        D1Client oneSecondClient = new D1Client(
                D1ConnectionConfig.parse(stub.url() + "&timeoutSeconds=1", stub.credentials()), new long[]{1, 1, 1});
        stub.handler(r -> {
            sleep(3000);
            return ok(result(new String[]{"1"}, new Object[][]{{1}}));
        });
        SQLException e = assertThrows(SQLException.class, () -> oneSecondClient.execute("SELECT 1", List.of()));
        assertEquals("08006", e.getSQLState());
        assertEquals(1, stub.requests().size());
    }

    @Test
    void pingHonoursItsOwnTimeoutWithoutRetry() throws Exception {
        stub.handler(r -> {
            sleep(3000);
            return ok(result(new String[]{"1"}, new Object[][]{{1}}));
        });
        SQLException e = assertThrows(SQLException.class, () -> client.ping(1));
        assertEquals("08006", e.getSQLState());
        assertEquals(1, stub.requests().size());
    }

    @Test
    void pingSucceedsOnce() throws Exception {
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));
        client.ping(5);
        assertEquals(1, stub.requests().size());
    }

    @Test
    void shouldRetryIoClassifiesTimeoutsAndConnectExceptions() {
        assertTrue(D1Client.shouldRetryIo(new java.net.ConnectException("refused"), false));
        assertFalse(D1Client.shouldRetryIo(new java.io.IOException("boom"), false));
        assertFalse(D1Client.shouldRetryIo(new java.net.http.HttpTimeoutException("slow"), true));
        assertTrue(D1Client.shouldRetryIo(new java.io.IOException("boom"), true));
        assertFalse(D1Client.shouldRetryIo(new java.net.http.HttpConnectTimeoutException("slow"), false));
    }

    @Test
    void isValidRunsOnceBoundedByTimeoutAndWithoutRetry() throws Exception {
        // F5: isValid(timeout) previously delegated to execute(), which retries read-only SQL up to 3x;
        // a hung connection would take ~90s to report invalid instead of honouring the caller's timeout.
        D1Connection connection = new D1Connection(D1ConnectionConfig.parse(
                stub.url() + "&timeoutSeconds=1", stub.credentials()), new D1Client(
                D1ConnectionConfig.parse(stub.url() + "&timeoutSeconds=1", stub.credentials()), new long[]{1, 1, 1}));
        stub.handler(r -> {
            sleep(3000);
            return ok(result(new String[]{"1"}, new Object[][]{{1}}));
        });
        long start = System.nanoTime();
        assertFalse(connection.isValid(1));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 2500, "isValid should not retry; took " + elapsedMillis + "ms");
        assertEquals(1, stub.requests().size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void declaredTypesFailureIsNotCachedSoATransientErrorCanRecover() throws SQLException {
        // F6: a transient pragma_table_info failure must not be cached as "this table has no columns"
        // forever; only a successful lookup (or a schema-changing DDL) should populate the cache.
        D1Connection connection = new D1Connection(stub.config(), client);
        stub.enqueue(error(500, 0, "boom"));
        stub.enqueue(error(500, 0, "boom"));
        stub.enqueue(error(500, 0, "boom"));
        stub.enqueue(error(500, 0, "boom"));
        assertEquals(Map.of(), connection.declaredTypes("t"));

        stub.enqueue(ok(result(new String[]{"name", "type"}, new Object[][]{{"id", "INTEGER"}})));
        assertEquals("INTEGER", connection.declaredTypes("t").get("id"));
    }

    @Test
    void resolvesDatabaseNameToUuid() throws SQLException {
        Properties creds = stub.credentials();
        D1Client byName = new D1Client(D1ConnectionConfig.parse(stub.url("my-db"), creds), new long[]{1});
        stub.enqueue(new StubD1Server.Response(200, "{\"success\":true,\"errors\":[],\"result\":["
                + "{\"name\":\"my-db-2\",\"uuid\":\"u2\"},{\"name\":\"my-db\",\"uuid\":\"" + DB_UUID + "\"}]}"));
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));
        byName.execute("SELECT 1", List.of());
        List<StubD1Server.Request> reqs = stub.requests();
        assertEquals("GET", reqs.get(0).method);
        assertEquals("/client/v4/accounts/" + ACCOUNT + "/d1/database", reqs.get(0).path);
        assertTrue(reqs.get(0).query.contains("name=my-db"));
        assertTrue(reqs.get(1).path.endsWith("/d1/database/" + DB_UUID + "/raw"));
    }

    @Test
    void unknownDatabaseNameFails() throws SQLException {
        D1Client byName = new D1Client(D1ConnectionConfig.parse(stub.url("nope"), stub.credentials()), new long[]{1});
        stub.enqueue(new StubD1Server.Response(200, "{\"success\":true,\"errors\":[],\"result\":[]}"));
        SQLException e = assertThrows(SQLException.class, byName::databaseId);
        assertEquals("08001", e.getSQLState());
    }
}

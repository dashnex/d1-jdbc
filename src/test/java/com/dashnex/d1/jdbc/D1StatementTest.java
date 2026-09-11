package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import static com.dashnex.d1.jdbc.StubD1Server.*;
import static org.junit.jupiter.api.Assertions.*;

class D1StatementTest {
    private StubD1Server stub;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubD1Server();
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));   // connect() probe
        conn = DriverManager.getConnection(stub.url(), stub.credentials());
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
        stub.close();
    }

    @Test
    void driverIsDiscoveredAndValidatesOnConnect() throws SQLException {
        assertTrue(conn instanceof D1Connection);
        assertEquals("SELECT 1", stub.requests().get(0).sql());
        assertNull(new D1Driver().connect("jdbc:sqlite:x", null));
    }

    @Test
    void connectFailsWithBadToken() {
        stub.enqueue(error(401, 10000, "Authentication error"));
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(stub.url(), stub.credentials()));
        assertEquals("28000", e.getSQLState());
    }

    @Test
    void executeQueryReturnsRowsAndTableMetadata() throws SQLException {
        stub.handler(r -> r.sql().contains("pragma_table_info")
                ? ok(result(new String[]{"name", "type"}, new Object[][]{{"id", "INTEGER"}, {"name", "TEXT"}}))
                : ok(result(new String[]{"id", "name"}, new Object[][]{{1, "a"}, {2, "b"}})));
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM users")) {
            assertEquals("users", rs.getMetaData().getTableName(1));
            assertEquals("INTEGER", rs.getMetaData().getColumnTypeName(1));
            assertTrue(rs.next());
            assertEquals("a", rs.getString("name"));
            assertTrue(rs.next());
            assertFalse(rs.next());
        }
    }

    @Test
    void executeUpdateReturnsChangesAndGeneratedKey() throws SQLException {
        stub.enqueue(ok(empty(1, 77)));
        try (Statement st = conn.createStatement()) {
            assertEquals(1, st.executeUpdate("INSERT INTO t(a) VALUES ('x')", Statement.RETURN_GENERATED_KEYS));
            ResultSet keys = st.getGeneratedKeys();
            assertTrue(keys.next());
            assertEquals(77L, keys.getLong(1));
        }
    }

    @Test
    void updateDoesNotReportStaleGeneratedKey() throws SQLException {
        stub.enqueue(ok(empty(3, 77)));
        try (Statement st = conn.createStatement()) {
            assertEquals(3, st.executeUpdate("UPDATE t SET a = 1"));
            assertFalse(st.getGeneratedKeys().next());
        }
    }

    @Test
    void cascadingDeleteReportsD1CombinedChangeCount() throws SQLException {
        // Real D1 response shape observed via /raw for a DELETE that fires ON DELETE CASCADE:
        // changes counts the cascaded child row together with the directly deleted parent row.
        // See "Verified D1 behaviour" in the design spec (meta.changes cascade-count bullet).
        stub.enqueue(ok(empty(2, 1)));
        try (Statement st = conn.createStatement()) {
            assertEquals(2, st.executeUpdate("DELETE FROM it_parent WHERE id = 1"));
            assertFalse(st.getGeneratedKeys().next());
        }
    }

    @Test
    void multipleResultsAreExposedInOrder() throws SQLException {
        stub.enqueue(ok(empty(2, 0), result(new String[]{"c"}, new Object[][]{{5}})));
        try (Statement st = conn.createStatement()) {
            assertFalse(st.execute("DELETE FROM t; SELECT count(*) c FROM t"));
            assertEquals(2, st.getUpdateCount());
            assertTrue(st.getMoreResults());
            ResultSet rs = st.getResultSet();
            rs.next();
            assertEquals(5, rs.getInt(1));
            assertFalse(st.getMoreResults());
            assertEquals(-1, st.getUpdateCount());
        }
    }

    @Test
    void transactionControlIsIgnoredWithWarning() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertFalse(st.execute("BEGIN TRANSACTION"));
            assertNotNull(st.getWarnings());
            assertEquals(0, st.getUpdateCount());
        }
        assertEquals(1, stub.requests().size());   // only the connect probe
        conn.setAutoCommit(false);
        assertNotNull(conn.getWarnings());
        conn.commit();
        conn.rollback();
    }

    @Test
    void sqliteVersionIsRewritten() throws SQLException {
        stub.enqueue(ok(result(new String[]{"v"}, new Object[][]{{"3.45.0"}})));
        try (Statement st = conn.createStatement()) {
            st.executeQuery("SELECT sqlite_version() v");
        }
        assertEquals("SELECT '3.45.0' v", stub.lastRequest().sql());
    }

    @Test
    void preparedStatementBindsParameters() throws SQLException {
        stub.enqueue(ok(empty(1, 9)));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t(a, b, c, d, e, f) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "x");
            ps.setInt(2, 5);
            ps.setBoolean(3, true);
            ps.setNull(4, java.sql.Types.VARCHAR);
            ps.setBytes(5, new byte[]{7});
            ps.setTimestamp(6, Timestamp.valueOf("2024-01-02 03:04:05"));
            assertEquals(1, ps.executeUpdate());
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            assertEquals(9, keys.getLong(1));
        }
        JsonNode p = stub.lastRequest().params();
        assertEquals("x", p.get(0).asText());
        assertEquals(5, p.get(1).asInt());
        assertEquals(1, p.get(2).asInt());
        assertTrue(p.get(3).isNull());
        assertEquals(7, p.get(4).get(0).asInt());
        assertEquals("2024-01-02 03:04:05", p.get(5).asText());
    }

    @Test
    void missingParameterFails() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM t WHERE a = ? AND b = ?")) {
            ps.setInt(1, 1);
            SQLException e = assertThrows(SQLException.class, ps::executeQuery);
            assertEquals("07001", e.getSQLState());
        }
    }

    @Test
    void preparedBatchIsSentInOneRequest() throws SQLException {
        stub.enqueue(ok(empty(1, 1), empty(1, 2)));
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t(a) VALUES (?)")) {
            ps.setString(1, "a");
            ps.addBatch();
            ps.setString(1, "b");
            ps.addBatch();
            assertArrayEquals(new int[]{1, 1}, ps.executeBatch());
        }
        JsonNode batch = stub.lastRequest().body.get("batch");
        assertEquals(2, batch.size());
        assertEquals("b", batch.get(1).get("params").get(0).asText());
    }

    @Test
    void failedBatchThrowsBatchUpdateException() throws SQLException {
        stub.enqueue(error(400, 7500, "UNIQUE constraint failed: t.a: SQLITE_CONSTRAINT"));
        try (Statement st = conn.createStatement()) {
            st.addBatch("INSERT INTO t(a) VALUES (1)");
            st.addBatch("INSERT INTO t(a) VALUES (1)");
            BatchUpdateException e = assertThrows(BatchUpdateException.class, st::executeBatch);
            assertEquals("23000", e.getSQLState());
        }
    }

    @Test
    void ddlClearsDeclaredTypeCache() throws SQLException {
        D1Connection d1 = conn.unwrap(D1Connection.class);
        stub.handler(r -> r.sql().contains("pragma_table_info")
                ? ok(result(new String[]{"name", "type"}, new Object[][]{{"id", "INTEGER"}}))
                : ok(empty(0, 0)));
        assertEquals("INTEGER", d1.declaredTypes("t").get("id"));
        int before = stub.requests().size();
        d1.declaredTypes("t");
        assertEquals(before, stub.requests().size());
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE t ADD COLUMN x TEXT");
        }
        d1.declaredTypes("t");
        assertEquals(before + 2, stub.requests().size());
    }

    @Test
    void closedConnectionRejectsStatements() throws SQLException {
        conn.close();
        assertTrue(conn.isClosed());
        assertThrows(SQLException.class, conn::createStatement);
    }
}

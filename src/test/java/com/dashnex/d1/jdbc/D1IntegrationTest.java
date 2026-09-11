package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class D1IntegrationTest {
    private static Connection conn;

    @BeforeAll
    static void connect() throws SQLException {
        String account = System.getenv("D1_ACCOUNT_ID");
        String token = System.getenv("D1_TOKEN");
        String database = System.getenv("D1_DATABASE");
        Assumptions.assumeTrue(account != null && token != null && database != null, "D1_* variables not set");
        Properties p = new Properties();
        p.setProperty("user", account);
        p.setProperty("password", token);
        conn = DriverManager.getConnection("jdbc:d1://" + database, p);
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS it_child");
            s.execute("DROP TABLE IF EXISTS it_parent");
            s.execute("CREATE TABLE it_parent (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE it_child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES it_parent(id) "
                    + "ON DELETE CASCADE, amount REAL, data BLOB, flag BOOLEAN, note VARCHAR(20) DEFAULT 'n/a')");
            s.execute("CREATE INDEX it_child_parent ON it_child(parent_id)");
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (conn == null) return;
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS it_child");
            s.execute("DROP TABLE IF EXISTS it_parent");
        }
        conn.close();
    }

    private static long insertParent(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO it_parent(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            assertEquals(1, ps.executeUpdate());
            ResultSet keys = ps.getGeneratedKeys();
            assertTrue(keys.next());
            return keys.getLong(1);
        }
    }

    @Test
    void crudRoundTrip() throws SQLException {
        long parentId = insertParent("crud-parent");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO it_child(parent_id, amount, data, flag) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, parentId);
            ps.setDouble(2, 12.5);
            ps.setBytes(3, new byte[]{0, 1, (byte) 255});
            ps.setBoolean(4, true);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM it_child WHERE parent_id = ?")) {
            ps.setLong(1, parentId);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(12.5, rs.getDouble("amount"));
            assertArrayEquals(new byte[]{0, 1, (byte) 255}, rs.getBytes("data"));
            assertTrue(rs.getBoolean("flag"));
            assertEquals("n/a", rs.getString("note"));
            assertFalse(rs.next());
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE it_child SET amount = ? WHERE parent_id = ?")) {
            ps.setDouble(1, 99.0);
            ps.setLong(2, parentId);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM it_parent WHERE id = ?")) {
            ps.setLong(1, parentId);
            // D1's meta.changes counts rows removed by ON DELETE CASCADE foreign-key actions
            // together with the directly targeted row, unlike stock SQLite's sqlite3_changes():
            // 1 parent row + 1 cascaded child row here. See "Verified D1 behaviour" in the spec.
            assertEquals(2, ps.executeUpdate());
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM it_child WHERE parent_id = " + parentId)) {
            rs.next();
            assertEquals(0, rs.getInt(1), "ON DELETE CASCADE should remove children");
        }
    }

    @Test
    void metadataShowsTablesKeysAndIndexes() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        List<String> tables = new ArrayList<>();
        ResultSet t = md.getTables(null, null, "%", new String[]{"TABLE"});
        while (t.next()) tables.add(t.getString("TABLE_NAME"));
        assertTrue(tables.containsAll(List.of("it_parent", "it_child")), tables.toString());
        assertFalse(tables.contains("_cf_KV"));

        ResultSet cols = md.getColumns(null, null, "it_child", "note");
        assertTrue(cols.next());
        assertEquals("VARCHAR", cols.getString("TYPE_NAME")); // F8: TYPE_NAME excludes the declared size
        assertEquals("'n/a'", cols.getString("COLUMN_DEF"));

        ResultSet pk = md.getPrimaryKeys(null, null, "it_parent");
        assertTrue(pk.next());
        assertEquals("id", pk.getString("COLUMN_NAME"));

        ResultSet fk = md.getImportedKeys(null, null, "it_child");
        assertTrue(fk.next());
        assertEquals("it_parent", fk.getString("PKTABLE_NAME"));
        assertEquals("id", fk.getString("PKCOLUMN_NAME"));
        assertEquals("parent_id", fk.getString("FKCOLUMN_NAME"));
        assertEquals(DatabaseMetaData.importedKeyCascade, fk.getInt("DELETE_RULE"));

        ResultSet exported = md.getExportedKeys(null, null, "it_parent");
        assertTrue(exported.next());
        assertEquals("it_child", exported.getString("FKTABLE_NAME"));

        List<String> indexes = new ArrayList<>();
        ResultSet ix = md.getIndexInfo(null, null, "it_child", false, true);
        while (ix.next()) indexes.add(ix.getString("INDEX_NAME"));
        assertTrue(indexes.contains("it_child_parent"), indexes.toString());
    }

    @Test
    void addAndDropColumn() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE it_parent ADD COLUMN extra TEXT");
            assertTrue(md.getColumns(null, null, "it_parent", "extra").next());
            s.execute("ALTER TABLE it_parent DROP COLUMN extra");
            assertFalse(md.getColumns(null, null, "it_parent", "extra").next());
        }
    }

    @Test
    void resultSetMetadataReportsSourceTableAndDeclaredTypes() throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM it_child LIMIT 1")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals("it_child", md.getTableName(1));
            assertEquals(Types.DOUBLE, md.getColumnType(rs.findColumn("amount")));
            assertEquals("VARCHAR", md.getColumnTypeName(rs.findColumn("note"))); // F8: no declared size
        }
    }

    @Test
    void constraintViolationMapsTo23000() throws SQLException {
        insertParent("dup");
        SQLException e = assertThrows(SQLException.class, () -> insertParent("dup"));
        assertEquals("23000", e.getSQLState());
    }

    @Test
    void failingBatchIsAtomic() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.addBatch("INSERT INTO it_parent(name) VALUES ('batch-1')");
            s.addBatch("INSERT INTO it_parent(name) VALUES ('batch-1')");
            assertThrows(BatchUpdateException.class, s::executeBatch);
            ResultSet rs = s.executeQuery("SELECT count(*) FROM it_parent WHERE name = 'batch-1'");
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void transactionStatementsAndVersionQueriesWork() throws SQLException {
        try (Statement s = conn.createStatement()) {
            assertFalse(s.execute("BEGIN"));
            assertFalse(s.execute("COMMIT"));
            ResultSet rs = s.executeQuery("SELECT sqlite_version()");
            rs.next();
            assertEquals("3.45.0", rs.getString(1));
        }
    }
}

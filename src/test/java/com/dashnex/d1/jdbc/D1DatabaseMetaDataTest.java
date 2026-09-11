package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static com.dashnex.d1.jdbc.StubD1Server.*;
import static org.junit.jupiter.api.Assertions.*;

class D1DatabaseMetaDataTest {
    private static final String[] COLUMN_HEADERS =
            {"tbl", "cid", "name", "type", "notnull", "dflt_value", "pk", "without_rowid"};
    private static final Object[][] COLUMN_ROWS = {
            {"kv", 0, "id", "INTEGER", 0, null, 1, 1},
            {"order_summary", 0, "n", "", 0, null, 0, 0},
            {"orders", 0, "id", "INTEGER", 0, null, 1, 0},
            {"orders", 1, "user_id", "INTEGER", 1, null, 0, 0},
            {"orders", 2, "total", "DECIMAL(10,2)", 0, "0", 0, 0},
            {"users", 0, "id", "INTEGER", 0, null, 1, 0},
            {"users", 1, "email", "VARCHAR(255)", 1, null, 0, 0},
            {"users", 2, "created_at", "DATETIME", 0, "CURRENT_TIMESTAMP", 0, 0}};

    private StubD1Server stub;
    private Connection conn;
    private DatabaseMetaData md;

    private static Object[][] columnsForTable(String table) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : COLUMN_ROWS) {
            if (((String) row[0]).equalsIgnoreCase(table)) out.add(row);
        }
        return out.toArray(new Object[0][]);
    }

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubD1Server();
        stub.handler(r -> {
            String sql = r.sql();
            if (sql.equals(D1DatabaseMetaData.TABLES_SQL)) {
                return ok(result(new String[]{"name", "type"}, new Object[][]{
                        {"orders", "table"}, {"order_summary", "view"}, {"users", "table"}}));
            }
            if (sql.equals(D1DatabaseMetaData.COLUMNS_SQL)) {
                return ok(result(COLUMN_HEADERS, COLUMN_ROWS));
            }
            if (sql.equals(D1DatabaseMetaData.COLUMNS_FOR_TABLE_SQL) || sql.equals(D1DatabaseMetaData.COLUMNS_ONE_TABLE_FALLBACK_SQL)) {
                String table = r.params().get(0).asText();
                return ok(result(COLUMN_HEADERS, columnsForTable(table)));
            }
            if (sql.equals(D1DatabaseMetaData.FOREIGN_KEYS_SQL)) {
                return ok(result(new String[]{"name", "id", "seq", "table", "from", "to", "on_update", "on_delete"}, new Object[][]{
                        {"orders", 0, 0, "users", "user_id", null, "NO ACTION", "CASCADE"}}));
            }
            if (sql.equals(D1DatabaseMetaData.INDEX_SQL)) {
                return ok(result(new String[]{"name", "unique", "seqno", "name", "desc"}, new Object[][]{
                        {"orders_user", 0, 0, "user_id", 0},
                        {"sqlite_autoindex_orders_1", 1, 0, "total", 1}}));
            }
            return ok(result(new String[]{"1"}, new Object[][]{{1}}));
        });
        conn = DriverManager.getConnection(stub.url(), stub.credentials());
        md = conn.getMetaData();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
        stub.close();
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        List<String> out = new ArrayList<>();
        while (rs.next()) out.add(rs.getString(column));
        return out;
    }

    @Test
    void identifiesAsSqlite() throws SQLException {
        assertEquals("SQLite", md.getDatabaseProductName());
        assertEquals("3.45.0", md.getDatabaseProductVersion());
        assertEquals("Cloudflare D1 JDBC", md.getDriverName());
        assertEquals("\"", md.getIdentifierQuoteString());
        assertFalse(md.supportsTransactions());
        assertEquals(Connection.TRANSACTION_NONE, md.getDefaultTransactionIsolation());
        assertTrue(md.supportsAlterTableWithAddColumn());
        assertTrue(md.supportsAlterTableWithDropColumn());
        assertTrue(md.supportsGetGeneratedKeys());
        assertSame(conn, md.getConnection());
    }

    @Test
    void metadataSqlExcludesInternalTables() {
        for (String sql : new String[]{D1DatabaseMetaData.TABLES_SQL, D1DatabaseMetaData.COLUMNS_SQL, D1DatabaseMetaData.FOREIGN_KEYS_SQL}) {
            assertTrue(sql.contains("NOT LIKE 'sqlite\\_%' ESCAPE '\\'"), sql);
            assertTrue(sql.contains("NOT LIKE '\\_cf\\_%' ESCAPE '\\'"), sql);
        }
    }

    @Test
    void listsTablesAndViews() throws SQLException {
        assertEquals(List.of("orders", "users", "order_summary"), strings(md.getTables(null, null, "%", null), "TABLE_NAME"));
        assertEquals(List.of("order_summary"), strings(md.getTables(null, null, null, new String[]{"VIEW"}), "TABLE_NAME"));
        ResultSet rs = md.getTables(null, null, "us%", new String[]{"TABLE"});
        assertTrue(rs.next());
        assertEquals("users", rs.getString("TABLE_NAME"));
        assertEquals("TABLE", rs.getString("TABLE_TYPE"));
        assertNull(rs.getString("TABLE_SCHEM"));
        assertFalse(rs.next());
        assertEquals(List.of("TABLE", "VIEW"), strings(md.getTableTypes(), "TABLE_TYPE"));
        assertFalse(md.getSchemas().next());
        assertFalse(md.getCatalogs().next());
    }

    @Test
    void describesColumns() throws SQLException {
        ResultSet rs = md.getColumns(null, null, "users", "%");
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals(Types.BIGINT, rs.getInt("DATA_TYPE"));
        assertEquals("INTEGER", rs.getString("TYPE_NAME"));
        assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));
        assertEquals("NO", rs.getString("IS_NULLABLE"));
        assertEquals(1, rs.getInt("ORDINAL_POSITION"));
        assertTrue(rs.next());
        assertEquals("email", rs.getString("COLUMN_NAME"));
        assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
        assertEquals("VARCHAR", rs.getString("TYPE_NAME")); // F8: TYPE_NAME excludes the declared size
        assertEquals(255, rs.getInt("COLUMN_SIZE"));
        assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
        assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));
        assertTrue(rs.next());
        assertEquals("CURRENT_TIMESTAMP", rs.getString("COLUMN_DEF"));
        assertEquals("YES", rs.getString("IS_NULLABLE"));
        assertFalse(rs.next());

        ResultSet total = md.getColumns(null, null, "orders", "total");
        assertTrue(total.next());
        assertEquals(Types.NUMERIC, total.getInt("DATA_TYPE"));
        assertEquals("DECIMAL", total.getString("TYPE_NAME")); // F8: TYPE_NAME excludes the declared size
        assertEquals(10, total.getInt("COLUMN_SIZE"));
        assertEquals(2, total.getInt("DECIMAL_DIGITS"));

        ResultSet view = md.getColumns(null, null, "order_summary", null);
        assertTrue(view.next());
        assertEquals("TEXT", view.getString("TYPE_NAME"));
    }

    @Test
    void reportsPrimaryKeys() throws SQLException {
        ResultSet rs = md.getPrimaryKeys(null, null, "USERS");
        assertTrue(rs.next());
        assertEquals("users", rs.getString("TABLE_NAME"));
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals(1, rs.getInt("KEY_SEQ"));
        assertEquals("pk_users", rs.getString("PK_NAME"));
        assertFalse(rs.next());
    }

    @Test
    void reportsImportedAndExportedKeysResolvingImplicitPk() throws SQLException {
        ResultSet imported = md.getImportedKeys(null, null, "orders");
        assertTrue(imported.next());
        assertEquals("users", imported.getString("PKTABLE_NAME"));
        assertEquals("id", imported.getString("PKCOLUMN_NAME"));
        assertEquals("orders", imported.getString("FKTABLE_NAME"));
        assertEquals("user_id", imported.getString("FKCOLUMN_NAME"));
        assertEquals(1, imported.getInt("KEY_SEQ"));
        assertEquals(DatabaseMetaData.importedKeyCascade, imported.getInt("DELETE_RULE"));
        assertEquals(DatabaseMetaData.importedKeyNoAction, imported.getInt("UPDATE_RULE"));
        assertEquals("fk_orders_0", imported.getString("FK_NAME"));
        assertFalse(imported.next());

        ResultSet exported = md.getExportedKeys(null, null, "users");
        assertTrue(exported.next());
        assertEquals("orders", exported.getString("FKTABLE_NAME"));
        assertFalse(exported.next());

        assertFalse(md.getImportedKeys(null, null, "users").next());
        assertTrue(md.getCrossReference(null, null, "users", null, null, "orders").next());
        assertFalse(md.getCrossReference(null, null, "orders", null, null, "users").next());
    }

    @Test
    void reportsIndexes() throws SQLException {
        ResultSet rs = md.getIndexInfo(null, null, "orders", false, true);
        assertTrue(rs.next());
        assertEquals("sqlite_autoindex_orders_1", rs.getString("INDEX_NAME"));
        assertFalse(rs.getBoolean("NON_UNIQUE"));
        assertEquals("D", rs.getString("ASC_OR_DESC"));
        assertTrue(rs.next());
        assertEquals("orders_user", rs.getString("INDEX_NAME"));
        assertTrue(rs.getBoolean("NON_UNIQUE"));
        assertEquals("user_id", rs.getString("COLUMN_NAME"));
        assertEquals(1, rs.getInt("ORDINAL_POSITION"));
        assertFalse(rs.next());
        assertEquals("orders", stub.lastRequest().params().get(0).asText());

        ResultSet unique = md.getIndexInfo(null, null, "orders", true, true);
        assertTrue(unique.next());
        assertFalse(unique.next());
    }

    @Test
    void typeInfoListsSqliteTypes() throws SQLException {
        List<String> names = strings(md.getTypeInfo(), "TYPE_NAME");
        assertTrue(names.containsAll(List.of("INTEGER", "REAL", "TEXT", "BLOB", "NUMERIC")));
    }

    @Test
    void bestRowIdentifierUsesPrimaryKey() throws SQLException {
        ResultSet rs = md.getBestRowIdentifier(null, null, "users", DatabaseMetaData.bestRowSession, true);
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertFalse(rs.next());
    }

    @Test
    void withoutRowidTablesAreNotRowidAliases() throws SQLException {
        ResultSet rs = md.getColumns(null, null, "kv", null);
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));
        assertEquals("YES", rs.getString("IS_NULLABLE"));
        assertFalse(rs.next());
    }

    @Test
    void getPrimaryKeysForASpecificTableUsesTheSingleTableQuery() throws SQLException {
        // F4: a request for one table's metadata must not run the all-tables COLUMNS_SQL,
        // which a single broken view elsewhere could take down.
        ResultSet rs = md.getPrimaryKeys(null, null, "users");
        assertTrue(rs.next());
        assertEquals("users", rs.getString("TABLE_NAME"));
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertFalse(rs.next());
        assertEquals(D1DatabaseMetaData.COLUMNS_FOR_TABLE_SQL, stub.lastRequest().sql());
        assertEquals("users", stub.lastRequest().params().get(0).asText());
    }

    @Test
    void columnsFallBackToPerTableQueriesWhenAllTablesQueryFails() throws SQLException {
        // F4: a view referencing a dropped table/column makes the joined pragma_table_info query
        // fail for ALL tables; the driver must fall back to one query per table and skip only the
        // broken one, instead of losing every table's column metadata.
        stub.handler(r -> {
            String sql = r.sql();
            if (sql.equals(D1DatabaseMetaData.TABLES_SQL)) {
                return ok(result(new String[]{"name", "type"}, new Object[][]{
                        {"broken_view", "view"}, {"users", "table"}}));
            }
            if (sql.equals(D1DatabaseMetaData.COLUMNS_SQL)) {
                return error(400, 7500, "no such column: missing: SQLITE_ERROR");
            }
            if (sql.equals(D1DatabaseMetaData.COLUMNS_ONE_TABLE_FALLBACK_SQL)) {
                String table = r.params().get(0).asText();
                if ("broken_view".equals(table)) {
                    return error(400, 7500, "no such column: missing: SQLITE_ERROR");
                }
                return ok(result(COLUMN_HEADERS, columnsForTable(table)));
            }
            return ok(result(new String[]{"1"}, new Object[][]{{1}}));
        });
        ResultSet rs = md.getColumns(null, null, "%", "%");
        List<String> tables = new ArrayList<>();
        while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        assertEquals(List.of("users", "users", "users"), tables);
    }
}

package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class D1ResultSetTest {
    private static D1Result sample() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, "alice", 1.5, new byte[]{1, 2}, 1L});
        rows.add(new Object[]{2L, "12", null, null, 0L});
        return new D1Result(Arrays.asList("id", "name", "score", "data", "flag"), rows, 0, 0);
    }

    @Test
    void iteratesAndReadsTypedValues() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, null, null);
        assertTrue(rs.isBeforeFirst());
        assertTrue(rs.next());
        assertEquals(1, rs.getRow());
        assertEquals(1L, rs.getLong("id"));
        assertEquals(1, rs.getInt(1));
        assertEquals("alice", rs.getString("NAME"));
        assertEquals(1.5, rs.getDouble(3));
        assertEquals(new BigDecimal("1.5"), rs.getBigDecimal(3));
        assertArrayEquals(new byte[]{1, 2}, rs.getBytes("data"));
        assertTrue(rs.getBoolean("flag"));
        assertFalse(rs.wasNull());
        assertTrue(rs.next());
        assertEquals(12, rs.getInt("name"));
        assertEquals(0.0, rs.getDouble("score"));
        assertTrue(rs.wasNull());
        assertNull(rs.getString("score"));
        assertNull(rs.getObject("data"));
        assertFalse(rs.next());
        assertTrue(rs.isAfterLast());
        assertThrows(SQLException.class, () -> rs.getString(1));
    }

    @Test
    void scrolls() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, null, null);
        assertTrue(rs.last());
        assertEquals(2, rs.getRow());
        assertTrue(rs.isLast());
        assertTrue(rs.previous());
        assertTrue(rs.isFirst());
        assertTrue(rs.absolute(-1));
        assertEquals(2L, rs.getLong(1));
        assertFalse(rs.relative(5));
        assertTrue(rs.isAfterLast());
        rs.beforeFirst();
        assertTrue(rs.first());
        assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
    }

    @Test
    void metadataInfersTypesWithoutTable() throws SQLException {
        ResultSetMetaData md = D1ResultSet.forQuery(null, sample(), 0, null, null).getMetaData();
        assertEquals(5, md.getColumnCount());
        assertEquals("name", md.getColumnLabel(2));
        assertEquals(Types.BIGINT, md.getColumnType(1));
        assertEquals(Types.VARCHAR, md.getColumnType(2));
        assertEquals(Types.DOUBLE, md.getColumnType(3));
        assertEquals(Types.BLOB, md.getColumnType(4));
        assertEquals("", md.getTableName(1));
        assertThrows(SQLException.class, () -> md.getColumnType(6));
    }

    @Test
    void metadataUsesDeclaredTypesAndTableName() throws SQLException {
        Map<String, String> declared = Map.of("id", "INTEGER", "name", "VARCHAR(20)", "score", "REAL", "data", "BLOB", "flag", "BOOLEAN");
        D1ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, "users", declared);
        ResultSetMetaData md = rs.getMetaData();
        assertEquals("users", md.getTableName(2));
        assertEquals("VARCHAR", md.getColumnTypeName(2)); // F8: TYPE_NAME excludes the declared size
        assertEquals(Types.BOOLEAN, md.getColumnType(5));
        rs.next();
        assertEquals(Boolean.TRUE, rs.getObject("flag"));
    }

    @Test
    void columnsNotInTableHaveNoTableName() throws SQLException {
        D1Result r = new D1Result(List.of("id", "total"), List.<Object[]>of(new Object[]{1L, 3L}), 0, 0);
        ResultSetMetaData md = D1ResultSet.forQuery(null, r, 0, "users", Map.of("id", "INTEGER")).getMetaData();
        assertEquals("users", md.getTableName(1));
        assertEquals("", md.getTableName(2));
    }

    @Test
    void inferTypeScansAllRowsForMixedNumericColumn() throws SQLException {
        // F3: D1 serialises REAL 10.0 as JSON 10 (-> Long); must scan all rows, not just the first.
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{10L});
        rows.add(new Object[]{10.5});
        D1Result r = new D1Result(List.of("v"), rows, 0, 0);
        D1ResultSet rs = D1ResultSet.forQuery(null, r, 0, null, null);
        assertEquals(Types.DOUBLE, rs.getMetaData().getColumnType(1));
        rs.next();
        rs.next();
        assertEquals(10.5, rs.getDouble(1));
        assertEquals(10.5, rs.getObject(1));
    }

    @Test
    void inferTypeRulesForMixedAndNullColumns() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{new byte[]{1}, null});
        rows.add(new Object[]{"x", null});
        D1Result r = new D1Result(List.of("blobOrStr", "allNull"), rows, 0, 0);
        ResultSetMetaData md = D1ResultSet.forQuery(null, r, 0, null, null).getMetaData();
        assertEquals(Types.VARCHAR, md.getColumnType(1)); // blob mixed with string -> VARCHAR
        assertEquals(Types.VARCHAR, md.getColumnType(2)); // all null -> VARCHAR
    }

    @Test
    void maxRowsTruncates() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 1, null, null);
        assertTrue(rs.next());
        assertFalse(rs.next());
    }

    @Test
    void ofBuildsMetadataResultSets() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{null, "t", 5});
        ResultSet rs = D1ResultSet.of(null, new String[]{"TABLE_CAT", "TABLE_NAME", "DATA_TYPE"}, null, rows);
        assertTrue(rs.next());
        assertNull(rs.getString("TABLE_CAT"));
        assertEquals("t", rs.getString("TABLE_NAME"));
        assertEquals(5, rs.getInt("DATA_TYPE"));
        assertEquals(Types.BIGINT, rs.getMetaData().getColumnType(3));
    }

    @Test
    void isReadOnlyAndClosable() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, null, null);
        rs.next();
        assertThrows(SQLFeatureNotSupportedException.class, () -> rs.updateString(1, "x"));
        assertThrows(SQLException.class, () -> rs.findColumn("nope"));
        rs.close();
        assertTrue(rs.isClosed());
        assertThrows(SQLException.class, rs::next);
    }
}

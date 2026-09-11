package com.dashnex.d1.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fully materialised, scroll-insensitive, read-only result set. */
public class D1ResultSet extends ReadOnlyResultSet {
    private final Statement statement;
    private final List<String> columns;
    private final List<Object[]> rows;
    private final D1ResultSetMetaData metaData;
    private int cursor = -1;
    private boolean closed;
    private boolean wasNull;
    private int fetchSize;

    D1ResultSet(Statement statement, List<String> columns, List<Object[]> rows, D1ResultSetMetaData metaData) {
        this.statement = statement;
        this.columns = columns;
        this.rows = rows;
        this.metaData = metaData;
    }

    static D1ResultSet forQuery(Statement statement, D1Result result, int maxRows, String table,
                                Map<String, String> declaredTypes) {
        List<String> columns = result.getColumns();
        List<Object[]> rows = result.getRows();
        if (maxRows > 0 && rows.size() > maxRows) {
            rows = rows.subList(0, maxRows);
        }
        int n = columns.size();
        int[] types = new int[n];
        String[] typeNames = new String[n];
        String[] tables = new String[n];
        for (int i = 0; i < n; i++) {
            String declared = declaredTypes == null ? null : declaredTypes.get(columns.get(i).toLowerCase(Locale.ROOT));
            if (declared != null) {
                types[i] = D1Types.fromDeclared(declared);
                typeNames[i] = declared.isBlank() ? D1Types.typeName(types[i]) : declared.trim().toUpperCase(Locale.ROOT);
                tables[i] = table;
            } else {
                types[i] = inferType(rows, i);
                typeNames[i] = D1Types.typeName(types[i]);
                tables[i] = "";
            }
        }
        return new D1ResultSet(statement, columns, rows, new D1ResultSetMetaData(columns, types, typeNames, tables));
    }

    static D1ResultSet of(Statement statement, String[] columnNames, int[] types, List<Object[]> rows) {
        List<String> columns = Arrays.asList(columnNames);
        int n = columnNames.length;
        int[] t = new int[n];
        String[] typeNames = new String[n];
        String[] tables = new String[n];
        for (int i = 0; i < n; i++) {
            t[i] = types != null ? types[i] : inferType(rows, i);
            typeNames[i] = D1Types.typeName(t[i]);
            tables[i] = "";
        }
        return new D1ResultSet(statement, columns, rows, new D1ResultSetMetaData(columns, t, typeNames, tables));
    }

    /** Infers a column's JDBC type from every row (not just the first non-null value): D1 serialises a REAL
     * that happens to be a whole number, e.g. 10.0, as a JSON integer, so a column can mix Long and Double
     * rows and must still be reported as DOUBLE. */
    private static int inferType(List<Object[]> rows, int column) {
        boolean sawDouble = false, sawLong = false, sawBlob = false, sawString = false, sawBoolean = false;
        for (Object[] row : rows) {
            Object v = row[column];
            if (v == null) continue;
            if (v instanceof Double || v instanceof Float) sawDouble = true;
            else if (v instanceof Boolean) sawBoolean = true;
            else if (v instanceof Number) sawLong = true;
            else if (v instanceof byte[]) sawBlob = true;
            else sawString = true;
        }
        if (sawDouble) return Types.DOUBLE;
        int distinctKinds = (sawLong ? 1 : 0) + (sawBlob ? 1 : 0) + (sawString ? 1 : 0) + (sawBoolean ? 1 : 0);
        if (distinctKinds > 1) return Types.VARCHAR;
        if (sawLong) return Types.BIGINT;
        if (sawBlob) return Types.BLOB;
        if (sawBoolean) return Types.BOOLEAN;
        return Types.VARCHAR; // only strings, or all-null
    }

    void closeSilently() {
        closed = true;
    }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed", "24000");
    }

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("No current row", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("Column index out of range: " + columnIndex, "07009");
        }
        Object v = rows.get(cursor)[columnIndex - 1];
        wasNull = v == null;
        return v;
    }

    // ---- navigation ----

    @Override public boolean next() throws SQLException {
        checkOpen();
        if (cursor < rows.size()) cursor++;
        return cursor < rows.size();
    }

    @Override public boolean previous() throws SQLException {
        checkOpen();
        if (cursor >= 0) cursor--;
        return cursor >= 0;
    }

    @Override public boolean isBeforeFirst() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor < 0; }
    @Override public boolean isAfterLast() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor >= rows.size(); }
    @Override public boolean isFirst() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor == 0; }
    @Override public boolean isLast() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor == rows.size() - 1; }
    @Override public void beforeFirst() throws SQLException { checkOpen(); cursor = -1; }
    @Override public void afterLast() throws SQLException { checkOpen(); cursor = rows.size(); }

    @Override public boolean first() throws SQLException {
        checkOpen();
        if (rows.isEmpty()) return false;
        cursor = 0;
        return true;
    }

    @Override public boolean last() throws SQLException {
        checkOpen();
        if (rows.isEmpty()) return false;
        cursor = rows.size() - 1;
        return true;
    }

    @Override public int getRow() throws SQLException {
        checkOpen();
        return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0;
    }

    @Override public boolean absolute(int row) throws SQLException {
        checkOpen();
        if (row > 0) cursor = Math.min(row - 1, rows.size());
        else if (row < 0) cursor = Math.max(rows.size() + row, -1);
        else cursor = -1;
        return cursor >= 0 && cursor < rows.size();
    }

    @Override public boolean relative(int n) throws SQLException {
        checkOpen();
        cursor = Math.max(-1, Math.min(rows.size(), cursor + n));
        return cursor >= 0 && cursor < rows.size();
    }

    // ---- getters by index ----

    @Override public boolean wasNull() { return wasNull; }
    @Override public String getString(int i) throws SQLException { return D1Values.toStr(value(i)); }
    @Override public boolean getBoolean(int i) throws SQLException { return D1Values.toBoolean(value(i)); }
    @Override public byte getByte(int i) throws SQLException { return (byte) D1Values.toLong(value(i)); }
    @Override public short getShort(int i) throws SQLException { return (short) D1Values.toLong(value(i)); }
    @Override public int getInt(int i) throws SQLException { return (int) D1Values.toLong(value(i)); }
    @Override public long getLong(int i) throws SQLException { return D1Values.toLong(value(i)); }
    @Override public float getFloat(int i) throws SQLException { return (float) D1Values.toDouble(value(i)); }
    @Override public double getDouble(int i) throws SQLException { return D1Values.toDouble(value(i)); }
    @Override public BigDecimal getBigDecimal(int i) throws SQLException { return D1Values.toBigDecimal(value(i)); }

    @Override @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int i, int scale) throws SQLException {
        BigDecimal d = getBigDecimal(i);
        return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override public byte[] getBytes(int i) throws SQLException { return D1Values.toBytes(value(i)); }
    @Override public Date getDate(int i) throws SQLException { return D1Values.toDate(value(i)); }
    @Override public Time getTime(int i) throws SQLException { return D1Values.toTime(value(i)); }
    @Override public Timestamp getTimestamp(int i) throws SQLException { return D1Values.toTimestamp(value(i)); }
    @Override public Date getDate(int i, Calendar cal) throws SQLException { return getDate(i); }
    @Override public Time getTime(int i, Calendar cal) throws SQLException { return getTime(i); }
    @Override public Timestamp getTimestamp(int i, Calendar cal) throws SQLException { return getTimestamp(i); }

    @Override public InputStream getAsciiStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override @SuppressWarnings("deprecation")
    public InputStream getUnicodeStream(int i) throws SQLException {
        throw new SQLFeatureNotSupportedException("getUnicodeStream is deprecated");
    }

    @Override public InputStream getBinaryStream(int i) throws SQLException {
        byte[] b = getBytes(i);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    @Override public Reader getCharacterStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new StringReader(s);
    }

    @Override public Reader getNCharacterStream(int i) throws SQLException { return getCharacterStream(i); }
    @Override public String getNString(int i) throws SQLException { return getString(i); }

    @Override public Object getObject(int i) throws SQLException {
        Object v = value(i);
        if (v != null && metaData.type(i) == Types.BOOLEAN) return D1Values.toBoolean(v);
        return v;
    }

    @Override public Object getObject(int i, Map<String, Class<?>> map) throws SQLException { return getObject(i); }

    @Override public <T> T getObject(int i, Class<T> type) throws SQLException {
        Object v = value(i);
        if (v == null) return null;
        if (type == String.class) return type.cast(D1Values.toStr(v));
        if (type == Long.class) return type.cast(D1Values.toLong(v));
        if (type == Integer.class) return type.cast((int) D1Values.toLong(v));
        if (type == Short.class) return type.cast((short) D1Values.toLong(v));
        if (type == Byte.class) return type.cast((byte) D1Values.toLong(v));
        if (type == Double.class) return type.cast(D1Values.toDouble(v));
        if (type == Float.class) return type.cast((float) D1Values.toDouble(v));
        if (type == Boolean.class) return type.cast(D1Values.toBoolean(v));
        if (type == BigDecimal.class) return type.cast(D1Values.toBigDecimal(v));
        if (type == byte[].class) return type.cast(D1Values.toBytes(v));
        if (type == Timestamp.class) return type.cast(D1Values.toTimestamp(v));
        if (type == Date.class) return type.cast(D1Values.toDate(v));
        if (type == Time.class) return type.cast(D1Values.toTime(v));
        if (type == LocalDateTime.class) return type.cast(D1Values.toTimestamp(v).toLocalDateTime());
        if (type == LocalDate.class) return type.cast(D1Values.toDate(v).toLocalDate());
        if (type == Object.class) return type.cast(getObject(i));
        if (type.isInstance(v)) return type.cast(v);
        throw new SQLException("Cannot convert column " + i + " to " + type.getName(), "22018");
    }

    @Override public URL getURL(int i) throws SQLException {
        String s = getString(i);
        try {
            return s == null ? null : new URL(s);
        } catch (MalformedURLException e) {
            throw new SQLException("Not a URL: " + s, "22018", e);
        }
    }

    @Override public Ref getRef(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getRef"); }
    @Override public Blob getBlob(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getBlob; use getBytes"); }
    @Override public Clob getClob(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getClob; use getString"); }
    @Override public NClob getNClob(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getNClob; use getString"); }
    @Override public Array getArray(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getArray"); }
    @Override public RowId getRowId(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getRowId"); }
    @Override public SQLXML getSQLXML(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getSQLXML"); }

    // ---- getters by label ----

    @Override public int findColumn(String label) throws SQLException {
        checkOpen();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(label)) return i + 1;
        }
        throw new SQLException("Column not found: " + label, "42S22");
    }

    @Override public String getString(String c) throws SQLException { return getString(findColumn(c)); }
    @Override public boolean getBoolean(String c) throws SQLException { return getBoolean(findColumn(c)); }
    @Override public byte getByte(String c) throws SQLException { return getByte(findColumn(c)); }
    @Override public short getShort(String c) throws SQLException { return getShort(findColumn(c)); }
    @Override public int getInt(String c) throws SQLException { return getInt(findColumn(c)); }
    @Override public long getLong(String c) throws SQLException { return getLong(findColumn(c)); }
    @Override public float getFloat(String c) throws SQLException { return getFloat(findColumn(c)); }
    @Override public double getDouble(String c) throws SQLException { return getDouble(findColumn(c)); }
    @Override @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(String c, int scale) throws SQLException { return getBigDecimal(findColumn(c), scale); }
    @Override public BigDecimal getBigDecimal(String c) throws SQLException { return getBigDecimal(findColumn(c)); }
    @Override public byte[] getBytes(String c) throws SQLException { return getBytes(findColumn(c)); }
    @Override public Date getDate(String c) throws SQLException { return getDate(findColumn(c)); }
    @Override public Time getTime(String c) throws SQLException { return getTime(findColumn(c)); }
    @Override public Timestamp getTimestamp(String c) throws SQLException { return getTimestamp(findColumn(c)); }
    @Override public Date getDate(String c, Calendar cal) throws SQLException { return getDate(findColumn(c)); }
    @Override public Time getTime(String c, Calendar cal) throws SQLException { return getTime(findColumn(c)); }
    @Override public Timestamp getTimestamp(String c, Calendar cal) throws SQLException { return getTimestamp(findColumn(c)); }
    @Override public InputStream getAsciiStream(String c) throws SQLException { return getAsciiStream(findColumn(c)); }
    @Override @SuppressWarnings("deprecation")
    public InputStream getUnicodeStream(String c) throws SQLException { return getUnicodeStream(findColumn(c)); }
    @Override public InputStream getBinaryStream(String c) throws SQLException { return getBinaryStream(findColumn(c)); }
    @Override public Reader getCharacterStream(String c) throws SQLException { return getCharacterStream(findColumn(c)); }
    @Override public Reader getNCharacterStream(String c) throws SQLException { return getNCharacterStream(findColumn(c)); }
    @Override public String getNString(String c) throws SQLException { return getNString(findColumn(c)); }
    @Override public Object getObject(String c) throws SQLException { return getObject(findColumn(c)); }
    @Override public Object getObject(String c, Map<String, Class<?>> map) throws SQLException { return getObject(findColumn(c)); }
    @Override public <T> T getObject(String c, Class<T> type) throws SQLException { return getObject(findColumn(c), type); }
    @Override public URL getURL(String c) throws SQLException { return getURL(findColumn(c)); }
    @Override public Ref getRef(String c) throws SQLException { return getRef(findColumn(c)); }
    @Override public Blob getBlob(String c) throws SQLException { return getBlob(findColumn(c)); }
    @Override public Clob getClob(String c) throws SQLException { return getClob(findColumn(c)); }
    @Override public NClob getNClob(String c) throws SQLException { return getNClob(findColumn(c)); }
    @Override public Array getArray(String c) throws SQLException { return getArray(findColumn(c)); }
    @Override public RowId getRowId(String c) throws SQLException { return getRowId(findColumn(c)); }
    @Override public SQLXML getSQLXML(String c) throws SQLException { return getSQLXML(findColumn(c)); }

    // ---- misc ----

    @Override public ResultSetMetaData getMetaData() throws SQLException { checkOpen(); return metaData; }
    @Override public Statement getStatement() { return statement; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() { }
    @Override public String getCursorName() throws SQLException { throw new SQLFeatureNotSupportedException("getCursorName"); }
    @Override public void setFetchDirection(int direction) { }
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) { fetchSize = rows; }
    @Override public int getFetchSize() { return fetchSize; }
    @Override public int getType() { return TYPE_SCROLL_INSENSITIVE; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
    @Override public int getHoldability() { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}

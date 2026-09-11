package com.dashnex.d1.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PreparedStatement that sends '?' parameters as D1 JSON params. */
public class D1PreparedStatement extends D1Statement implements PreparedStatement {
    private final String sql;
    private final int placeholderCount;
    private final Map<Integer, Object> parameters = new HashMap<>();

    D1PreparedStatement(D1Connection connection, String sql) {
        super(connection);
        this.sql = sql;
        this.placeholderCount = SqlText.countParameters(sql);
    }

    private int parameterCount() {
        int max = placeholderCount;
        for (int k : parameters.keySet()) max = Math.max(max, k);
        return max;
    }

    private List<Object> boundParameters() throws SQLException {
        int n = parameterCount();
        List<Object> values = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            if (!parameters.containsKey(i)) {
                throw new SQLException("No value specified for parameter " + i, "07001");
            }
            values.add(parameters.get(i));
        }
        return values;
    }

    private void set(int index, Object value) throws SQLException {
        checkOpen();
        if (index < 1) throw new SQLException("Parameter index out of range: " + index, "07009");
        parameters.put(index, D1Values.toParam(value));
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!executeInternal(sql, boundParameters())) {
            throw new SQLException("Statement did not return a result set", "02000");
        }
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        executeInternal(sql, boundParameters());
        return Math.max(getUpdateCount(), 0);
    }

    @Override public long executeLargeUpdate() throws SQLException { return executeUpdate(); }
    @Override public boolean execute() throws SQLException { return executeInternal(sql, boundParameters()); }
    @Override public void addBatch() throws SQLException { addToBatch(new D1Client.Stmt(sql, boundParameters())); }
    @Override public void clearParameters() { parameters.clear(); }
    @Override public ResultSetMetaData getMetaData() { return null; }
    @Override public ParameterMetaData getParameterMetaData() { return new D1ParameterMetaData(parameterCount()); }

    @Override public void setNull(int i, int sqlType) throws SQLException { set(i, null); }
    @Override public void setNull(int i, int sqlType, String typeName) throws SQLException { set(i, null); }
    @Override public void setBoolean(int i, boolean x) throws SQLException { set(i, x); }
    @Override public void setByte(int i, byte x) throws SQLException { set(i, (long) x); }
    @Override public void setShort(int i, short x) throws SQLException { set(i, (long) x); }
    @Override public void setInt(int i, int x) throws SQLException { set(i, (long) x); }
    @Override public void setLong(int i, long x) throws SQLException { set(i, x); }
    @Override public void setFloat(int i, float x) throws SQLException { set(i, Double.parseDouble(Float.toString(x))); }
    @Override public void setDouble(int i, double x) throws SQLException { set(i, x); }
    @Override public void setBigDecimal(int i, BigDecimal x) throws SQLException { set(i, x); }
    @Override public void setString(int i, String x) throws SQLException { set(i, x); }
    @Override public void setNString(int i, String x) throws SQLException { set(i, x); }
    @Override public void setBytes(int i, byte[] x) throws SQLException { set(i, x); }
    @Override public void setDate(int i, Date x) throws SQLException { set(i, x); }
    @Override public void setTime(int i, Time x) throws SQLException { set(i, x); }
    @Override public void setTimestamp(int i, Timestamp x) throws SQLException { set(i, x); }
    @Override public void setDate(int i, Date x, Calendar cal) throws SQLException { set(i, x); }
    @Override public void setTime(int i, Time x, Calendar cal) throws SQLException { set(i, x); }
    @Override public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException { set(i, x); }
    @Override public void setObject(int i, Object x) throws SQLException { set(i, x); }
    @Override public void setObject(int i, Object x, int targetSqlType) throws SQLException { set(i, x); }
    @Override public void setObject(int i, Object x, int targetSqlType, int scale) throws SQLException { set(i, x); }
    @Override public void setURL(int i, URL x) throws SQLException { set(i, x == null ? null : x.toString()); }

    @Override public void setAsciiStream(int i, InputStream x, int length) throws SQLException { setAsciiStream(i, x); }
    @Override public void setAsciiStream(int i, InputStream x, long length) throws SQLException { setAsciiStream(i, x); }
    @Override public void setAsciiStream(int i, InputStream x) throws SQLException {
        byte[] b = D1Values.readBytes(x);
        set(i, b == null ? null : new String(b, StandardCharsets.US_ASCII));
    }

    @Override @SuppressWarnings("deprecation")
    public void setUnicodeStream(int i, InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setUnicodeStream is deprecated");
    }

    @Override public void setBinaryStream(int i, InputStream x, int length) throws SQLException { set(i, D1Values.readBytes(x)); }
    @Override public void setBinaryStream(int i, InputStream x, long length) throws SQLException { set(i, D1Values.readBytes(x)); }
    @Override public void setBinaryStream(int i, InputStream x) throws SQLException { set(i, D1Values.readBytes(x)); }
    @Override public void setCharacterStream(int i, Reader x, int length) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setCharacterStream(int i, Reader x, long length) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setCharacterStream(int i, Reader x) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setNCharacterStream(int i, Reader x, long length) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setNCharacterStream(int i, Reader x) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setBlob(int i, Blob x) throws SQLException { set(i, x == null ? null : x.getBytes(1, (int) x.length())); }
    @Override public void setBlob(int i, InputStream x, long length) throws SQLException { set(i, D1Values.readBytes(x)); }
    @Override public void setBlob(int i, InputStream x) throws SQLException { set(i, D1Values.readBytes(x)); }
    @Override public void setClob(int i, Clob x) throws SQLException { set(i, x == null ? null : x.getSubString(1, (int) x.length())); }
    @Override public void setClob(int i, Reader x, long length) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setClob(int i, Reader x) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setNClob(int i, NClob x) throws SQLException { set(i, x == null ? null : x.getSubString(1, (int) x.length())); }
    @Override public void setNClob(int i, Reader x, long length) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setNClob(int i, Reader x) throws SQLException { set(i, D1Values.readString(x)); }
    @Override public void setRef(int i, Ref x) throws SQLException { throw new SQLFeatureNotSupportedException("setRef"); }
    @Override public void setArray(int i, Array x) throws SQLException { throw new SQLFeatureNotSupportedException("setArray"); }
    @Override public void setRowId(int i, RowId x) throws SQLException { throw new SQLFeatureNotSupportedException("setRowId"); }
    @Override public void setSQLXML(int i, SQLXML x) throws SQLException { throw new SQLFeatureNotSupportedException("setSQLXML"); }

    private static final class D1ParameterMetaData implements ParameterMetaData {
        private final int count;

        D1ParameterMetaData(int count) { this.count = count; }

        @Override public int getParameterCount() { return count; }
        @Override public int isNullable(int param) { return parameterNullableUnknown; }
        @Override public boolean isSigned(int param) { return false; }
        @Override public int getPrecision(int param) { return 0; }
        @Override public int getScale(int param) { return 0; }
        @Override public int getParameterType(int param) { return Types.VARCHAR; }
        @Override public String getParameterTypeName(int param) { return "TEXT"; }
        @Override public String getParameterClassName(int param) { return "java.lang.Object"; }
        @Override public int getParameterMode(int param) { return parameterModeIn; }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    }
}

package com.dashnex.d1.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;

/** Base class that rejects every ResultSet mutation; IDEs edit data with SQL statements instead. */
abstract class ReadOnlyResultSet implements ResultSet {
    private static SQLFeatureNotSupportedException readOnly() {
        return new SQLFeatureNotSupportedException("D1 result sets are read-only");
    }

    @Override public boolean rowUpdated() { return false; }
    @Override public boolean rowInserted() { return false; }
    @Override public boolean rowDeleted() { return false; }
    @Override public void insertRow() throws SQLException { throw readOnly(); }
    @Override public void updateRow() throws SQLException { throw readOnly(); }
    @Override public void deleteRow() throws SQLException { throw readOnly(); }
    @Override public void refreshRow() throws SQLException { throw readOnly(); }
    @Override public void cancelRowUpdates() throws SQLException { throw readOnly(); }
    @Override public void moveToInsertRow() throws SQLException { throw readOnly(); }
    @Override public void moveToCurrentRow() throws SQLException { throw readOnly(); }

    @Override public void updateNull(int i) throws SQLException { throw readOnly(); }
    @Override public void updateBoolean(int i, boolean x) throws SQLException { throw readOnly(); }
    @Override public void updateByte(int i, byte x) throws SQLException { throw readOnly(); }
    @Override public void updateShort(int i, short x) throws SQLException { throw readOnly(); }
    @Override public void updateInt(int i, int x) throws SQLException { throw readOnly(); }
    @Override public void updateLong(int i, long x) throws SQLException { throw readOnly(); }
    @Override public void updateFloat(int i, float x) throws SQLException { throw readOnly(); }
    @Override public void updateDouble(int i, double x) throws SQLException { throw readOnly(); }
    @Override public void updateBigDecimal(int i, BigDecimal x) throws SQLException { throw readOnly(); }
    @Override public void updateString(int i, String x) throws SQLException { throw readOnly(); }
    @Override public void updateBytes(int i, byte[] x) throws SQLException { throw readOnly(); }
    @Override public void updateDate(int i, Date x) throws SQLException { throw readOnly(); }
    @Override public void updateTime(int i, Time x) throws SQLException { throw readOnly(); }
    @Override public void updateTimestamp(int i, Timestamp x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(int i, InputStream x, int l) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(int i, InputStream x, int l) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(int i, Reader x, int l) throws SQLException { throw readOnly(); }
    @Override public void updateObject(int i, Object x, int s) throws SQLException { throw readOnly(); }
    @Override public void updateObject(int i, Object x) throws SQLException { throw readOnly(); }
    @Override public void updateNull(String c) throws SQLException { throw readOnly(); }
    @Override public void updateBoolean(String c, boolean x) throws SQLException { throw readOnly(); }
    @Override public void updateByte(String c, byte x) throws SQLException { throw readOnly(); }
    @Override public void updateShort(String c, short x) throws SQLException { throw readOnly(); }
    @Override public void updateInt(String c, int x) throws SQLException { throw readOnly(); }
    @Override public void updateLong(String c, long x) throws SQLException { throw readOnly(); }
    @Override public void updateFloat(String c, float x) throws SQLException { throw readOnly(); }
    @Override public void updateDouble(String c, double x) throws SQLException { throw readOnly(); }
    @Override public void updateBigDecimal(String c, BigDecimal x) throws SQLException { throw readOnly(); }
    @Override public void updateString(String c, String x) throws SQLException { throw readOnly(); }
    @Override public void updateBytes(String c, byte[] x) throws SQLException { throw readOnly(); }
    @Override public void updateDate(String c, Date x) throws SQLException { throw readOnly(); }
    @Override public void updateTime(String c, Time x) throws SQLException { throw readOnly(); }
    @Override public void updateTimestamp(String c, Timestamp x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(String c, InputStream x, int l) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(String c, InputStream x, int l) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(String c, Reader x, int l) throws SQLException { throw readOnly(); }
    @Override public void updateObject(String c, Object x, int s) throws SQLException { throw readOnly(); }
    @Override public void updateObject(String c, Object x) throws SQLException { throw readOnly(); }
    @Override public void updateRef(int i, Ref x) throws SQLException { throw readOnly(); }
    @Override public void updateRef(String c, Ref x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(int i, Blob x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(String c, Blob x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(int i, Clob x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(String c, Clob x) throws SQLException { throw readOnly(); }
    @Override public void updateArray(int i, Array x) throws SQLException { throw readOnly(); }
    @Override public void updateArray(String c, Array x) throws SQLException { throw readOnly(); }
    @Override public void updateRowId(int i, RowId x) throws SQLException { throw readOnly(); }
    @Override public void updateRowId(String c, RowId x) throws SQLException { throw readOnly(); }
    @Override public void updateNString(int i, String x) throws SQLException { throw readOnly(); }
    @Override public void updateNString(String c, String x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(int i, NClob x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(String c, NClob x) throws SQLException { throw readOnly(); }
    @Override public void updateSQLXML(int i, SQLXML x) throws SQLException { throw readOnly(); }
    @Override public void updateSQLXML(String c, SQLXML x) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(int i, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(String c, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(int i, InputStream x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(int i, InputStream x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(int i, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(String c, InputStream x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(String c, InputStream x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(String c, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(int i, InputStream x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(String c, InputStream x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateClob(int i, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateClob(String c, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(int i, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(String c, Reader x, long l) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(int i, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(String c, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(int i, InputStream x) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(int i, InputStream x) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(int i, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(String c, InputStream x) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(String c, InputStream x) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(String c, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(int i, InputStream x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(String c, InputStream x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(int i, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(String c, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(int i, Reader x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(String c, Reader x) throws SQLException { throw readOnly(); }
}

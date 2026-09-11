package com.dashnex.d1.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

final class D1ResultSetMetaData implements ResultSetMetaData {
    private final List<String> columns;
    private final int[] types;
    private final String[] typeNames;
    private final String[] tables;

    D1ResultSetMetaData(List<String> columns, int[] types, String[] typeNames, String[] tables) {
        this.columns = columns;
        this.types = types;
        this.typeNames = typeNames;
        this.tables = tables;
    }

    private int idx(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException("Column index out of range: " + column, "07009");
        }
        return column - 1;
    }

    int type(int column) throws SQLException { return types[idx(column)]; }

    @Override public int getColumnCount() { return columns.size(); }
    @Override public boolean isAutoIncrement(int column) throws SQLException { idx(column); return false; }
    @Override public boolean isCaseSensitive(int column) throws SQLException { return types[idx(column)] == Types.VARCHAR; }
    @Override public boolean isSearchable(int column) throws SQLException { idx(column); return true; }
    @Override public boolean isCurrency(int column) throws SQLException { idx(column); return false; }
    @Override public int isNullable(int column) throws SQLException { idx(column); return columnNullableUnknown; }
    @Override public boolean isSigned(int column) throws SQLException { return D1Types.isNumeric(types[idx(column)]); }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        switch (types[idx(column)]) {
            case Types.BIGINT: return 20;
            case Types.DOUBLE:
            case Types.NUMERIC: return 25;
            case Types.BOOLEAN: return 5;
            default: return 255;
        }
    }

    @Override public String getColumnLabel(int column) throws SQLException { return columns.get(idx(column)); }
    @Override public String getColumnName(int column) throws SQLException { return columns.get(idx(column)); }
    @Override public String getSchemaName(int column) throws SQLException { idx(column); return ""; }
    @Override public int getPrecision(int column) throws SQLException { idx(column); return 0; }
    @Override public int getScale(int column) throws SQLException { idx(column); return 0; }
    @Override public String getTableName(int column) throws SQLException { return tables[idx(column)]; }
    @Override public String getCatalogName(int column) throws SQLException { idx(column); return ""; }
    @Override public int getColumnType(int column) throws SQLException { return types[idx(column)]; }
    @Override public String getColumnTypeName(int column) throws SQLException { return typeNames[idx(column)]; }
    @Override public boolean isReadOnly(int column) throws SQLException { idx(column); return false; }
    @Override public boolean isWritable(int column) throws SQLException { idx(column); return true; }
    @Override public boolean isDefinitelyWritable(int column) throws SQLException { idx(column); return false; }
    @Override public String getColumnClassName(int column) throws SQLException { return D1Types.className(types[idx(column)]); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}

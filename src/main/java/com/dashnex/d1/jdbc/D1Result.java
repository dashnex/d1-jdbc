package com.dashnex.d1.jdbc;

import java.util.List;

/** The outcome of one SQL statement returned by D1. Row values are Long, Double, String, byte[] or null. */
public final class D1Result {
    private final List<String> columns;
    private final List<Object[]> rows;
    private final long changes;
    private final long lastRowId;

    public D1Result(List<String> columns, List<Object[]> rows, long changes, long lastRowId) {
        this.columns = columns;
        this.rows = rows;
        this.changes = changes;
        this.lastRowId = lastRowId;
    }

    public List<String> getColumns() { return columns; }
    public List<Object[]> getRows() { return rows; }
    public long getChanges() { return changes; }
    public long getLastRowId() { return lastRowId; }
    public boolean hasColumns() { return !columns.isEmpty(); }
}

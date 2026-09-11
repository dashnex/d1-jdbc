package com.dashnex.d1.jdbc;

import java.sql.Types;
import java.util.Locale;

/** Maps SQLite declared types and D1 JSON values to java.sql.Types. */
public final class D1Types {
    private D1Types() {
    }

    public static int fromDeclared(String declared) {
        if (declared == null || declared.isBlank()) return Types.VARCHAR;
        String t = declared.toUpperCase(Locale.ROOT);
        if (t.contains("INT")) return Types.BIGINT;
        if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return Types.VARCHAR;
        if (t.contains("BLOB")) return Types.BLOB;
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")) return Types.DOUBLE;
        if (t.startsWith("BOOL")) return Types.BOOLEAN;
        if (t.contains("DATE") || t.contains("TIME")) return Types.VARCHAR;
        return Types.NUMERIC;
    }

    public static int fromValue(Object v) {
        if (v instanceof Double || v instanceof Float) return Types.DOUBLE;
        if (v instanceof Number) return Types.BIGINT;
        if (v instanceof Boolean) return Types.BOOLEAN;
        if (v instanceof byte[]) return Types.BLOB;
        return Types.VARCHAR;
    }

    public static String typeName(int jdbcType) {
        switch (jdbcType) {
            case Types.BIGINT: return "INTEGER";
            case Types.DOUBLE: return "REAL";
            case Types.BLOB: return "BLOB";
            case Types.BOOLEAN: return "BOOLEAN";
            case Types.NUMERIC: return "NUMERIC";
            default: return "TEXT";
        }
    }

    public static String className(int jdbcType) {
        switch (jdbcType) {
            case Types.BIGINT: return "java.lang.Long";
            case Types.DOUBLE: return "java.lang.Double";
            case Types.BLOB: return "[B";
            case Types.BOOLEAN: return "java.lang.Boolean";
            case Types.NUMERIC: return "java.lang.Number";
            default: return "java.lang.String";
        }
    }

    public static boolean isNumeric(int jdbcType) {
        return jdbcType == Types.BIGINT || jdbcType == Types.DOUBLE || jdbcType == Types.NUMERIC;
    }
}

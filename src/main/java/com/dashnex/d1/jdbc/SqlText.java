package com.dashnex.d1.jdbc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight SQL text inspection. Deliberately conservative: when unsure, answer "no". */
final class SqlText {
    static final String SQLITE_VERSION = "3.45.0";

    private static final Pattern TX_CONTROL = Pattern.compile(
            "(?is)^\\s*(?:BEGIN(?:\\s+(?:DEFERRED|IMMEDIATE|EXCLUSIVE))?(?:\\s+TRANSACTION)?"
                    + "|COMMIT(?:\\s+TRANSACTION)?|END(?:\\s+TRANSACTION)?"
                    + "|ROLLBACK(?:\\s+TRANSACTION)?(?:\\s+TO(?:\\s+SAVEPOINT)?\\s+\\S+)?"
                    + "|SAVEPOINT\\s+\\S+|RELEASE(?:\\s+SAVEPOINT)?\\s+\\S+)\\s*;?\\s*$");
    private static final Pattern SQLITE_VERSION_CALL = Pattern.compile("(?i)\\bsqlite_version\\s*\\(\\s*\\)");
    private static final Pattern DDL = Pattern.compile("(?i)\\b(?:CREATE|ALTER|DROP)\\b");
    private static final Pattern SET_OPERATION = Pattern.compile("(?i)\\b(?:UNION|INTERSECT|EXCEPT)\\b");
    private static final Pattern SINGLE_TABLE_SELECT = Pattern.compile(
            "(?is)^\\s*SELECT\\s+.+?\\s+FROM\\s+(?:main\\.)?"
                    + "(\"(?:[^\"]|\"\")+\"|`[^`]+`|\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "(?:\\s+(?:AS\\s+)?[A-Za-z_][A-Za-z0-9_]*)?"
                    + "(?:\\s+(?:WHERE|GROUP|ORDER|LIMIT)\\b.*)?\\s*$");

    private SqlText() {
    }

    static boolean isTransactionControl(String sql) {
        return sql != null && TX_CONTROL.matcher(sql).matches();
    }

    static String rewrite(String sql) {
        return SQLITE_VERSION_CALL.matcher(sql).replaceAll("'" + SQLITE_VERSION + "'");
    }

    /** True only for a single statement that cannot write: SELECT, EXPLAIN, VALUES, PRAGMA without '='. */
    static boolean isReadOnly(String sql) {
        if (sql == null || stripTrailingSemicolons(sql).indexOf(';') >= 0) return false;
        String kw = firstKeyword(sql);
        return kw.equals("SELECT") || kw.equals("EXPLAIN") || kw.equals("VALUES")
                || (kw.equals("PRAGMA") && sql.indexOf('=') < 0);
    }

    static boolean isInsert(String sql) {
        String kw = firstKeyword(sql);
        return kw.equals("INSERT") || kw.equals("REPLACE");
    }

    static boolean isDdl(String sql) {
        return sql != null && DDL.matcher(sql).find();
    }

    /** Returns the (unquoted) table of a simple single-table SELECT, or null. */
    static String singleTable(String sql) {
        if (sql == null) return null;
        String s = stripTrailingSemicolons(sql);
        if (s.indexOf(';') >= 0 || SET_OPERATION.matcher(s).find()) return null;
        Matcher m = SINGLE_TABLE_SELECT.matcher(s);
        return m.matches() ? unquoteIdentifier(m.group(1)) : null;
    }

    static int countParameters(String sql) {
        int count = 0;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                i = end < 0 ? n : end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
            } else if (c == '?') {
                count++;
            }
        }
        return count;
    }

    /** JDBC metadata pattern match: '%' any run, '_' any char, '\' escapes; case-insensitive. */
    static boolean matchesPattern(String pattern, String value) {
        if (pattern == null || pattern.equals("%")) return true;
        if (value == null) return false;
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                re.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                re.append(".*");
            } else if (c == '_') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(value).matches();
    }

    static String unquoteIdentifier(String id) {
        if (id.length() >= 2) {
            char first = id.charAt(0);
            char last = id.charAt(id.length() - 1);
            if (first == '"' && last == '"') return id.substring(1, id.length() - 1).replace("\"\"", "\"");
            if (first == '`' && last == '`') return id.substring(1, id.length() - 1);
            if (first == '[' && last == ']') return id.substring(1, id.length() - 1);
        }
        return id;
    }

    private static String firstKeyword(String sql) {
        if (sql == null) return "";
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) i++;
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static String stripTrailingSemicolons(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return sql.length();
    }
}

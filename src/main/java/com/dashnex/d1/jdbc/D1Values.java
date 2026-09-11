package com.dashnex.d1.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

/** Value coercion between D1 JSON values (Long, Double, String, byte[], null) and JDBC types. */
final class D1Values {
    private static final DateTimeFormatter PARSE_TS = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();
    private static final DateTimeFormatter FORMAT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FORMAT_TS_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FORMAT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private D1Values() {
    }

    static String toStr(Object v) {
        if (v == null) return null;
        if (v instanceof byte[]) return new String((byte[]) v, StandardCharsets.UTF_8);
        return v.toString();
    }

    static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Boolean) return ((Boolean) v) ? 1L : 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        String s = toStr(v).trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(s);
            } catch (NumberFormatException e2) {
                return 0L;
            }
        }
    }

    static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(toStr(v).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    static boolean toBoolean(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return toDouble(v) != 0.0;
    }

    static BigDecimal toBigDecimal(Object v) throws SQLException {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Double || v instanceof Float) return BigDecimal.valueOf(((Number) v).doubleValue());
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).longValue());
        if (v instanceof Boolean) return ((Boolean) v) ? BigDecimal.ONE : BigDecimal.ZERO;
        String s = toStr(v).trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert '" + s + "' to BigDecimal", "22018", e);
        }
    }

    static byte[] toBytes(Object v) {
        if (v == null) return null;
        if (v instanceof byte[]) return (byte[]) v;
        return toStr(v).getBytes(StandardCharsets.UTF_8);
    }

    static Timestamp toTimestamp(Object v) throws SQLException {
        if (v == null) return null;
        if (v instanceof Number) return new Timestamp(((Number) v).longValue());
        return Timestamp.valueOf(parseLocalDateTime(toStr(v)));
    }

    static Date toDate(Object v) throws SQLException {
        if (v == null) return null;
        if (v instanceof Number) return new Date(((Number) v).longValue());
        return Date.valueOf(parseLocalDateTime(toStr(v)).toLocalDate());
    }

    static Time toTime(Object v) throws SQLException {
        if (v == null) return null;
        if (v instanceof Number) return new Time(((Number) v).longValue());
        String s = toStr(v).trim();
        try {
            if (s.length() <= 12 && s.indexOf(':') > 0 && s.indexOf('-') < 0) return Time.valueOf(LocalTime.parse(s));
        } catch (DateTimeParseException e) {
            throw new SQLException("Cannot convert '" + s + "' to a time", "22007", e);
        }
        return Time.valueOf(parseLocalDateTime(s).toLocalTime());
    }

    private static LocalDateTime parseLocalDateTime(String s) throws SQLException {
        String t = s.trim().replace('T', ' ');
        if (t.endsWith("Z")) t = t.substring(0, t.length() - 1);
        try {
            if (t.length() == 10) return LocalDate.parse(t).atStartOfDay();
            if (t.length() == 16) t = t + ":00";
            return LocalDateTime.parse(t, PARSE_TS);
        } catch (DateTimeParseException e) {
            throw new SQLException("Cannot convert '" + s + "' to a date/time", "22007", e);
        }
    }

    /** Normalises a bound parameter to a type D1Client can encode (null, Boolean, Number, String, byte[]). */
    static Object toParam(Object x) {
        if (x instanceof Timestamp) return formatTimestamp(((Timestamp) x).toLocalDateTime());
        if (x instanceof Date) return ((Date) x).toLocalDate().toString();
        if (x instanceof Time) return ((Time) x).toLocalTime().format(FORMAT_TIME);
        if (x instanceof java.util.Date) return formatTimestamp(new Timestamp(((java.util.Date) x).getTime()).toLocalDateTime());
        if (x instanceof LocalDateTime) return formatTimestamp((LocalDateTime) x);
        if (x instanceof LocalTime) return ((LocalTime) x).format(FORMAT_TIME);
        if (x instanceof Character || x instanceof java.util.UUID || x instanceof java.time.temporal.Temporal) return x.toString();
        return x;
    }

    private static String formatTimestamp(LocalDateTime t) {
        return t.getNano() == 0 ? t.format(FORMAT_TS) : t.format(FORMAT_TS_MILLIS);
    }

    static byte[] readBytes(InputStream in) throws SQLException {
        if (in == null) return null;
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            is.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Cannot read stream: " + e.getMessage(), "HY000", e);
        }
    }

    static String readString(Reader in) throws SQLException {
        if (in == null) return null;
        try (Reader r = in) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            throw new SQLException("Cannot read reader: " + e.getMessage(), "HY000", e);
        }
    }
}

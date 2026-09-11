package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class D1ValuesTest {
    @Test
    void coercesLikeSqlite() throws SQLException {
        assertEquals("42", D1Values.toStr(42L));
        assertEquals("1.5", D1Values.toStr(1.5));
        assertEquals("hi", D1Values.toStr("hi".getBytes(StandardCharsets.UTF_8)));
        assertNull(D1Values.toStr(null));
        assertEquals(12L, D1Values.toLong("12"));
        assertEquals(12L, D1Values.toLong(" 12.9 "));
        assertEquals(0L, D1Values.toLong("abc"));
        assertEquals(3L, D1Values.toLong(3.7));
        assertEquals(1L, D1Values.toLong(Boolean.TRUE));
        assertEquals(2.5, D1Values.toDouble("2.5"));
        assertTrue(D1Values.toBoolean(1L));
        assertTrue(D1Values.toBoolean("true"));
        assertFalse(D1Values.toBoolean("0"));
        assertFalse(D1Values.toBoolean(null));
        assertEquals(new BigDecimal("12.34"), D1Values.toBigDecimal("12.34"));
        assertEquals(BigDecimal.valueOf(7L), D1Values.toBigDecimal(7L));
        assertThrows(SQLException.class, () -> D1Values.toBigDecimal("x"));
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), D1Values.toBytes("ab"));
    }

    @Test
    void parsesDatesFromText() throws SQLException {
        assertEquals(Timestamp.valueOf(LocalDateTime.of(2024, 1, 2, 3, 4, 5)), D1Values.toTimestamp("2024-01-02 03:04:05"));
        assertEquals(Timestamp.valueOf(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_000_000)), D1Values.toTimestamp("2024-01-02T03:04:05.123Z"));
        assertEquals(Timestamp.valueOf(LocalDateTime.of(2024, 1, 2, 0, 0)), D1Values.toTimestamp("2024-01-02"));
        assertEquals(Date.valueOf(LocalDate.of(2024, 1, 2)), D1Values.toDate("2024-01-02 10:00:00"));
        assertEquals(Time.valueOf("10:11:12"), D1Values.toTime("10:11:12"));
        assertEquals(new Timestamp(1000L), D1Values.toTimestamp(1000L));
        assertThrows(SQLException.class, () -> D1Values.toTimestamp("not a date"));
    }

    @Test
    void normalisesParameters() throws SQLException {
        assertEquals("2024-01-02 03:04:05", D1Values.toParam(Timestamp.valueOf("2024-01-02 03:04:05")));
        assertEquals("2024-01-02 03:04:05.500", D1Values.toParam(Timestamp.valueOf("2024-01-02 03:04:05.5")));
        assertEquals("2024-01-02", D1Values.toParam(Date.valueOf("2024-01-02")));
        assertEquals("10:00:00", D1Values.toParam(Time.valueOf("10:00:00")));
        assertEquals("x", D1Values.toParam('x'));
        assertEquals(5L, D1Values.toParam(5L));
        assertArrayEquals(new byte[]{1, 2}, D1Values.readBytes(new ByteArrayInputStream(new byte[]{1, 2})));
        assertEquals("abc", D1Values.readString(new StringReader("abc")));
    }
}

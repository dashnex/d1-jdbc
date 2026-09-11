package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

class D1TypesTest {
    @Test
    void mapsDeclaredTypesByAffinity() {
        assertEquals(Types.BIGINT, D1Types.fromDeclared("INTEGER"));
        assertEquals(Types.BIGINT, D1Types.fromDeclared("bigint"));
        assertEquals(Types.VARCHAR, D1Types.fromDeclared("VARCHAR(255)"));
        assertEquals(Types.VARCHAR, D1Types.fromDeclared("TEXT"));
        assertEquals(Types.BLOB, D1Types.fromDeclared("BLOB"));
        assertEquals(Types.DOUBLE, D1Types.fromDeclared("REAL"));
        assertEquals(Types.DOUBLE, D1Types.fromDeclared("double precision"));
        assertEquals(Types.BOOLEAN, D1Types.fromDeclared("BOOLEAN"));
        assertEquals(Types.VARCHAR, D1Types.fromDeclared("DATETIME"));
        assertEquals(Types.NUMERIC, D1Types.fromDeclared("DECIMAL(10,2)"));
        assertEquals(Types.VARCHAR, D1Types.fromDeclared(""));
        assertEquals(Types.VARCHAR, D1Types.fromDeclared(null));
    }

    @Test
    void infersTypesFromValues() {
        assertEquals(Types.BIGINT, D1Types.fromValue(1L));
        assertEquals(Types.DOUBLE, D1Types.fromValue(1.5));
        assertEquals(Types.BLOB, D1Types.fromValue(new byte[]{1}));
        assertEquals(Types.VARCHAR, D1Types.fromValue("x"));
        assertEquals(Types.VARCHAR, D1Types.fromValue(null));
    }

    @Test
    void namesAndClasses() {
        assertEquals("INTEGER", D1Types.typeName(Types.BIGINT));
        assertEquals("TEXT", D1Types.typeName(Types.VARCHAR));
        assertEquals("java.lang.Long", D1Types.className(Types.BIGINT));
        assertEquals("[B", D1Types.className(Types.BLOB));
        assertTrue(D1Types.isNumeric(Types.DOUBLE));
        assertFalse(D1Types.isNumeric(Types.VARCHAR));
    }
}

package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlTextTest {
    @Test
    void detectsStandaloneTransactionControl() {
        for (String s : new String[]{"BEGIN", "begin transaction;", " BEGIN IMMEDIATE TRANSACTION ", "COMMIT", "end",
                "ROLLBACK", "rollback to savepoint sp1", "SAVEPOINT sp1", "RELEASE SAVEPOINT sp1", "release sp1"}) {
            assertTrue(SqlText.isTransactionControl(s), s);
        }
        for (String s : new String[]{"SELECT 1", "BEGIN; SELECT 1", "CREATE TABLE begin_x(a)", "UPDATE t SET commit = 1"}) {
            assertFalse(SqlText.isTransactionControl(s), s);
        }
    }

    @Test
    void rewritesSqliteVersionCalls() {
        assertEquals("SELECT '3.45.0' AS v", SqlText.rewrite("SELECT sqlite_version() AS v"));
        assertEquals("select '3.45.0'", SqlText.rewrite("select SQLITE_VERSION ( )"));
        assertEquals("SELECT 1", SqlText.rewrite("SELECT 1"));
    }

    @Test
    void classifiesStatements() {
        assertTrue(SqlText.isReadOnly("  -- c\n SELECT * FROM t"));
        assertTrue(SqlText.isReadOnly("/* x */ (SELECT 1)"));
        assertTrue(SqlText.isReadOnly("PRAGMA table_info(t)"));
        assertTrue(SqlText.isReadOnly("explain query plan select 1;"));
        assertFalse(SqlText.isReadOnly("PRAGMA foreign_keys = ON"));
        assertFalse(SqlText.isReadOnly("SELECT 1; DELETE FROM t"));
        assertFalse(SqlText.isReadOnly("WITH x AS (SELECT 1) DELETE FROM t"));
        assertFalse(SqlText.isReadOnly("INSERT INTO t VALUES (1)"));
        assertTrue(SqlText.isInsert("insert into t values (1)"));
        assertTrue(SqlText.isInsert("REPLACE INTO t VALUES (1)"));
        assertFalse(SqlText.isInsert("UPDATE t SET a = 1"));
        assertTrue(SqlText.isDdl("ALTER TABLE t ADD COLUMN c TEXT"));
        assertTrue(SqlText.isDdl("create index i on t(a)"));
        assertFalse(SqlText.isDdl("SELECT created FROM t"));
        assertTrue(SqlText.isSelect("  select * from t"));
        assertTrue(SqlText.isSelect("VALUES (1), (2)"));
        assertFalse(SqlText.isSelect("EXPLAIN SELECT 1"));
        assertFalse(SqlText.isSelect("PRAGMA table_info(t)"));
        assertFalse(SqlText.isSelect("SELECT 1; SELECT 2"));
        assertFalse(SqlText.isSelect("INSERT INTO t VALUES (1)"));
    }

    @Test
    void detectsSingleTableSelects() {
        assertEquals("users", SqlText.singleTable("SELECT * FROM users"));
        assertEquals("users", SqlText.singleTable("select u.* from users u where u.id > 1 order by id limit 0, 200;"));
        assertEquals("my table", SqlText.singleTable("SELECT * FROM \"my table\" LIMIT 10"));
        assertEquals("a\"b", SqlText.singleTable("SELECT * FROM \"a\"\"b\""));
        assertEquals("t", SqlText.singleTable("SELECT * FROM `t`"));
        assertEquals("t", SqlText.singleTable("SELECT * FROM [t] AS x"));
        assertEquals("t", SqlText.singleTable("SELECT * FROM main.t"));
        assertNull(SqlText.singleTable("SELECT * FROM a JOIN b ON a.id = b.id"));
        assertNull(SqlText.singleTable("SELECT * FROM a, b"));
        assertNull(SqlText.singleTable("SELECT * FROM a UNION SELECT * FROM b"));
        assertNull(SqlText.singleTable("SELECT * FROM a; SELECT * FROM b"));
        assertNull(SqlText.singleTable("UPDATE t SET a = 1"));
        assertNull(SqlText.singleTable("SELECT 1"));
    }

    @Test
    void ignoresTablesInsideSubqueries() {
        // F1: a subquery inside WHERE (or a JOIN) must not be mistaken for the FROM table.
        assertNull(SqlText.singleTable(
                "SELECT * FROM a JOIN b ON a.id=b.id WHERE a.x IN (SELECT y FROM c WHERE z=1)"));
        assertNull(SqlText.singleTable("SELECT a.id FROM a, b WHERE a.id IN (SELECT id FROM c WHERE 1)"));
        assertNull(SqlText.singleTable("SELECT * FROM (SELECT * FROM b WHERE x=1) t"));
        assertEquals("t", SqlText.singleTable("SELECT * FROM t WHERE id IN (SELECT id FROM u)"));
    }

    @Test
    void countsParametersOutsideLiteralsAndComments() {
        assertEquals(2, SqlText.countParameters("SELECT * FROM t WHERE a = ? AND b = ?"));
        assertEquals(1, SqlText.countParameters("SELECT '?', \"?\", `?`, [?] -- ?\n, ? /* ? */"));
        assertEquals(0, SqlText.countParameters("SELECT 'it''s ?'"));
    }

    @Test
    void matchesJdbcLikePatterns() {
        assertTrue(SqlText.matchesPattern(null, "anything"));
        assertTrue(SqlText.matchesPattern("%", "anything"));
        assertTrue(SqlText.matchesPattern("us%", "USERS"));
        assertTrue(SqlText.matchesPattern("u_ers", "users"));
        assertFalse(SqlText.matchesPattern("my\\_t", "myXt"));
        assertTrue(SqlText.matchesPattern("my\\_t", "my_t"));
        assertFalse(SqlText.matchesPattern("users", null));
    }
}

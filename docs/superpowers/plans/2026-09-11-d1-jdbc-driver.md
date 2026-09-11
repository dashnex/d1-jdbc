# Cloudflare D1 JDBC Driver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A JDBC driver JAR that lets DBeaver and DataGrip browse (incl. foreign keys), edit (CRUD) and alter (add/drop columns) a Cloudflare D1 database using an account ID + API token.

**Architecture:** Pure REST driver: every statement is a `POST …/d1/database/{id}/raw` call via `java.net.http`. The driver reports itself as SQLite so IDEs use their SQLite dialect; `DatabaseMetaData` is built from `sqlite_master` + table-valued PRAGMA functions. No transactions (D1 REST has none).

**Tech Stack:** Java 11 bytecode (built with JDK 17), Gradle 8.10.2 wrapper, Shadow plugin 8.3.5, Jackson 2.17.2 (shaded), JUnit 5.10.3, JDK `HttpServer` as test stub.

**Spec:** `docs/superpowers/specs/2026-09-11-d1-jdbc-driver-design.md` (read the "Verified D1 behaviour" section first).

## Global Constraints

- Package: `com.dashnex.d1.jdbc`. Driver class: `com.dashnex.d1.jdbc.D1Driver`.
- URL: `jdbc:d1://<database name or UUID>[?key=value&…]`; `user` = account ID, `password` = token; optional props `accountId`, `token`, `apiBase` (default `https://api.cloudflare.com/client/v4`), `timeoutSeconds` (default 30).
- `options.release = 11`. Only runtime dependency: `com.fasterxml.jackson.core:jackson-databind:2.17.2`, relocated to `com.dashnex.d1.jdbc.shaded.jackson` in the `-all` jar.
- Product name `SQLite`, product version `3.45.0` (`SqlText.SQLITE_VERSION`), driver name `Cloudflare D1 JDBC`, driver version `0.1`.
- Metadata SQL must always exclude `sqlite\_%` and `\_cf\_%` tables (else D1 returns `SQLITE_AUTH`).
- Booleans are sent to D1 as `1`/`0`; `byte[]` as JSON arrays of 0–255 ints.
- Never retry non-read-only SQL on 5xx/I/O errors.
- Unit tests must never hit the network; integration tests are tagged `integration` and read `D1_ACCOUNT_ID`, `D1_TOKEN`, `D1_DATABASE` (from env or the gitignored `.env`).
- Commit after every task; never commit `.env`.

## File Structure

```
build.gradle.kts, settings.gradle.kts, gradlew, gradlew.bat, gradle/wrapper/*
src/main/java/com/dashnex/d1/jdbc/
  D1ConnectionConfig.java   URL + property parsing (immutable)
  SqlText.java              SQL text helpers: tx-control detection, rewrite, single-table detection, param count, LIKE patterns
  D1Types.java              declared type / value -> java.sql.Types, type & class names
  D1Values.java             value coercion for getters and parameter normalisation
  D1Result.java             one statement's columns/rows/changes/lastRowId
  D1Errors.java             Cloudflare error JSON -> SQLException
  D1Client.java             HTTP + JSON, retries, database-name resolution
  ReadOnlyResultSet.java    abstract base: all ResultSet update* methods throw
  D1ResultSet.java          in-memory scrollable result set
  D1ResultSetMetaData.java  column names/types/table names
  D1Connection.java         Connection; declared-type cache
  D1Statement.java          Statement; results, update counts, generated keys, batch
  D1PreparedStatement.java  PreparedStatement; parameter binding
  D1DatabaseMetaData.java   DatabaseMetaData via PRAGMA
  D1Driver.java             Driver entry point
src/main/resources/META-INF/services/java.sql.Driver
src/test/java/com/dashnex/d1/jdbc/
  StubD1Server.java, D1ConnectionConfigTest, SqlTextTest, D1TypesTest, D1ValuesTest,
  D1ClientTest, D1ResultSetTest, D1StatementTest, D1DatabaseMetaDataTest, D1IntegrationTest
README.md
```

---
### Task 1: Gradle scaffold + D1ConnectionConfig

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper files
- Create: `src/main/java/com/dashnex/d1/jdbc/D1ConnectionConfig.java`
- Test: `src/test/java/com/dashnex/d1/jdbc/D1ConnectionConfigTest.java`

**Interfaces:**
- Produces: `D1ConnectionConfig.parse(String url, Properties info) throws SQLException`, `static boolean acceptsUrl(String)`, getters `getAccountId()`, `getToken()`, `getDatabase()`, `getApiBase()`, `getTimeoutSeconds()`, `boolean isDatabaseUuid()`; constants `URL_PREFIX = "jdbc:d1:"`, `DEFAULT_API_BASE`.

- [ ] **Step 1: Bootstrap the Gradle wrapper** (Gradle is not installed; ask the user before downloading)

```bash
S=/private/tmp/claude-501/-Users-me-work-d1-jdbc/6c47440e-ae66-4b89-8892-026a9ed5ac5d/scratchpad
curl -fsSL -o $S/gradle-8.10.2-bin.zip https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
unzip -q -o $S/gradle-8.10.2-bin.zip -d $S
cd /Users/me/work/d1-jdbc && printf 'rootProject.name = "d1-jdbc"\n' > settings.gradle.kts
$S/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2
```

- [ ] **Step 2: Write `build.gradle.kts`**

```kotlin
plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.dashnex"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

fun dotEnv(): Map<String, String> {
    val f = rootProject.file(".env")
    if (!f.exists()) return emptyMap()
    return f.readLines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim().removePrefix("export ").trim() to it.substringAfter("=").trim() }
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs tests against a real Cloudflare D1 database (D1_ACCOUNT_ID, D1_TOKEN, D1_DATABASE)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    environment(dotEnv())
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    relocate("com.fasterxml.jackson", "com.dashnex.d1.jdbc.shaded.jackson")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
```

- [ ] **Step 3: Write the failing test** `D1ConnectionConfigTest.java`

```java
package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class D1ConnectionConfigTest {
    private static final String UUID = "3f1b6aab-97b8-4db6-8df4-1ace68f716a0";

    private static Properties creds(String user, String password) {
        Properties p = new Properties();
        if (user != null) p.setProperty("user", user);
        if (password != null) p.setProperty("password", password);
        return p;
    }

    @Test
    void parsesUuidUrlWithUserAndPassword() throws SQLException {
        D1ConnectionConfig c = D1ConnectionConfig.parse("jdbc:d1://" + UUID, creds("acc", "tok"));
        assertEquals("acc", c.getAccountId());
        assertEquals("tok", c.getToken());
        assertEquals(UUID, c.getDatabase());
        assertTrue(c.isDatabaseUuid());
        assertEquals(D1ConnectionConfig.DEFAULT_API_BASE, c.getApiBase());
        assertEquals(30, c.getTimeoutSeconds());
    }

    @Test
    void acceptsNameWithoutSlashesAndTrailingSlash() throws SQLException {
        assertEquals("my-db", D1ConnectionConfig.parse("jdbc:d1:my-db", creds("a", "t")).getDatabase());
        D1ConnectionConfig c = D1ConnectionConfig.parse("jdbc:d1://my-db/", creds("a", "t"));
        assertEquals("my-db", c.getDatabase());
        assertFalse(c.isDatabaseUuid());
    }

    @Test
    void urlQueryParametersAreReadAndInfoOverridesThem() throws SQLException {
        Properties info = creds("acc", "tok");
        info.setProperty("timeoutSeconds", "5");
        D1ConnectionConfig c = D1ConnectionConfig.parse(
                "jdbc:d1://db?apiBase=http%3A%2F%2F127.0.0.1%3A8080%2Fv4%2F&timeoutSeconds=9", info);
        assertEquals("http://127.0.0.1:8080/v4", c.getApiBase());
        assertEquals(5, c.getTimeoutSeconds());
    }

    @Test
    void explicitAccountIdAndTokenWinOverUserAndPassword() throws SQLException {
        Properties info = creds("user-acc", "pw-token");
        info.setProperty("accountId", "acc2");
        info.setProperty("token", "tok2");
        D1ConnectionConfig c = D1ConnectionConfig.parse("jdbc:d1://db", info);
        assertEquals("acc2", c.getAccountId());
        assertEquals("tok2", c.getToken());
    }

    @Test
    void missingCredentialsFailWithSqlState28000() {
        SQLException noUser = assertThrows(SQLException.class,
                () -> D1ConnectionConfig.parse("jdbc:d1://db", creds(null, "tok")));
        assertEquals("28000", noUser.getSQLState());
        SQLException noToken = assertThrows(SQLException.class,
                () -> D1ConnectionConfig.parse("jdbc:d1://db", creds("acc", " ")));
        assertEquals("28000", noToken.getSQLState());
    }

    @Test
    void missingDatabaseFails() {
        SQLException e = assertThrows(SQLException.class,
                () -> D1ConnectionConfig.parse("jdbc:d1://", creds("a", "t")));
        assertEquals("08001", e.getSQLState());
    }

    @Test
    void invalidTimeoutFails() {
        Properties info = creds("a", "t");
        info.setProperty("timeoutSeconds", "abc");
        assertThrows(SQLException.class, () -> D1ConnectionConfig.parse("jdbc:d1://db", info));
    }

    @Test
    void acceptsOnlyD1Urls() {
        assertTrue(D1ConnectionConfig.acceptsUrl("jdbc:d1://x"));
        assertFalse(D1ConnectionConfig.acceptsUrl("jdbc:sqlite:x"));
        assertFalse(D1ConnectionConfig.acceptsUrl(null));
    }
}
```

- [ ] **Step 4: Run it — expect compilation failure** (`D1ConnectionConfig` missing)

Run: `./gradlew test --tests '*D1ConnectionConfigTest'` → FAIL (cannot find symbol).

- [ ] **Step 5: Implement `D1ConnectionConfig.java`**

```java
package com.dashnex.d1.jdbc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Pattern;

/** Immutable connection settings parsed from a {@code jdbc:d1://} URL and driver properties. */
public final class D1ConnectionConfig {
    public static final String URL_PREFIX = "jdbc:d1:";
    public static final String DEFAULT_API_BASE = "https://api.cloudflare.com/client/v4";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final String accountId;
    private final String token;
    private final String database;
    private final String apiBase;
    private final int timeoutSeconds;

    private D1ConnectionConfig(String accountId, String token, String database, String apiBase, int timeoutSeconds) {
        this.accountId = accountId;
        this.token = token;
        this.database = database;
        this.apiBase = apiBase;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static boolean acceptsUrl(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    public static D1ConnectionConfig parse(String url, Properties info) throws SQLException {
        if (!acceptsUrl(url)) {
            throw new SQLException("Not a D1 JDBC URL: " + url, "08001");
        }
        String rest = url.substring(URL_PREFIX.length());
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        Properties props = new Properties();
        int q = rest.indexOf('?');
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                props.setProperty(key, value);
            }
            rest = rest.substring(0, q);
        }
        if (info != null) {
            for (String key : info.stringPropertyNames()) {
                props.setProperty(key, info.getProperty(key));
            }
        }
        String database = stripTrailingSlashes(decode(rest)).trim();
        if (database.isEmpty()) {
            throw new SQLException("Missing D1 database name or UUID in URL (expected jdbc:d1://<database>)", "08001");
        }
        String accountId = firstNonBlank(props.getProperty("accountId"), props.getProperty("user"));
        if (accountId == null) {
            throw new SQLException("Missing Cloudflare account ID: set the User field (or the accountId property)", "28000");
        }
        String token = firstNonBlank(props.getProperty("token"), props.getProperty("password"));
        if (token == null) {
            throw new SQLException("Missing Cloudflare API token: set the Password field (or the token property)", "28000");
        }
        String apiBase = stripTrailingSlashes(firstNonBlank(props.getProperty("apiBase"), DEFAULT_API_BASE).trim());
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        String timeoutText = props.getProperty("timeoutSeconds");
        if (timeoutText != null && !timeoutText.isBlank()) {
            try {
                timeout = Integer.parseInt(timeoutText.trim());
            } catch (NumberFormatException e) {
                throw new SQLException("Invalid timeoutSeconds: " + timeoutText, "08001");
            }
            if (timeout <= 0) {
                throw new SQLException("timeoutSeconds must be positive: " + timeoutText, "08001");
            }
        }
        return new D1ConnectionConfig(accountId.trim(), token.trim(), database, apiBase, timeout);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlashes(String s) {
        String r = s;
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    public boolean isDatabaseUuid() {
        return UUID_PATTERN.matcher(database).matches();
    }

    public String getAccountId() { return accountId; }
    public String getToken() { return token; }
    public String getDatabase() { return database; }
    public String getApiBase() { return apiBase; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
}
```

- [ ] **Step 6: Run tests — expect PASS**

Run: `./gradlew test --tests '*D1ConnectionConfigTest'` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle src
git commit -m "feat: gradle scaffold and D1 connection config parsing"
```

---
### Task 2: SQL text, type mapping and value coercion helpers

**Files:**
- Create: `src/main/java/com/dashnex/d1/jdbc/SqlText.java`, `D1Types.java`, `D1Values.java`
- Test: `src/test/java/com/dashnex/d1/jdbc/SqlTextTest.java`, `D1TypesTest.java`, `D1ValuesTest.java`

**Interfaces:**
- Produces (`SqlText`, package-private static): `SQLITE_VERSION = "3.45.0"`, `boolean isTransactionControl(String)`, `String rewrite(String)`, `boolean isReadOnly(String)`, `boolean isInsert(String)`, `boolean isDdl(String)`, `String singleTable(String)` (unquoted name or null), `int countParameters(String)`, `boolean matchesPattern(String likePattern, String value)`.
- Produces (`D1Types`, public static): `int fromDeclared(String)`, `int fromValue(Object)`, `String typeName(int)`, `String className(int)`, `boolean isNumeric(int)`.
- Produces (`D1Values`, package-private static): `String toStr(Object)`, `long toLong(Object)`, `double toDouble(Object)`, `boolean toBoolean(Object)`, `BigDecimal toBigDecimal(Object) throws SQLException`, `byte[] toBytes(Object)`, `Timestamp toTimestamp(Object) throws SQLException`, `Date toDate(Object) throws SQLException`, `Time toTime(Object) throws SQLException`, `Object toParam(Object)`, `byte[] readBytes(InputStream) throws SQLException`, `String readString(Reader) throws SQLException`.

- [ ] **Step 1: Write failing tests**

`SqlTextTest.java`:

```java
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
```

`D1TypesTest.java`:

```java
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
```

`D1ValuesTest.java`:

```java
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
```

- [ ] **Step 2: Run — expect compilation failure**

Run: `./gradlew test --tests '*SqlTextTest' --tests '*D1TypesTest' --tests '*D1ValuesTest'` → FAIL.

- [ ] **Step 3: Implement `SqlText.java`**

```java
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
```

- [ ] **Step 4: Implement `D1Types.java`**

```java
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
```

- [ ] **Step 5: Implement `D1Values.java`**

```java
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
```

Note: `LocalDate` is a `Temporal`, so `toParam(LocalDate)` yields `yyyy-MM-dd` via `toString()`; `LocalDateTime`/`LocalTime` are handled before that branch.

- [ ] **Step 6: Run — expect PASS**

Run: `./gradlew test --tests '*SqlTextTest' --tests '*D1TypesTest' --tests '*D1ValuesTest'` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src && git commit -m "feat: SQL text, type mapping and value coercion helpers"
```

---
### Task 3: D1 HTTP client (results, errors, retries, name resolution)

**Files:**
- Create: `src/main/java/com/dashnex/d1/jdbc/D1Result.java`, `D1Errors.java`, `D1Client.java`
- Create (test helper, reused by later tasks): `src/test/java/com/dashnex/d1/jdbc/StubD1Server.java`
- Test: `src/test/java/com/dashnex/d1/jdbc/D1ClientTest.java`

**Interfaces:**
- Consumes: `D1ConnectionConfig` (Task 1), `SqlText.isReadOnly` (Task 2).
- Produces:
  - `D1Result(List<String> columns, List<Object[]> rows, long changes, long lastRowId)`; `getColumns()`, `getRows()`, `getChanges()`, `getLastRowId()`, `hasColumns()`.
  - `D1Client(D1ConnectionConfig)`; package-private `D1Client(D1ConnectionConfig, long[] backoffMillis)`; `String databaseId() throws SQLException`; `List<D1Result> execute(String sql, List<?> params) throws SQLException`; `List<D1Result> batch(List<D1Client.Stmt>) throws SQLException`.
  - `D1Client.Stmt(String sql, List<Object> params)`; `getSql()`, `getParams()`.
  - `D1Errors.toSqlException(int httpStatus, JsonNode body)`.
  - Test helper `StubD1Server` (see code): `url()`, `credentials()`, `config()`, `requests()`, `lastRequest()`, `enqueue(Response)`, `handler(Function<Request, Response>)`, static `ok(String...)`, `result(...)`, `empty(long, long)`, `error(int, int, String)`; `Request.sql()`, `Request.params()`, `Request.body`, `Request.path`, `Request.query`, `Request.method`, `Request.authorization`.

- [ ] **Step 1: Write the stub server** `StubD1Server.java`

```java
package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** In-process fake of the Cloudflare API for unit tests. */
final class StubD1Server implements AutoCloseable {
    static final String ACCOUNT = "acc123";
    static final String TOKEN = "tok456";
    static final String DB_UUID = "3f1b6aab-97b8-4db6-8df4-1ace68f716a0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final class Request {
        final String method;
        final String path;
        final String query;
        final String authorization;
        final JsonNode body;

        Request(String method, String path, String query, String authorization, JsonNode body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.authorization = authorization;
            this.body = body;
        }

        String sql() {
            return body == null ? null : body.path("sql").asText(null);
        }

        JsonNode params() {
            return body == null ? null : body.path("params");
        }
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Response> queued = new ConcurrentLinkedDeque<>();
    private volatile Function<Request, Response> handler =
            r -> error(500, 0, "no stub response for " + r.sql());

    StubD1Server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? null : MAPPER.readTree(raw);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"), body);
            requests.add(request);
            Response response = queued.poll();
            if (response == null) response = handler.apply(request);
            byte[] out = response.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
    }

    String url() {
        return url(DB_UUID);
    }

    String url(String database) {
        return "jdbc:d1://" + database + "?apiBase=" + apiBase();
    }

    Properties credentials() {
        Properties p = new Properties();
        p.setProperty("user", ACCOUNT);
        p.setProperty("password", TOKEN);
        return p;
    }

    D1ConnectionConfig config() throws SQLException {
        return D1ConnectionConfig.parse(url(), credentials());
    }

    List<Request> requests() {
        return new ArrayList<>(requests);
    }

    Request lastRequest() {
        return requests.get(requests.size() - 1);
    }

    void enqueue(Response response) {
        queued.add(response);
    }

    void handler(Function<Request, Response> h) {
        handler = h;
    }

    static Response ok(String... results) {
        return new Response(200, "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":["
                + String.join(",", results) + "]}");
    }

    static String result(String[] columns, Object[][] rows) {
        return result(columns, rows, 0, 0);
    }

    /** Builds one D1 result entry. Use int[] (not byte[]) for blob values. */
    static String result(String[] columns, Object[][] rows, long changes, long lastRowId) {
        ObjectNode r = MAPPER.createObjectNode();
        ObjectNode results = r.putObject("results");
        ArrayNode cols = results.putArray("columns");
        for (String c : columns) cols.add(c);
        results.set("rows", MAPPER.valueToTree(rows));
        ObjectNode meta = r.putObject("meta");
        meta.put("changes", changes);
        meta.put("last_row_id", lastRowId);
        r.put("success", true);
        return r.toString();
    }

    static String empty(long changes, long lastRowId) {
        return result(new String[0], new Object[0][], changes, lastRowId);
    }

    static Response error(int status, int code, String message) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("success", false);
        r.putArray("result");
        r.putArray("messages");
        r.putArray("errors").addObject().put("code", code).put("message", message);
        return new Response(status, r.toString());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: Write the failing test** `D1ClientTest.java`

```java
package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static com.dashnex.d1.jdbc.StubD1Server.*;
import static org.junit.jupiter.api.Assertions.*;

class D1ClientTest {
    private StubD1Server stub;
    private D1Client client;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubD1Server();
        client = new D1Client(stub.config(), new long[]{1, 1, 1});
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void executeSendsAuthorizedJsonAndParsesResults() throws SQLException {
        stub.enqueue(ok(result(new String[]{"i", "r", "s", "b", "n"},
                new Object[][]{{1, 1.5, "x", new int[]{1, 2, 255}, null}})));
        List<D1Result> results = client.execute("SELECT ?, ?, ?, ?, ?",
                Arrays.asList(true, 7L, 2.5, new byte[]{1, (byte) 255}, null));

        Request req = stub.lastRequest();
        assertEquals("POST", req.method);
        assertEquals("/client/v4/accounts/" + ACCOUNT + "/d1/database/" + DB_UUID + "/raw", req.path);
        assertEquals("Bearer " + TOKEN, req.authorization);
        assertEquals("SELECT ?, ?, ?, ?, ?", req.sql());
        JsonNode p = req.params();
        assertEquals(1, p.get(0).asInt());
        assertEquals(7, p.get(1).asLong());
        assertEquals(2.5, p.get(2).asDouble());
        assertTrue(p.get(3).isArray());
        assertEquals(255, p.get(3).get(1).asInt());
        assertTrue(p.get(4).isNull());

        assertEquals(1, results.size());
        D1Result r = results.get(0);
        assertEquals(List.of("i", "r", "s", "b", "n"), r.getColumns());
        Object[] row = r.getRows().get(0);
        assertEquals(1L, row[0]);
        assertEquals(1.5, row[1]);
        assertEquals("x", row[2]);
        assertArrayEquals(new byte[]{1, 2, (byte) 255}, (byte[]) row[3]);
        assertNull(row[4]);
        assertTrue(r.hasColumns());
    }

    @Test
    void parsesChangesAndLastRowIdForEachStatement() throws SQLException {
        stub.enqueue(ok(empty(1, 42), empty(0, 42)));
        List<D1Result> results = client.execute("INSERT INTO t VALUES (1); CREATE INDEX i ON t(a)", List.of());
        assertEquals(2, results.size());
        assertEquals(1, results.get(0).getChanges());
        assertEquals(42, results.get(0).getLastRowId());
        assertFalse(results.get(1).hasColumns());
    }

    @Test
    void batchSendsAllStatements() throws SQLException {
        stub.enqueue(ok(empty(1, 1), empty(1, 2)));
        List<D1Result> results = client.batch(List.of(
                new D1Client.Stmt("INSERT INTO t(a) VALUES (?)", List.of("x")),
                new D1Client.Stmt("INSERT INTO t(a) VALUES (?)", List.of("y"))));
        JsonNode batch = stub.lastRequest().body.get("batch");
        assertEquals(2, batch.size());
        assertEquals("y", batch.get(1).get("params").get(0).asText());
        assertEquals(2, results.size());
    }

    @Test
    void mapsErrorsToSqlStates() {
        stub.enqueue(error(400, 7500, "UNIQUE constraint failed: t.e: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_UNIQUE)"));
        SQLException constraint = assertThrows(SQLException.class, () -> client.execute("INSERT INTO t VALUES (1)", List.of()));
        assertEquals("23000", constraint.getSQLState());
        assertEquals(7500, constraint.getErrorCode());
        assertTrue(constraint.getMessage().contains("UNIQUE constraint failed"));

        stub.enqueue(error(400, 7500, "near \"selec\": syntax error at offset 0: SQLITE_ERROR"));
        assertEquals("42000", assertThrows(SQLException.class, () -> client.execute("selec 1", List.of())).getSQLState());

        stub.enqueue(error(401, 10000, "Authentication error"));
        SQLException auth = assertThrows(SQLException.class, () -> client.execute("SELECT 1", List.of()));
        assertEquals("28000", auth.getSQLState());
        assertTrue(auth.getMessage().contains("D1"));

        stub.enqueue(error(404, 7404, "The database x could not be found"));
        assertEquals("08001", assertThrows(SQLException.class, () -> client.execute("SELECT 1", List.of())).getSQLState());

        stub.enqueue(new Response(502, "<html>bad gateway</html>"));
        stub.enqueue(new Response(502, "<html>bad gateway</html>"));
        stub.enqueue(new Response(502, "<html>bad gateway</html>"));
        stub.enqueue(new Response(502, "<html>bad gateway</html>"));
        SQLException gateway = assertThrows(SQLException.class, () -> client.execute("SELECT 1", List.of()));
        assertTrue(gateway.getMessage().contains("502"));
    }

    @Test
    void retries429ThenSucceeds() throws SQLException {
        stub.enqueue(error(429, 971, "rate limited"));
        stub.enqueue(ok(empty(1, 5)));
        List<D1Result> results = client.execute("INSERT INTO t VALUES (1)", List.of());
        assertEquals(1, results.get(0).getChanges());
        assertEquals(2, stub.requests().size());
    }

    @Test
    void retries5xxOnlyForReadOnlySql() throws SQLException {
        stub.enqueue(error(503, 0, "unavailable"));
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));
        assertEquals(1, client.execute("SELECT 1", List.of()).size());
        assertEquals(2, stub.requests().size());

        stub.enqueue(error(503, 0, "unavailable"));
        stub.enqueue(ok(empty(1, 1)));
        assertThrows(SQLException.class, () -> client.execute("DELETE FROM t", List.of()));
        assertEquals(3, stub.requests().size());
    }

    @Test
    void resolvesDatabaseNameToUuid() throws SQLException {
        Properties creds = stub.credentials();
        D1Client byName = new D1Client(D1ConnectionConfig.parse(stub.url("my-db"), creds), new long[]{1});
        stub.enqueue(new Response(200, "{\"success\":true,\"errors\":[],\"result\":["
                + "{\"name\":\"my-db-2\",\"uuid\":\"u2\"},{\"name\":\"my-db\",\"uuid\":\"" + DB_UUID + "\"}]}"));
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));
        byName.execute("SELECT 1", List.of());
        List<Request> reqs = stub.requests();
        assertEquals("GET", reqs.get(0).method);
        assertEquals("/client/v4/accounts/" + ACCOUNT + "/d1/database", reqs.get(0).path);
        assertTrue(reqs.get(0).query.contains("name=my-db"));
        assertTrue(reqs.get(1).path.endsWith("/d1/database/" + DB_UUID + "/raw"));
    }

    @Test
    void unknownDatabaseNameFails() throws SQLException {
        D1Client byName = new D1Client(D1ConnectionConfig.parse(stub.url("nope"), stub.credentials()), new long[]{1});
        stub.enqueue(new Response(200, "{\"success\":true,\"errors\":[],\"result\":[]}"));
        SQLException e = assertThrows(SQLException.class, byName::databaseId);
        assertEquals("08001", e.getSQLState());
    }
}
```

- [ ] **Step 3: Run — expect compilation failure**

Run: `./gradlew test --tests '*D1ClientTest'` → FAIL.

- [ ] **Step 4: Implement `D1Result.java`**

```java
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
```

- [ ] **Step 5: Implement `D1Errors.java`**

```java
package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;

/** Converts Cloudflare API error responses to SQLExceptions with meaningful SQLStates. */
final class D1Errors {
    private D1Errors() {
    }

    static SQLException toSqlException(int status, JsonNode body) {
        int code = 0;
        String message = null;
        JsonNode first = body.path("errors").path(0);
        if (!first.isMissingNode()) {
            code = first.path("code").asInt(0);
            message = first.path("message").asText(null);
        }
        if (message == null || message.isEmpty()) {
            message = "HTTP " + status;
        }
        if (status == 401 || status == 403 || code == 10000) {
            return new SQLException("Cloudflare rejected the API token (it needs the Account → D1 → Edit permission): "
                    + message, "28000", code);
        }
        if (status == 404 || code == 7404) {
            return new SQLException("D1 database not found: " + message, "08001", code);
        }
        return new SQLException(message, sqlState(message), code);
    }

    static String sqlState(String message) {
        if (message.contains("SQLITE_CONSTRAINT")) return "23000";
        if (message.contains("syntax error") || message.contains("no such table") || message.contains("no such column")) {
            return "42000";
        }
        return "HY000";
    }
}
```

- [ ] **Step 6: Implement `D1Client.java`**

```java
package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** HTTP client for the Cloudflare D1 REST API ({@code /raw} endpoint). */
public class D1Client {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long[] DEFAULT_BACKOFF_MILLIS = {200, 800, 1600};
    private static final int PAGE_SIZE = 100;

    /** One SQL statement plus its positional parameters. */
    public static final class Stmt {
        private final String sql;
        private final List<Object> params;

        public Stmt(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params == null ? Collections.emptyList() : params;
        }

        public String getSql() { return sql; }
        public List<Object> getParams() { return params; }
    }

    private final D1ConnectionConfig config;
    private final HttpClient http;
    private final long[] backoffMillis;
    private volatile String databaseId;

    public D1Client(D1ConnectionConfig config) {
        this(config, DEFAULT_BACKOFF_MILLIS);
    }

    D1Client(D1ConnectionConfig config, long[] backoffMillis) {
        this.config = config;
        this.backoffMillis = backoffMillis.clone();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
        this.databaseId = config.isDatabaseUuid() ? config.getDatabase() : null;
    }

    /** The database UUID, resolving a database name on first use. */
    public String databaseId() throws SQLException {
        String id = databaseId;
        if (id == null) {
            id = resolveDatabaseId(config.getDatabase());
            databaseId = id;
        }
        return id;
    }

    /** Runs SQL (possibly several ';'-separated statements); returns one result per statement. */
    public List<D1Result> execute(String sql, List<?> params) throws SQLException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("sql", sql);
        body.set("params", encodeParams(params));
        return parseResults(send(rawRequest(body), SqlText.isReadOnly(sql)));
    }

    /** Runs statements in one request; D1 applies a batch atomically. */
    public List<D1Result> batch(List<Stmt> statements) throws SQLException {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode batch = body.putArray("batch");
        boolean readOnly = true;
        for (Stmt s : statements) {
            ObjectNode item = batch.addObject();
            item.put("sql", s.getSql());
            item.set("params", encodeParams(s.getParams()));
            readOnly &= SqlText.isReadOnly(s.getSql());
        }
        return parseResults(send(rawRequest(body), readOnly));
    }

    String resolveDatabaseId(String name) throws SQLException {
        for (int page = 1; page <= 1000; page++) {
            URI uri = URI.create(accountUrl() + "/d1/database?name=" + encode(name)
                    + "&per_page=" + PAGE_SIZE + "&page=" + page);
            JsonNode result = send(baseRequest(uri).GET().build(), true).path("result");
            for (JsonNode db : result) {
                if (name.equals(db.path("name").asText())) {
                    return db.path("uuid").asText();
                }
            }
            if (result.size() < PAGE_SIZE) {
                break;
            }
        }
        throw new SQLException("D1 database not found: " + name, "08001");
    }

    private HttpRequest rawRequest(ObjectNode body) throws SQLException {
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot encode D1 request: " + e.getMessage(), "HY000", e);
        }
        URI uri = URI.create(accountUrl() + "/d1/database/" + encode(databaseId()) + "/raw");
        return baseRequest(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + config.getToken())
                .header("Accept", "application/json");
    }

    private String accountUrl() {
        return config.getApiBase() + "/accounts/" + encode(config.getAccountId());
    }

    private JsonNode send(HttpRequest request, boolean retryable) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            boolean canRetry = attempt < backoffMillis.length;
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (canRetry && (retryable || e instanceof ConnectException)) {
                    sleep(backoffMillis[attempt]);
                    continue;
                }
                throw new SQLException("Network error calling Cloudflare D1: " + e, "08006", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while calling Cloudflare D1", "08006", e);
            }
            int status = response.statusCode();
            if (canRetry && (status == 429 || (status >= 500 && retryable))) {
                sleep(backoffMillis[attempt]);
                continue;
            }
            JsonNode json = parseJson(response.body(), status);
            if (status >= 200 && status < 300 && json.path("success").asBoolean(false)) {
                return json;
            }
            throw D1Errors.toSqlException(status, json);
        }
    }

    private static void sleep(long millis) throws SQLException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting to retry", "08006", e);
        }
    }

    private static JsonNode parseJson(byte[] body, int status) {
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (IOException ignored) {
            // fall through to a synthetic error body
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        ObjectNode fallback = MAPPER.createObjectNode();
        fallback.put("success", false);
        fallback.putArray("errors").addObject()
                .put("code", 0)
                .put("message", "HTTP " + status + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(200, text.length()))));
        return fallback;
    }

    static List<D1Result> parseResults(JsonNode json) {
        List<D1Result> out = new ArrayList<>();
        for (JsonNode r : json.path("result")) {
            JsonNode results = r.path("results");
            List<String> columns = new ArrayList<>();
            for (JsonNode c : results.path("columns")) {
                columns.add(c.asText());
            }
            List<Object[]> rows = new ArrayList<>();
            for (JsonNode row : results.path("rows")) {
                Object[] values = new Object[columns.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = toJava(row.get(i));
                }
                rows.add(values);
            }
            JsonNode meta = r.path("meta");
            out.add(new D1Result(columns, rows, meta.path("changes").asLong(0), meta.path("last_row_id").asLong(0)));
        }
        return out;
    }

    static Object toJava(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isIntegralNumber()) return n.canConvertToLong() ? (Object) n.asLong() : (Object) n.asDouble();
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean() ? 1L : 0L;
        if (n.isArray()) {
            byte[] bytes = new byte[n.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) n.get(i).asInt();
            }
            return bytes;
        }
        return n.toString();
    }

    static ArrayNode encodeParams(List<?> params) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (params == null) return arr;
        for (Object p : params) {
            if (p == null) {
                arr.addNull();
            } else if (p instanceof Boolean) {
                arr.add(((Boolean) p) ? 1 : 0);
            } else if (p instanceof Byte || p instanceof Short || p instanceof Integer || p instanceof Long) {
                arr.add(((Number) p).longValue());
            } else if (p instanceof Float || p instanceof Double) {
                arr.add(((Number) p).doubleValue());
            } else if (p instanceof BigDecimal) {
                arr.add((BigDecimal) p);
            } else if (p instanceof BigInteger) {
                arr.add(new BigDecimal((BigInteger) p));
            } else if (p instanceof byte[]) {
                ArrayNode bytes = arr.addArray();
                for (byte b : (byte[]) p) bytes.add(b & 0xff);
            } else {
                arr.add(p.toString());
            }
        }
        return arr;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 7: Run — expect PASS**

Run: `./gradlew test --tests '*D1ClientTest'` → BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src && git commit -m "feat: D1 REST client with error mapping, retries and name resolution"
```

---
### Task 4: In-memory ResultSet and ResultSetMetaData

**Files:**
- Create: `src/main/java/com/dashnex/d1/jdbc/ReadOnlyResultSet.java`, `D1ResultSet.java`, `D1ResultSetMetaData.java`
- Test: `src/test/java/com/dashnex/d1/jdbc/D1ResultSetTest.java`

**Interfaces:**
- Consumes: `D1Result` (Task 3), `D1Types`, `D1Values` (Task 2).
- Produces:
  - `static D1ResultSet D1ResultSet.forQuery(Statement st, D1Result r, int maxRows, String table, Map<String, String> declaredTypes)` — `declaredTypes` keyed by lower-case column name (may be null); matching columns get `getTableName()==table` and the declared type.
  - `static D1ResultSet D1ResultSet.of(Statement st, String[] columns, int[] types, List<Object[]> rows)` — `types` may be null (inferred).
  - `void closeSilently()`.
  - `D1ResultSetMetaData(List<String> columns, int[] types, String[] typeNames, String[] tables)`; package-private `int type(int column)`.

- [ ] **Step 1: Write the failing test** `D1ResultSetTest.java`

```java
package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class D1ResultSetTest {
    private static D1Result sample() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, "alice", 1.5, new byte[]{1, 2}, 1L});
        rows.add(new Object[]{2L, "12", null, null, 0L});
        return new D1Result(Arrays.asList("id", "name", "score", "data", "flag"), rows, 0, 0);
    }

    @Test
    void iteratesAndReadsTypedValues() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, null, null);
        assertTrue(rs.isBeforeFirst());
        assertTrue(rs.next());
        assertEquals(1, rs.getRow());
        assertEquals(1L, rs.getLong("id"));
        assertEquals(1, rs.getInt(1));
        assertEquals("alice", rs.getString("NAME"));
        assertEquals(1.5, rs.getDouble(3));
        assertEquals(new BigDecimal("1.5"), rs.getBigDecimal(3));
        assertArrayEquals(new byte[]{1, 2}, rs.getBytes("data"));
        assertTrue(rs.getBoolean("flag"));
        assertFalse(rs.wasNull());
        assertTrue(rs.next());
        assertEquals(12, rs.getInt("name"));
        assertEquals(0.0, rs.getDouble("score"));
        assertTrue(rs.wasNull());
        assertNull(rs.getString("score"));
        assertNull(rs.getObject("data"));
        assertFalse(rs.next());
        assertTrue(rs.isAfterLast());
        assertThrows(SQLException.class, () -> rs.getString(1));
    }

    @Test
    void scrolls() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, null, null);
        assertTrue(rs.last());
        assertEquals(2, rs.getRow());
        assertTrue(rs.isLast());
        assertTrue(rs.previous());
        assertTrue(rs.isFirst());
        assertTrue(rs.absolute(-1));
        assertEquals(2L, rs.getLong(1));
        assertFalse(rs.relative(5));
        assertTrue(rs.isAfterLast());
        rs.beforeFirst();
        assertTrue(rs.first());
        assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
    }

    @Test
    void metadataInfersTypesWithoutTable() throws SQLException {
        ResultSetMetaData md = D1ResultSet.forQuery(null, sample(), 0, null, null).getMetaData();
        assertEquals(5, md.getColumnCount());
        assertEquals("name", md.getColumnLabel(2));
        assertEquals(Types.BIGINT, md.getColumnType(1));
        assertEquals(Types.VARCHAR, md.getColumnType(2));
        assertEquals(Types.DOUBLE, md.getColumnType(3));
        assertEquals(Types.BLOB, md.getColumnType(4));
        assertEquals("", md.getTableName(1));
        assertThrows(SQLException.class, () -> md.getColumnType(6));
    }

    @Test
    void metadataUsesDeclaredTypesAndTableName() throws SQLException {
        Map<String, String> declared = Map.of("id", "INTEGER", "name", "VARCHAR(20)", "score", "REAL", "data", "BLOB", "flag", "BOOLEAN");
        D1ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, "users", declared);
        ResultSetMetaData md = rs.getMetaData();
        assertEquals("users", md.getTableName(2));
        assertEquals("VARCHAR(20)", md.getColumnTypeName(2));
        assertEquals(Types.BOOLEAN, md.getColumnType(5));
        rs.next();
        assertEquals(Boolean.TRUE, rs.getObject("flag"));
    }

    @Test
    void columnsNotInTableHaveNoTableName() throws SQLException {
        D1Result r = new D1Result(List.of("id", "total"), List.<Object[]>of(new Object[]{1L, 3L}), 0, 0);
        ResultSetMetaData md = D1ResultSet.forQuery(null, r, 0, "users", Map.of("id", "INTEGER")).getMetaData();
        assertEquals("users", md.getTableName(1));
        assertEquals("", md.getTableName(2));
    }

    @Test
    void maxRowsTruncates() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 1, null, null);
        assertTrue(rs.next());
        assertFalse(rs.next());
    }

    @Test
    void ofBuildsMetadataResultSets() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{null, "t", 5});
        ResultSet rs = D1ResultSet.of(null, new String[]{"TABLE_CAT", "TABLE_NAME", "DATA_TYPE"}, null, rows);
        assertTrue(rs.next());
        assertNull(rs.getString("TABLE_CAT"));
        assertEquals("t", rs.getString("TABLE_NAME"));
        assertEquals(5, rs.getInt("DATA_TYPE"));
        assertEquals(Types.BIGINT, rs.getMetaData().getColumnType(3));
    }

    @Test
    void isReadOnlyAndClosable() throws SQLException {
        ResultSet rs = D1ResultSet.forQuery(null, sample(), 0, null, null);
        rs.next();
        assertThrows(SQLFeatureNotSupportedException.class, () -> rs.updateString(1, "x"));
        assertThrows(SQLException.class, () -> rs.findColumn("nope"));
        rs.close();
        assertTrue(rs.isClosed());
        assertThrows(SQLException.class, rs::next);
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

Run: `./gradlew test --tests '*D1ResultSetTest'` → FAIL.

- [ ] **Step 3: Implement `ReadOnlyResultSet.java`** (all mutating methods of `java.sql.ResultSet`)

```java
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
```

- [ ] **Step 4: Implement `D1ResultSetMetaData.java`**

```java
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
```

- [ ] **Step 5: Implement `D1ResultSet.java`**

```java
package com.dashnex.d1.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fully materialised, scroll-insensitive, read-only result set. */
public class D1ResultSet extends ReadOnlyResultSet {
    private final Statement statement;
    private final List<String> columns;
    private final List<Object[]> rows;
    private final D1ResultSetMetaData metaData;
    private int cursor = -1;
    private boolean closed;
    private boolean wasNull;
    private int fetchSize;

    D1ResultSet(Statement statement, List<String> columns, List<Object[]> rows, D1ResultSetMetaData metaData) {
        this.statement = statement;
        this.columns = columns;
        this.rows = rows;
        this.metaData = metaData;
    }

    static D1ResultSet forQuery(Statement statement, D1Result result, int maxRows, String table,
                                Map<String, String> declaredTypes) {
        List<String> columns = result.getColumns();
        List<Object[]> rows = result.getRows();
        if (maxRows > 0 && rows.size() > maxRows) {
            rows = rows.subList(0, maxRows);
        }
        int n = columns.size();
        int[] types = new int[n];
        String[] typeNames = new String[n];
        String[] tables = new String[n];
        for (int i = 0; i < n; i++) {
            String declared = declaredTypes == null ? null : declaredTypes.get(columns.get(i).toLowerCase(Locale.ROOT));
            if (declared != null) {
                types[i] = D1Types.fromDeclared(declared);
                typeNames[i] = declared.isBlank() ? D1Types.typeName(types[i]) : declared.trim().toUpperCase(Locale.ROOT);
                tables[i] = table;
            } else {
                types[i] = inferType(rows, i);
                typeNames[i] = D1Types.typeName(types[i]);
                tables[i] = "";
            }
        }
        return new D1ResultSet(statement, columns, rows, new D1ResultSetMetaData(columns, types, typeNames, tables));
    }

    static D1ResultSet of(Statement statement, String[] columnNames, int[] types, List<Object[]> rows) {
        List<String> columns = Arrays.asList(columnNames);
        int n = columnNames.length;
        int[] t = new int[n];
        String[] typeNames = new String[n];
        String[] tables = new String[n];
        for (int i = 0; i < n; i++) {
            t[i] = types != null ? types[i] : inferType(rows, i);
            typeNames[i] = D1Types.typeName(t[i]);
            tables[i] = "";
        }
        return new D1ResultSet(statement, columns, rows, new D1ResultSetMetaData(columns, t, typeNames, tables));
    }

    private static int inferType(List<Object[]> rows, int column) {
        for (Object[] row : rows) {
            if (row[column] != null) return D1Types.fromValue(row[column]);
        }
        return Types.VARCHAR;
    }

    void closeSilently() {
        closed = true;
    }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed", "24000");
    }

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("No current row", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("Column index out of range: " + columnIndex, "07009");
        }
        Object v = rows.get(cursor)[columnIndex - 1];
        wasNull = v == null;
        return v;
    }

    // ---- navigation ----

    @Override public boolean next() throws SQLException {
        checkOpen();
        if (cursor < rows.size()) cursor++;
        return cursor < rows.size();
    }

    @Override public boolean previous() throws SQLException {
        checkOpen();
        if (cursor >= 0) cursor--;
        return cursor >= 0;
    }

    @Override public boolean isBeforeFirst() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor < 0; }
    @Override public boolean isAfterLast() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor >= rows.size(); }
    @Override public boolean isFirst() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor == 0; }
    @Override public boolean isLast() throws SQLException { checkOpen(); return !rows.isEmpty() && cursor == rows.size() - 1; }
    @Override public void beforeFirst() throws SQLException { checkOpen(); cursor = -1; }
    @Override public void afterLast() throws SQLException { checkOpen(); cursor = rows.size(); }

    @Override public boolean first() throws SQLException {
        checkOpen();
        if (rows.isEmpty()) return false;
        cursor = 0;
        return true;
    }

    @Override public boolean last() throws SQLException {
        checkOpen();
        if (rows.isEmpty()) return false;
        cursor = rows.size() - 1;
        return true;
    }

    @Override public int getRow() throws SQLException {
        checkOpen();
        return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0;
    }

    @Override public boolean absolute(int row) throws SQLException {
        checkOpen();
        if (row > 0) cursor = Math.min(row - 1, rows.size());
        else if (row < 0) cursor = Math.max(rows.size() + row, -1);
        else cursor = -1;
        return cursor >= 0 && cursor < rows.size();
    }

    @Override public boolean relative(int n) throws SQLException {
        checkOpen();
        cursor = Math.max(-1, Math.min(rows.size(), cursor + n));
        return cursor >= 0 && cursor < rows.size();
    }

    // ---- getters by index ----

    @Override public boolean wasNull() { return wasNull; }
    @Override public String getString(int i) throws SQLException { return D1Values.toStr(value(i)); }
    @Override public boolean getBoolean(int i) throws SQLException { return D1Values.toBoolean(value(i)); }
    @Override public byte getByte(int i) throws SQLException { return (byte) D1Values.toLong(value(i)); }
    @Override public short getShort(int i) throws SQLException { return (short) D1Values.toLong(value(i)); }
    @Override public int getInt(int i) throws SQLException { return (int) D1Values.toLong(value(i)); }
    @Override public long getLong(int i) throws SQLException { return D1Values.toLong(value(i)); }
    @Override public float getFloat(int i) throws SQLException { return (float) D1Values.toDouble(value(i)); }
    @Override public double getDouble(int i) throws SQLException { return D1Values.toDouble(value(i)); }
    @Override public BigDecimal getBigDecimal(int i) throws SQLException { return D1Values.toBigDecimal(value(i)); }

    @Override @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int i, int scale) throws SQLException {
        BigDecimal d = getBigDecimal(i);
        return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override public byte[] getBytes(int i) throws SQLException { return D1Values.toBytes(value(i)); }
    @Override public Date getDate(int i) throws SQLException { return D1Values.toDate(value(i)); }
    @Override public Time getTime(int i) throws SQLException { return D1Values.toTime(value(i)); }
    @Override public Timestamp getTimestamp(int i) throws SQLException { return D1Values.toTimestamp(value(i)); }
    @Override public Date getDate(int i, Calendar cal) throws SQLException { return getDate(i); }
    @Override public Time getTime(int i, Calendar cal) throws SQLException { return getTime(i); }
    @Override public Timestamp getTimestamp(int i, Calendar cal) throws SQLException { return getTimestamp(i); }

    @Override public InputStream getAsciiStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override @SuppressWarnings("deprecation")
    public InputStream getUnicodeStream(int i) throws SQLException {
        throw new SQLFeatureNotSupportedException("getUnicodeStream is deprecated");
    }

    @Override public InputStream getBinaryStream(int i) throws SQLException {
        byte[] b = getBytes(i);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    @Override public Reader getCharacterStream(int i) throws SQLException {
        String s = getString(i);
        return s == null ? null : new StringReader(s);
    }

    @Override public Reader getNCharacterStream(int i) throws SQLException { return getCharacterStream(i); }
    @Override public String getNString(int i) throws SQLException { return getString(i); }

    @Override public Object getObject(int i) throws SQLException {
        Object v = value(i);
        if (v != null && metaData.type(i) == Types.BOOLEAN) return D1Values.toBoolean(v);
        return v;
    }

    @Override public Object getObject(int i, Map<String, Class<?>> map) throws SQLException { return getObject(i); }

    @Override public <T> T getObject(int i, Class<T> type) throws SQLException {
        Object v = value(i);
        if (v == null) return null;
        if (type == String.class) return type.cast(D1Values.toStr(v));
        if (type == Long.class) return type.cast(D1Values.toLong(v));
        if (type == Integer.class) return type.cast((int) D1Values.toLong(v));
        if (type == Short.class) return type.cast((short) D1Values.toLong(v));
        if (type == Byte.class) return type.cast((byte) D1Values.toLong(v));
        if (type == Double.class) return type.cast(D1Values.toDouble(v));
        if (type == Float.class) return type.cast((float) D1Values.toDouble(v));
        if (type == Boolean.class) return type.cast(D1Values.toBoolean(v));
        if (type == BigDecimal.class) return type.cast(D1Values.toBigDecimal(v));
        if (type == byte[].class) return type.cast(D1Values.toBytes(v));
        if (type == Timestamp.class) return type.cast(D1Values.toTimestamp(v));
        if (type == Date.class) return type.cast(D1Values.toDate(v));
        if (type == Time.class) return type.cast(D1Values.toTime(v));
        if (type == LocalDateTime.class) return type.cast(D1Values.toTimestamp(v).toLocalDateTime());
        if (type == LocalDate.class) return type.cast(D1Values.toDate(v).toLocalDate());
        if (type == Object.class) return type.cast(getObject(i));
        if (type.isInstance(v)) return type.cast(v);
        throw new SQLException("Cannot convert column " + i + " to " + type.getName(), "22018");
    }

    @Override public URL getURL(int i) throws SQLException {
        String s = getString(i);
        try {
            return s == null ? null : new URL(s);
        } catch (MalformedURLException e) {
            throw new SQLException("Not a URL: " + s, "22018", e);
        }
    }

    @Override public Ref getRef(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getRef"); }
    @Override public Blob getBlob(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getBlob; use getBytes"); }
    @Override public Clob getClob(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getClob; use getString"); }
    @Override public NClob getNClob(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getNClob; use getString"); }
    @Override public Array getArray(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getArray"); }
    @Override public RowId getRowId(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getRowId"); }
    @Override public SQLXML getSQLXML(int i) throws SQLException { throw new SQLFeatureNotSupportedException("getSQLXML"); }

    // ---- getters by label ----

    @Override public int findColumn(String label) throws SQLException {
        checkOpen();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(label)) return i + 1;
        }
        throw new SQLException("Column not found: " + label, "42S22");
    }

    @Override public String getString(String c) throws SQLException { return getString(findColumn(c)); }
    @Override public boolean getBoolean(String c) throws SQLException { return getBoolean(findColumn(c)); }
    @Override public byte getByte(String c) throws SQLException { return getByte(findColumn(c)); }
    @Override public short getShort(String c) throws SQLException { return getShort(findColumn(c)); }
    @Override public int getInt(String c) throws SQLException { return getInt(findColumn(c)); }
    @Override public long getLong(String c) throws SQLException { return getLong(findColumn(c)); }
    @Override public float getFloat(String c) throws SQLException { return getFloat(findColumn(c)); }
    @Override public double getDouble(String c) throws SQLException { return getDouble(findColumn(c)); }
    @Override @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(String c, int scale) throws SQLException { return getBigDecimal(findColumn(c), scale); }
    @Override public BigDecimal getBigDecimal(String c) throws SQLException { return getBigDecimal(findColumn(c)); }
    @Override public byte[] getBytes(String c) throws SQLException { return getBytes(findColumn(c)); }
    @Override public Date getDate(String c) throws SQLException { return getDate(findColumn(c)); }
    @Override public Time getTime(String c) throws SQLException { return getTime(findColumn(c)); }
    @Override public Timestamp getTimestamp(String c) throws SQLException { return getTimestamp(findColumn(c)); }
    @Override public Date getDate(String c, Calendar cal) throws SQLException { return getDate(findColumn(c)); }
    @Override public Time getTime(String c, Calendar cal) throws SQLException { return getTime(findColumn(c)); }
    @Override public Timestamp getTimestamp(String c, Calendar cal) throws SQLException { return getTimestamp(findColumn(c)); }
    @Override public InputStream getAsciiStream(String c) throws SQLException { return getAsciiStream(findColumn(c)); }
    @Override @SuppressWarnings("deprecation")
    public InputStream getUnicodeStream(String c) throws SQLException { return getUnicodeStream(findColumn(c)); }
    @Override public InputStream getBinaryStream(String c) throws SQLException { return getBinaryStream(findColumn(c)); }
    @Override public Reader getCharacterStream(String c) throws SQLException { return getCharacterStream(findColumn(c)); }
    @Override public Reader getNCharacterStream(String c) throws SQLException { return getNCharacterStream(findColumn(c)); }
    @Override public String getNString(String c) throws SQLException { return getNString(findColumn(c)); }
    @Override public Object getObject(String c) throws SQLException { return getObject(findColumn(c)); }
    @Override public Object getObject(String c, Map<String, Class<?>> map) throws SQLException { return getObject(findColumn(c)); }
    @Override public <T> T getObject(String c, Class<T> type) throws SQLException { return getObject(findColumn(c), type); }
    @Override public URL getURL(String c) throws SQLException { return getURL(findColumn(c)); }
    @Override public Ref getRef(String c) throws SQLException { return getRef(findColumn(c)); }
    @Override public Blob getBlob(String c) throws SQLException { return getBlob(findColumn(c)); }
    @Override public Clob getClob(String c) throws SQLException { return getClob(findColumn(c)); }
    @Override public NClob getNClob(String c) throws SQLException { return getNClob(findColumn(c)); }
    @Override public Array getArray(String c) throws SQLException { return getArray(findColumn(c)); }
    @Override public RowId getRowId(String c) throws SQLException { return getRowId(findColumn(c)); }
    @Override public SQLXML getSQLXML(String c) throws SQLException { return getSQLXML(findColumn(c)); }

    // ---- misc ----

    @Override public ResultSetMetaData getMetaData() throws SQLException { checkOpen(); return metaData; }
    @Override public Statement getStatement() { return statement; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() { }
    @Override public String getCursorName() throws SQLException { throw new SQLFeatureNotSupportedException("getCursorName"); }
    @Override public void setFetchDirection(int direction) { }
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) { fetchSize = rows; }
    @Override public int getFetchSize() { return fetchSize; }
    @Override public int getType() { return TYPE_SCROLL_INSENSITIVE; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
    @Override public int getHoldability() { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
```

- [ ] **Step 6: Run — expect PASS**

Run: `./gradlew test --tests '*D1ResultSetTest'` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src && git commit -m "feat: in-memory read-only result set and metadata"
```

---
### Task 5: Driver, Connection, Statement, PreparedStatement

**Files:**
- Create: `src/main/java/com/dashnex/d1/jdbc/D1Driver.java`, `D1Connection.java`, `D1Statement.java`, `D1PreparedStatement.java`
- Create: `src/main/resources/META-INF/services/java.sql.Driver`
- Test: `src/test/java/com/dashnex/d1/jdbc/D1StatementTest.java`

**Interfaces:**
- Consumes: `D1ConnectionConfig`, `D1Client`, `D1Client.Stmt`, `D1Result`, `D1ResultSet.forQuery/of/closeSilently`, `SqlText.*`, `D1Values.toParam/readBytes/readString`.
- Produces:
  - `D1Driver` (registers itself with `DriverManager`; `connect` validates with `SELECT 1`).
  - `D1Connection(D1ConnectionConfig, D1Client)`; package-private `D1Client client()`, `D1ConnectionConfig config()`, `Map<String,String> declaredTypes(String table)`, `void schemaChanged()`.
  - `D1Statement(D1Connection)` with protected `boolean executeInternal(String sql, List<Object> params)` and `void addToBatch(D1Client.Stmt)`.
  - `D1PreparedStatement(D1Connection, String sql)`.
  - `D1Connection.getMetaData()` throws `SQLFeatureNotSupportedException` in this task (so it compiles without `D1DatabaseMetaData`); Task 6 changes it to `return new D1DatabaseMetaData(this);`.

- [ ] **Step 1: Write the failing test** `D1StatementTest.java`

```java
package com.dashnex.d1.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import static com.dashnex.d1.jdbc.StubD1Server.*;
import static org.junit.jupiter.api.Assertions.*;

class D1StatementTest {
    private StubD1Server stub;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubD1Server();
        stub.enqueue(ok(result(new String[]{"1"}, new Object[][]{{1}})));   // connect() probe
        conn = DriverManager.getConnection(stub.url(), stub.credentials());
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
        stub.close();
    }

    @Test
    void driverIsDiscoveredAndValidatesOnConnect() throws SQLException {
        assertTrue(conn instanceof D1Connection);
        assertEquals("SELECT 1", stub.requests().get(0).sql());
        assertNull(new D1Driver().connect("jdbc:sqlite:x", null));
    }

    @Test
    void connectFailsWithBadToken() {
        stub.enqueue(error(401, 10000, "Authentication error"));
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(stub.url(), stub.credentials()));
        assertEquals("28000", e.getSQLState());
    }

    @Test
    void executeQueryReturnsRowsAndTableMetadata() throws SQLException {
        stub.handler(r -> r.sql().contains("pragma_table_info")
                ? ok(result(new String[]{"name", "type"}, new Object[][]{{"id", "INTEGER"}, {"name", "TEXT"}}))
                : ok(result(new String[]{"id", "name"}, new Object[][]{{1, "a"}, {2, "b"}})));
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM users")) {
            assertEquals("users", rs.getMetaData().getTableName(1));
            assertEquals("INTEGER", rs.getMetaData().getColumnTypeName(1));
            assertTrue(rs.next());
            assertEquals("a", rs.getString("name"));
            assertTrue(rs.next());
            assertFalse(rs.next());
        }
    }

    @Test
    void executeUpdateReturnsChangesAndGeneratedKey() throws SQLException {
        stub.enqueue(ok(empty(1, 77)));
        try (Statement st = conn.createStatement()) {
            assertEquals(1, st.executeUpdate("INSERT INTO t(a) VALUES ('x')", Statement.RETURN_GENERATED_KEYS));
            ResultSet keys = st.getGeneratedKeys();
            assertTrue(keys.next());
            assertEquals(77L, keys.getLong(1));
        }
    }

    @Test
    void updateDoesNotReportStaleGeneratedKey() throws SQLException {
        stub.enqueue(ok(empty(3, 77)));
        try (Statement st = conn.createStatement()) {
            assertEquals(3, st.executeUpdate("UPDATE t SET a = 1"));
            assertFalse(st.getGeneratedKeys().next());
        }
    }

    @Test
    void multipleResultsAreExposedInOrder() throws SQLException {
        stub.enqueue(ok(empty(2, 0), result(new String[]{"c"}, new Object[][]{{5}})));
        try (Statement st = conn.createStatement()) {
            assertFalse(st.execute("DELETE FROM t; SELECT count(*) c FROM t"));
            assertEquals(2, st.getUpdateCount());
            assertTrue(st.getMoreResults());
            ResultSet rs = st.getResultSet();
            rs.next();
            assertEquals(5, rs.getInt(1));
            assertFalse(st.getMoreResults());
            assertEquals(-1, st.getUpdateCount());
        }
    }

    @Test
    void transactionControlIsIgnoredWithWarning() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertFalse(st.execute("BEGIN TRANSACTION"));
            assertNotNull(st.getWarnings());
            assertEquals(0, st.getUpdateCount());
        }
        assertEquals(1, stub.requests().size());   // only the connect probe
        conn.setAutoCommit(false);
        assertNotNull(conn.getWarnings());
        conn.commit();
        conn.rollback();
    }

    @Test
    void sqliteVersionIsRewritten() throws SQLException {
        stub.enqueue(ok(result(new String[]{"v"}, new Object[][]{{"3.45.0"}})));
        try (Statement st = conn.createStatement()) {
            st.executeQuery("SELECT sqlite_version() v");
        }
        assertEquals("SELECT '3.45.0' v", stub.lastRequest().sql());
    }

    @Test
    void preparedStatementBindsParameters() throws SQLException {
        stub.enqueue(ok(empty(1, 9)));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t(a, b, c, d, e, f) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "x");
            ps.setInt(2, 5);
            ps.setBoolean(3, true);
            ps.setNull(4, java.sql.Types.VARCHAR);
            ps.setBytes(5, new byte[]{7});
            ps.setTimestamp(6, Timestamp.valueOf("2024-01-02 03:04:05"));
            assertEquals(1, ps.executeUpdate());
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            assertEquals(9, keys.getLong(1));
        }
        JsonNode p = stub.lastRequest().params();
        assertEquals("x", p.get(0).asText());
        assertEquals(5, p.get(1).asInt());
        assertEquals(1, p.get(2).asInt());
        assertTrue(p.get(3).isNull());
        assertEquals(7, p.get(4).get(0).asInt());
        assertEquals("2024-01-02 03:04:05", p.get(5).asText());
    }

    @Test
    void missingParameterFails() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM t WHERE a = ? AND b = ?")) {
            ps.setInt(1, 1);
            SQLException e = assertThrows(SQLException.class, ps::executeQuery);
            assertEquals("07001", e.getSQLState());
        }
    }

    @Test
    void preparedBatchIsSentInOneRequest() throws SQLException {
        stub.enqueue(ok(empty(1, 1), empty(1, 2)));
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t(a) VALUES (?)")) {
            ps.setString(1, "a");
            ps.addBatch();
            ps.setString(1, "b");
            ps.addBatch();
            assertArrayEquals(new int[]{1, 1}, ps.executeBatch());
        }
        JsonNode batch = stub.lastRequest().body.get("batch");
        assertEquals(2, batch.size());
        assertEquals("b", batch.get(1).get("params").get(0).asText());
    }

    @Test
    void failedBatchThrowsBatchUpdateException() throws SQLException {
        stub.enqueue(error(400, 7500, "UNIQUE constraint failed: t.a: SQLITE_CONSTRAINT"));
        try (Statement st = conn.createStatement()) {
            st.addBatch("INSERT INTO t(a) VALUES (1)");
            st.addBatch("INSERT INTO t(a) VALUES (1)");
            BatchUpdateException e = assertThrows(BatchUpdateException.class, st::executeBatch);
            assertEquals("23000", e.getSQLState());
        }
    }

    @Test
    void ddlClearsDeclaredTypeCache() throws SQLException {
        D1Connection d1 = conn.unwrap(D1Connection.class);
        stub.handler(r -> r.sql().contains("pragma_table_info")
                ? ok(result(new String[]{"name", "type"}, new Object[][]{{"id", "INTEGER"}}))
                : ok(empty(0, 0)));
        assertEquals("INTEGER", d1.declaredTypes("t").get("id"));
        int before = stub.requests().size();
        d1.declaredTypes("t");
        assertEquals(before, stub.requests().size());
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE t ADD COLUMN x TEXT");
        }
        d1.declaredTypes("t");
        assertEquals(before + 2, stub.requests().size());
    }

    @Test
    void closedConnectionRejectsStatements() throws SQLException {
        conn.close();
        assertTrue(conn.isClosed());
        assertThrows(SQLException.class, conn::createStatement);
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

Run: `./gradlew test --tests '*D1StatementTest'` → FAIL.

- [ ] **Step 3: Implement `D1Connection.java`**

```java
package com.dashnex.d1.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** A connection to one D1 database. Stateless over HTTP; always auto-commit. */
public class D1Connection implements Connection {
    private final D1ConnectionConfig config;
    private final D1Client client;
    private final Map<String, Map<String, String>> declaredTypesCache = new ConcurrentHashMap<>();
    private final Properties clientInfo = new Properties();
    private volatile boolean closed;
    private boolean autoCommit = true;
    private boolean readOnly;
    private SQLWarning warnings;
    private int networkTimeoutMillis;

    public D1Connection(D1ConnectionConfig config, D1Client client) {
        this.config = config;
        this.client = client;
    }

    D1Client client() { return client; }
    D1ConnectionConfig config() { return config; }

    /** Declared column types of a table keyed by lower-case column name; empty if unknown. Cached. */
    Map<String, String> declaredTypes(String table) {
        return declaredTypesCache.computeIfAbsent(table.toLowerCase(Locale.ROOT), key -> {
            try {
                List<D1Result> results = client.execute("SELECT name, type FROM pragma_table_info(?)", List.of(table));
                Map<String, String> types = new HashMap<>();
                if (!results.isEmpty()) {
                    for (Object[] row : results.get(results.size() - 1).getRows()) {
                        types.put(String.valueOf(row[0]).toLowerCase(Locale.ROOT), row[1] == null ? "" : String.valueOf(row[1]));
                    }
                }
                return types;
            } catch (SQLException e) {
                return Map.of();
            }
        });
    }

    void schemaChanged() {
        declaredTypesCache.clear();
    }

    void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Connection is closed", "08003");
    }

    private void addWarning(SQLWarning w) {
        if (warnings == null) warnings = w;
        else warnings.setNextWarning(w);
    }

    private static SQLFeatureNotSupportedException unsupported(String what) {
        return new SQLFeatureNotSupportedException(what + " is not supported by Cloudflare D1");
    }

    @Override public Statement createStatement() throws SQLException { checkOpen(); return new D1Statement(this); }
    @Override public Statement createStatement(int t, int c) throws SQLException { return createStatement(); }
    @Override public Statement createStatement(int t, int c, int h) throws SQLException { return createStatement(); }
    @Override public PreparedStatement prepareStatement(String sql) throws SQLException { checkOpen(); return new D1PreparedStatement(this, sql); }
    @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int t, int c) throws SQLException { return prepareStatement(sql); }
    @Override public PreparedStatement prepareStatement(String sql, int t, int c, int h) throws SQLException { return prepareStatement(sql); }
    @Override public CallableStatement prepareCall(String sql) throws SQLException { throw unsupported("Stored procedures"); }
    @Override public CallableStatement prepareCall(String sql, int t, int c) throws SQLException { throw unsupported("Stored procedures"); }
    @Override public CallableStatement prepareCall(String sql, int t, int c, int h) throws SQLException { throw unsupported("Stored procedures"); }
    @Override public String nativeSQL(String sql) { return sql; }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (!autoCommit && this.autoCommit) {
            addWarning(new SQLWarning("Cloudflare D1 has no interactive transactions: every statement is committed "
                    + "immediately; commit() and rollback() do nothing"));
        }
        this.autoCommit = autoCommit;
    }

    @Override public boolean getAutoCommit() throws SQLException { checkOpen(); return autoCommit; }
    @Override public void commit() throws SQLException { checkOpen(); }
    @Override public void rollback() throws SQLException { checkOpen(); }
    @Override public void rollback(Savepoint savepoint) throws SQLException { throw unsupported("Savepoints"); }
    @Override public Savepoint setSavepoint() throws SQLException { throw unsupported("Savepoints"); }
    @Override public Savepoint setSavepoint(String name) throws SQLException { throw unsupported("Savepoints"); }
    @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { throw unsupported("Savepoints"); }
    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }
    @Override public DatabaseMetaData getMetaData() throws SQLException { checkOpen(); throw unsupported("DatabaseMetaData (added in Task 6)"); }
    @Override public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    @Override public boolean isReadOnly() { return readOnly; }
    @Override public void setCatalog(String catalog) { }
    @Override public String getCatalog() { return null; }
    @Override public void setTransactionIsolation(int level) { }
    @Override public int getTransactionIsolation() { return TRANSACTION_NONE; }
    @Override public SQLWarning getWarnings() { return warnings; }
    @Override public void clearWarnings() { warnings = null; }
    @Override public Map<String, Class<?>> getTypeMap() { return new HashMap<>(); }
    @Override public void setTypeMap(Map<String, Class<?>> map) { }
    @Override public void setHoldability(int holdability) { }
    @Override public int getHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public Clob createClob() throws SQLException { throw unsupported("Clob"); }
    @Override public Blob createBlob() throws SQLException { throw unsupported("Blob"); }
    @Override public NClob createNClob() throws SQLException { throw unsupported("NClob"); }
    @Override public SQLXML createSQLXML() throws SQLException { throw unsupported("SQLXML"); }

    @Override
    public boolean isValid(int timeout) {
        if (closed) return false;
        try {
            client.execute("SELECT 1", List.of());
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (value == null) clientInfo.remove(name);
        else clientInfo.setProperty(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        clientInfo.clear();
        if (properties != null) clientInfo.putAll(properties);
    }

    @Override public String getClientInfo(String name) { return clientInfo.getProperty(name); }

    @Override
    public Properties getClientInfo() {
        Properties copy = new Properties();
        copy.putAll(clientInfo);
        return copy;
    }

    @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { throw unsupported("Arrays"); }
    @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException { throw unsupported("Structs"); }
    @Override public void setSchema(String schema) { }
    @Override public String getSchema() { return null; }
    @Override public void abort(Executor executor) { closed = true; }
    @Override public void setNetworkTimeout(Executor executor, int milliseconds) { networkTimeoutMillis = milliseconds; }
    @Override public int getNetworkTimeout() { return networkTimeoutMillis; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
```

- [ ] **Step 4: Implement `D1Statement.java`**

```java
package com.dashnex.d1.jdbc;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Statement backed by one D1 HTTP call per execution (or per batch). */
public class D1Statement implements Statement {
    protected final D1Connection connection;
    private final List<D1Client.Stmt> batch = new ArrayList<>();
    private final Deque<D1Result> pendingResults = new ArrayDeque<>();
    private D1ResultSet currentResultSet;
    private int updateCount = -1;
    private long generatedKey = -1;
    private String lastSql;
    private boolean closed;
    private int maxRows;
    private int queryTimeout;
    private int fetchSize;
    private boolean poolable;
    private boolean closeOnCompletion;
    private SQLWarning warnings;

    D1Statement(D1Connection connection) {
        this.connection = connection;
    }

    protected void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Statement is closed", "HY010");
        connection.checkOpen();
    }

    private void addWarning(SQLWarning w) {
        if (warnings == null) warnings = w;
        else warnings.setNextWarning(w);
    }

    private void closeCurrentResultSet() {
        if (currentResultSet != null) {
            currentResultSet.closeSilently();
            currentResultSet = null;
        }
    }

    protected boolean executeInternal(String sql, List<Object> params) throws SQLException {
        checkOpen();
        closeCurrentResultSet();
        pendingResults.clear();
        generatedKey = -1;
        lastSql = sql;
        if (SqlText.isTransactionControl(sql)) {
            addWarning(new SQLWarning("Ignored '" + sql.trim() + "': Cloudflare D1 has no interactive transactions"));
            updateCount = 0;
            return false;
        }
        List<D1Result> results = connection.client().execute(SqlText.rewrite(sql), params);
        if (SqlText.isDdl(sql)) {
            connection.schemaChanged();
        }
        if (SqlText.isInsert(sql) && !results.isEmpty()) {
            D1Result last = results.get(results.size() - 1);
            if (last.getChanges() > 0) generatedKey = last.getLastRowId();
        }
        pendingResults.addAll(results);
        return nextResult();
    }

    private boolean nextResult() {
        D1Result r = pendingResults.poll();
        if (r == null) {
            currentResultSet = null;
            updateCount = -1;
            return false;
        }
        if (r.hasColumns()) {
            String table = SqlText.singleTable(lastSql);
            Map<String, String> declared = table == null ? null : connection.declaredTypes(table);
            currentResultSet = D1ResultSet.forQuery(this, r, maxRows, table, declared);
            updateCount = -1;
            return true;
        }
        currentResultSet = null;
        updateCount = (int) Math.min(Integer.MAX_VALUE, r.getChanges());
        return false;
    }

    protected void addToBatch(D1Client.Stmt stmt) throws SQLException {
        checkOpen();
        batch.add(stmt);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        if (!executeInternal(sql, List.of())) {
            throw new SQLException("Statement did not return a result set", "02000");
        }
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        executeInternal(sql, List.of());
        return Math.max(updateCount, 0);
    }

    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { return executeUpdate(sql); }
    @Override public long executeLargeUpdate(String sql) throws SQLException { return executeUpdate(sql); }
    @Override public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException { return executeUpdate(sql); }
    @Override public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException { return executeUpdate(sql); }
    @Override public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql) throws SQLException { return executeInternal(sql, List.of()); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { return execute(sql); }
    @Override public ResultSet getResultSet() throws SQLException { checkOpen(); return currentResultSet; }
    @Override public int getUpdateCount() throws SQLException { checkOpen(); return updateCount; }
    @Override public long getLargeUpdateCount() throws SQLException { return getUpdateCount(); }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkOpen();
        closeCurrentResultSet();
        return nextResult();
    }

    @Override public boolean getMoreResults(int current) throws SQLException { return getMoreResults(); }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();
        if (generatedKey > 0) rows.add(new Object[]{generatedKey});
        return D1ResultSet.of(this, new String[]{"last_insert_rowid()"}, new int[]{Types.BIGINT}, rows);
    }

    @Override public void addBatch(String sql) throws SQLException { addToBatch(new D1Client.Stmt(sql, List.of())); }
    @Override public void clearBatch() { batch.clear(); }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        closeCurrentResultSet();
        List<D1Client.Stmt> toRun = new ArrayList<>(batch);
        batch.clear();
        int[] counts = new int[toRun.size()];
        List<D1Client.Stmt> sent = new ArrayList<>();
        List<Integer> sentIndex = new ArrayList<>();
        for (int i = 0; i < toRun.size(); i++) {
            D1Client.Stmt s = toRun.get(i);
            if (SqlText.isTransactionControl(s.getSql())) {
                counts[i] = SUCCESS_NO_INFO;
                continue;
            }
            sent.add(new D1Client.Stmt(SqlText.rewrite(s.getSql()), s.getParams()));
            sentIndex.add(i);
        }
        if (sent.isEmpty()) return counts;
        List<D1Result> results;
        try {
            results = connection.client().batch(sent);
        } catch (SQLException e) {
            throw new BatchUpdateException(e.getMessage(), e.getSQLState(), e.getErrorCode(), new int[0], e);
        }
        boolean aligned = results.size() == sent.size();
        generatedKey = -1;
        for (int j = 0; j < sent.size(); j++) {
            int i = sentIndex.get(j);
            if (!aligned) {
                counts[i] = SUCCESS_NO_INFO;
                continue;
            }
            D1Result r = results.get(j);
            counts[i] = (int) Math.min(Integer.MAX_VALUE, r.getChanges());
            if (SqlText.isInsert(sent.get(j).getSql()) && r.getChanges() > 0) generatedKey = r.getLastRowId();
        }
        for (D1Client.Stmt s : sent) {
            if (SqlText.isDdl(s.getSql())) {
                connection.schemaChanged();
                break;
            }
        }
        return counts;
    }

    @Override public long[] executeLargeBatch() throws SQLException {
        int[] counts = executeBatch();
        long[] out = new long[counts.length];
        for (int i = 0; i < counts.length; i++) out[i] = counts[i];
        return out;
    }

    @Override public void close() { closeCurrentResultSet(); closed = true; }
    @Override public boolean isClosed() { return closed; }
    @Override public int getMaxFieldSize() { return 0; }
    @Override public void setMaxFieldSize(int max) { }
    @Override public int getMaxRows() { return maxRows; }
    @Override public void setMaxRows(int max) { maxRows = Math.max(0, max); }
    @Override public void setEscapeProcessing(boolean enable) { }
    @Override public int getQueryTimeout() { return queryTimeout; }
    @Override public void setQueryTimeout(int seconds) { queryTimeout = seconds; }
    @Override public void cancel() { }
    @Override public SQLWarning getWarnings() { return warnings; }
    @Override public void clearWarnings() { warnings = null; }
    @Override public void setCursorName(String name) { }
    @Override public void setFetchDirection(int direction) { }
    @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) { fetchSize = rows; }
    @Override public int getFetchSize() { return fetchSize; }
    @Override public int getResultSetConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() { return ResultSet.TYPE_SCROLL_INSENSITIVE; }
    @Override public Connection getConnection() { return connection; }
    @Override public int getResultSetHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public void setPoolable(boolean poolable) { this.poolable = poolable; }
    @Override public boolean isPoolable() { return poolable; }
    @Override public void closeOnCompletion() { closeOnCompletion = true; }
    @Override public boolean isCloseOnCompletion() { return closeOnCompletion; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
```

- [ ] **Step 5: Implement `D1PreparedStatement.java`**

```java
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
```

- [ ] **Step 6: Implement `D1Driver.java` and the service file**

```java
package com.dashnex.d1.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/** JDBC entry point: {@code jdbc:d1://<database>} with User = account ID, Password = API token. */
public class D1Driver implements Driver {
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;

    static {
        try {
            DriverManager.registerDriver(new D1Driver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        D1ConnectionConfig config = D1ConnectionConfig.parse(url, info);
        D1Client client = new D1Client(config);
        client.execute("SELECT 1", List.of());   // resolves the database and validates the token
        return new D1Connection(config, client);
    }

    @Override
    public boolean acceptsURL(String url) {
        return D1ConnectionConfig.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Properties p = info == null ? new Properties() : info;
        DriverPropertyInfo user = new DriverPropertyInfo("user", p.getProperty("user"));
        user.required = true;
        user.description = "Cloudflare account ID";
        DriverPropertyInfo password = new DriverPropertyInfo("password", null);
        password.required = true;
        password.description = "Cloudflare API token with the Account → D1 → Edit permission";
        DriverPropertyInfo apiBase = new DriverPropertyInfo("apiBase",
                p.getProperty("apiBase", D1ConnectionConfig.DEFAULT_API_BASE));
        apiBase.description = "Cloudflare API base URL";
        DriverPropertyInfo timeout = new DriverPropertyInfo("timeoutSeconds",
                p.getProperty("timeoutSeconds", String.valueOf(D1ConnectionConfig.DEFAULT_TIMEOUT_SECONDS)));
        timeout.description = "HTTP timeout in seconds";
        return new DriverPropertyInfo[]{user, password, apiBase, timeout};
    }

    @Override public int getMajorVersion() { return MAJOR_VERSION; }
    @Override public int getMinorVersion() { return MINOR_VERSION; }
    @Override public boolean jdbcCompliant() { return false; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
    }
}
```

`src/main/resources/META-INF/services/java.sql.Driver`:

```
com.dashnex.d1.jdbc.D1Driver
```

- [ ] **Step 7: Run — expect PASS**

Run: `./gradlew test` → BUILD SUCCESSFUL (all unit tests so far).

- [ ] **Step 8: Commit**

```bash
git add src && git commit -m "feat: JDBC driver, connection, statement and prepared statement"
```

---
### Task 6: DatabaseMetaData (tables, columns, PKs, FKs, indexes, types)

**Files:**
- Create: `src/main/java/com/dashnex/d1/jdbc/D1DatabaseMetaData.java`
- Modify: `src/main/java/com/dashnex/d1/jdbc/D1Connection.java` (`getMetaData()`)
- Test: `src/test/java/com/dashnex/d1/jdbc/D1DatabaseMetaDataTest.java`

**Interfaces:**
- Consumes: `D1Connection.client()`, `D1Connection.config()`, `D1Connection.isReadOnly()`, `D1ResultSet.of(...)`, `D1Types`, `D1Values.toLong`, `SqlText.matchesPattern`, `SqlText.SQLITE_VERSION`.
- Produces: `D1DatabaseMetaData(D1Connection)`; public constants `COLUMNS_SQL`, `FOREIGN_KEYS_SQL`, `TABLES_SQL`, `INDEX_SQL` (package-private `static final String`, used by tests to route stub responses).

- [ ] **Step 1: Write the failing test** `D1DatabaseMetaDataTest.java`

```java
package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static com.dashnex.d1.jdbc.StubD1Server.*;
import static org.junit.jupiter.api.Assertions.*;

class D1DatabaseMetaDataTest {
    private StubD1Server stub;
    private Connection conn;
    private DatabaseMetaData md;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubD1Server();
        stub.handler(r -> {
            String sql = r.sql();
            if (sql.equals(D1DatabaseMetaData.TABLES_SQL)) {
                return ok(result(new String[]{"name", "type"}, new Object[][]{
                        {"orders", "table"}, {"order_summary", "view"}, {"users", "table"}}));
            }
            if (sql.equals(D1DatabaseMetaData.COLUMNS_SQL)) {
                return ok(result(new String[]{"tbl", "cid", "name", "type", "notnull", "dflt_value", "pk"}, new Object[][]{
                        {"order_summary", 0, "n", "", 0, null, 0},
                        {"orders", 0, "id", "INTEGER", 0, null, 1},
                        {"orders", 1, "user_id", "INTEGER", 1, null, 0},
                        {"orders", 2, "total", "DECIMAL(10,2)", 0, "0", 0},
                        {"users", 0, "id", "INTEGER", 0, null, 1},
                        {"users", 1, "email", "VARCHAR(255)", 1, null, 0},
                        {"users", 2, "created_at", "DATETIME", 0, "CURRENT_TIMESTAMP", 0}}));
            }
            if (sql.equals(D1DatabaseMetaData.FOREIGN_KEYS_SQL)) {
                return ok(result(new String[]{"name", "id", "seq", "table", "from", "to", "on_update", "on_delete"}, new Object[][]{
                        {"orders", 0, 0, "users", "user_id", null, "NO ACTION", "CASCADE"}}));
            }
            if (sql.equals(D1DatabaseMetaData.INDEX_SQL)) {
                return ok(result(new String[]{"name", "unique", "seqno", "name", "desc"}, new Object[][]{
                        {"orders_user", 0, 0, "user_id", 0},
                        {"sqlite_autoindex_orders_1", 1, 0, "total", 1}}));
            }
            return ok(result(new String[]{"1"}, new Object[][]{{1}}));
        });
        conn = DriverManager.getConnection(stub.url(), stub.credentials());
        md = conn.getMetaData();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
        stub.close();
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        List<String> out = new ArrayList<>();
        while (rs.next()) out.add(rs.getString(column));
        return out;
    }

    @Test
    void identifiesAsSqlite() throws SQLException {
        assertEquals("SQLite", md.getDatabaseProductName());
        assertEquals("3.45.0", md.getDatabaseProductVersion());
        assertEquals("Cloudflare D1 JDBC", md.getDriverName());
        assertEquals("\"", md.getIdentifierQuoteString());
        assertFalse(md.supportsTransactions());
        assertEquals(Connection.TRANSACTION_NONE, md.getDefaultTransactionIsolation());
        assertTrue(md.supportsAlterTableWithAddColumn());
        assertTrue(md.supportsAlterTableWithDropColumn());
        assertTrue(md.supportsGetGeneratedKeys());
        assertSame(conn, md.getConnection());
    }

    @Test
    void metadataSqlExcludesInternalTables() {
        for (String sql : new String[]{D1DatabaseMetaData.TABLES_SQL, D1DatabaseMetaData.COLUMNS_SQL, D1DatabaseMetaData.FOREIGN_KEYS_SQL}) {
            assertTrue(sql.contains("NOT LIKE 'sqlite\\_%' ESCAPE '\\'"), sql);
            assertTrue(sql.contains("NOT LIKE '\\_cf\\_%' ESCAPE '\\'"), sql);
        }
    }

    @Test
    void listsTablesAndViews() throws SQLException {
        assertEquals(List.of("orders", "users", "order_summary"), strings(md.getTables(null, null, "%", null), "TABLE_NAME"));
        assertEquals(List.of("order_summary"), strings(md.getTables(null, null, null, new String[]{"VIEW"}), "TABLE_NAME"));
        ResultSet rs = md.getTables(null, null, "us%", new String[]{"TABLE"});
        assertTrue(rs.next());
        assertEquals("users", rs.getString("TABLE_NAME"));
        assertEquals("TABLE", rs.getString("TABLE_TYPE"));
        assertNull(rs.getString("TABLE_SCHEM"));
        assertFalse(rs.next());
        assertEquals(List.of("TABLE", "VIEW"), strings(md.getTableTypes(), "TABLE_TYPE"));
        assertFalse(md.getSchemas().next());
        assertFalse(md.getCatalogs().next());
    }

    @Test
    void describesColumns() throws SQLException {
        ResultSet rs = md.getColumns(null, null, "users", "%");
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals(Types.BIGINT, rs.getInt("DATA_TYPE"));
        assertEquals("INTEGER", rs.getString("TYPE_NAME"));
        assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));
        assertEquals("NO", rs.getString("IS_NULLABLE"));
        assertEquals(1, rs.getInt("ORDINAL_POSITION"));
        assertTrue(rs.next());
        assertEquals("email", rs.getString("COLUMN_NAME"));
        assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
        assertEquals("VARCHAR(255)", rs.getString("TYPE_NAME"));
        assertEquals(255, rs.getInt("COLUMN_SIZE"));
        assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
        assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));
        assertTrue(rs.next());
        assertEquals("CURRENT_TIMESTAMP", rs.getString("COLUMN_DEF"));
        assertEquals("YES", rs.getString("IS_NULLABLE"));
        assertFalse(rs.next());

        ResultSet total = md.getColumns(null, null, "orders", "total");
        assertTrue(total.next());
        assertEquals(Types.NUMERIC, total.getInt("DATA_TYPE"));
        assertEquals(10, total.getInt("COLUMN_SIZE"));
        assertEquals(2, total.getInt("DECIMAL_DIGITS"));

        ResultSet view = md.getColumns(null, null, "order_summary", null);
        assertTrue(view.next());
        assertEquals("TEXT", view.getString("TYPE_NAME"));
    }

    @Test
    void reportsPrimaryKeys() throws SQLException {
        ResultSet rs = md.getPrimaryKeys(null, null, "USERS");
        assertTrue(rs.next());
        assertEquals("users", rs.getString("TABLE_NAME"));
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals(1, rs.getInt("KEY_SEQ"));
        assertEquals("pk_users", rs.getString("PK_NAME"));
        assertFalse(rs.next());
    }

    @Test
    void reportsImportedAndExportedKeysResolvingImplicitPk() throws SQLException {
        ResultSet imported = md.getImportedKeys(null, null, "orders");
        assertTrue(imported.next());
        assertEquals("users", imported.getString("PKTABLE_NAME"));
        assertEquals("id", imported.getString("PKCOLUMN_NAME"));
        assertEquals("orders", imported.getString("FKTABLE_NAME"));
        assertEquals("user_id", imported.getString("FKCOLUMN_NAME"));
        assertEquals(1, imported.getInt("KEY_SEQ"));
        assertEquals(DatabaseMetaData.importedKeyCascade, imported.getInt("DELETE_RULE"));
        assertEquals(DatabaseMetaData.importedKeyNoAction, imported.getInt("UPDATE_RULE"));
        assertEquals("fk_orders_0", imported.getString("FK_NAME"));
        assertFalse(imported.next());

        ResultSet exported = md.getExportedKeys(null, null, "users");
        assertTrue(exported.next());
        assertEquals("orders", exported.getString("FKTABLE_NAME"));
        assertFalse(exported.next());

        assertFalse(md.getImportedKeys(null, null, "users").next());
        assertTrue(md.getCrossReference(null, null, "users", null, null, "orders").next());
        assertFalse(md.getCrossReference(null, null, "orders", null, null, "users").next());
    }

    @Test
    void reportsIndexes() throws SQLException {
        ResultSet rs = md.getIndexInfo(null, null, "orders", false, true);
        assertTrue(rs.next());
        assertEquals("sqlite_autoindex_orders_1", rs.getString("INDEX_NAME"));
        assertFalse(rs.getBoolean("NON_UNIQUE"));
        assertEquals("D", rs.getString("ASC_OR_DESC"));
        assertTrue(rs.next());
        assertEquals("orders_user", rs.getString("INDEX_NAME"));
        assertTrue(rs.getBoolean("NON_UNIQUE"));
        assertEquals("user_id", rs.getString("COLUMN_NAME"));
        assertEquals(1, rs.getInt("ORDINAL_POSITION"));
        assertFalse(rs.next());
        assertEquals("orders", stub.lastRequest().params().get(0).asText());

        ResultSet unique = md.getIndexInfo(null, null, "orders", true, true);
        assertTrue(unique.next());
        assertFalse(unique.next());
    }

    @Test
    void typeInfoListsSqliteTypes() throws SQLException {
        List<String> names = strings(md.getTypeInfo(), "TYPE_NAME");
        assertTrue(names.containsAll(List.of("INTEGER", "REAL", "TEXT", "BLOB", "NUMERIC")));
    }

    @Test
    void bestRowIdentifierUsesPrimaryKey() throws SQLException {
        ResultSet rs = md.getBestRowIdentifier(null, null, "users", DatabaseMetaData.bestRowSession, true);
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertFalse(rs.next());
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

Run: `./gradlew test --tests '*D1DatabaseMetaDataTest'` → FAIL.

- [ ] **Step 3: Implement `D1DatabaseMetaData.java`**

```java
package com.dashnex.d1.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DatabaseMetaData built from sqlite_master and PRAGMA table-valued functions. */
public class D1DatabaseMetaData implements DatabaseMetaData {
    /** D1 rejects any query touching its internal _cf_* tables with SQLITE_AUTH, so always exclude them. */
    private static final String USER_TABLES =
            "m.name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND m.name NOT LIKE '\\_cf\\_%' ESCAPE '\\'";
    static final String TABLES_SQL = "SELECT m.name, m.type FROM sqlite_master m WHERE m.type IN ('table','view') AND "
            + USER_TABLES + " ORDER BY m.name";
    static final String COLUMNS_SQL = "SELECT m.name, p.cid, p.name, p.type, p.\"notnull\", p.dflt_value, p.pk "
            + "FROM sqlite_master m JOIN pragma_table_info(m.name) p WHERE m.type IN ('table','view') AND "
            + USER_TABLES + " ORDER BY m.name, p.cid";
    static final String FOREIGN_KEYS_SQL = "SELECT m.name, f.id, f.seq, f.\"table\", f.\"from\", f.\"to\", f.on_update, f.on_delete "
            + "FROM sqlite_master m JOIN pragma_foreign_key_list(m.name) f WHERE m.type = 'table' AND "
            + USER_TABLES + " ORDER BY m.name, f.id, f.seq";
    static final String INDEX_SQL = "SELECT il.name, il.\"unique\", ix.seqno, ix.name, ix.desc "
            + "FROM pragma_index_list(?) il JOIN pragma_index_xinfo(il.name) ix WHERE ix.key = 1 ORDER BY il.name, ix.seqno";

    private static final Pattern SIZE = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

    private static final String[] TABLES_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"};
    private static final String[] COLUMNS_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
            "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
            "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
            "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"};
    private static final int[] COLUMNS_TYPES = {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.INTEGER,
            Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR,
            Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR};
    private static final String[] PK_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"};
    private static final String[] FK_COLUMNS = {"PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
            "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE",
            "FK_NAME", "PK_NAME", "DEFERRABILITY"};
    private static final int[] FK_TYPES = {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.SMALLINT, Types.SMALLINT, Types.SMALLINT, Types.VARCHAR,
            Types.VARCHAR, Types.SMALLINT};
    private static final String[] INDEX_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE",
            "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY",
            "PAGES", "FILTER_CONDITION"};
    private static final int[] INDEX_TYPES = {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BOOLEAN, Types.VARCHAR,
            Types.VARCHAR, Types.SMALLINT, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT,
            Types.VARCHAR};
    private static final String[] TYPE_INFO_COLUMNS = {"TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX",
            "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
            "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB", "NUM_PREC_RADIX"};
    private static final String[] BEST_ROW_COLUMNS = {"SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
            "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN"};
    private static final String[] SCHEMAS_COLUMNS = {"TABLE_SCHEM", "TABLE_CATALOG"};
    private static final String[] CATALOGS_COLUMNS = {"TABLE_CAT"};
    private static final String[] TABLE_TYPES_COLUMNS = {"TABLE_TYPE"};
    private static final String[] PROCEDURES_COLUMNS = {"PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
            "RESERVED1", "RESERVED2", "RESERVED3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME"};
    private static final String[] PROCEDURE_COLUMNS_COLUMNS = {"PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
            "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
            "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
            "IS_NULLABLE", "SPECIFIC_NAME"};
    private static final String[] FUNCTIONS_COLUMNS = {"FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS",
            "FUNCTION_TYPE", "SPECIFIC_NAME"};
    private static final String[] FUNCTION_COLUMNS_COLUMNS = {"FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME",
            "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
            "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"};
    private static final String[] COLUMN_PRIVILEGES_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
            "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE"};
    private static final String[] TABLE_PRIVILEGES_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR",
            "GRANTEE", "PRIVILEGE", "IS_GRANTABLE"};
    private static final String[] UDT_COLUMNS = {"TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE",
            "REMARKS", "BASE_TYPE"};
    private static final String[] SUPER_TYPES_COLUMNS = {"TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT",
            "SUPERTYPE_SCHEM", "SUPERTYPE_NAME"};
    private static final String[] SUPER_TABLES_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"};
    private static final String[] ATTRIBUTES_COLUMNS = {"TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE",
            "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF",
            "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
            "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE"};
    private static final String[] PSEUDO_COLUMNS_COLUMNS = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
            "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS",
            "CHAR_OCTET_LENGTH", "IS_NULLABLE"};
    private static final String[] CLIENT_INFO_COLUMNS = {"NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"};

    private final D1Connection connection;

    public D1DatabaseMetaData(D1Connection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------- helpers

    private List<Object[]> query(String sql, Object... params) throws SQLException {
        List<D1Result> results = connection.client().execute(sql, Arrays.asList(params));
        return results.isEmpty() ? new ArrayList<>() : results.get(results.size() - 1).getRows();
    }

    private static ResultSet result(String[] columns, int[] types, List<Object[]> rows) {
        return D1ResultSet.of(null, columns, types, rows);
    }

    private static ResultSet empty(String[] columns) {
        return D1ResultSet.of(null, columns, null, new ArrayList<>());
    }

    private static final class ColumnRow {
        final String table;
        final int cid;
        final String name;
        final String type;
        final boolean notNull;
        final String defaultValue;
        final int pk;

        ColumnRow(Object[] r) {
            table = (String) r[0];
            cid = (int) D1Values.toLong(r[1]);
            name = (String) r[2];
            type = r[3] == null ? "" : ((String) r[3]).trim();
            notNull = D1Values.toLong(r[4]) != 0;
            defaultValue = D1Values.toStr(r[5]);
            pk = (int) D1Values.toLong(r[6]);
        }
    }

    private List<ColumnRow> columnRows() throws SQLException {
        List<ColumnRow> out = new ArrayList<>();
        for (Object[] r : query(COLUMNS_SQL)) out.add(new ColumnRow(r));
        return out;
    }

    private static Map<String, Integer> pkCounts(List<ColumnRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (ColumnRow c : rows) {
            if (c.pk > 0) counts.merge(c.table.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return counts;
    }

    /** A single-column INTEGER PRIMARY KEY is an alias for rowid: auto-assigned and never NULL. */
    private static boolean isRowidAlias(ColumnRow c, Map<String, Integer> pkCounts) {
        return c.pk > 0 && pkCounts.getOrDefault(c.table.toLowerCase(Locale.ROOT), 0) == 1
                && c.type.equalsIgnoreCase("INTEGER");
    }

    private static Integer sizePart(String declared, int group) {
        Matcher m = SIZE.matcher(declared);
        if (!m.find() || m.group(group) == null) return null;
        return Integer.valueOf(m.group(group));
    }

    private static String typeName(ColumnRow c, int jdbcType) {
        return c.type.isEmpty() ? D1Types.typeName(jdbcType) : c.type.toUpperCase(Locale.ROOT);
    }

    private static boolean sameName(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static int rule(Object action) {
        String a = action == null ? "" : action.toString().toUpperCase(Locale.ROOT);
        switch (a) {
            case "CASCADE": return importedKeyCascade;
            case "RESTRICT": return importedKeyRestrict;
            case "SET NULL": return importedKeySetNull;
            case "SET DEFAULT": return importedKeySetDefault;
            default: return importedKeyNoAction;
        }
    }

    /** All foreign keys, one row per column, in FK_COLUMNS layout. */
    private List<Object[]> foreignKeyRows() throws SQLException {
        List<Object[]> raw = query(FOREIGN_KEYS_SQL);
        Map<String, List<String>> pkColumns = null;
        List<Object[]> out = new ArrayList<>();
        for (Object[] r : raw) {
            String fkTable = (String) r[0];
            long id = D1Values.toLong(r[1]);
            int seq = (int) D1Values.toLong(r[2]);
            String pkTable = (String) r[3];
            String fkColumn = (String) r[4];
            String pkColumn = (String) r[5];
            if (pkColumn == null) {
                if (pkColumns == null) pkColumns = primaryKeyColumnsByTable();
                List<String> pks = pkColumns.getOrDefault(pkTable.toLowerCase(Locale.ROOT), List.of());
                pkColumn = seq < pks.size() ? pks.get(seq) : null;
            }
            out.add(new Object[]{null, null, pkTable, pkColumn, null, null, fkTable, fkColumn, seq + 1,
                    rule(r[6]), rule(r[7]), "fk_" + fkTable + "_" + id, "pk_" + pkTable, importedKeyNotDeferrable});
        }
        return out;
    }

    private Map<String, List<String>> primaryKeyColumnsByTable() throws SQLException {
        List<ColumnRow> rows = columnRows();
        rows.sort(Comparator.comparingInt(c -> c.pk));
        Map<String, List<String>> out = new HashMap<>();
        for (ColumnRow c : rows) {
            if (c.pk > 0) out.computeIfAbsent(c.table.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c.name);
        }
        return out;
    }

    private static Comparator<Object[]> byStringThenInt(int stringIndex, int intIndex) {
        return Comparator.comparing((Object[] r) -> String.valueOf(r[stringIndex]).toLowerCase(Locale.ROOT))
                .thenComparingInt(r -> ((Number) r[intIndex]).intValue());
    }

    // ---------------------------------------------------------------- tables & columns

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        Set<String> wanted = null;
        if (types != null) {
            wanted = new HashSet<>();
            for (String t : types) wanted.add(t.toUpperCase(Locale.ROOT));
        }
        List<Object[]> rows = new ArrayList<>();
        for (Object[] t : query(TABLES_SQL)) {
            String name = (String) t[0];
            String type = "view".equals(t[1]) ? "VIEW" : "TABLE";
            if (wanted != null && !wanted.contains(type)) continue;
            if (!SqlText.matchesPattern(tableNamePattern, name)) continue;
            rows.add(new Object[]{null, null, name, type, null, null, null, null, null, null});
        }
        rows.sort(Comparator.comparing((Object[] r) -> (String) r[3]).thenComparing(r -> ((String) r[2]).toLowerCase(Locale.ROOT)));
        return result(TABLES_COLUMNS, null, rows);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        List<ColumnRow> all = columnRows();
        Map<String, Integer> pkCounts = pkCounts(all);
        List<Object[]> rows = new ArrayList<>();
        for (ColumnRow c : all) {
            if (!SqlText.matchesPattern(tableNamePattern, c.table) || !SqlText.matchesPattern(columnNamePattern, c.name)) {
                continue;
            }
            int jdbcType = D1Types.fromDeclared(c.type);
            boolean rowidAlias = isRowidAlias(c, pkCounts);
            boolean notNull = c.notNull || rowidAlias;
            Integer size = sizePart(c.type, 1);
            rows.add(new Object[]{null, null, c.table, c.name, jdbcType, typeName(c, jdbcType),
                    size == null ? 0 : size, null, sizePart(c.type, 2), 10,
                    notNull ? columnNoNulls : columnNullable, null, c.defaultValue, null, null,
                    size == null ? 0 : size, c.cid + 1, notNull ? "NO" : "YES", null, null, null, null,
                    rowidAlias ? "YES" : "NO", "NO"});
        }
        return result(COLUMNS_COLUMNS, COLUMNS_TYPES, rows);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (ColumnRow c : columnRows()) {
            if (c.pk > 0 && (table == null || sameName(c.table, table))) {
                rows.add(new Object[]{null, null, c.table, c.name, c.pk, "pk_" + c.table});
            }
        }
        rows.sort(Comparator.comparing((Object[] r) -> (String) r[3]));
        return result(PK_COLUMNS, new int[]{Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                Types.SMALLINT, Types.VARCHAR}, rows);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (ColumnRow c : columnRows()) {
            if (c.pk > 0 && sameName(c.table, table)) {
                int jdbcType = D1Types.fromDeclared(c.type);
                rows.add(new Object[]{bestRowSession, c.name, jdbcType, typeName(c, jdbcType), 0, null, null, bestRowNotPseudo});
            }
        }
        if (rows.isEmpty()) {
            rows.add(new Object[]{bestRowSession, "rowid", Types.BIGINT, "INTEGER", 0, null, null, bestRowPseudo});
        }
        return result(BEST_ROW_COLUMNS, null, rows);
    }

    // ---------------------------------------------------------------- keys & indexes

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : foreignKeyRows()) {
            if (sameName((String) r[6], table)) rows.add(r);
        }
        rows.sort(byStringThenInt(2, 8));
        return result(FK_COLUMNS, FK_TYPES, rows);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : foreignKeyRows()) {
            if (sameName((String) r[2], table)) rows.add(r);
        }
        rows.sort(byStringThenInt(6, 8));
        return result(FK_COLUMNS, FK_TYPES, rows);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : foreignKeyRows()) {
            if ((parentTable == null || sameName((String) r[2], parentTable))
                    && (foreignTable == null || sameName((String) r[6], foreignTable))) {
                rows.add(r);
            }
        }
        rows.sort(byStringThenInt(6, 8));
        return result(FK_COLUMNS, FK_TYPES, rows);
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : query(INDEX_SQL, table)) {
            boolean isUnique = D1Values.toLong(r[1]) != 0;
            if (unique && !isUnique) continue;
            rows.add(new Object[]{null, null, table, !isUnique, null, r[0], (int) tableIndexOther,
                    (int) D1Values.toLong(r[2]) + 1, r[3], D1Values.toLong(r[4]) != 0 ? "D" : "A", 0L, 0L, null});
        }
        rows.sort(Comparator.comparing((Object[] r) -> (Boolean) r[3])
                .thenComparing(r -> String.valueOf(r[5]))
                .thenComparingInt(r -> (Integer) r[7]));
        return result(INDEX_COLUMNS, INDEX_TYPES, rows);
    }

    @Override
    public ResultSet getTypeInfo() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(typeInfo("INTEGER", Types.BIGINT, 19, null, null, false, true));
        rows.add(typeInfo("NUMERIC", Types.NUMERIC, 38, null, null, false, false));
        rows.add(typeInfo("REAL", Types.DOUBLE, 15, null, null, false, false));
        rows.add(typeInfo("TEXT", Types.VARCHAR, 0, "'", "'", true, false));
        rows.add(typeInfo("BOOLEAN", Types.BOOLEAN, 1, null, null, false, false));
        rows.add(typeInfo("BLOB", Types.BLOB, 0, "X'", "'", false, false));
        return result(TYPE_INFO_COLUMNS, null, rows);
    }

    private static Object[] typeInfo(String name, int type, int precision, String prefix, String suffix,
                                     boolean caseSensitive, boolean autoIncrement) {
        return new Object[]{name, type, precision, prefix, suffix, null, (int) typeNullable, caseSensitive,
                (int) typeSearchable, false, false, autoIncrement, name, 0, 0, null, null, 10};
    }

    @Override public ResultSet getTableTypes() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"TABLE"});
        rows.add(new Object[]{"VIEW"});
        return result(TABLE_TYPES_COLUMNS, null, rows);
    }

    // ---------------------------------------------------------------- empty result sets

    @Override public ResultSet getSchemas() { return empty(SCHEMAS_COLUMNS); }
    @Override public ResultSet getSchemas(String catalog, String schemaPattern) { return empty(SCHEMAS_COLUMNS); }
    @Override public ResultSet getCatalogs() { return empty(CATALOGS_COLUMNS); }
    @Override public ResultSet getProcedures(String c, String s, String p) { return empty(PROCEDURES_COLUMNS); }
    @Override public ResultSet getProcedureColumns(String c, String s, String p, String col) { return empty(PROCEDURE_COLUMNS_COLUMNS); }
    @Override public ResultSet getFunctions(String c, String s, String f) { return empty(FUNCTIONS_COLUMNS); }
    @Override public ResultSet getFunctionColumns(String c, String s, String f, String col) { return empty(FUNCTION_COLUMNS_COLUMNS); }
    @Override public ResultSet getColumnPrivileges(String c, String s, String t, String col) { return empty(COLUMN_PRIVILEGES_COLUMNS); }
    @Override public ResultSet getTablePrivileges(String c, String s, String t) { return empty(TABLE_PRIVILEGES_COLUMNS); }
    @Override public ResultSet getVersionColumns(String c, String s, String t) { return empty(BEST_ROW_COLUMNS); }
    @Override public ResultSet getUDTs(String c, String s, String t, int[] types) { return empty(UDT_COLUMNS); }
    @Override public ResultSet getSuperTypes(String c, String s, String t) { return empty(SUPER_TYPES_COLUMNS); }
    @Override public ResultSet getSuperTables(String c, String s, String t) { return empty(SUPER_TABLES_COLUMNS); }
    @Override public ResultSet getAttributes(String c, String s, String t, String a) { return empty(ATTRIBUTES_COLUMNS); }
    @Override public ResultSet getPseudoColumns(String c, String s, String t, String col) { return empty(PSEUDO_COLUMNS_COLUMNS); }
    @Override public ResultSet getClientInfoProperties() { return empty(CLIENT_INFO_COLUMNS); }

    // ---------------------------------------------------------------- identity

    @Override public String getURL() { return "jdbc:d1://" + connection.config().getDatabase(); }
    @Override public String getUserName() { return connection.config().getAccountId(); }
    @Override public boolean isReadOnly() { return connection.isReadOnly(); }
    @Override public String getDatabaseProductName() { return "SQLite"; }
    @Override public String getDatabaseProductVersion() { return SqlText.SQLITE_VERSION; }
    @Override public String getDriverName() { return "Cloudflare D1 JDBC"; }
    @Override public String getDriverVersion() { return D1Driver.MAJOR_VERSION + "." + D1Driver.MINOR_VERSION; }
    @Override public int getDriverMajorVersion() { return D1Driver.MAJOR_VERSION; }
    @Override public int getDriverMinorVersion() { return D1Driver.MINOR_VERSION; }
    @Override public int getDatabaseMajorVersion() { return 3; }
    @Override public int getDatabaseMinorVersion() { return 45; }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }
    @Override public Connection getConnection() { return connection; }

    // ---------------------------------------------------------------- capabilities

    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean allTablesAreSelectable() { return true; }
    @Override public boolean nullsAreSortedHigh() { return false; }
    @Override public boolean nullsAreSortedLow() { return true; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }
    @Override public boolean usesLocalFiles() { return false; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return false; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean storesMixedCaseIdentifiers() { return true; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public String getIdentifierQuoteString() { return "\""; }
    @Override public String getSQLKeywords() {
        return "ABORT,ACTION,AFTER,ANALYZE,ATTACH,AUTOINCREMENT,BEFORE,CASCADE,CONFLICT,DATABASE,DEFERRABLE,DEFERRED,"
                + "DETACH,EXCLUSIVE,EXPLAIN,FAIL,GLOB,IGNORE,INDEXED,INITIALLY,INSTEAD,ISNULL,LIMIT,NOTNULL,OFFSET,PLAN,"
                + "PRAGMA,QUERY,RAISE,REGEXP,REINDEX,RENAME,REPLACE,RESTRICT,TEMP,TEMPORARY,VACUUM,VIEW,VIRTUAL,WITHOUT";
    }
    @Override public String getNumericFunctions() { return "abs,max,min,round,random,sign,ceil,floor,sqrt,pow,mod"; }
    @Override public String getStringFunctions() { return "length,lower,upper,ltrim,rtrim,trim,replace,substr,instr,hex,quote,printf,format"; }
    @Override public String getSystemFunctions() { return "changes,last_insert_rowid,total_changes,typeof,coalesce,ifnull,nullif,iif"; }
    @Override public String getTimeDateFunctions() { return "date,time,datetime,julianday,strftime,unixepoch"; }
    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return ""; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return true; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(int fromType, int toType) { return false; }
    @Override public boolean supportsTableCorrelationNames() { return true; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsGroupByUnrelated() { return true; }
    @Override public boolean supportsGroupByBeyondSelect() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return true; }
    @Override public boolean supportsMultipleResultSets() { return true; }
    @Override public boolean supportsMultipleTransactions() { return false; }
    @Override public boolean supportsNonNullableColumns() { return true; }
    @Override public boolean supportsMinimumSQLGrammar() { return true; }
    @Override public boolean supportsCoreSQLGrammar() { return true; }
    @Override public boolean supportsExtendedSQLGrammar() { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return true; }
    @Override public boolean supportsANSI92IntermediateSQL() { return false; }
    @Override public boolean supportsANSI92FullSQL() { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return true; }
    @Override public boolean supportsLimitedOuterJoins() { return true; }
    @Override public String getSchemaTerm() { return "schema"; }
    @Override public String getProcedureTerm() { return "procedure"; }
    @Override public String getCatalogTerm() { return "catalog"; }
    @Override public boolean isCatalogAtStart() { return true; }
    @Override public String getCatalogSeparator() { return "."; }
    @Override public boolean supportsSchemasInDataManipulation() { return false; }
    @Override public boolean supportsSchemasInProcedureCalls() { return false; }
    @Override public boolean supportsSchemasInTableDefinitions() { return false; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return false; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsSubqueriesInComparisons() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return false; }
    @Override public boolean supportsCorrelatedSubqueries() { return true; }
    @Override public boolean supportsUnion() { return true; }
    @Override public boolean supportsUnionAll() { return true; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return true; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return true; }
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 0; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 100; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 0; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 0; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 100_000; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 0; }
    @Override public int getMaxTablesInSelect() { return 0; }
    @Override public int getMaxUserNameLength() { return 0; }
    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_NONE; }
    @Override public boolean supportsTransactions() { return false; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return level == Connection.TRANSACTION_NONE; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return false; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return supportsResultSetType(type) && concurrency == ResultSet.CONCUR_READ_ONLY;
    }
    @Override public boolean ownUpdatesAreVisible(int type) { return false; }
    @Override public boolean ownDeletesAreVisible(int type) { return false; }
    @Override public boolean ownInsertsAreVisible(int type) { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) { return false; }
    @Override public boolean othersDeletesAreVisible(int type) { return false; }
    @Override public boolean othersInsertsAreVisible(int type) { return false; }
    @Override public boolean updatesAreDetected(int type) { return false; }
    @Override public boolean deletesAreDetected(int type) { return false; }
    @Override public boolean insertsAreDetected(int type) { return false; }
    @Override public boolean supportsBatchUpdates() { return true; }
    @Override public boolean supportsSavepoints() { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean supportsGetGeneratedKeys() { return true; }
    @Override public boolean supportsResultSetHoldability(int holdability) { return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getResultSetHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getSQLStateType() { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return true; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
```

- [ ] **Step 4: Wire it into `D1Connection.getMetaData()`**

Replace the Task 5 body with:

```java
    @Override public DatabaseMetaData getMetaData() throws SQLException { checkOpen(); return new D1DatabaseMetaData(this); }
```

- [ ] **Step 5: Run — expect PASS**

Run: `./gradlew test` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat: DatabaseMetaData with tables, columns, keys, foreign keys and indexes"
```

---
### Task 7: Integration tests against real D1

**Files:**
- Test: `src/test/java/com/dashnex/d1/jdbc/D1IntegrationTest.java`
- Modify (only if a test exposes a real-D1 difference): the class the failure points at; add a matching unit test first.

**Interfaces:**
- Consumes: the public driver (`DriverManager.getConnection("jdbc:d1://" + D1_DATABASE, user/password)`).

- [ ] **Step 1: Write the integration test**

```java
package com.dashnex.d1.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class D1IntegrationTest {
    private static Connection conn;

    @BeforeAll
    static void connect() throws SQLException {
        String account = System.getenv("D1_ACCOUNT_ID");
        String token = System.getenv("D1_TOKEN");
        String database = System.getenv("D1_DATABASE");
        Assumptions.assumeTrue(account != null && token != null && database != null, "D1_* variables not set");
        Properties p = new Properties();
        p.setProperty("user", account);
        p.setProperty("password", token);
        conn = DriverManager.getConnection("jdbc:d1://" + database, p);
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS it_child");
            s.execute("DROP TABLE IF EXISTS it_parent");
            s.execute("CREATE TABLE it_parent (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE it_child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES it_parent(id) "
                    + "ON DELETE CASCADE, amount REAL, data BLOB, flag BOOLEAN, note VARCHAR(20) DEFAULT 'n/a')");
            s.execute("CREATE INDEX it_child_parent ON it_child(parent_id)");
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (conn == null) return;
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS it_child");
            s.execute("DROP TABLE IF EXISTS it_parent");
        }
        conn.close();
    }

    private static long insertParent(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO it_parent(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            assertEquals(1, ps.executeUpdate());
            ResultSet keys = ps.getGeneratedKeys();
            assertTrue(keys.next());
            return keys.getLong(1);
        }
    }

    @Test
    void crudRoundTrip() throws SQLException {
        long parentId = insertParent("crud-parent");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO it_child(parent_id, amount, data, flag) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, parentId);
            ps.setDouble(2, 12.5);
            ps.setBytes(3, new byte[]{0, 1, (byte) 255});
            ps.setBoolean(4, true);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM it_child WHERE parent_id = ?")) {
            ps.setLong(1, parentId);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(12.5, rs.getDouble("amount"));
            assertArrayEquals(new byte[]{0, 1, (byte) 255}, rs.getBytes("data"));
            assertTrue(rs.getBoolean("flag"));
            assertEquals("n/a", rs.getString("note"));
            assertFalse(rs.next());
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE it_child SET amount = ? WHERE parent_id = ?")) {
            ps.setDouble(1, 99.0);
            ps.setLong(2, parentId);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM it_parent WHERE id = ?")) {
            ps.setLong(1, parentId);
            assertEquals(1, ps.executeUpdate());
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM it_child WHERE parent_id = " + parentId)) {
            rs.next();
            assertEquals(0, rs.getInt(1), "ON DELETE CASCADE should remove children");
        }
    }

    @Test
    void metadataShowsTablesKeysAndIndexes() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        List<String> tables = new ArrayList<>();
        ResultSet t = md.getTables(null, null, "%", new String[]{"TABLE"});
        while (t.next()) tables.add(t.getString("TABLE_NAME"));
        assertTrue(tables.containsAll(List.of("it_parent", "it_child")), tables.toString());
        assertFalse(tables.contains("_cf_KV"));

        ResultSet cols = md.getColumns(null, null, "it_child", "note");
        assertTrue(cols.next());
        assertEquals("VARCHAR(20)", cols.getString("TYPE_NAME"));
        assertEquals("'n/a'", cols.getString("COLUMN_DEF"));

        ResultSet pk = md.getPrimaryKeys(null, null, "it_parent");
        assertTrue(pk.next());
        assertEquals("id", pk.getString("COLUMN_NAME"));

        ResultSet fk = md.getImportedKeys(null, null, "it_child");
        assertTrue(fk.next());
        assertEquals("it_parent", fk.getString("PKTABLE_NAME"));
        assertEquals("id", fk.getString("PKCOLUMN_NAME"));
        assertEquals("parent_id", fk.getString("FKCOLUMN_NAME"));
        assertEquals(DatabaseMetaData.importedKeyCascade, fk.getInt("DELETE_RULE"));

        ResultSet exported = md.getExportedKeys(null, null, "it_parent");
        assertTrue(exported.next());
        assertEquals("it_child", exported.getString("FKTABLE_NAME"));

        List<String> indexes = new ArrayList<>();
        ResultSet ix = md.getIndexInfo(null, null, "it_child", false, true);
        while (ix.next()) indexes.add(ix.getString("INDEX_NAME"));
        assertTrue(indexes.contains("it_child_parent"), indexes.toString());
    }

    @Test
    void addAndDropColumn() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE it_parent ADD COLUMN extra TEXT");
            assertTrue(md.getColumns(null, null, "it_parent", "extra").next());
            s.execute("ALTER TABLE it_parent DROP COLUMN extra");
            assertFalse(md.getColumns(null, null, "it_parent", "extra").next());
        }
    }

    @Test
    void resultSetMetadataReportsSourceTableAndDeclaredTypes() throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM it_child LIMIT 1")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals("it_child", md.getTableName(1));
            assertEquals(Types.DOUBLE, md.getColumnType(rs.findColumn("amount")));
            assertEquals("VARCHAR(20)", md.getColumnTypeName(rs.findColumn("note")));
        }
    }

    @Test
    void constraintViolationMapsTo23000() throws SQLException {
        insertParent("dup");
        SQLException e = assertThrows(SQLException.class, () -> insertParent("dup"));
        assertEquals("23000", e.getSQLState());
    }

    @Test
    void failingBatchIsAtomic() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.addBatch("INSERT INTO it_parent(name) VALUES ('batch-1')");
            s.addBatch("INSERT INTO it_parent(name) VALUES ('batch-1')");
            assertThrows(BatchUpdateException.class, s::executeBatch);
            ResultSet rs = s.executeQuery("SELECT count(*) FROM it_parent WHERE name = 'batch-1'");
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void transactionStatementsAndVersionQueriesWork() throws SQLException {
        try (Statement s = conn.createStatement()) {
            assertFalse(s.execute("BEGIN"));
            assertFalse(s.execute("COMMIT"));
            ResultSet rs = s.executeQuery("SELECT sqlite_version()");
            rs.next();
            assertEquals("3.45.0", rs.getString(1));
        }
    }
}
```

- [ ] **Step 2: Run against real D1**

Run: `./gradlew integrationTest` (reads `.env`) → all 7 tests PASS.
If a test fails because D1 behaves differently than the spec assumed: use superpowers:systematic-debugging, reproduce the behaviour with a raw `curl` call against `/raw`, add a unit test in the relevant `*Test` class capturing the real response, fix, re-run `./gradlew test integrationTest`, and record the new fact in the spec's "Verified D1 behaviour" section.

- [ ] **Step 3: Commit**

```bash
git add src docs && git commit -m "test: integration tests against Cloudflare D1"
```

---

### Task 8: Packaging, README and IDE smoke test

**Files:**
- Create: `README.md`
- Verify: `build/libs/d1-jdbc-0.1.0-all.jar`

- [ ] **Step 1: Build the fat jar and check its contents**

```bash
./gradlew clean build
unzip -l build/libs/d1-jdbc-0.1.0-all.jar | grep -E 'META-INF/services/java.sql.Driver|com/dashnex/d1/jdbc/D1Driver.class|shaded/jackson/databind/ObjectMapper.class'
unzip -l build/libs/d1-jdbc-0.1.0-all.jar | grep -c 'com/fasterxml' || true
```

Expected: the three entries are listed; the `com/fasterxml` count is `0`.

- [ ] **Step 2: Smoke-test the jar from a plain JVM** (no Gradle classpath)

```bash
S=/private/tmp/claude-501/-Users-me-work-d1-jdbc/6c47440e-ae66-4b89-8892-026a9ed5ac5d/scratchpad
cat > $S/Smoke.java <<'EOF'
import java.sql.*;
import java.util.Properties;
public class Smoke {
    public static void main(String[] a) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", System.getenv("D1_ACCOUNT_ID"));
        p.setProperty("password", System.getenv("D1_TOKEN"));
        try (Connection c = DriverManager.getConnection("jdbc:d1://" + System.getenv("D1_DATABASE"), p)) {
            ResultSet t = c.getMetaData().getTables(null, null, "%", null);
            while (t.next()) System.out.println(t.getString("TABLE_TYPE") + " " + t.getString("TABLE_NAME"));
            System.out.println("OK " + c.getMetaData().getDatabaseProductName());
        }
    }
}
EOF
set -a; source .env; set +a
java -cp build/libs/d1-jdbc-0.1.0-all.jar $S/Smoke.java
```

Expected: table list (possibly empty) then `OK SQLite`.

- [ ] **Step 3: Write `README.md`**

````markdown
# Cloudflare D1 JDBC Driver

JDBC driver for [Cloudflare D1](https://developers.cloudflare.com/d1/) over the D1 REST API. Browse tables,
views, columns, primary/foreign keys and indexes, edit rows and alter tables from DBeaver or DataGrip.

## Build

```bash
./gradlew build
```

The driver jar is `build/libs/d1-jdbc-0.1.0-all.jar` (dependencies shaded; no other files needed).

## Cloudflare API token

Create a token at **My Profile → API Tokens → Create Token → Custom token** with
**Account → D1 → Edit** for your account. You also need your **account ID** (Workers & Pages overview, right sidebar).

## Connection settings

| Field | Value |
|---|---|
| Driver class | `com.dashnex.d1.jdbc.D1Driver` |
| URL | `jdbc:d1://<database name or UUID>` |
| User | Cloudflare account ID |
| Password | Cloudflare API token |

Optional properties: `apiBase` (default `https://api.cloudflare.com/client/v4`), `timeoutSeconds` (default `30`).

## DBeaver

1. **Database → Driver Manager → New**.
2. *Settings*: Driver Name `Cloudflare D1`, Driver Type `Generic`, Class Name `com.dashnex.d1.jdbc.D1Driver`,
   URL Template `jdbc:d1://{database}`, leave Default Port empty, tick *No authentication* **off**.
3. *Libraries*: **Add File** → `d1-jdbc-0.1.0-all.jar`. Click **OK**.
4. **New Database Connection → Cloudflare D1**: Database = database name or UUID, Username = account ID,
   Password = API token. **Test Connection**.

## DataGrip

1. **Database Explorer → + → Driver**. Name `Cloudflare D1`.
2. *Driver Files*: **+ → Custom JARs…** → `d1-jdbc-0.1.0-all.jar`. Class `com.dashnex.d1.jdbc.D1Driver`.
3. *URL templates*: add `default` = `jdbc:d1://{database}`. *Options → Dialect*: **SQLite**.
4. **+ → Data Source → Cloudflare D1**: Authentication *User & Password*, User = account ID,
   Password = API token, Database = name or UUID. **Test Connection**.

## Limitations

- **No transactions.** The D1 REST API commits every statement immediately. `BEGIN`/`COMMIT`/`ROLLBACK`
  are accepted and ignored (with a warning); keep the IDE in auto-commit mode. JDBC batches run atomically.
- Integers larger than 2^53 lose precision (D1 returns JSON numbers).
- Dates are stored as text/integers exactly as SQLite does; no time-zone conversion.
- D1 limits apply (e.g. 100 KB per SQL statement, 100 columns per table).

## Development

```bash
./gradlew test                 # unit tests (no network)
./gradlew integrationTest      # real D1; needs D1_ACCOUNT_ID, D1_TOKEN, D1_DATABASE (env or .env)
```
````

- [ ] **Step 4: Manual IDE smoke checklist** (user runs; report results)

In DBeaver and in DataGrip, using the jar from Step 1:
1. Test Connection succeeds; a wrong token shows the "Cloudflare rejected the API token" message.
2. Tables `it_parent`/`it_child` (create them with the SQL from `D1IntegrationTest.connect()`) appear; `_cf_KV` does not.
3. The FK `it_child.parent_id → it_parent.id` shows in the table's *Foreign Keys* tab and ER diagram.
4. Open `it_parent` data: add a row, edit a cell, delete a row, **Save** — each change persists after refresh.
5. Add a column via the table editor and save; drop it again.
If an IDE step fails, capture the IDE's error log/SQL and debug with superpowers:systematic-debugging before changing code.

- [ ] **Step 5: Commit**

```bash
git add README.md && git commit -m "docs: README with DBeaver and DataGrip setup"
```

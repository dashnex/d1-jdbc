# Cloudflare D1 JDBC Driver — Design

**Date:** 2026-09-11
**Status:** Approved (brainstorming), pending spec review

## Goal

A JDBC driver that lets DBeaver and DataGrip connect to a Cloudflare D1 database using
only a Cloudflare API token, with:

- schema browsing including foreign keys (tree + ER diagram) and indexes
- data editing in the IDE grid (insert / update / delete rows)
- DDL: create and drop tables and columns

## Non-goals

- Interactive transactions (the D1 REST API does not support them).
- Multiple databases per connection (one D1 database per IDE connection).
- Local SQLite mirroring, Worker proxies, or any server-side deployment.
- Date/time type conversion beyond what SQLite itself stores.

## Approach

Pure REST driver. Every JDBC call that executes SQL becomes an HTTPS request to

```
POST https://api.cloudflare.com/client/v4/accounts/{account_id}/d1/database/{database_id}/raw
Authorization: Bearer {token}
{"sql": "...", "params": [...]}
```

The `/raw` endpoint is used (not `/query`) because it returns `columns` + `rows` as arrays,
preserving column order and duplicate column names.

The driver reports `getDatabaseProductName() == "SQLite"` so both IDEs pick their SQLite
dialect for SQL generation, data-editor behaviour (rowid fallback) and DDL (ALTER TABLE
ADD/DROP/RENAME COLUMN, table rebuild for type changes).

## Connection configuration

| Item | Value |
|---|---|
| Driver class | `com.dashnex.d1.jdbc.D1Driver` |
| URL | `jdbc:d1://<database name or UUID>` |
| User | Cloudflare account ID |
| Password | Cloudflare API token (scope: Account → D1 → Edit) |
| Optional properties | `accountId`, `token` (alternatives to user/password), `apiBase` (default `https://api.cloudflare.com/client/v4`, overridable for tests), `timeoutSeconds` (default 30) |

If the URL value is not a UUID it is resolved to a UUID once per connection via
`GET /accounts/{account_id}/d1/database?name=<name>`; an exact-name match is required.

`Driver.connect` returns `null` for URLs not starting with `jdbc:d1:` (JDBC contract).
The driver self-registers via `META-INF/services/java.sql.Driver`.

## Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `D1Driver` | URL/property parsing, `acceptsURL`, `getPropertyInfo`, creates connections | `D1Client`, `D1Connection` |
| `D1ConnectionConfig` | Immutable parsed config (account, token, database ref, apiBase, timeout) | — |
| `D1Client` | HTTP + JSON only: `query(sql, params) -> D1Result`, `batch(List<stmt>) -> List<D1Result>`, `resolveDatabaseId(name)`. Retries 429/5xx (3 attempts, exponential backoff 200ms→1.6s). Maps API errors to `SQLException`. | `java.net.http`, Jackson (shaded) |
| `D1Result` | `columns`, `rows` (List of Object[]), `meta` (`changes`, `last_row_id`, `rows_read`, `rows_written`) | — |
| `D1Connection` | Holds client + config; creates statements; autocommit semantics; `getMetaData` | `D1Client` |
| `D1Statement` | `execute*`, `executeBatch` (one HTTP call, atomic in D1), update counts from `meta.changes`, generated keys from `meta.last_row_id` | `D1Connection` |
| `D1PreparedStatement` | Positional `?` params → JSON params; blobs as byte arrays | `D1Statement` |
| `D1ResultSet` / `D1ResultSetMetaData` | In-memory, forward + scroll-insensitive read-only; typed getters with SQLite-style coercion | `D1Result` |
| `D1DatabaseMetaData` | Metadata via SQL/PRAGMA (see below) | `D1Connection` |
| `D1Types` | Declared-type → `java.sql.Types` mapping (SQLite affinity rules) and value-based inference | — |

## Metadata mapping

Catalogs: none (`getCatalogs` empty). Schemas: single schema `main`.

| JDBC method | Source |
|---|---|
| `getTables` | `SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','view')`, excluding `sqlite_%`, `_cf_%` (D1 internal) |
| `getColumns` | `PRAGMA table_info("<t>")` — name, declared type, notnull, dflt_value, pk; autoincrement detected from `sqlite_master.sql` |
| `getPrimaryKeys` | `PRAGMA table_info` rows with `pk > 0`, ordered by pk |
| `getImportedKeys` | `PRAGMA foreign_key_list("<t>")` — groups by `id`, maps `on_update`/`on_delete` to `DatabaseMetaData.importedKey*` |
| `getExportedKeys` / `getCrossReference` | `foreign_key_list` across all tables, filtered by referenced table |
| `getIndexInfo` | `PRAGMA index_list` + `PRAGMA index_info` |
| `getTypeInfo` | Static list: INTEGER, REAL, TEXT, BLOB, NUMERIC |
| Product info | name `SQLite`, version from `SELECT sqlite_version()`; driver name `Cloudflare D1 JDBC` |

Identifiers are quoted with `"` and embedded quotes doubled.

## Types

Declared type → JDBC type by SQLite affinity:

- contains `INT` → `BIGINT` (`INTEGER` exactly → `INTEGER`)
- contains `CHAR`, `CLOB`, `TEXT` → `VARCHAR`
- contains `BLOB` or empty → `BLOB`
- contains `REAL`, `FLOA`, `DOUB` → `DOUBLE`
- `BOOLEAN`/`BOOL` → `BOOLEAN`; `DATE`/`DATETIME`/`TIMESTAMP` → `VARCHAR` (stored as text)
- otherwise → `NUMERIC`

Result-set columns (no declared type available from `/raw`) infer type from the first
non-null value in the column: Long → `BIGINT`, Double → `DOUBLE`, String → `VARCHAR`,
byte array → `BLOB`, all-null → `VARCHAR`. D1 returns blobs as JSON arrays of numbers; the
client converts them to `byte[]`.

Getters coerce like sqlite-jdbc (`getInt` on text parses, `getString` on number formats,
`getBoolean` on 0/1, `getBytes` on text returns UTF-8).

## Transactions

- Default autocommit = true; every statement commits immediately.
- `setAutoCommit(false)` is accepted (IDEs call it) and adds a `SQLWarning` explaining that
  D1 has no interactive transactions; `commit()`/`rollback()` are no-ops.
- `executeBatch()` sends all statements in one request, which D1 applies atomically.
- `setSavepoint` etc. throw `SQLFeatureNotSupportedException`.

## Errors

- HTTP 401/403 → `SQLException` "Cloudflare rejected the token (needs Account → D1 → Edit)", SQLState `28000`.
- HTTP 404 on database → "D1 database not found", SQLState `08001`.
- API `success:false` with SQLite error text → `SQLException` carrying the message; SQLState
  `23000` for constraint violations, `42000` for syntax errors, otherwise `HY000`.
- 429/5xx retried as above, then surfaced with the last error.
- Network failures → `SQLException` SQLState `08006`.

## Testing

- **Unit tests** (JUnit 5): URL/config parsing, type mapping, value coercion, JSON parsing,
  error mapping, and statement/metadata behaviour against a JDK `HttpServer` stub returning
  recorded D1 responses.
- **Integration tests** (tagged `integration`, run only when `D1_ACCOUNT_ID`, `D1_TOKEN`,
  `D1_DATABASE` are set): create tables with FKs, CRUD, ADD/DROP COLUMN, metadata calls,
  cleanup of created tables.
- **Manual smoke**: DBeaver and DataGrip — add driver, connect, view FKs in ER diagram, edit
  grid rows, add/drop a column.

## Build & packaging

- Java 11 bytecode target (runs on the IDEs' bundled JREs); built with JDK 17.
- Gradle (wrapper committed) + Shadow plugin; Jackson relocated to
  `com.dashnex.d1.jdbc.shaded.jackson`. Output: `build/libs/d1-jdbc-<version>-all.jar`.
- README: per-IDE setup (custom driver, jar, class, URL template, user/password meaning,
  token scope) and known limitations (no transactions, statement size/row limits of D1).

# Cloudflare D1 JDBC Driver

JDBC driver for [Cloudflare D1](https://developers.cloudflare.com/d1/) over the D1 REST API. Browse tables,
views, columns, primary/foreign keys and indexes, edit rows and alter tables from DBeaver or DataGrip.

## Download

Grab the driver jar (single file, dependencies shaded, Java 11+):

- **Latest:** [d1-jdbc.jar](https://github.com/dashnex/d1-jdbc/releases/latest/download/d1-jdbc.jar)
- **All versions:** [Releases](https://github.com/dashnex/d1-jdbc/releases) — each release has
  `d1-jdbc-<version>.jar`, `d1-jdbc.jar` and `SHA256SUMS`.

Or build it yourself:

```bash
./gradlew build    # → build/libs/d1-jdbc-<version>-all.jar
```

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
3. *Libraries*: **Add File** → the downloaded `d1-jdbc.jar`. Click **OK**.
4. **New Database Connection → Cloudflare D1**: Database = database name or UUID, Username = account ID,
   Password = API token. **Test Connection**.

If the table editor doesn't offer SQLite-specific DDL, you can instead copy DBeaver's built-in SQLite
driver (Driver Manager → SQLite → Copy), replace its library with the d1-jdbc jar and set the class name
and URL template as above.

## DataGrip

1. **Database Explorer → + → Driver**. Name `Cloudflare D1`.
2. *Driver Files*: **+ → Custom JARs…** → the downloaded `d1-jdbc.jar`. Class `com.dashnex.d1.jdbc.D1Driver`.
3. *URL templates*: add `default` = `jdbc:d1://{database}`. *Options → Dialect*: **SQLite**.
4. **+ → Data Source → Cloudflare D1**: Authentication *User & Password*, User = account ID,
   Password = API token, Database = name or UUID. **Test Connection**.
5. In the data source's **Options** tab, enable **Introspect using JDBC metadata** (DataGrip's native
   SQLite introspector queries D1-internal tables that D1 rejects).

## Limitations

- **No transactions.** The D1 REST API commits every statement immediately. `BEGIN`/`COMMIT`/`ROLLBACK`
  are accepted and ignored (with a warning); keep the IDE in auto-commit mode. JDBC batches run atomically.
- Integers larger than 2^53 lose precision (D1 returns JSON numbers).
- Dates are stored as text/integers exactly as SQLite does; no time-zone conversion.
- `meta.changes` (update counts) for a DELETE that cascades via `ON DELETE CASCADE` include the cascaded rows (D1 behaviour).
- D1 limits apply (e.g. 100 KB per SQL statement, 100 columns per table).
- **Column type changes / table rebuilds are not atomic.** D1 always enforces foreign keys
  (`PRAGMA foreign_keys=OFF` has no effect), so an IDE-generated table rebuild that drops and recreates a
  parent table can fire `ON DELETE CASCADE` or fail midway. Prefer `ALTER TABLE … ADD/DROP/RENAME COLUMN`;
  for rebuilds, run the script yourself as one statement batch beginning with `PRAGMA defer_foreign_keys = on`.
- `Statement.setMaxRows` is pushed down as a `LIMIT` on the query sent to D1 (for a single SELECT/VALUES),
  not just applied after downloading the full result.

## Development

```bash
./gradlew test                 # unit tests (no network)
./gradlew integrationTest      # real D1; needs D1_ACCOUNT_ID, D1_TOKEN, D1_DATABASE (env or .env)
```

## Releasing

Push a version tag; the [Release workflow](.github/workflows/release.yml) builds and tests the jar and
publishes a GitHub Release with the jar attached:

```bash
git tag v0.1.0
git push origin v0.1.0
```

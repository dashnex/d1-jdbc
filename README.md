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
- `meta.changes` (update counts) for a DELETE that cascades via `ON DELETE CASCADE` include the cascaded rows (D1 behaviour).
- D1 limits apply (e.g. 100 KB per SQL statement, 100 columns per table).

## Development

```bash
./gradlew test                 # unit tests (no network)
./gradlew integrationTest      # real D1; needs D1_ACCOUNT_ID, D1_TOKEN, D1_DATABASE (env or .env)
```

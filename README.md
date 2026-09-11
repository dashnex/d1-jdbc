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

## What you need

1. **The driver jar** — download [d1-jdbc.jar](https://github.com/dashnex/d1-jdbc/releases/latest/download/d1-jdbc.jar)
   and keep it somewhere permanent (e.g. `~/jdbc-drivers/d1-jdbc.jar`); the IDE loads it from that path.
2. **Your Cloudflare account ID** — Cloudflare dashboard → **Workers & Pages** → *Account details* in the
   right sidebar (a 32-character hex string).
3. **A Cloudflare API token** — **My Profile → API Tokens → Create Token → Create Custom Token**,
   permission **Account → D1 → Edit**, account resource = your account. Copy the token once shown.
4. **The database name or UUID** — **Workers & Pages → D1 SQL Database**, or `npx wrangler d1 list`.

## Connection settings (reference)

| Field | Value |
|---|---|
| Driver class | `com.dashnex.d1.jdbc.D1Driver` |
| URL | `jdbc:d1://<database name or UUID>` |
| User | Cloudflare account ID |
| Password | Cloudflare API token |

Optional driver properties: `apiBase` (default `https://api.cloudflare.com/client/v4`),
`timeoutSeconds` (default `30`). They can also be appended to the URL: `jdbc:d1://my-db?timeoutSeconds=60`.

## Add the driver to DBeaver

Tested with DBeaver 24+ (Community or PRO).

**1. Register the driver (once)**

1. Open **Database → Driver Manager** and click **New**.
2. On the **Settings** tab fill in:

   | Field | Value |
   |---|---|
   | Driver Name | `Cloudflare D1` |
   | Driver Type | `Generic` |
   | Class Name | `com.dashnex.d1.jdbc.D1Driver` |
   | URL Template | `jdbc:d1://{database}` |
   | Default Port | *(leave empty)* |
   | No authentication | *(unchecked)* |

3. On the **Libraries** tab click **Add File**, pick `d1-jdbc.jar`, then click **Find Class** and confirm
   `com.dashnex.d1.jdbc.D1Driver` is selected.
4. Click **OK** to save the driver.

**2. Create a connection**

1. **Database → New Database Connection**, type `Cloudflare D1` in the search box, select it, **Next**.
2. On the **Main** tab:
   - **Database**: your D1 database name (e.g. `my-db`) or its UUID
   - **Username**: your Cloudflare account ID
   - **Password**: your API token (tick *Save password* to store it in DBeaver's secure storage)
3. Click **Test Connection** — you should see *Connected*. Then **Finish**.
4. Expand the connection: tables and views appear directly under it. Open a table to see
   **Columns**, **Foreign Keys** and **Indexes**, or switch to the **ER Diagram** tab.

**3. Recommended settings**

- Keep **auto-commit** on (the default). D1 has no transactions; every change is saved immediately.
- To edit data: open a table's **Data** tab, edit cells or add/delete rows, then **Save** (or `Ctrl/Cmd+S`).
- Tip: if you prefer DBeaver's SQLite-specific editors, copy the built-in driver instead of step 1
  (**Driver Manager → SQLite → Copy**), replace its library with `d1-jdbc.jar`, and set the class name and
  URL template as above.

## Add the driver to DataGrip

Tested with DataGrip 2024.x+ (also works in IntelliJ IDEA Ultimate's Database tool window).

**1. Register the driver (once)**

1. Open the **Database** tool window, click **+ → Driver** (or **+ → Data Source → Driver** in older versions).
2. **Name**: `Cloudflare D1`.
3. **Driver Files**: click **+ → Custom JARs…** and select `d1-jdbc.jar`.
4. **Class**: choose `com.dashnex.d1.jdbc.D1Driver` from the dropdown.
5. **URL templates**: click **+**, name `default`, template `jdbc:d1://{database}`.
6. **Options** tab → **Dialect**: `SQLite`. Click **OK** / **Apply**.

**2. Create a data source**

1. **+ → Data Source → Cloudflare D1**.
2. **Authentication**: `User & Password`.
   - **User**: your Cloudflare account ID
   - **Password**: your API token
   - **Database**: your D1 database name or UUID (the URL preview should read `jdbc:d1://<database>`)
3. Open the data source's **Options** tab and enable **Introspect using JDBC metadata**. This is required:
   DataGrip's built-in SQLite introspector queries D1-internal tables that D1 refuses.
4. Click **Test Connection**, then **OK**. The schema tree shows tables, columns, keys and indexes;
   right-click a table → **Diagrams → Show Diagram** for the ER view.

**3. Recommended settings**

- Leave the transaction mode on **Auto** commit (toolbar *Tx* dropdown). Changes are saved immediately.
- Edit data by opening a table (double-click), changing cells, then **Submit** (`Ctrl/Cmd+Enter`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| *Cloudflare rejected the API token* | Token lacks **Account → D1 → Edit**, is for another account, or was revoked. |
| *D1 database not found* | Check the database name/UUID and that the account ID matches the token's account. |
| *Missing Cloudflare account ID / API token* | Fill the User and Password fields (not only the URL). |
| DataGrip schema tree empty or `SQLITE_AUTH` errors | Enable **Introspect using JDBC metadata** in the data source's Options tab. |
| Driver class not found | Re-add `d1-jdbc.jar` in the driver's library list; make sure the file still exists at that path. |

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

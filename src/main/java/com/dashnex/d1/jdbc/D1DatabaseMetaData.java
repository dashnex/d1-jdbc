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
    static final String COLUMNS_SQL = "SELECT m.name, p.cid, p.name, p.type, p.\"notnull\", p.dflt_value, p.pk, (m.sql LIKE '%WITHOUT ROWID%') AS without_rowid "
            + "FROM sqlite_master m JOIN pragma_table_info(m.name) p WHERE m.type IN ('table','view') AND "
            + USER_TABLES + " ORDER BY m.name, p.cid";
    /** Same projection as COLUMNS_SQL but restricted to one table, so a broken view elsewhere can't fail this query. */
    static final String COLUMNS_FOR_TABLE_SQL = "SELECT m.name, p.cid, p.name, p.type, p.\"notnull\", p.dflt_value, p.pk, (m.sql LIKE '%WITHOUT ROWID%') AS without_rowid "
            + "FROM sqlite_master m JOIN pragma_table_info(m.name) p WHERE m.type IN ('table','view') AND "
            + USER_TABLES + " AND m.name = ? COLLATE NOCASE ORDER BY p.cid";
    /** Per-table fallback used when COLUMNS_SQL itself fails (e.g. a broken view): queries pragma_table_info
     * directly for one already-known-good table name, skipping the sqlite_master join entirely. */
    static final String COLUMNS_ONE_TABLE_FALLBACK_SQL =
            "SELECT ?, cid, name, type, \"notnull\", dflt_value, pk, "
                    + "(SELECT sql LIKE '%WITHOUT ROWID%' FROM sqlite_master WHERE name = ?) FROM pragma_table_info(?)";
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
        final boolean withoutRowid;

        ColumnRow(Object[] r) {
            table = (String) r[0];
            cid = (int) D1Values.toLong(r[1]);
            name = (String) r[2];
            type = r[3] == null ? "" : ((String) r[3]).trim();
            notNull = D1Values.toLong(r[4]) != 0;
            defaultValue = D1Values.toStr(r[5]);
            pk = (int) D1Values.toLong(r[6]);
            withoutRowid = D1Values.toLong(r[7]) != 0;
        }
    }

    /** All tables' columns. Falls back to one query per table (skipping any that fail, e.g. a view
     * referencing a dropped table/column) if the joined all-tables query itself fails. */
    private List<ColumnRow> columnRows() throws SQLException {
        try {
            List<ColumnRow> out = new ArrayList<>();
            for (Object[] r : query(COLUMNS_SQL)) out.add(new ColumnRow(r));
            return out;
        } catch (SQLException allTablesFailed) {
            List<ColumnRow> out = new ArrayList<>();
            for (Object[] t : query(TABLES_SQL)) {
                String table = (String) t[0];
                try {
                    out.addAll(columnRowsForOneTable(table));
                } catch (SQLException brokenTable) {
                    // skip only this table; keep the rest of the schema browsable
                }
            }
            return out;
        }
    }

    /** One table's columns, filtered/matched against {@code tableNamePattern}. When the pattern carries no
     * unescaped '%' it is tried first as an exact (COLLATE NOCASE) table name in a single request — the
     * common case for IDEs, which pass literal names (occasionally with an unescaped '_') rather than LIKE
     * patterns. Falls back to the all-tables scan (with its own per-table fallback) otherwise. */
    private List<ColumnRow> columnRows(String tableNamePattern) throws SQLException {
        if (tableNamePattern != null && !hasUnescapedPercent(tableNamePattern)) {
            String exactName = unescapePattern(tableNamePattern);
            List<ColumnRow> viaSingleTable;
            try {
                viaSingleTable = new ArrayList<>();
                for (Object[] r : query(COLUMNS_FOR_TABLE_SQL, exactName)) viaSingleTable.add(new ColumnRow(r));
            } catch (SQLException singleTableFailed) {
                try {
                    viaSingleTable = columnRowsForOneTable(exactName);
                } catch (SQLException stillFailed) {
                    viaSingleTable = List.of();
                }
            }
            List<ColumnRow> filtered = new ArrayList<>();
            for (ColumnRow c : viaSingleTable) {
                if (SqlText.matchesPattern(tableNamePattern, c.table)) filtered.add(c);
            }
            if (!filtered.isEmpty()) return filtered;
        }
        return columnRows();
    }

    private List<ColumnRow> columnRowsForOneTable(String table) throws SQLException {
        List<ColumnRow> out = new ArrayList<>();
        for (Object[] r : query(COLUMNS_ONE_TABLE_FALLBACK_SQL, table, table, table)) out.add(new ColumnRow(r));
        return out;
    }

    private static boolean hasUnescapedPercent(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                i++;
            } else if (c == '%') {
                return true;
            }
        }
        return false;
    }

    private static String unescapePattern(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                sb.append(pattern.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
                && c.type.equalsIgnoreCase("INTEGER") && !c.withoutRowid;
    }

    private static Integer sizePart(String declared, int group) {
        Matcher m = SIZE.matcher(declared);
        if (!m.find() || m.group(group) == null) return null;
        return Integer.valueOf(m.group(group));
    }

    private static String typeName(ColumnRow c, int jdbcType) {
        return c.type.isEmpty() ? D1Types.typeName(jdbcType) : D1Types.stripSize(c.type).toUpperCase(Locale.ROOT);
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
        List<ColumnRow> all = columnRows(tableNamePattern);
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
        for (ColumnRow c : columnRows(table)) {
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
        for (ColumnRow c : columnRows(table)) {
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

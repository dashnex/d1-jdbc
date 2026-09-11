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

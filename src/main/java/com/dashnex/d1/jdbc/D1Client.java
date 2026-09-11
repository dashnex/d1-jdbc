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
import java.net.http.HttpTimeoutException;
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

    /** Sends one SELECT 1 bounded by {@code timeoutSeconds}, with no retry regardless of the failure kind;
     * used by {@link D1Connection#isValid(int)} so a slow/hung connection is reported within the caller's
     * own timeout instead of after up to three retries of the (much larger) configured request timeout. */
    void ping(int timeoutSeconds) throws SQLException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("sql", "SELECT 1");
        body.set("params", encodeParams(List.of()));
        send(rawRequest(body, timeoutSeconds), true, false);
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
        return rawRequest(body, config.getTimeoutSeconds());
    }

    private HttpRequest rawRequest(ObjectNode body, int timeoutSeconds) throws SQLException {
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot encode D1 request: " + e.getMessage(), "HY000", e);
        }
        URI uri = URI.create(accountUrl() + "/d1/database/" + encode(databaseId()) + "/raw");
        return baseRequest(uri, timeoutSeconds)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return baseRequest(uri, config.getTimeoutSeconds());
    }

    private HttpRequest.Builder baseRequest(URI uri, int timeoutSeconds) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + config.getToken())
                .header("Accept", "application/json");
    }

    private String accountUrl() {
        return config.getApiBase() + "/accounts/" + encode(config.getAccountId());
    }

    private JsonNode send(HttpRequest request, boolean retryable) throws SQLException {
        return send(request, retryable, true);
    }

    private JsonNode send(HttpRequest request, boolean retryable, boolean allowRetry) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            boolean canRetry = allowRetry && attempt < backoffMillis.length;
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (canRetry && shouldRetryIo(e, retryable)) {
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

    /** Whether an I/O failure should be retried. A timeout (request or connect) is never retried — the
     * caller already waited the full configured timeout once, so retrying would multiply that wait for a
     * possibly-just-slow server. A refused/dropped connection is retried unconditionally, since a write that
     * never reached the server is safe to resend. Any other I/O error is retried only for read-only SQL, so
     * a write is never silently applied twice. */
    static boolean shouldRetryIo(IOException e, boolean readOnly) {
        if (e instanceof HttpTimeoutException) return false;
        if (e instanceof ConnectException) return true;
        return readOnly;
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

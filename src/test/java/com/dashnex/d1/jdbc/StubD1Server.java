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

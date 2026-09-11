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
        if (code == 7500) {
            // A SQL error (D1's generic SQL-error code) — classify by message even if the HTTP status
            // happens to be 401/403; it is not a rejected API token.
            return new SQLException(message, sqlState(message), code);
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

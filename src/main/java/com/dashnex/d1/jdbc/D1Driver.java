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

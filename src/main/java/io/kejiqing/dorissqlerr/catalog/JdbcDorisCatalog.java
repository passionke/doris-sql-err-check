package io.kejiqing.dorissqlerr.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Read-only Doris catalog via MySQL protocol ({@code DESC db.table}).
 * Author: kejiqing
 */
public final class JdbcDorisCatalog implements Catalog, AutoCloseable {
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final Map<String, RelationSchema> cache = new LinkedHashMap<>();
    private Connection shared;

    public JdbcDorisCatalog(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    public static JdbcDorisCatalog fromEnv() {
        String url = firstNonBlank(System.getenv("DORIS_JDBC_URL"), System.getenv("JDBC_URL"));
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DORIS_JDBC_URL (or JDBC_URL) is required");
        }
        String user = firstNonBlank(System.getenv("DORIS_USER"), System.getenv("JDBC_USER"), "root");
        String pass = firstNonBlank(System.getenv("DORIS_PASSWORD"), System.getenv("JDBC_PASSWORD"), "");
        return new JdbcDorisCatalog(url, user, pass);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Connection conn() throws SQLException {
        if (shared == null || shared.isClosed()) {
            Properties p = new Properties();
            p.setProperty("user", user);
            p.setProperty("password", password);
            p.setProperty("useSSL", "false");
            p.setProperty("allowPublicKeyRetrieval", "true");
            shared = DriverManager.getConnection(jdbcUrl, p);
        }
        return shared;
    }

    @Override
    public Optional<RelationSchema> describe(String database, String table) {
        String db = RelationSchema.strip(database);
        String tb = RelationSchema.strip(table);
        if (tb.isEmpty()) {
            return Optional.empty();
        }
        String cacheKey = db.toLowerCase(Locale.ROOT) + "." + tb.toLowerCase(Locale.ROOT);
        if (cache.containsKey(cacheKey)) {
            return Optional.ofNullable(cache.get(cacheKey));
        }
        String sql;
        if (db.isEmpty()) {
            sql = "DESC `" + tb.replace("`", "``") + "`";
        } else {
            sql = "DESC `" + db.replace("`", "``") + "`.`" + tb.replace("`", "``") + "`";
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            Set<String> cols = new LinkedHashSet<>();
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
            RelationSchema schema = new RelationSchema(db, tb, cols);
            cache.put(cacheKey, schema);
            return Optional.of(schema);
        } catch (SQLException e) {
            cache.put(cacheKey, null);
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (shared != null) {
            try {
                shared.close();
            } catch (SQLException ignored) {
            }
            shared = null;
        }
    }
}

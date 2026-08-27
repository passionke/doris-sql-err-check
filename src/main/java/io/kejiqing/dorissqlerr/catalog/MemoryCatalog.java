package io.kejiqing.dorissqlerr.catalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory catalog for tests and offline fixtures.
 * Author: kejiqing
 */
public final class MemoryCatalog implements Catalog {
    private final Map<String, RelationSchema> tables = new LinkedHashMap<>();

    private static String key(String database, String table) {
        String db = RelationSchema.strip(database).toLowerCase(Locale.ROOT);
        String tb = RelationSchema.strip(table).toLowerCase(Locale.ROOT);
        return db + "." + tb;
    }

    @Override
    public void put(String database, String table, Set<String> columns) {
        tables.put(key(database, table), new RelationSchema(database, table, columns));
    }

    public MemoryCatalog with(String database, String table, String... columns) {
        put(database, table, Set.of(columns));
        return this;
    }

    @Override
    public Optional<RelationSchema> describe(String database, String table) {
        String k = key(database, table);
        RelationSchema s = tables.get(k);
        if (s != null) {
            return Optional.of(s);
        }
        // fallback: match by table name only
        String tb = RelationSchema.strip(table).toLowerCase(Locale.ROOT);
        for (RelationSchema r : tables.values()) {
            if (r.name.toLowerCase(Locale.ROOT).equals(tb)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}

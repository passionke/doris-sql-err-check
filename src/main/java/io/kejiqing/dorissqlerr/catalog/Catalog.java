package io.kejiqing.dorissqlerr.catalog;

import java.util.Optional;
import java.util.Set;

/**
 * Read-only catalog for bind. Implementations must not mutate the cluster.
 * Author: kejiqing
 */
public interface Catalog {
    /**
     * Resolve base table columns. {@code database} may be empty → use binder default DB.
     */
    Optional<RelationSchema> describe(String database, String table);

    /** Optional helper for tests / preloaded catalogs. */
    default void put(String database, String table, Set<String> columns) {
        throw new UnsupportedOperationException("put not supported");
    }
}

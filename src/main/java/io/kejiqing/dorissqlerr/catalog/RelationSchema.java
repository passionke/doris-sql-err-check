package io.kejiqing.dorissqlerr.catalog;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * One base table / CTE / subquery projection schema.
 * Author: kejiqing
 */
public final class RelationSchema {
    public final String database; // may be empty
    public final String name;     // table or CTE name
    public final Set<String> columns; // lower-case

    public RelationSchema(String database, String name, Set<String> columns) {
        this.database = database == null ? "" : database;
        this.name = name == null ? "" : name;
        this.columns = new LinkedHashSet<>();
        if (columns != null) {
            for (String c : columns) {
                if (c != null && !c.isBlank()) {
                    this.columns.add(strip(c).toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    public boolean hasColumn(String col) {
        return columns.contains(strip(col).toLowerCase(Locale.ROOT));
    }

    public static String strip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("`") && t.endsWith("`")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    public String displayName() {
        if (database.isEmpty()) {
            return name;
        }
        return database + "." + name;
    }

    public Optional<String> suggestSimilar(String col) {
        String target = strip(col).toLowerCase(Locale.ROOT);
        for (String c : columns) {
            if (c.contains(target) || target.contains(c)) {
                return Optional.of(c);
            }
        }
        return columns.stream().findFirst();
    }
}

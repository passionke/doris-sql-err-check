package io.kejiqing.dorissqlerr.classify;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse FE unknown-column error into column + bind scope (+ optional clause).
 * Author: kejiqing
 */
public final class UnknownColumnRef {
    /** Newer Nereids BindExpression: Unknown column 'c' in 'T0' in FILTER clause */
    private static final Pattern P2 = Pattern.compile(
            "Unknown column '([^']+)' in '([^']*)' in ([A-Za-z0-9_]+) clause",
            Pattern.CASE_INSENSITIVE);

    public final String column;
    /** FE second field: {@code table list}, alias {@code T0}, or {@code db.alias}. */
    public final String scope;
    /** Optional LOGICAL_* clause suffix without LOGICAL_ prefix, e.g. FILTER / PROJECT. */
    public final Optional<String> planClause;

    public UnknownColumnRef(String column, String scope, Optional<String> planClause) {
        this.column = column;
        this.scope = scope == null ? "" : scope;
        this.planClause = planClause == null ? Optional.empty() : planClause;
    }

    public boolean isTableList() {
        return scope.isEmpty() || "table list".equalsIgnoreCase(scope);
    }

    /** Last segment of scope for alias match: {@code gposv2.alias_0} → {@code alias_0}. */
    public String scopeAlias() {
        if (scope.isEmpty()) {
            return "";
        }
        int dot = scope.lastIndexOf('.');
        return dot < 0 ? scope : scope.substring(dot + 1);
    }

    public static Optional<UnknownColumnRef> parse(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return Optional.empty();
        }
        Matcher m2 = P2.matcher(errorMessage);
        if (m2.find()) {
            return Optional.of(new UnknownColumnRef(m2.group(1), m2.group(2),
                    Optional.of(m2.group(3).toUpperCase(Locale.ROOT))));
        }
        Matcher m = Pattern.compile(
                "Unknown column '([^']+)' in '([^']*)'", Pattern.CASE_INSENSITIVE)
                .matcher(errorMessage);
        if (m.find()) {
            return Optional.of(new UnknownColumnRef(m.group(1), m.group(2), Optional.empty()));
        }
        Matcher bare = Pattern.compile(
                "Unknown column '([^']+)'", Pattern.CASE_INSENSITIVE).matcher(errorMessage);
        if (bare.find()) {
            return Optional.of(new UnknownColumnRef(bare.group(1), "table list", Optional.empty()));
        }
        return Optional.empty();
    }
}

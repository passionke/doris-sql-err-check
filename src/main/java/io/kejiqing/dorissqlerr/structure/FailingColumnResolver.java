package io.kejiqing.dorissqlerr.structure;

import io.kejiqing.dorissqlerr.classify.UnknownColumnRef;
import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pick the single failing column reference from Origin hits using FE bind scope.
 * Author: kejiqing
 *
 * <p>FE already decided the column is unknown; this resolves <em>which occurrence</em>
 * in the SQL text that decision refers to.
 */
public final class FailingColumnResolver {

    private FailingColumnResolver() {
    }

    public static Optional<DiagnosisReport.IdentifierCandidate> resolve(
            UnknownColumnRef ref, List<DiagnosisReport.IdentifierCandidate> hits) {
        if (ref == null || hits == null || hits.isEmpty()) {
            return Optional.empty();
        }
        List<DiagnosisReport.IdentifierCandidate> pool = new ArrayList<>(hits);

        if (!ref.isTableList()) {
            String alias = ref.scopeAlias().toLowerCase(Locale.ROOT);
            String full = ref.scope.toLowerCase(Locale.ROOT);
            List<DiagnosisReport.IdentifierCandidate> qualified = new ArrayList<>();
            for (DiagnosisReport.IdentifierCandidate h : pool) {
                if (h.qualifier != null && !h.qualifier.isEmpty()) {
                    String q = h.qualifier.toLowerCase(Locale.ROOT);
                    if (q.equals(alias) || q.equals(full) || full.endsWith("." + q)) {
                        qualified.add(h);
                    }
                }
            }
            if (!qualified.isEmpty()) {
                pool = qualified;
            } else {
                // Bare name under a FROM alias matching scope — keep bare only.
                List<DiagnosisReport.IdentifierCandidate> bare = new ArrayList<>();
                for (DiagnosisReport.IdentifierCandidate h : pool) {
                    if (h.qualifier == null || h.qualifier.isEmpty()) {
                        bare.add(h);
                    }
                }
                if (!bare.isEmpty()) {
                    pool = bare;
                }
            }
        } else {
            // table list = unbound without qualifier in FE
            List<DiagnosisReport.IdentifierCandidate> bare = new ArrayList<>();
            for (DiagnosisReport.IdentifierCandidate h : pool) {
                if (h.qualifier == null || h.qualifier.isEmpty()) {
                    bare.add(h);
                }
            }
            if (!bare.isEmpty()) {
                pool = bare;
            }
        }

        if (ref.planClause.isPresent()) {
            String want = mapPlanClauseToSqlClause(ref.planClause.get());
            if (want != null) {
                List<DiagnosisReport.IdentifierCandidate> byClause = new ArrayList<>();
                for (DiagnosisReport.IdentifierCandidate h : pool) {
                    if (want.equalsIgnoreCase(h.clause)) {
                        byClause.add(h);
                    }
                }
                if (!byClause.isEmpty()) {
                    pool = byClause;
                }
            }
        }

        pool.sort(Comparator
                .comparingInt((DiagnosisReport.IdentifierCandidate h) -> clauseBindRank(h.clause))
                .thenComparingInt(h -> h.startOffset));

        DiagnosisReport.IdentifierCandidate winner = pool.get(0);
        winner.failed = true;
        return Optional.of(winner);
    }

    /**
     * Nereids typically binds Filter before Project for SELECT…WHERE…,
     * so WHERE ranks ahead of SELECT when FE only says {@code table list}.
     */
    private static int clauseBindRank(String clause) {
        if (clause == null) {
            return 50;
        }
        return switch (clause.toUpperCase(Locale.ROOT)) {
            case "JOIN" -> 10;
            case "WHERE" -> 20;
            case "GROUP BY" -> 30;
            case "HAVING" -> 40;
            case "SELECT" -> 50;
            case "ORDER BY" -> 60;
            case "LIMIT" -> 70;
            default -> 55;
        };
    }

    private static String mapPlanClauseToSqlClause(String planClause) {
        return switch (planClause.toUpperCase(Locale.ROOT)) {
            case "FILTER" -> "WHERE";
            case "PROJECT", "RESULT_SINK" -> "SELECT";
            case "AGGREGATE", "AGGREGATE_AGG" -> "GROUP BY";
            case "SORT", "TOP_N" -> "ORDER BY";
            case "JOIN", "HASH_JOIN", "NESTED_LOOP_JOIN" -> "JOIN";
            default -> null;
        };
    }
}

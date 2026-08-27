package io.kejiqing.dorissqlerr.diagnose;

import io.kejiqing.dorissqlerr.bind.CatalogBinder;
import io.kejiqing.dorissqlerr.catalog.Catalog;
import io.kejiqing.dorissqlerr.classify.ErrorClassifier;
import io.kejiqing.dorissqlerr.classify.UnknownColumnRef;
import io.kejiqing.dorissqlerr.location.LocationExtractor;
import io.kejiqing.dorissqlerr.structure.FailingColumnResolver;
import io.kejiqing.dorissqlerr.structure.StructureMapper;
import org.apache.doris.nereids.exceptions.ParseException;
import org.apache.doris.nereids.parser.DorisSqlParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrate FE error enhance + sidecar Origin parse + optional read-only catalog bind.
 * Author: kejiqing
 */
public class Diagnoser {

    public static final class Options {
        public Catalog catalog;
        public String defaultDatabase = "";

        public static Options none() {
            return new Options();
        }

        public static Options withCatalog(Catalog catalog, String defaultDatabase) {
            Options o = new Options();
            o.catalog = catalog;
            o.defaultDatabase = defaultDatabase == null ? "" : defaultDatabase;
            return o;
        }
    }

    public DiagnosisReport diagnose(String sql, String errorMessage) {
        return diagnose(sql, errorMessage, Options.none());
    }

    public DiagnosisReport diagnose(String sql, String errorMessage, Options options) {
        if (options == null) {
            options = Options.none();
        }
        DiagnosisReport report = new DiagnosisReport();
        report.evidence.rawError = errorMessage == null ? "" : errorMessage;
        report.category = ErrorClassifier.classify(errorMessage);
        String safeSql = sql == null ? "" : sql;

        if (report.category == ErrorCategory.RUNTIME) {
            report.confidence = "high";
            report.enhancedMessage = "Runtime error (not a SQL structure issue): "
                    + trim(errorMessage, 200);
            return report;
        }

        LocationExtractor.extract(safeSql, errorMessage).ifPresent(loc -> {
            report.location = loc;
            report.confidence = "medium";
            report.evidence.matchedPattern = "fe-error-location";
        });

        boolean sidecarParseFailed = false;
        try {
            new DorisSqlParser().parseSingleStatement(safeSql);
            report.evidence.matchedPattern = join(report.evidence.matchedPattern, "sidecar-parse-ok");
        } catch (ParseException pe) {
            sidecarParseFailed = true;
            report.category = ErrorCategory.PARSE;
            report.evidence.sidecarParseError = pe.getMessage();
            report.enhancedMessage = pe.getMessage();
            report.evidence.matchedPattern = join(report.evidence.matchedPattern, "sidecar-parse-origin");
            pe.getStart().line.ifPresent(line -> {
                int col = pe.getStart().startPosition.orElse(0);
                report.location = LocationExtractor.buildLocation(safeSql, line, col);
            });
            report.confidence = "high";
        } catch (Exception e) {
            report.evidence.sidecarParseError = String.valueOf(e.getMessage());
        }

        StructureMapper.enrich(safeSql, report);

        boolean catalogResolved = false;
        if (!sidecarParseFailed && options.catalog != null) {
            catalogResolved = applyCatalogBind(safeSql, errorMessage, options, report);
        }

        if (!catalogResolved && report.category == ErrorCategory.ANALYSIS && !sidecarParseFailed) {
            applyFeScopeFallback(safeSql, errorMessage, report);
        }

        if (report.enhancedMessage == null || report.enhancedMessage.isEmpty()) {
            report.enhancedMessage = buildFallbackEnhanced(report);
        }
        return report;
    }

    /**
     * Catalog bind is authoritative for unknown-column when DESC schemas are available.
     */
    private boolean applyCatalogBind(String sql, String errorMessage, Options options,
            DiagnosisReport report) {
        CatalogBinder.BindResult br = new CatalogBinder(options.catalog, options.defaultDatabase).bind(sql);
        report.evidence.bindMode = "catalog-bind";
        report.evidence.loadedRelations = new ArrayList<>(br.loadedRelations);
        report.evidence.missingBaseTables = new ArrayList<>(br.missingBaseTables);
        Set<String> avail = new LinkedHashSet<>();
        for (Set<String> cols : br.outermostScope.values()) {
            avail.addAll(cols);
        }
        report.evidence.availableColumns = new ArrayList<>(avail);
        report.evidence.matchedPattern = join(report.evidence.matchedPattern, br.note);

        if (br.unbound.isEmpty()) {
            Optional<UnknownColumnRef> uref = UnknownColumnRef.parse(errorMessage);
            if (uref.isPresent()) {
                report.evidence.matchedPattern = join(report.evidence.matchedPattern,
                        "fe-catalog-conflict");
                report.enhancedMessage = "FE said unknown column '" + uref.get().column
                        + "' but read-only catalog bind found no unbound reference. "
                        + "availableColumns=" + avail;
                report.confidence = "medium";
                report.category = ErrorCategory.ANALYSIS;
                return true;
            }
            return false;
        }

        report.category = ErrorCategory.ANALYSIS;
        report.identifierHits = new ArrayList<>(br.unbound);
        report.identifierCandidates = new ArrayList<>();
        for (DiagnosisReport.IdentifierCandidate u : br.unbound) {
            report.identifierCandidates.add(u.toSummary());
        }

        // Prefer FE-named column among unbound; else first by bind order.
        Optional<UnknownColumnRef> uref = UnknownColumnRef.parse(errorMessage);
        DiagnosisReport.IdentifierCandidate failed = br.unbound.get(0);
        if (uref.isPresent()) {
            String want = uref.get().column.toLowerCase(Locale.ROOT);
            for (DiagnosisReport.IdentifierCandidate u : br.unbound) {
                if (u.name != null && u.name.replace("`", "").equalsIgnoreCase(want)) {
                    failed = u;
                    break;
                }
            }
        }
        failed.failed = true;
        report.failedHit = Optional.of(failed);
        report.confidence = "high";
        report.evidence.matchedPattern = join(report.evidence.matchedPattern, "catalog-unbound");
        report.location = LocationExtractor.buildLocation(
                sql, failed.line == null ? 1 : failed.line, failed.column == null ? 0 : failed.column);
        DiagnosisReport.IdentifierHit nearest = new DiagnosisReport.IdentifierHit();
        nearest.name = failed.qualifiedName();
        nearest.kind = "column";
        nearest.startOffset = failed.startOffset;
        nearest.stopOffset = failed.stopOffset;
        report.structure.nearestIdentifier = Optional.of(nearest);
        if (failed.clause != null && !failed.clause.isEmpty()) {
            report.structure.clause = Optional.of(failed.clause);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Catalog bind: unbound column ").append(failed.qualifiedName())
                .append(" @ line ").append(failed.line).append(", col ").append(failed.column);
        if (failed.clause != null && !failed.clause.isEmpty()) {
            sb.append(" (").append(failed.clause).append(")");
        }
        sb.append("\n");
        sb.append("availableColumns: ").append(avail).append("\n");
        if (br.unbound.size() > 1) {
            sb.append("otherUnbound: ").append(br.unbound.size() - 1).append("\n");
        }
        if (uref.isPresent()) {
            sb.append("feScope: '").append(uref.get().scope).append("' (corroboration only)");
        }
        report.enhancedMessage = sb.toString().trim();
        return true;
    }

    private void applyFeScopeFallback(String sql, String errorMessage, DiagnosisReport report) {
        Optional<UnknownColumnRef> uref = UnknownColumnRef.parse(errorMessage);
        if (uref.isEmpty()) {
            return;
        }
        UnknownColumnRef ref = uref.get();
        report.evidence.bindMode = "fe-scope";
        report.identifierHits = StructureMapper.resolveIdentifierHits(sql, ref.column);
        report.identifierCandidates = new ArrayList<>();
        for (DiagnosisReport.IdentifierCandidate hit : report.identifierHits) {
            report.identifierCandidates.add(hit.toSummary());
        }
        report.evidence.matchedPattern = join(report.evidence.matchedPattern,
                "unknown-column-origin-hits");

        Optional<DiagnosisReport.IdentifierCandidate> failed =
                FailingColumnResolver.resolve(ref, report.identifierHits);
        report.failedHit = failed;
        if (failed.isPresent()) {
            DiagnosisReport.IdentifierCandidate f = failed.get();
            report.confidence = "high";
            report.evidence.matchedPattern = join(report.evidence.matchedPattern,
                    "failed-column-resolved");
            report.location = LocationExtractor.buildLocation(
                    sql, f.line == null ? 1 : f.line, f.column == null ? 0 : f.column);
            DiagnosisReport.IdentifierHit nearest = new DiagnosisReport.IdentifierHit();
            nearest.name = f.qualifiedName();
            nearest.kind = "column";
            nearest.startOffset = f.startOffset;
            nearest.stopOffset = f.stopOffset;
            report.structure.nearestIdentifier = Optional.of(nearest);
            if (f.clause != null && !f.clause.isEmpty()) {
                report.structure.clause = Optional.of(f.clause);
            }
            if (f.subqueryDepth != null) {
                DiagnosisReport.Subquery sq = new DiagnosisReport.Subquery();
                sq.depth = f.subqueryDepth;
                sq.sqlSlice = f.subquerySlice == null ? "" : f.subquerySlice;
                sq.startOffset = f.startOffset;
                sq.stopOffset = f.stopOffset;
                report.structure.enclosingSubquery = Optional.of(sq);
            }
            report.enhancedMessage = "Failed column reference: " + f.qualifiedName()
                    + " @ line " + f.line + ", col " + f.column
                    + (f.clause == null || f.clause.isEmpty() ? "" : (" (" + f.clause + ")"))
                    + "\nFE scope: '" + ref.scope + "' (no catalog; FE-scope fallback)";
        } else if (!report.identifierHits.isEmpty()) {
            report.confidence = "medium";
            report.enhancedMessage = "Unknown column '" + ref.column + "' — "
                    + report.identifierHits.size() + " Origin hit(s) (no catalog)";
        }
    }

    private static String buildFallbackEnhanced(DiagnosisReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("category=").append(report.category);
        if (report.location != null && report.location.line != null) {
            sb.append(" @ line ").append(report.location.line)
                    .append(" col ").append(report.location.column);
        }
        report.structure.clause.ifPresent(c -> sb.append(" clause=").append(c));
        report.structure.enclosingSubquery.ifPresent(q -> sb.append(" subqueryDepth=").append(q.depth));
        return sb.toString();
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        return a + "," + b;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}

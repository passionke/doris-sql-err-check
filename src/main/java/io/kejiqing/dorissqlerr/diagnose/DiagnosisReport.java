package io.kejiqing.dorissqlerr.diagnose;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Structured diagnosis report.
 * Author: kejiqing
 */
public class DiagnosisReport {
    public ErrorCategory category = ErrorCategory.UNKNOWN;
    public String confidence = "low";
    public Location location;
    public Structure structure = new Structure();
    public Evidence evidence = new Evidence();
    public String enhancedMessage = "";
    /** Rich Origin-backed candidates (unknown column / ambiguous id). */
    public List<IdentifierCandidate> identifierHits = new ArrayList<>();
    /** The single failing column reference (FE scope + Origin), when resolved. */
    public Optional<IdentifierCandidate> failedHit = Optional.empty();
    /** Flat strings kept for JSON/CLI backward compatibility. */
    public List<String> identifierCandidates = new ArrayList<>();

    public static class Location {
        public Integer line;
        public Integer column;
        public Integer charOffset;
        public String snippet = "";
        public String caret = "";
    }

    public static class Structure {
        public Optional<Subquery> enclosingSubquery = Optional.empty();
        public Optional<String> clause = Optional.empty();
        public Optional<FunctionHit> nearestFunction = Optional.empty();
        public Optional<IdentifierHit> nearestIdentifier = Optional.empty();
    }

    public static class Subquery {
        public int depth;
        public String alias = "";
        public String sqlSlice = "";
        public int startOffset;
        public int stopOffset;
    }

    public static class FunctionHit {
        public String name;
        public Integer argIndex;
        public int startOffset;
    }

    public static class IdentifierHit {
        public String name;
        public String kind = "column";
        public int startOffset;
        public int stopOffset;
    }

    /**
     * One occurrence of a named identifier with Origin + structure doorplate.
     */
    public static class IdentifierCandidate {
        public String name;
        /** Qualifier before '.', e.g. {@code T0} for {@code T0.col}; empty if bare. */
        public String qualifier = "";
        public Integer line;
        public Integer column;
        public int startOffset;
        public int stopOffset;
        public String clause = "";
        public Integer subqueryDepth;
        public String subquerySlice = "";
        public String snippet = "";
        public String caret = "";
        /** True when this occurrence is the resolved failing reference. */
        public boolean failed;

        public String qualifiedName() {
            if (qualifier == null || qualifier.isEmpty()) {
                return name;
            }
            return qualifier + "." + name;
        }

        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            if (failed) {
                sb.append("[FAILED] ");
            }
            sb.append("line ").append(line).append(", col ").append(column)
                    .append(", offset ").append(startOffset).append("..").append(stopOffset);
            if (qualifier != null && !qualifier.isEmpty()) {
                sb.append(", qual=").append(qualifier);
            }
            if (clause != null && !clause.isEmpty()) {
                sb.append(", clause=").append(clause);
            }
            if (subqueryDepth != null) {
                sb.append(", subqueryDepth=").append(subqueryDepth);
            }
            sb.append(", text=").append(qualifiedName());
            return sb.toString();
        }
    }

    public static class Evidence {
        public String matchedPattern = "";
        public String rawError = "";
        public String sidecarParseError = "";
        /** catalog-bind | fe-scope | none */
        public String bindMode = "none";
        public List<String> availableColumns = new ArrayList<>();
        public List<String> loadedRelations = new ArrayList<>();
        public List<String> missingBaseTables = new ArrayList<>();
    }

    public String toHumanText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(category.name().toLowerCase()).append("] confidence=")
                .append(confidence).append("\n");
        if (location != null && location.line != null) {
            sb.append("location: line ").append(location.line);
            if (location.column != null) {
                sb.append(", col ").append(location.column);
            }
            if (location.charOffset != null) {
                sb.append(", offset ").append(location.charOffset);
            }
            sb.append("\n");
        }
        structure.enclosingSubquery.ifPresent(q ->
                sb.append("subquery: depth=").append(q.depth)
                        .append(q.alias.isEmpty() ? "" : (" alias=" + q.alias))
                        .append("\n")
                        .append("  slice: ").append(trimOneLine(q.sqlSlice, 160)).append("\n"));
        structure.clause.ifPresent(c -> sb.append("clause: ").append(c).append("\n"));
        structure.nearestFunction.ifPresent(f ->
                sb.append("function: ").append(f.name)
                        .append(f.argIndex == null ? "" : (" arg#" + f.argIndex))
                        .append("\n"));
        structure.nearestIdentifier.ifPresent(i ->
                sb.append("identifier: ").append(i.name).append(" (").append(i.kind).append(")\n"));
        failedHit.ifPresent(c -> {
            sb.append("failedColumn: ").append(c.qualifiedName())
                    .append(" @ line ").append(c.line).append(", col ").append(c.column)
                    .append(c.clause == null || c.clause.isEmpty() ? "" : (" clause=" + c.clause))
                    .append("\n");
            if (c.snippet != null && !c.snippet.isEmpty()) {
                sb.append(c.snippet).append("\n");
                if (c.caret != null && !c.caret.isEmpty()) {
                    sb.append(c.caret).append("\n");
                }
            }
        });
        if (!identifierHits.isEmpty()) {
            sb.append("identifierHits (").append(identifierHits.size()).append("):\n");
            for (IdentifierCandidate c : identifierHits) {
                sb.append("  - ").append(c.toSummary()).append("\n");
                if (c.subquerySlice != null && !c.subquerySlice.isEmpty()) {
                    sb.append("    subquery: ").append(trimOneLine(c.subquerySlice, 120)).append("\n");
                }
                if (!c.failed && c.snippet != null && !c.snippet.isEmpty()) {
                    sb.append("    ").append(trimOneLine(c.snippet, 100)).append("\n");
                    if (c.caret != null && !c.caret.isEmpty()) {
                        sb.append("    ").append(c.caret).append("\n");
                    }
                }
            }
        } else if (!identifierCandidates.isEmpty()) {
            sb.append("identifierCandidates:\n");
            for (String c : identifierCandidates) {
                sb.append("  - ").append(c).append("\n");
            }
        }
        if (location != null && location.snippet != null && !location.snippet.isEmpty()) {
            sb.append("snippet:\n").append(location.snippet).append("\n");
            if (location.caret != null && !location.caret.isEmpty()) {
                sb.append(location.caret).append("\n");
            }
        }
        if (enhancedMessage != null && !enhancedMessage.isEmpty()) {
            sb.append("enhanced:\n").append(enhancedMessage).append("\n");
        }
        if (!evidence.availableColumns.isEmpty()) {
            sb.append("availableColumns: ").append(evidence.availableColumns).append("\n");
        }
        if (!evidence.loadedRelations.isEmpty()) {
            sb.append("loadedRelations: ").append(trimOneLine(String.valueOf(evidence.loadedRelations), 200))
                    .append("\n");
        }
        if (!"none".equals(evidence.bindMode) && evidence.bindMode != null && !evidence.bindMode.isEmpty()) {
            sb.append("bindMode: ").append(evidence.bindMode).append("\n");
        }
        if (evidence.rawError != null && !evidence.rawError.isEmpty()) {
            sb.append("feError: ").append(trimOneLine(evidence.rawError, 240)).append("\n");
        }
        return sb.toString();
    }

    private static String trimOneLine(String s, int max) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "...";
    }
}

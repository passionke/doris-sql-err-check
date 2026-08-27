package io.kejiqing.dorissqlerr.structure;

import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;
import io.kejiqing.dorissqlerr.location.LocationExtractor;
import io.kejiqing.dorissqlerr.location.SqlOriginIndex;
import org.apache.doris.nereids.DorisParser;
import org.apache.doris.nereids.exceptions.ParseException;
import org.apache.doris.nereids.parser.DorisSqlParser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Map Origin char offsets onto subquery / clause / function using Doris lexer + parse tree.
 * Author: kejiqing
 */
public final class StructureMapper {

    private StructureMapper() {
    }

    public static void enrich(String sql, DiagnosisReport report) {
        if (sql == null || sql.isEmpty()) {
            return;
        }
        SqlOriginIndex index = SqlOriginIndex.build(sql);
        List<Token> tokens = index.tokens();
        Integer offset = report.location == null ? null : report.location.charOffset;
        if (offset != null) {
            report.structure.enclosingSubquery = findEnclosingSubquery(sql, tokens, offset);
            report.structure.clause = findClause(tokens, offset);
            report.structure.nearestFunction = findNearestFunction(tokens, offset);
            report.structure.nearestIdentifier = findNearestIdentifier(index, offset);
        }

        try {
            ParserRuleContext tree = new DorisSqlParser().parseSingleStatement(sql);
            if (report.location != null && report.location.charOffset != null) {
                annotateFromTree(sql, tree, report.location.charOffset, report);
            }
        } catch (ParseException pe) {
            report.evidence.sidecarParseError = pe.getMessage();
            // Prefer sidecar Origin over FE legacy location.
            pe.getStart().line.ifPresent(l -> {
                int col = pe.getStart().startPosition.orElse(0);
                report.location = LocationExtractor.buildLocation(sql, l, col);
                report.confidence = "high";
                report.enhancedMessage = pe.getMessage();
                Integer off = report.location.charOffset;
                report.structure.enclosingSubquery = findEnclosingSubquery(sql, tokens, off);
                report.structure.clause = findClause(tokens, off);
                report.structure.nearestFunction = findNearestFunction(tokens, off);
                report.structure.nearestIdentifier = findNearestIdentifier(index, off);
            });
        } catch (Exception ignored) {
            // keep token-based enrichment
        }
    }

    /**
     * Resolve unknown-column (or similar) name to every Origin-backed occurrence with doorplates.
     */
    public static List<DiagnosisReport.IdentifierCandidate> resolveIdentifierHits(String sql, String name) {
        List<DiagnosisReport.IdentifierCandidate> hits = new ArrayList<>();
        if (sql == null || name == null || name.isBlank()) {
            return hits;
        }
        SqlOriginIndex index = SqlOriginIndex.build(sql);
        List<Token> tokens = index.tokens();
        for (SqlOriginIndex.IdentOrigin id : index.findByName(name)) {
            DiagnosisReport.IdentifierCandidate c = new DiagnosisReport.IdentifierCandidate();
            c.name = id.text;
            c.qualifier = id.qualifier.orElse("");
            c.line = id.origin.line.orElse(null);
            c.column = id.origin.startPosition.orElse(null);
            c.startOffset = id.startOffset;
            c.stopOffset = id.stopOffset;
            c.clause = findClause(tokens, id.startOffset).orElse("");
            findEnclosingSubquery(sql, tokens, id.startOffset).ifPresent(sq -> {
                c.subqueryDepth = sq.depth;
                c.subquerySlice = sq.sqlSlice;
            });
            DiagnosisReport.Location loc = LocationExtractor.buildLocation(
                    sql, c.line == null ? 1 : c.line, c.column == null ? 0 : c.column);
            c.snippet = loc.snippet;
            c.caret = loc.caret;
            hits.add(c);
        }
        return hits;
    }

    /** Flat string summaries; prefer {@link #resolveIdentifierHits}. */
    @Deprecated
    public static List<String> findIdentifierSpans(String sql, String name) {
        List<String> out = new ArrayList<>();
        for (DiagnosisReport.IdentifierCandidate c : resolveIdentifierHits(sql, name)) {
            out.add(c.toSummary());
        }
        return out;
    }

    private static void annotateFromTree(String sql, ParserRuleContext tree, int offset, DiagnosisReport report) {
        ParserRuleContext bestQuery = deepestCovering(tree, offset, null, 0, new int[]{-1});
        if (bestQuery != null) {
            DiagnosisReport.Subquery sq = new DiagnosisReport.Subquery();
            sq.depth = Math.max(1, countSelectAncestors(bestQuery));
            sq.startOffset = bestQuery.getStart().getStartIndex();
            sq.stopOffset = bestQuery.getStop().getStopIndex();
            sq.sqlSlice = sql.substring(sq.startOffset, Math.min(sql.length(), sq.stopOffset + 1));
            report.structure.enclosingSubquery = Optional.of(sq);
        }
    }

    private static ParserRuleContext deepestCovering(ParseTree node, int offset, ParserRuleContext best,
            int depth, int[] bestDepth) {
        if (!(node instanceof ParserRuleContext ctx)) {
            return best;
        }
        if (ctx.getStart() == null || ctx.getStop() == null) {
            return best;
        }
        if (ctx.getStart().getStartIndex() <= offset && ctx.getStop().getStopIndex() >= offset) {
            String rule = DorisParser.ruleNames[ctx.getRuleIndex()];
            if (rule.contains("query") || rule.contains("Query") || rule.equals("selectClause")
                    || rule.equals("querySpecification") || rule.equals("queryPrimaryDefault")
                    || rule.equals("subquery")) {
                if (depth >= bestDepth[0]) {
                    bestDepth[0] = depth;
                    best = ctx;
                }
            }
            for (int i = 0; i < ctx.getChildCount(); i++) {
                best = deepestCovering(ctx.getChild(i), offset, best, depth + 1, bestDepth);
            }
        }
        return best;
    }

    private static int countSelectAncestors(ParserRuleContext ctx) {
        int n = 0;
        ParserRuleContext cur = ctx;
        while (cur != null) {
            String rule = DorisParser.ruleNames[cur.getRuleIndex()];
            if (rule.toLowerCase(Locale.ROOT).contains("query") || rule.contains("select")) {
                n++;
            }
            cur = cur.getParent();
        }
        return Math.max(1, n / 2);
    }

    /**
     * Find the SELECT that owns {@code offset} using a paren/SELECT stack.
     * Inner subquery SELECT is popped when its closing ')' is passed, so WHERE of the
     * outer query is not attributed to the inner SELECT.
     */
    private static Optional<DiagnosisReport.Subquery> findEnclosingSubquery(String sql, List<Token> tokens,
            int offset) {
        int parenDepth = 0;
        // each entry: [selectStart, parenDepthAtSelect]
        List<int[]> selectStack = new ArrayList<>();
        for (Token t : tokens) {
            if (t.getType() == Token.EOF) {
                break;
            }
            if (t.getStartIndex() > offset) {
                break;
            }
            String text = t.getText();
            if ("(".equals(text)) {
                parenDepth++;
            } else if (")".equals(text)) {
                while (!selectStack.isEmpty()
                        && selectStack.get(selectStack.size() - 1)[1] >= parenDepth) {
                    selectStack.remove(selectStack.size() - 1);
                }
                parenDepth = Math.max(0, parenDepth - 1);
            } else if ("SELECT".equalsIgnoreCase(text)) {
                selectStack.add(new int[]{t.getStartIndex(), parenDepth});
            }
        }
        if (selectStack.isEmpty()) {
            return Optional.empty();
        }
        int[] owner = selectStack.get(selectStack.size() - 1);
        DiagnosisReport.Subquery sq = new DiagnosisReport.Subquery();
        sq.depth = selectStack.size();
        sq.startOffset = owner[0];
        int end = findSelectRegionEnd(tokens, owner[0], owner[1], sql.length());
        sq.stopOffset = Math.max(owner[0], end - 1);
        sq.sqlSlice = sql.substring(owner[0], Math.min(sql.length(), Math.min(end, owner[0] + 240)));
        return Optional.of(sq);
    }

    /** End of SELECT region: closing ')' that wrapped this subquery, or EOF for top-level. */
    private static int findSelectRegionEnd(List<Token> tokens, int selectStart, int selectParenDepth, int sqlLen) {
        if (selectParenDepth <= 0) {
            return sqlLen;
        }
        int parenDepth = 0;
        for (Token t : tokens) {
            String text = t.getText();
            if ("(".equals(text)) {
                parenDepth++;
            } else if (")".equals(text)) {
                if (parenDepth == selectParenDepth && t.getStartIndex() > selectStart) {
                    return t.getStopIndex() + 1;
                }
                parenDepth = Math.max(0, parenDepth - 1);
            }
        }
        return sqlLen;
    }

    private static Optional<String> findClause(List<Token> tokens, int offset) {
        String current = "UNKNOWN";
        for (Token t : tokens) {
            if (t.getStartIndex() > offset) {
                break;
            }
            String u = t.getText().toUpperCase(Locale.ROOT);
            switch (u) {
                case "SELECT" -> current = "SELECT";
                case "FROM" -> current = "FROM";
                case "WHERE" -> current = "WHERE";
                case "GROUP" -> current = "GROUP BY";
                case "HAVING" -> current = "HAVING";
                case "ORDER" -> current = "ORDER BY";
                case "LIMIT" -> current = "LIMIT";
                case "JOIN", "LEFT", "RIGHT", "INNER", "FULL", "CROSS" -> current = "JOIN";
                case "WITH" -> current = "WITH";
                default -> {
                }
            }
        }
        return Optional.of(current);
    }

    private static Optional<DiagnosisReport.FunctionHit> findNearestFunction(List<Token> tokens, int offset) {
        DiagnosisReport.FunctionHit best = null;
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token name = tokens.get(i);
            Token next = tokens.get(i + 1);
            if ("(".equals(next.getText()) && looksLikeIdent(name)) {
                int end = findMatchingParen(tokens, i + 1);
                if (name.getStartIndex() <= offset && end >= offset) {
                    DiagnosisReport.FunctionHit hit = new DiagnosisReport.FunctionHit();
                    hit.name = stripTicks(name.getText());
                    hit.startOffset = name.getStartIndex();
                    hit.argIndex = argIndexAt(tokens, i + 1, end, offset);
                    best = hit;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<DiagnosisReport.IdentifierHit> findNearestIdentifier(SqlOriginIndex index, int offset) {
        return index.nearest(offset).map(id -> {
            DiagnosisReport.IdentifierHit hit = new DiagnosisReport.IdentifierHit();
            hit.name = stripTicks(id.text);
            hit.startOffset = id.startOffset;
            hit.stopOffset = id.stopOffset;
            return hit;
        });
    }

    private static boolean looksLikeIdent(Token t) {
        String text = t.getText();
        if (text == null || text.isEmpty()) {
            return false;
        }
        char c = text.charAt(0);
        return Character.isLetter(c) || c == '_' || c == '`';
    }

    private static int findMatchingParen(List<Token> tokens, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            String t = tokens.get(i).getText();
            if ("(".equals(t)) {
                depth++;
            } else if (")".equals(t)) {
                depth--;
                if (depth == 0) {
                    return tokens.get(i).getStopIndex();
                }
            }
        }
        return tokens.get(tokens.size() - 1).getStopIndex();
    }

    private static int argIndexAt(List<Token> tokens, int openIdx, int endOffset, int offset) {
        int depth = 0;
        int arg = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.getStartIndex() > offset) {
                return arg;
            }
            if (t.getStopIndex() > endOffset) {
                break;
            }
            String text = t.getText();
            if ("(".equals(text)) {
                depth++;
            } else if (")".equals(text)) {
                depth--;
                if (depth == 0) {
                    return arg;
                }
            } else if (",".equals(text) && depth == 1) {
                arg++;
            }
        }
        return arg;
    }

    private static String stripTicks(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() >= 2 && s.startsWith("`") && s.endsWith("`")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}

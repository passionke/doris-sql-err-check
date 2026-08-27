package io.kejiqing.dorissqlerr.bind;

import io.kejiqing.dorissqlerr.catalog.Catalog;
import io.kejiqing.dorissqlerr.catalog.RelationSchema;
import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;
import io.kejiqing.dorissqlerr.location.LocationExtractor;
import io.kejiqing.dorissqlerr.location.SqlOriginIndex;
import io.kejiqing.dorissqlerr.structure.StructureMapper;
import org.apache.doris.nereids.DorisParser;
import org.apache.doris.nereids.parser.DorisSqlParser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only catalog bind: resolve column references against DESC schemas + CTE/subquery projections.
 * Author: kejiqing
 *
 * <p>Does not embed FE Env/Planner. When catalog is present this path is authoritative for
 * "which column failed"; FE unknown-column text is corroboration only.
 */
public final class CatalogBinder {

    public static final class BindResult {
        public final List<DiagnosisReport.IdentifierCandidate> unbound = new ArrayList<>();
        public final List<String> loadedRelations = new ArrayList<>();
        public final List<String> missingBaseTables = new ArrayList<>();
        /** alias -> columns visible in outermost query scope (for evidence). */
        public final Map<String, Set<String>> outermostScope = new LinkedHashMap<>();
        public String note = "";
    }

    private final Catalog catalog;
    private final String defaultDatabase;

    public CatalogBinder(Catalog catalog, String defaultDatabase) {
        this.catalog = catalog;
        this.defaultDatabase = defaultDatabase == null ? "" : defaultDatabase;
    }

    public BindResult bind(String sql) {
        BindResult result = new BindResult();
        if (sql == null || sql.isBlank() || catalog == null) {
            result.note = "no-catalog";
            return result;
        }
        ParserRuleContext tree;
        try {
            tree = new DorisSqlParser().parseSingleStatement(sql);
        } catch (Exception e) {
            result.note = "parse-failed:" + e.getMessage();
            return result;
        }

        Map<String, RelationSchema> ctes = new LinkedHashMap<>();
        collectCtes(sql, tree, ctes, result);

        // Bind each querySpecification independently using its FROM scope.
        List<ParserRuleContext> specs = new ArrayList<>();
        collectByRule(tree, "querySpecification", specs);
        collectByRule(tree, "regularQuerySpecification", specs);
        if (specs.isEmpty()) {
            // fallback: whole tree as one scope
            specs.add(tree);
        }

        SqlOriginIndex index = SqlOriginIndex.build(sql);
        Set<String> seenUnbound = new LinkedHashSet<>();

        for (ParserRuleContext spec : specs) {
            Map<String, RelationSchema> scope = buildScope(sql, spec, ctes, result);
            if (result.outermostScope.isEmpty()) {
                for (Map.Entry<String, RelationSchema> e : scope.entrySet()) {
                    result.outermostScope.put(e.getKey(), new LinkedHashSet<>(e.getValue().columns));
                }
            }
            int start = spec.getStart().getStartIndex();
            int stop = spec.getStop().getStopIndex();
            for (SqlOriginIndex.IdentOrigin id : index.idents()) {
                if (id.startOffset < start || id.startOffset > stop) {
                    continue;
                }
                if (!isColumnRef(sql, index, id, scope)) {
                    continue;
                }
                if (binds(id, scope)) {
                    continue;
                }
                String key = id.startOffset + ":" + id.normalized;
                if (!seenUnbound.add(key)) {
                    continue;
                }
                DiagnosisReport.IdentifierCandidate c = toCandidate(sql, id);
                result.unbound.add(c);
            }
        }

        result.unbound.sort((a, b) -> {
            int ra = clauseRank(a.clause);
            int rb = clauseRank(b.clause);
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return Integer.compare(a.startOffset, b.startOffset);
        });
        result.note = "catalog-bind";
        return result;
    }

    private void collectCtes(String sql, ParserRuleContext tree,
            Map<String, RelationSchema> ctes, BindResult result) {
        List<ParserRuleContext> aliasQueries = new ArrayList<>();
        collectByRule(tree, "aliasQuery", aliasQueries);
        for (ParserRuleContext aq : aliasQueries) {
            String cteName = firstIdentifierText(aq);
            if (cteName.isEmpty()) {
                continue;
            }
            // columnAliases? or infer from inner select
            Set<String> cols = new LinkedHashSet<>();
            ParserRuleContext colAliases = childByRule(aq, "columnAliases");
            if (colAliases != null) {
                cols.addAll(identifiersIn(colAliases));
            } else {
                ParserRuleContext query = childByRule(aq, "query");
                if (query != null) {
                    cols.addAll(inferSelectOutputNames(query));
                    // also need inner bind to know subquery outputs — best-effort from AS aliases
                }
            }
            ctes.put(cteName.toLowerCase(Locale.ROOT),
                    new RelationSchema("", cteName, cols));
            result.loadedRelations.add("cte:" + cteName + cols);
        }
    }

    private Map<String, RelationSchema> buildScope(String sql, ParserRuleContext spec,
            Map<String, RelationSchema> ctes, BindResult result) {
        Map<String, RelationSchema> scope = new LinkedHashMap<>();
        List<ParserRuleContext> tableNames = new ArrayList<>();
        collectByRule(spec, "tableName", tableNames);
        // ANTLR labeled alt may appear as relationPrimary — also scan multipart under fromClause
        ParserRuleContext from = childByRule(spec, "fromClause");
        ParserRuleContext scanRoot = from != null ? from : spec;

        // tableName contexts
        tableNames.clear();
        collectLabeledOrRule(scanRoot, "tableName", "relationPrimary", tableNames);
        for (ParserRuleContext tn : tableNames) {
            if (!ruleEquals(tn, "tableName") && !hasMultipartChild(tn)) {
                continue;
            }
            if (ruleEquals(tn, "relationPrimary") && !isTableNameAlt(tn)) {
                continue;
            }
            List<String> parts = multipartParts(tn);
            if (parts.isEmpty()) {
                continue;
            }
            String table = parts.get(parts.size() - 1);
            String db = parts.size() >= 2 ? parts.get(parts.size() - 2) : defaultDatabase;
            String alias = tableAliasOf(tn);
            if (alias.isEmpty()) {
                alias = table;
            }
            String aliasKey = alias.toLowerCase(Locale.ROOT);

            // CTE?
            if (ctes.containsKey(table.toLowerCase(Locale.ROOT))) {
                scope.put(aliasKey, ctes.get(table.toLowerCase(Locale.ROOT)));
                continue;
            }
            Optional<RelationSchema> schema = catalog.describe(db, table);
            if (schema.isEmpty() && !defaultDatabase.isEmpty() && !db.equals(defaultDatabase)) {
                schema = catalog.describe(defaultDatabase, table);
            }
            if (schema.isPresent()) {
                scope.put(aliasKey, schema.get());
                result.loadedRelations.add(schema.get().displayName() + " AS " + alias
                        + schema.get().columns);
            } else {
                result.missingBaseTables.add((db.isEmpty() ? "" : db + ".") + table);
                // unknown table: empty schema so all its cols fail
                scope.put(aliasKey, new RelationSchema(db, table, Set.of()));
            }
        }

        // aliased subqueries: (query) alias
        List<ParserRuleContext> aliased = new ArrayList<>();
        collectLabeledOrRule(scanRoot, "aliasedQuery", "relationPrimary", aliased);
        for (ParserRuleContext aq : aliased) {
            if (!ruleEquals(aq, "aliasedQuery") && !isAliasedQueryAlt(aq)) {
                continue;
            }
            String alias = tableAliasOf(aq);
            if (alias.isEmpty()) {
                continue;
            }
            Set<String> cols = inferSelectOutputNames(aq);
            scope.put(alias.toLowerCase(Locale.ROOT), new RelationSchema("", alias, cols));
            result.loadedRelations.add("subquery AS " + alias + cols);
        }
        return scope;
    }

    private boolean binds(SqlOriginIndex.IdentOrigin id, Map<String, RelationSchema> scope) {
        if (scope.isEmpty()) {
            return false;
        }
        if (id.qualifier.isPresent()) {
            String q = id.qualifier.get();
            RelationSchema rs = scope.get(q);
            if (rs == null) {
                // qualifier may be db.table style last segment already handled; try raw
                return false;
            }
            return rs.hasColumn(id.normalized);
        }
        int hits = 0;
        for (RelationSchema rs : scope.values()) {
            if (rs.hasColumn(id.normalized)) {
                hits++;
            }
        }
        return hits == 1 || hits > 1; // ambiguous still "found"; FE handles ambiguous separately
    }

    private boolean isColumnRef(String sql, SqlOriginIndex index, SqlOriginIndex.IdentOrigin id,
            Map<String, RelationSchema> scope) {
        String n = id.normalized;
        if (n.isEmpty() || isSqlKeyword(n)) {
            return false;
        }
        // alias / relation name itself
        if (scope.containsKey(n)) {
            return false;
        }
        // function call: IDENT (
        if (followedBy(sql, id.stopOffset + 1, '(')) {
            return false;
        }
        // AS alias definition: previous significant token AS
        if (precededByAs(sql, id.startOffset)) {
            return false;
        }
        // multipart table name parts (db / table) in FROM — skip if this ident is a known table name part
        // Heuristic: if qualifier empty and clause is FROM/JOIN and equals a loaded table name → skip
        DiagnosisReport.IdentifierCandidate tmp = toCandidate(sql, id);
        String clause = tmp.clause == null ? "" : tmp.clause;
        if ("FROM".equals(clause) || "JOIN".equals(clause) || "WITH".equals(clause)) {
            // bare names in FROM are usually tables/aliases, not column refs
            if (id.qualifier.isEmpty()) {
                return false;
            }
        }
        if ("UNKNOWN".equals(clause)) {
            return id.qualifier.isPresent();
        }
        return true;
    }

    private static DiagnosisReport.IdentifierCandidate toCandidate(String sql, SqlOriginIndex.IdentOrigin id) {
        List<DiagnosisReport.IdentifierCandidate> hits =
                StructureMapper.resolveIdentifierHits(sql, id.text);
        for (DiagnosisReport.IdentifierCandidate h : hits) {
            if (h.startOffset == id.startOffset) {
                h.qualifier = id.qualifier.orElse("");
                return h;
            }
        }
        DiagnosisReport.IdentifierCandidate c = new DiagnosisReport.IdentifierCandidate();
        c.name = id.text;
        c.qualifier = id.qualifier.orElse("");
        c.line = id.origin.line.orElse(null);
        c.column = id.origin.startPosition.orElse(null);
        c.startOffset = id.startOffset;
        c.stopOffset = id.stopOffset;
        DiagnosisReport.Location loc = LocationExtractor.buildLocation(
                sql, c.line == null ? 1 : c.line, c.column == null ? 0 : c.column);
        c.snippet = loc.snippet;
        c.caret = loc.caret;
        return c;
    }

    private static int clauseRank(String clause) {
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
            default -> 55;
        };
    }

    // ---- tree helpers ----

    private static void collectByRule(ParseTree node, String ruleName, List<ParserRuleContext> out) {
        if (node instanceof ParserRuleContext ctx) {
            if (ruleEquals(ctx, ruleName)) {
                out.add(ctx);
            }
            for (int i = 0; i < ctx.getChildCount(); i++) {
                collectByRule(ctx.getChild(i), ruleName, out);
            }
        }
    }

    private static void collectLabeledOrRule(ParseTree node, String labelOrRule, String alsoRule,
            List<ParserRuleContext> out) {
        if (node instanceof ParserRuleContext ctx) {
            String rn = ruleName(ctx);
            if (labelOrRule.equals(rn) || alsoRule.equals(rn)
                    || ctx.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                    .startsWith(labelOrRule.toLowerCase(Locale.ROOT))) {
                out.add(ctx);
            }
            for (int i = 0; i < ctx.getChildCount(); i++) {
                collectLabeledOrRule(ctx.getChild(i), labelOrRule, alsoRule, out);
            }
        }
    }

    private static boolean ruleEquals(ParserRuleContext ctx, String name) {
        return name.equals(ruleName(ctx));
    }

    private static String ruleName(ParserRuleContext ctx) {
        int idx = ctx.getRuleIndex();
        if (idx >= 0 && idx < DorisParser.ruleNames.length) {
            return DorisParser.ruleNames[idx];
        }
        return "";
    }

    private static boolean isTableNameAlt(ParserRuleContext ctx) {
        // TableNameContext class name from ANTLR label #tableName
        return ctx.getClass().getSimpleName().startsWith("TableName");
    }

    private static boolean isAliasedQueryAlt(ParserRuleContext ctx) {
        return ctx.getClass().getSimpleName().startsWith("AliasedQuery");
    }

    private static boolean hasMultipartChild(ParserRuleContext ctx) {
        return childByRule(ctx, "multipartIdentifier") != null;
    }

    private static ParserRuleContext childByRule(ParserRuleContext ctx, String rule) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree ch = ctx.getChild(i);
            if (ch instanceof ParserRuleContext c && ruleEquals(c, rule)) {
                return c;
            }
        }
        // deep search one level of wrappers
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree ch = ctx.getChild(i);
            if (ch instanceof ParserRuleContext c) {
                ParserRuleContext found = childByRule(c, rule);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> multipartParts(ParserRuleContext ctx) {
        ParserRuleContext mp = ruleEquals(ctx, "multipartIdentifier")
                ? ctx : childByRule(ctx, "multipartIdentifier");
        if (mp == null) {
            return List.of();
        }
        return identifiersIn(mp);
    }

    private static String tableAliasOf(ParserRuleContext relationPrimary) {
        ParserRuleContext ta = childByRule(relationPrimary, "tableAlias");
        if (ta == null) {
            return "";
        }
        List<String> ids = identifiersIn(ta);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private static String firstIdentifierText(ParserRuleContext ctx) {
        List<String> ids = identifiersIn(ctx);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private static List<String> identifiersIn(ParserRuleContext ctx) {
        List<String> out = new ArrayList<>();
        collectIdentifiers(ctx, out);
        return out;
    }

    private static void collectIdentifiers(ParseTree node, List<String> out) {
        if (node instanceof TerminalNode tn) {
            String t = tn.getText();
            if (t != null && !t.isEmpty() && isIdentText(t) && !".".equals(t) && !",".equals(t)
                    && !"(".equals(t) && !")".equals(t) && !isSqlKeyword(t)) {
                // only IDENTIFIER-like terminals under identifier rules — filter AS
                if (!"AS".equalsIgnoreCase(t)) {
                    out.add(RelationSchema.strip(t));
                }
            }
            return;
        }
        if (node instanceof ParserRuleContext ctx) {
            String rn = ruleName(ctx);
            if (rn.contains("identifier") || rn.equals("multipartIdentifier")
                    || rn.equals("errorCapturingIdentifier") || rn.equals("strictIdentifier")
                    || rn.equals("identifierOrText") || rn.equals("columnAliases")
                    || rn.equals("tableAlias") || rn.equals("aliasQuery")) {
                for (int i = 0; i < ctx.getChildCount(); i++) {
                    collectIdentifiers(ctx.getChild(i), out);
                }
                return;
            }
            // for aliasQuery only take leading identifier, not whole subtree blindly
            if (rn.equals("namedExpression")) {
                // AS alias is last identifier
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < ctx.getChildCount(); i++) {
                    collectIdentifiers(ctx.getChild(i), ids);
                }
                out.addAll(ids);
                return;
            }
            for (int i = 0; i < ctx.getChildCount(); i++) {
                collectIdentifiers(ctx.getChild(i), out);
            }
        }
    }

    private static Set<String> inferSelectOutputNames(ParserRuleContext queryOrSpec) {
        Set<String> cols = new LinkedHashSet<>();
        List<ParserRuleContext> named = new ArrayList<>();
        collectByRule(queryOrSpec, "namedExpression", named);
        for (ParserRuleContext ne : named) {
            List<String> ids = identifiersIn(ne);
            if (ids.isEmpty()) {
                continue;
            }
            // prefer alias (last id) when AS present
            String text = ne.getText().toLowerCase(Locale.ROOT);
            if (text.contains("as")) {
                cols.add(ids.get(ids.size() - 1).toLowerCase(Locale.ROOT));
            } else {
                cols.add(ids.get(ids.size() - 1).toLowerCase(Locale.ROOT));
            }
        }
        return cols;
    }

    private static boolean isIdentText(String t) {
        char c = t.charAt(0);
        return Character.isLetter(c) || c == '_' || c == '`';
    }

    private static boolean followedBy(String sql, int from, char ch) {
        int i = from;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i < sql.length() && sql.charAt(i) == ch;
    }

    private static boolean precededByAs(String sql, int offset) {
        int i = offset - 1;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        if (i < 1) {
            return false;
        }
        // match AS
        if (i >= 1 && (sql.charAt(i) == 'S' || sql.charAt(i) == 's')
                && (sql.charAt(i - 1) == 'A' || sql.charAt(i - 1) == 'a')) {
            int before = i - 2;
            return before < 0 || !Character.isLetterOrDigit(sql.charAt(before));
        }
        return false;
    }

    private static boolean isSqlKeyword(String n) {
        String u = n.toUpperCase(Locale.ROOT);
        return switch (u) {
            case "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT",
                    "JOIN", "LEFT", "RIGHT", "INNER", "FULL", "OUTER", "CROSS", "ON", "AS",
                    "WITH", "AND", "OR", "NOT", "NULL", "TRUE", "FALSE", "CASE", "WHEN",
                    "THEN", "ELSE", "END", "IN", "IS", "LIKE", "BETWEEN", "EXISTS",
                    "DISTINCT", "ALL", "UNION", "INSERT", "INTO", "OVERWRITE", "VALUES",
                    "UPDATE", "DELETE", "SET", "EXPLAIN", "DESC", "DESCRIBE", "SHOW",
                    "CREATE", "TABLE", "VIEW", "DATABASE", "SCHEMA", "USE", "DROP", "ALTER",
                    "CAST", "DATE", "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE",
                    "IF", "IFNULL", "NULLIF", "OVER", "PARTITION", "ROWS", "RANGE",
                    "UNBOUNDED", "PRECEDING", "FOLLOWING", "CURRENT", "ROW" -> true;
            default -> false;
        };
    }
}

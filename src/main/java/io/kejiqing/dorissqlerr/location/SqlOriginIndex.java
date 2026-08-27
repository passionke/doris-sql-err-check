package io.kejiqing.dorissqlerr.location;

import org.apache.doris.nereids.DorisLexer;
import org.apache.doris.nereids.parser.CaseInsensitiveStream;
import org.apache.doris.nereids.parser.Origin;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sidecar equivalent of FE Slot.indexInSqlString: every identifier token carries Origin + char span
 * + optional qualifier ({@code T0.col}).
 * Author: kejiqing
 */
public final class SqlOriginIndex {

    public static final class IdentOrigin {
        public final String text;
        public final String normalized;
        /** Qualifier before '.', if any (e.g. {@code T0} in {@code T0.col}). */
        public final Optional<String> qualifier;
        public final Origin origin;
        public final int startOffset;
        public final int stopOffset;

        public IdentOrigin(String text, Optional<String> qualifier, Origin origin,
                int startOffset, int stopOffset) {
            this.text = text;
            this.normalized = stripTicks(text).toLowerCase(Locale.ROOT);
            this.qualifier = qualifier.map(q -> stripTicks(q).toLowerCase(Locale.ROOT));
            this.origin = origin;
            this.startOffset = startOffset;
            this.stopOffset = stopOffset;
        }

        public boolean isQualified() {
            return qualifier.isPresent();
        }
    }

    private final String sql;
    private final List<IdentOrigin> idents;
    private final List<Token> tokens;

    private SqlOriginIndex(String sql, List<IdentOrigin> idents, List<Token> tokens) {
        this.sql = sql;
        this.idents = idents;
        this.tokens = tokens;
    }

    public static SqlOriginIndex build(String sql) {
        if (sql == null) {
            sql = "";
        }
        DorisLexer lexer = new DorisLexer(new CaseInsensitiveStream(CharStreams.fromString(sql)));
        CommonTokenStream ts = new CommonTokenStream(lexer);
        ts.fill();
        List<Token> tokens = ts.getTokens();
        List<IdentOrigin> idents = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.getType() == Token.EOF || !isIdent(t)) {
                continue;
            }
            Optional<String> qual = Optional.empty();
            // pattern: QUAL . COL  — current token is COL
            if (i >= 2 && ".".equals(tokens.get(i - 1).getText()) && isIdent(tokens.get(i - 2))) {
                qual = Optional.of(tokens.get(i - 2).getText());
            }
            Origin origin = new Origin(t.getLine(), t.getCharPositionInLine());
            idents.add(new IdentOrigin(t.getText(), qual, origin, t.getStartIndex(), t.getStopIndex()));
        }
        return new SqlOriginIndex(sql, Collections.unmodifiableList(idents), tokens);
    }

    public String sql() {
        return sql;
    }

    public List<IdentOrigin> idents() {
        return idents;
    }

    public List<Token> tokens() {
        return tokens;
    }

    public List<IdentOrigin> findByName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String target = stripTicks(name).toLowerCase(Locale.ROOT);
        List<IdentOrigin> hits = new ArrayList<>();
        for (IdentOrigin id : idents) {
            if (id.normalized.equals(target)) {
                hits.add(id);
            }
        }
        return hits;
    }

    public Optional<IdentOrigin> nearest(int offset) {
        IdentOrigin best = null;
        int bestDist = Integer.MAX_VALUE;
        for (IdentOrigin id : idents) {
            int dist;
            if (id.startOffset <= offset && id.stopOffset >= offset) {
                dist = 0;
            } else {
                dist = Math.min(Math.abs(id.startOffset - offset), Math.abs(id.stopOffset - offset));
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = id;
            }
        }
        if (best == null || bestDist > 40) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private static boolean isIdent(Token t) {
        String text = t.getText();
        if (text == null || text.isEmpty()) {
            return false;
        }
        if ("(".equals(text) || ")".equals(text) || ",".equals(text) || ".".equals(text)
                || "*".equals(text) || ";".equals(text)) {
            return false;
        }
        char c = text.charAt(0);
        return Character.isLetter(c) || c == '_' || c == '`';
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

package io.kejiqing.dorissqlerr.location;

import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extract line/col from Doris FE error text (legacy CUP + Nereids).
 * Author: kejiqing
 */
public final class LocationExtractor {
    private static final Pattern LEGACY_LINE = Pattern.compile(
            "Syntax error in line (\\d+):", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEREIDS_POS = Pattern.compile(
            "\\(line (\\d+), pos (\\d+)\\)", Pattern.CASE_INSENSITIVE);

    private LocationExtractor() {
    }

    public static Optional<DiagnosisReport.Location> extract(String sql, String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return Optional.empty();
        }
        Matcher nereids = NEREIDS_POS.matcher(errorMessage);
        if (nereids.find()) {
            int line = Integer.parseInt(nereids.group(1));
            int col = Integer.parseInt(nereids.group(2));
            return Optional.of(buildLocation(sql, line, col));
        }

        Matcher legacy = LEGACY_LINE.matcher(errorMessage);
        if (legacy.find()) {
            int line = Integer.parseInt(legacy.group(1));
            int col = extractLegacyCaretColumn(errorMessage, line).orElse(0);
            return Optional.of(buildLocation(sql, line, col));
        }
        return Optional.empty();
    }

    private static Optional<Integer> extractLegacyCaretColumn(String errorMessage, int line) {
        String[] parts = errorMessage.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.contains("^") && !p.contains("Syntax error")) {
                int idx = p.indexOf('^');
                if (idx >= 0) {
                    return Optional.of(idx);
                }
            }
        }
        // detailMessage may be single-line: "... LIMIT 0, 1000; ^ Encountered"
        int caret = errorMessage.indexOf('^');
        if (caret > 0) {
            int lineStart = errorMessage.lastIndexOf('\n', caret);
            if (lineStart < 0) {
                // estimate from "in line N: " fragment
                Matcher m = Pattern.compile("Syntax error in line " + line + ":\\s*(.*?)\\s*\\^")
                        .matcher(errorMessage);
                if (m.find()) {
                    return Optional.of(Math.max(0, m.group(1).length()));
                }
            } else {
                return Optional.of(caret - lineStart - 1);
            }
        }
        return Optional.empty();
    }

    public static DiagnosisReport.Location buildLocation(String sql, int line, int column) {
        DiagnosisReport.Location loc = new DiagnosisReport.Location();
        loc.line = line;
        loc.column = Math.max(0, column);
        String[] lines = sql == null ? new String[]{""} : sql.split("\n", -1);
        int idx = Math.min(Math.max(line - 1, 0), Math.max(lines.length - 1, 0));
        String snippetLine = lines.length == 0 ? "" : lines[idx];
        // fuzzy: if FE truncated the line, try locate fragment in sql line
        loc.snippet = snippetLine;
        StringBuilder caret = new StringBuilder();
        int caretCol = Math.min(loc.column, Math.max(snippetLine.length(), 0));
        for (int i = 0; i < caretCol; i++) {
            caret.append(i < snippetLine.length() && snippetLine.charAt(i) == '\t' ? '\t' : ' ');
        }
        caret.append('^');
        loc.caret = caret.toString();
        loc.charOffset = offsetOf(sql, line, loc.column);
        return loc;
    }

    public static int offsetOf(String sql, int line, int column) {
        if (sql == null || sql.isEmpty()) {
            return 0;
        }
        int currentLine = 1;
        for (int i = 0; i < sql.length(); i++) {
            if (currentLine == line) {
                return Math.min(i + Math.max(column, 0), sql.length());
            }
            if (sql.charAt(i) == '\n') {
                currentLine++;
            }
        }
        return sql.length();
    }
}

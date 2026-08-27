package io.kejiqing.dorissqlerr.classify;

import io.kejiqing.dorissqlerr.diagnose.ErrorCategory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classify Doris FE error messages.
 * Author: kejiqing
 */
public final class ErrorClassifier {
    private static final Pattern SYNTAX = Pattern.compile(
            "Syntax error|extraneous input|mismatched input|\\(line \\d+, pos \\d+\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANALYSIS = Pattern.compile(
            "Unknown column|does not exist|Can not find the compatibility function|ambiguous",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RUNTIME = Pattern.compile(
            "Query timeout|timeout|Cancelled|Mem exceeded|memory limit",
            Pattern.CASE_INSENSITIVE);

    private ErrorClassifier() {
    }

    public static ErrorCategory classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return ErrorCategory.UNKNOWN;
        }
        String msg = errorMessage;
        if (RUNTIME.matcher(msg).find() && !SYNTAX.matcher(msg).find()) {
            return ErrorCategory.RUNTIME;
        }
        if (SYNTAX.matcher(msg).find()) {
            return ErrorCategory.PARSE;
        }
        if (ANALYSIS.matcher(msg).find()) {
            return ErrorCategory.ANALYSIS;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("errCode") && lower.contains("detailMessage")) {
            return ErrorCategory.UNKNOWN;
        }
        return ErrorCategory.UNKNOWN;
    }

    public static String extractUnknownColumn(String errorMessage) {
        return UnknownColumnRef.parse(errorMessage).map(r -> r.column).orElse(null);
    }
}

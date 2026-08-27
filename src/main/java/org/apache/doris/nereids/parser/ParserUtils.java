// Licensed to the Apache Software Foundation (ASF) under one
package org.apache.doris.nereids.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Parser utils. Sidecar fix: withOrigin actually pushes Origin onto a thread-local stack
 * (Doris FE version is a no-op).
 * Author: kejiqing
 */
public final class ParserUtils {
    private static final ThreadLocal<Deque<Origin>> ORIGIN_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ParserUtils() {
    }

    public static <T> T withOrigin(ParserRuleContext ctx, Supplier<T> f) {
        Origin origin = position(ctx.getStart());
        ORIGIN_STACK.get().push(origin);
        try {
            return f.get();
        } finally {
            ORIGIN_STACK.get().pop();
            if (ORIGIN_STACK.get().isEmpty()) {
                ORIGIN_STACK.remove();
            }
        }
    }

    public static Optional<Origin> currentOrigin() {
        Deque<Origin> stack = ORIGIN_STACK.get();
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(stack.peek());
    }

    public static String command(ParserRuleContext ctx) {
        CharStream stream = ctx.getStart().getInputStream();
        return stream.getText(Interval.of(0, stream.size() - 1));
    }

    public static Origin position(Token token) {
        return new Origin(token.getLine(), token.getCharPositionInLine());
    }
}

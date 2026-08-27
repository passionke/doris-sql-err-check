// Licensed to the Apache Software Foundation (ASF) under one
package org.apache.doris.nereids.parser;

import org.apache.doris.nereids.exceptions.ParseException;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Interval;

import java.util.Optional;

/**
 * Listen parse error. Sidecar fix: always attach full SQL command for ^^^ caret.
 * Author: kejiqing
 */
public class ParseErrorListener extends BaseErrorListener {
    private final String sql;

    public ParseErrorListener(String sql) {
        this.sql = sql;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
            String msg, RecognitionException e) {
        Origin start;
        if (offendingSymbol instanceof CommonToken) {
            CommonToken token = (CommonToken) offendingSymbol;
            start = new Origin(line, token.getCharPositionInLine());
        } else {
            start = new Origin(line, charPositionInLine);
        }
        String command = sql;
        if (command == null || command.isEmpty()) {
            try {
                if (offendingSymbol instanceof CommonToken) {
                    CommonToken token = (CommonToken) offendingSymbol;
                    if (token.getInputStream() != null) {
                        command = token.getInputStream().getText(Interval.of(0, token.getInputStream().size() - 1));
                    }
                }
            } catch (Exception ignored) {
                command = "";
            }
        }
        throw new ParseException(msg, start, Optional.ofNullable(command).filter(s -> !s.isEmpty()));
    }
}

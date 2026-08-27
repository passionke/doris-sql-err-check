// Licensed to the Apache Software Foundation (ASF) under one
package org.apache.doris.nereids.exceptions;

import org.apache.doris.nereids.parser.Origin;
import org.apache.doris.nereids.parser.ParserUtils;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Optional;

/**
 * SQL parsing exception with Origin + optional SQL caret (always attach command when possible).
 * Author: kejiqing
 */
public class ParseException extends RuntimeException {
    private final String message;
    private final Origin start;
    private final Optional<String> command;

    public ParseException(String message) {
        this(message, new Origin(0, 0), Optional.empty());
    }

    public ParseException(String message, Origin start, Optional<String> command) {
        super(message);
        this.message = message;
        this.start = start;
        this.command = command;
    }

    public ParseException(String message, ParserRuleContext ctx) {
        this(message, ParserUtils.position(ctx.getStart()), Optional.of(ParserUtils.command(ctx)));
    }

    public Origin getStart() {
        return start;
    }

    public Optional<String> getCommand() {
        return command;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(message);
        if (start.line.isPresent() && start.startPosition.isPresent()) {
            int line = start.line.get();
            int startPosition = start.startPosition.get();
            sb.append("(line ").append(line).append(", pos ").append(startPosition).append(")").append("\n");
            if (command.isPresent()) {
                sb.append("\n== SQL ==\n");
                String cmd = command.get();
                String[] splitCmd = cmd.split("\n", -1);
                for (int i = 0; i < line && i < splitCmd.length; i++) {
                    sb.append(splitCmd[i]).append("\n");
                }
                for (int i = 0; i < startPosition; i++) {
                    sb.append("-");
                }
                sb.append("^^^\n");
                for (int i = line; i < splitCmd.length; i++) {
                    sb.append(splitCmd[i]).append("\n");
                }
            }
        }
        return sb.toString();
    }
}

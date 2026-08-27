// Licensed to the Apache Software Foundation (ASF) under one
package org.apache.doris.nereids.parser;

import org.apache.doris.nereids.DorisLexer;
import org.apache.doris.nereids.DorisParser;
import org.apache.doris.nereids.exceptions.ParseException;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.util.function.Function;

/**
 * Slim Doris SQL parser (Nereids toAst slice). Does not pull LogicalPlanBuilder/Env.
 * Author: kejiqing
 */
public class DorisSqlParser {

    public ParserRuleContext parseMultiStatements(String sql) {
        return toAst(sql, DorisParser::multiStatements);
    }

    public ParserRuleContext parseSingleStatement(String sql) {
        return toAst(sql, DorisParser::singleStatement);
    }

    public static ParserRuleContext toAst(String sql, Function<DorisParser, ParserRuleContext> parseFunction) {
        DorisLexer lexer = new DorisLexer(new CaseInsensitiveStream(CharStreams.fromString(sql)));
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        DorisParser parser = new DorisParser(tokenStream);

        parser.removeErrorListeners();
        parser.addErrorListener(new ParseErrorListener(sql));

        try {
            parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
            return parseFunction.apply(parser);
        } catch (ParseCancellationException ex) {
            tokenStream.seek(0);
            parser.reset();
            parser.getInterpreter().setPredictionMode(PredictionMode.LL);
            return parseFunction.apply(parser);
        } catch (ParseException pe) {
            throw pe;
        }
    }
}

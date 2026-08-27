package io.kejiqing.dorissqlerr;

import io.kejiqing.dorissqlerr.classify.ErrorClassifier;
import io.kejiqing.dorissqlerr.diagnose.Diagnoser;
import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;
import io.kejiqing.dorissqlerr.diagnose.ErrorCategory;
import io.kejiqing.dorissqlerr.location.LocationExtractor;
import org.apache.doris.nereids.exceptions.ParseException;
import org.apache.doris.nereids.parser.DorisSqlParser;
import org.apache.doris.nereids.parser.ParserUtils;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Author: kejiqing
 */
public class OriginAndLocationTest {

    @Test
    void sidecarParseExceptionCarriesOriginAndCaret() {
        ParseException pe = assertThrows(ParseException.class,
                () -> new DorisSqlParser().parseSingleStatement("SELECT * FROM (SELECT 1 AS a"));
        assertTrue(pe.getStart().line.isPresent());
        assertTrue(pe.getMessage().contains("line"));
        assertTrue(pe.getMessage().contains("^^^") || pe.getCommand().isPresent()
                || pe.getMessage().contains("pos"));
    }

    @Test
    void legacyLocationExtract() {
        String sql = "SELECT a\nFROM t\nLIMIT 1";
        String err = "errCode = 2, detailMessage = Syntax error in line 3: LIMIT 1 ^ Encountered: LIMIT Expected: || ";
        Optional<DiagnosisReport.Location> loc = LocationExtractor.extract(sql, err);
        assertTrue(loc.isPresent());
        assertEquals(3, loc.get().line);
        assertNotNull(loc.get().charOffset);
    }

    @Test
    void nereidsLocationExtract() {
        String sql = "SELECT * FROM t WHERE";
        String err = "\nmismatched input '<EOF>' expecting {..., IDENTIFIER}(line 1, pos 21)\n";
        Optional<DiagnosisReport.Location> loc = LocationExtractor.extract(sql, err);
        assertTrue(loc.isPresent());
        assertEquals(1, loc.get().line);
        assertEquals(21, loc.get().column);
    }

    @Test
    void classifyRuntime() {
        assertEquals(ErrorCategory.RUNTIME,
                ErrorClassifier.classify("errCode = 2, detailMessage = Query timeout"));
    }

    @Test
    void diagnoseMissingParenUsesSidecarOrigin() {
        DiagnosisReport r = new Diagnoser().diagnose("SELECT * FROM (SELECT 1 AS a", "");
        assertEquals(ErrorCategory.PARSE, r.category);
        assertEquals("high", r.confidence);
        assertNotNull(r.location);
        assertNotNull(r.location.line);
        assertFalse(r.enhancedMessage.isBlank());
    }

    @Test
    void diagnoseUnknownColumnCandidates() {
        String sql = "SELECT order_id, a FROM (SELECT 1 AS a) t WHERE order_id = 1";
        String err = "errCode = 2, detailMessage = Unknown column 'order_id' in 'table list'";
        DiagnosisReport r = new Diagnoser().diagnose(sql, err);
        assertEquals(ErrorCategory.ANALYSIS, r.category);
        assertFalse(r.identifierHits.isEmpty());
        assertEquals(2, r.identifierHits.size());
        assertTrue(r.failedHit.isPresent());
        // Nereids binds Filter before Project → WHERE is the failing occurrence
        assertEquals("WHERE", r.failedHit.get().clause);
        assertTrue(r.failedHit.get().failed);
        assertEquals("high", r.confidence);
        assertTrue(r.enhancedMessage.contains("Failed column reference"));
    }

    @Test
    void diagnoseFailedColumnPrefersQualifiedAliasScope() {
        String sql = "SELECT T0.pos_staff_id AS pos_staff_id, T0.order_sn AS order_sn, staff_id FROM dataset_x T0";
        String err = "errCode = 2, detailMessage = Unknown column 'staff_id' in 'table list'";
        DiagnosisReport r = new Diagnoser().diagnose(sql, err);
        assertTrue(r.failedHit.isPresent());
        assertEquals("staff_id", r.failedHit.get().name.replace("`", ""));
        assertTrue(r.failedHit.get().qualifier == null || r.failedHit.get().qualifier.isEmpty());
        assertEquals("SELECT", r.failedHit.get().clause);
    }

    @Test
    void diagnoseFailedColumnMatchesT0Scope() {
        String sql = "SELECT CAST(T0.settle_time AS DATE) AS ds, T0.store_id "
                + "FROM ai_test.dataset_x T0 WHERE CAST(T0.settle_time AS DATE) = '20260101'";
        String err = "errCode = 2, detailMessage = Unknown column 'settle_time' in 'T0'";
        DiagnosisReport r = new Diagnoser().diagnose(sql, err);
        assertTrue(r.failedHit.isPresent());
        assertEquals("t0", r.failedHit.get().qualifier.toLowerCase());
        assertTrue(r.failedHit.get().name.toLowerCase().contains("settle_time")
                || r.failedHit.get().qualifiedName().toLowerCase().contains("settle_time"));
        assertEquals("high", r.confidence);
    }

    @Test
    void diagnoseParsePrefersSidecarOriginOverFeLegacy() {
        String sql = "SELECT * FROM (SELECT 1 AS a LIMIT 1";
        String fe = "errCode = 2, detailMessage = Syntax error in line 1: ... Encountered: LIMIT";
        DiagnosisReport r = new Diagnoser().diagnose(sql, fe);
        assertEquals(ErrorCategory.PARSE, r.category);
        assertEquals("high", r.confidence);
        assertNotNull(r.location);
        assertNotNull(r.location.line);
        assertTrue(r.enhancedMessage.contains("line") || r.enhancedMessage.contains("^^^")
                || r.evidence.sidecarParseError.contains("LIMIT"));
    }

    @Test
    void parserUtilsCurrentOriginEmptyByDefault() {
        assertTrue(ParserUtils.currentOrigin().isEmpty());
    }
}

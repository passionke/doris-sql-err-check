package io.kejiqing.dorissqlerr;

import io.kejiqing.dorissqlerr.catalog.MemoryCatalog;
import io.kejiqing.dorissqlerr.diagnose.Diagnoser;
import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;
import io.kejiqing.dorissqlerr.diagnose.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only catalog bind tests (schema truth, not FE guess).
 * Author: kejiqing
 */
public class CatalogBindTest {

    @Test
    void catalogBindFindsMissingSettleTimeAgainstRealShape() {
        // Mirrors th.ai_test.w56fa4498_dataset_dwd_catering_order_detail_di (DESC: id, ds only)
        MemoryCatalog cat = new MemoryCatalog()
                .with("ai_test", "w56fa4498_dataset_dwd_catering_order_detail_di", "id", "ds");
        String sql = "SELECT CAST(T0.settle_time AS DATE) AS ds, T0.store_id "
                + "FROM `ai_test`.`w56fa4498_dataset_dwd_catering_order_detail_di` T0 "
                + "WHERE CAST(T0.settle_time AS DATE) = '20260101'";
        String err = "errCode = 2, detailMessage = Unknown column 'settle_time' in 'T0'";

        DiagnosisReport r = new Diagnoser().diagnose(sql, err,
                Diagnoser.Options.withCatalog(cat, "ai_test"));

        assertEquals(ErrorCategory.ANALYSIS, r.category);
        assertEquals("catalog-bind", r.evidence.bindMode);
        assertTrue(r.failedHit.isPresent());
        assertTrue(r.failedHit.get().qualifiedName().toLowerCase().contains("settle_time"));
        assertTrue(r.evidence.availableColumns.contains("ds"));
        assertTrue(r.evidence.availableColumns.contains("id"));
        assertFalse(r.evidence.availableColumns.contains("settle_time"));
        assertEquals("high", r.confidence);
        assertTrue(r.enhancedMessage.contains("Catalog bind"));
    }

    @Test
    void catalogBindBareStaffIdNotOnTable() {
        MemoryCatalog cat = new MemoryCatalog().with("ai_test", "dataset_x", "pos_staff_id", "order_sn");
        String sql = "SELECT T0.pos_staff_id AS pos_staff_id, T0.order_sn AS order_sn, staff_id "
                + "FROM dataset_x T0";
        DiagnosisReport r = new Diagnoser().diagnose(sql, "",
                Diagnoser.Options.withCatalog(cat, "ai_test"));
        assertTrue(r.failedHit.isPresent());
        assertEquals("staff_id", r.failedHit.get().name.replace("`", ""));
        assertTrue(r.failedHit.get().qualifier == null || r.failedHit.get().qualifier.isEmpty());
    }

    @Test
    void withoutCatalogFallsBackToFeScope() {
        String sql = "SELECT order_id, a FROM (SELECT 1 AS a) t WHERE order_id = 1";
        String err = "Unknown column 'order_id' in 'table list'";
        DiagnosisReport r = new Diagnoser().diagnose(sql, err);
        assertEquals("fe-scope", r.evidence.bindMode);
        assertTrue(r.failedHit.isPresent());
    }
}

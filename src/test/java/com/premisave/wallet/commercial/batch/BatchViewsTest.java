package com.premisave.wallet.commercial.batch;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.TxResult;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BatchViewsTest {

    private static PayoutBatch batch() {
        PayoutBatch b = new PayoutBatch();
        b.setBatchId("bat_1");
        b.setMerchantReference("PAYROLL");
        b.setMode(PayoutBatch.Mode.BEST_EFFORT);
        b.setStatus(PayoutBatch.Status.PROCESSING);
        b.setAcceptedCount(2);
        b.setTotalAmountCents(6000);
        b.setTotalFeeCents(120);
        b.setRejected(List.of(new PayoutBatch.Rejection("SAL-3", "VALIDATION_FAILED", "phoneNumber is invalid")));
        return b;
    }

    private static CommercialTransaction tx(String id, String ref) {
        CommercialTransaction t = new CommercialTransaction();
        t.setId(id);
        t.setMerchantReference(ref);
        t.setStatus(TxStatus.PENDING);
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCreatedViewListsAcceptedAndRejectedItemsWithTotals() {
        Map<String, Object> v = BatchViews.created(batch(), List.of(tx("po_1", "SAL-1"), tx("po_2", "SAL-2")));
        assertEquals("PROCESSING", v.get("status"));
        Map<String, Object> totals = (Map<String, Object>) v.get("totals");
        assertEquals(2, totals.get("count"));
        assertEquals("60.00", totals.get("amount"));
        assertEquals("1.20", totals.get("fee"));
        assertEquals("USD", totals.get("currency"));
        List<Map<String, Object>> accepted = (List<Map<String, Object>>) v.get("accepted");
        assertEquals("po_2", accepted.get(1).get("payoutId"));
        List<Map<String, Object>> rejected = (List<Map<String, Object>>) v.get("rejected");
        assertEquals("VALIDATION_FAILED", rejected.get(0).get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDetailViewCarriesCounters() {
        Map<String, Object> v = BatchViews.detail(batch(), new BatchRules.Counters(1, 2, 3, 4));
        Map<String, Object> c = (Map<String, Object>) v.get("counters");
        assertEquals(1L, c.get("pending"));
        assertEquals(2L, c.get("success"));
        assertEquals(3L, c.get("failed"));
        assertEquals(4L, c.get("timedOut"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anItemShowsItsResultOnlyOnceItHasOne() {
        CommercialTransaction t = tx("po_9", "SAL-9");
        assertFalse(BatchViews.item(t).containsKey("result"));
        t.setStatus(TxStatus.FAILED);
        t.setResult(new TxResult(ResultCode.INVALID_DESTINATION, "bad number", null, null));
        Map<String, Object> r = (Map<String, Object>) BatchViews.item(t).get("result");
        assertEquals("INVALID_DESTINATION", r.get("code"));
    }
}

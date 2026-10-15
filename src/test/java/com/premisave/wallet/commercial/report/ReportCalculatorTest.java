package com.premisave.wallet.commercial.report;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportCalculatorTest {

    private static ReportBucket b(String day, TxType type, TxStatus status, MethodType method, String app, long count, long amount, long fee) {
        return new ReportBucket(day, type, status, method, app, count, amount, fee);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> block, String name) {
        return (Map<String, Object>) block.get(name);
    }

    @Test
    void successfulCollectionsCountAsVolumeAndFailuresAreCountedApart() {
        Map<String, Object> block = ReportCalculator.block(List.of(
                b("2026-10-14", TxType.PAYMENT, TxStatus.SUCCESS, MethodType.MOBILE_MONEY, "a", 3, 9_000, 180),
                b("2026-10-14", TxType.PAYMENT, TxStatus.SUCCESS, MethodType.CARD, "a", 1, 5_000, 175),
                b("2026-10-14", TxType.PAYMENT, TxStatus.FAILED, MethodType.CARD, "a", 2, 7_000, 0),
                b("2026-10-14", TxType.PAYMENT, TxStatus.TIMED_OUT, MethodType.MOBILE_MONEY, "a", 1, 1_000, 0)));
        Map<String, Object> c = section(block, "collections");
        assertEquals(4L, c.get("count"));
        assertEquals("140.00", c.get("volume"));
        assertEquals("3.55", c.get("fees"));
        assertEquals(3L, c.get("failed"));
        assertEquals(Map.of("CARD", "50.00", "MOBILE_MONEY", "90.00"), c.get("byMethod"));
    }

    @Test
    void netIsTheChangeInBalance() {
        Map<String, Object> block = ReportCalculator.block(List.of(
                b("d", TxType.PAYMENT, TxStatus.SUCCESS, MethodType.CARD, "a", 1, 10_000, 300),
                b("d", TxType.PAYOUT, TxStatus.SUCCESS, MethodType.MOBILE_MONEY, "a", 1, 2_000, 40),
                b("d", TxType.SETTLEMENT, TxStatus.SUCCESS, MethodType.BANK_TRANSFER, "a", 1, 3_000, 100),
                b("d", TxType.TRANSFER, TxStatus.SUCCESS, MethodType.PREMISAVE_WALLET, "a", 1, 500, 5),
                b("d", TxType.REFUND, TxStatus.SUCCESS, MethodType.CARD, "a", 1, 1_000, 0)));
        // 10,000 - 300 - (2,000 + 40) - (3,000 + 100) - (500 + 5) - 1,000 = 3,055
        assertEquals("30.55", block.get("net"));
    }

    @Test
    void reversedPayoutKeepsItsFeeButNotItsVolume() {
        Map<String, Object> block = ReportCalculator.block(List.of(
                b("d", TxType.PAYOUT, TxStatus.REVERSED, MethodType.MOBILE_MONEY, "a", 1, 2_000, 40)));
        Map<String, Object> p = section(block, "payouts");
        assertEquals("0.00", p.get("volume"));
        assertEquals("0.40", p.get("fees"));
        assertEquals(1L, p.get("reversed"));
        assertEquals("-0.40", block.get("net"));
    }

    @Test
    void failedOutflowsAreNotVolume() {
        Map<String, Object> block = ReportCalculator.block(List.of(
                b("d", TxType.SETTLEMENT, TxStatus.FAILED, MethodType.BANK_TRANSFER, "a", 2, 9_000, 90)));
        Map<String, Object> s = section(block, "settlements");
        assertEquals(0L, s.get("count"));
        assertEquals("0.00", s.get("volume"));
        assertEquals("0.00", block.get("net"));
    }

    @Test
    void emptyInputGivesZeroes() {
        Map<String, Object> block = ReportCalculator.block(List.of());
        assertEquals("0.00", block.get("net"));
        assertEquals(0L, section(block, "collections").get("count"));
    }

    @Test
    void groupsByDayInOrder() {
        List<Map<String, Object>> rows = ReportCalculator.grouped(List.of(
                b("2026-10-15", TxType.PAYMENT, TxStatus.SUCCESS, MethodType.CARD, "a", 1, 1_000, 30),
                b("2026-10-14", TxType.PAYMENT, TxStatus.SUCCESS, MethodType.CARD, "a", 1, 2_000, 60)), ReportCalculator.GroupBy.DAY);
        assertEquals("2026-10-14", rows.get(0).get("group"));
        assertEquals("2026-10-15", rows.get(1).get("group"));
    }

    @Test
    void weeksStartOnMonday() {
        // 2026-10-14 is a Wednesday, 2026-10-12 the Monday before it
        assertEquals("2026-10-12", ReportCalculator.keyOf(
                b("2026-10-14", TxType.PAYMENT, TxStatus.SUCCESS, null, null, 1, 1, 0), ReportCalculator.GroupBy.WEEK));
        assertEquals("2026-10-12", ReportCalculator.keyOf(
                b("2026-10-12", TxType.PAYMENT, TxStatus.SUCCESS, null, null, 1, 1, 0), ReportCalculator.GroupBy.WEEK));
        assertEquals("2026-10-12", ReportCalculator.keyOf(
                b("2026-10-18", TxType.PAYMENT, TxStatus.SUCCESS, null, null, 1, 1, 0), ReportCalculator.GroupBy.WEEK));
    }

    @Test
    void groupsByMonthMethodAndApp() {
        ReportBucket x = b("2026-10-14", TxType.PAYMENT, TxStatus.SUCCESS, MethodType.CARD, "app_1", 1, 1, 0);
        ReportBucket y = b("2026-10-14", TxType.PAYMENT, TxStatus.SUCCESS, null, null, 1, 1, 0);
        assertEquals("2026-10", ReportCalculator.keyOf(x, ReportCalculator.GroupBy.MONTH));
        assertEquals("CARD", ReportCalculator.keyOf(x, ReportCalculator.GroupBy.METHOD));
        assertEquals("NONE", ReportCalculator.keyOf(y, ReportCalculator.GroupBy.METHOD));
        assertEquals("app_1", ReportCalculator.keyOf(x, ReportCalculator.GroupBy.APP));
        assertEquals("NONE", ReportCalculator.keyOf(y, ReportCalculator.GroupBy.APP));
    }
}

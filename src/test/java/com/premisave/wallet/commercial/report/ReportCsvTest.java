package com.premisave.wallet.commercial.report;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportCsvTest {

    @Test
    void nestedValuesBecomeDottedNames() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("count", 3L);
        inner.put("volume", "9.00");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-10-14");
        data.put("collections", inner);
        assertEquals("metric,value\ndate,2026-10-14\ncollections.count,3\ncollections.volume,9.00\n", ReportCsv.flatten(data));
    }

    @Test
    void cellsWithCommasAreQuotedAndFormulasNeutralised() {
        assertEquals("\"a,b\"", ReportCsv.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ReportCsv.cell("say \"hi\""));
        assertEquals("'=SUM(A1)", ReportCsv.cell("=SUM(A1)"));
        assertEquals("", ReportCsv.cell(null));
    }
}

package com.premisave.wallet.commercial.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain CSV for reports: a metric name and its value, one pair per line. */
public final class ReportCsv {

    private ReportCsv() {}

    /** Nested maps become dotted names, so collections.byMethod.MOBILE_MONEY is one line. */
    public static String flatten(Map<String, Object> data) {
        List<String> rows = new ArrayList<>();
        rows.add("metric,value");
        walk("", data, rows);
        return String.join("\n", rows) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static void walk(String prefix, Map<String, Object> map, List<String> rows) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String name = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> nested) {
                walk(name, (Map<String, Object>) nested, rows);
            } else {
                rows.add(cell(name) + "," + cell(e.getValue() == null ? "" : String.valueOf(e.getValue())));
            }
        }
    }

    public static String cell(String s) {
        if (s == null) return "";
        // A cell that starts with a formula character is prefixed so spreadsheets show it as text.
        String safe = !s.isEmpty() && "=+@".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
        boolean quote = safe.contains(",") || safe.contains("\"") || safe.contains("\n");
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}

package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.report.ReconciliationService;
import com.premisave.wallet.commercial.report.ReportCsv;
import com.premisave.wallet.commercial.report.ReportService;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Section 12: daily and summary reports and the reconciliation report. */
@RestController("commercialReportController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/reports")
public class CommercialReportController {

    private final ReportService reports;
    private final ReconciliationService reconciliation;

    public CommercialReportController(ReportService reports, ReconciliationService reconciliation) {
        this.reports = reports;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/daily")
    public ResponseEntity<?> daily(@RequestParam(required = false) String date,
                                   @RequestParam(required = false) String appId,
                                   @RequestParam(required = false, defaultValue = "JSON") String format) {
        Map<String, Object> report = reports.daily(CommercialContextHolder.require(), date, appId);
        return respond("Daily report", report, format, "daily-report.csv");
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam String from, @RequestParam String to,
                                     @RequestParam(required = false) String groupBy,
                                     @RequestParam(required = false) String appId) {
        CommercialContext ctx = CommercialContextHolder.require();
        return Responses.ok("Summary report", reports.summary(ctx, instant(from, "from"), instant(to, "to"), groupBy, appId));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<?> reconciliation(@RequestParam String from, @RequestParam String to,
                                            @RequestParam(required = false) String provider,
                                            @RequestParam(required = false, defaultValue = "JSON") String format) {
        CommercialContext ctx = CommercialContextHolder.require();
        Map<String, Object> report = reconciliation.reconcile(ctx, instant(from, "from"), instant(to, "to"), provider);
        if (!format.toUpperCase(Locale.ROOT).equals("CSV")) return respond("Reconciliation report", report, format, null);
        return csv(exceptionsCsv(report), "reconciliation-report.csv");
    }

    private ResponseEntity<?> respond(String message, Map<String, Object> report, String format, String csvName) {
        String f = format.toUpperCase(Locale.ROOT);
        if (f.equals("JSON")) return Responses.ok(message, report);
        if (f.equals("CSV") && csvName != null) return csv(ReportCsv.flatten(report), csvName);
        throw CommercialException.validation("format", "must be JSON or CSV");
    }

    private static ResponseEntity<String> csv(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @SuppressWarnings("unchecked")
    private static String exceptionsCsv(Map<String, Object> report) {
        List<String> rows = new ArrayList<>();
        rows.add("type,objectId,amount,ledgerAmount,note");
        for (Map<String, Object> e : (List<Map<String, Object>>) report.get("exceptions")) {
            rows.add(String.join(",", ReportCsv.cell(String.valueOf(e.get("type"))), ReportCsv.cell(String.valueOf(e.get("objectId"))),
                    ReportCsv.cell(String.valueOf(e.get("amount"))),
                    ReportCsv.cell(e.get("ledgerAmount") == null ? "" : String.valueOf(e.get("ledgerAmount"))),
                    ReportCsv.cell(String.valueOf(e.get("note")))));
        }
        return String.join("\n", rows) + "\n";
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
        }
    }
}

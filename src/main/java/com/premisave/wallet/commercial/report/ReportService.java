package com.premisave.wallet.commercial.report;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Money;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Daily and summary reports. The database does the adding up: one grouped
 * query returns a handful of buckets per day, however many transactions there
 * were. A transaction counts on the day it finished, not the day it began.
 */
@Service("commercialReportService")
public class ReportService {

    private static final Duration MAX_SUMMARY_RANGE = Duration.ofDays(366);

    private final MongoTemplate mongo;

    public ReportService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Map<String, Object> daily(CommercialContext ctx, String date, String appId) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(ZoneOffset.UTC) : parseDate(date);
        Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("currency", Money.BASE_CURRENCY);
        out.putAll(ReportCalculator.block(buckets(ctx, from, to, appId)));
        return out;
    }

    public Map<String, Object> summary(CommercialContext ctx, Instant from, Instant to, String groupBy, String appId) {
        if (from == null || to == null) throw CommercialException.validation("from", "from and to are required");
        if (to.isBefore(from)) throw CommercialException.validation("to", "must not be before from");
        if (Duration.between(from, to).compareTo(MAX_SUMMARY_RANGE) > 0) {
            throw CommercialException.validation("to", "the range may not exceed 366 days");
        }
        ReportCalculator.GroupBy by = parseGroup(groupBy);
        List<ReportBucket> all = buckets(ctx, from, to, appId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("currency", Money.BASE_CURRENCY);
        out.put("groupBy", by.name());
        out.put("rows", ReportCalculator.grouped(all, by));
        out.put("totals", ReportCalculator.block(all));
        return out;
    }

    List<ReportBucket> buckets(CommercialContext ctx, Instant from, Instant to, String appId) {
        Document match = new Document("merchantId", ctx.merchantId())
                .append("livemode", ctx.livemode())
                .append("completedAt", new Document("$gte", Date.from(from)).append("$lte", Date.from(to)));
        if (appId != null && !appId.isBlank()) match.append("appId", appId.trim());

        Document id = new Document("day", new Document("$dateToString",
                new Document("format", "%Y-%m-%d").append("date", "$completedAt").append("timezone", "UTC")))
                .append("type", "$type").append("status", "$status").append("method", "$methodType").append("appId", "$appId");
        Document group = new Document("_id", id)
                .append("count", new Document("$sum", 1))
                .append("amount", new Document("$sum", "$amountCents"))
                .append("fee", new Document("$sum", "$feeCents"));

        List<ReportBucket> out = new ArrayList<>();
        for (Document row : mongo.getCollection("commercial_transactions")
                .aggregate(List.of(new Document("$match", match), new Document("$group", group)))) {
            Document key = row.get("_id", Document.class);
            String method = key.getString("method");
            out.add(new ReportBucket(key.getString("day"),
                    TxType.valueOf(key.getString("type")), TxStatus.valueOf(key.getString("status")),
                    method == null ? null : MethodType.valueOf(method), key.getString("appId"),
                    number(row, "count"), number(row, "amount"), number(row, "fee")));
        }
        return out;
    }

    private static long number(Document d, String key) {
        Object v = d.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation("date", "must look like 2026-10-14");
        }
    }

    static ReportCalculator.GroupBy parseGroup(String s) {
        if (s == null || s.isBlank()) return ReportCalculator.GroupBy.DAY;
        try {
            return ReportCalculator.GroupBy.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation("groupBy", "must be DAY, WEEK, MONTH, METHOD or APP");
        }
    }
}

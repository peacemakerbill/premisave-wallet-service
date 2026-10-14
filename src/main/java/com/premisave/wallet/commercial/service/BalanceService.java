package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.LedgerLine;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.CommercialLedgerLineRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Balance and statement for one merchant wallet. All figures are USD. */
@Service("commercialBalanceService")
public class BalanceService {

    private static final Duration MAX_STATEMENT_RANGE = Duration.ofDays(92);

    private final LedgerService ledger;
    private final CommercialLedgerLineRepository lines;
    private final MongoTemplate mongo;
    private final PricingService pricing;
    private final FxConverter fx;

    public BalanceService(LedgerService ledger, CommercialLedgerLineRepository lines, MongoTemplate mongo,
                          PricingService pricing, FxConverter fx) {
        this.ledger = ledger;
        this.lines = lines;
        this.mongo = mongo;
        this.pricing = pricing;
        this.fx = fx;
    }

    public Map<String, Object> balance(CommercialContext ctx, String currency) {
        MerchantWallet w = ledger.wallet(ctx.walletId());
        Map<String, Object> view = Views.balance(w, ctx.livemode());
        if (currency != null && !currency.isBlank()) {
            String c = pricing.normaliseCurrency(currency);
            if (!c.equals(Money.BASE_CURRENCY)) {
                BigDecimal rate = fx.nativePerUsd(c);
                BigDecimal native_ = Money.fromCents(w.getAvailableCents()).multiply(rate).setScale(2, RoundingMode.HALF_UP);
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("currency", c);
                n.put("available", native_.toPlainString());
                n.put("rate", rate.toPlainString());
                view.put("native", List.of(n));
            }
        }
        return view;
    }

    /** Statement for a date range: opening and closing available balance, totals for the whole range and one page of lines. */
    public Map<String, Object> statement(CommercialContext ctx, Instant from, Instant to, int page, int size) {
        validateRange(from, to);
        String walletId = ctx.walletId();
        Query range = Query.query(Criteria.where("walletId").is(walletId).and("createdAt").gte(from).lte(to));

        List<LedgerLine> all = mongo.find(Query.of(range).with(Sort.by(Sort.Direction.ASC, "createdAt")), LedgerLine.class);
        long opening;
        long closing;
        long credits = 0;
        long debits = 0;
        long fees = 0;
        if (all.isEmpty()) {
            LedgerLine before = mongo.findOne(Query.query(Criteria.where("walletId").is(walletId).and("createdAt").lt(from))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1), LedgerLine.class);
            opening = before == null ? 0 : before.getAvailableAfterCents();
            closing = opening;
        } else {
            LedgerLine first = all.get(0);
            opening = first.getAvailableAfterCents() - first.getAvailableDeltaCents();
            closing = all.get(all.size() - 1).getAvailableAfterCents();
            for (LedgerLine l : all) {
                if (l.getAvailableDeltaCents() > 0) credits += l.getAvailableDeltaCents();
                else debits += -l.getAvailableDeltaCents();
                fees += l.getFeeCents();
            }
        }

        Page<LedgerLine> pageOfLines = lines.findByWalletIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                walletId, from, to, PageRequest.of(page, size));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", Money.BASE_CURRENCY);
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("openingBalance", Money.format(opening));
        m.put("closingBalance", Money.format(closing));
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("credits", Money.format(credits));
        totals.put("debits", Money.format(debits));
        totals.put("fees", Money.format(fees));
        m.put("totals", totals);
        m.put("lines", com.premisave.wallet.commercial.dto.PageViews.of(pageOfLines, Views::ledgerLine));
        return m;
    }

    /** The same lines as CSV, newest first, for the whole range. */
    public String statementCsv(CommercialContext ctx, Instant from, Instant to) {
        validateRange(from, to);
        Query range = Query.query(Criteria.where("walletId").is(ctx.walletId()).and("createdAt").gte(from).lte(to))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<String> rows = new ArrayList<>();
        rows.add("at,type,objectId,description,credit,debit,fee,balanceAfter");
        for (LedgerLine l : mongo.find(range, LedgerLine.class)) {
            rows.add(String.join(",",
                    l.getCreatedAt().toString(), l.getEntryType().name(), csv(l.getObjectId()), csv(l.getDescription()),
                    l.getAvailableDeltaCents() > 0 ? Money.format(l.getAvailableDeltaCents()) : "",
                    l.getAvailableDeltaCents() < 0 ? Money.format(-l.getAvailableDeltaCents()) : "",
                    l.getFeeCents() == 0 ? "" : Money.format(l.getFeeCents()),
                    Money.format(l.getAvailableAfterCents())));
        }
        return String.join("\n", rows) + "\n";
    }

    private static void validateRange(Instant from, Instant to) {
        if (from == null || to == null) throw CommercialException.validation("from", "from and to are required");
        if (to.isBefore(from)) throw CommercialException.validation("to", "must not be before from");
        if (Duration.between(from, to).compareTo(MAX_STATEMENT_RANGE) > 0) {
            throw CommercialException.validation("to", "the range may not exceed 92 days");
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n");
        String escaped = s.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }
}

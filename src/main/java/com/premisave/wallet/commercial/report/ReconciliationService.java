package com.premisave.wallet.commercial.report;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.LedgerLine;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reconciliation report (section 12.3). It checks the merchant's
 * transactions against the merchant's ledger, using the rules in
 * ReconciliationRules, and lists every disagreement.
 *
 * Provider statements are not available to this service, so the report does
 * not compare against a provider: it says so in providerComparison, and the
 * totals compare the transactions with the ledger only. At most
 * MAX_TRANSACTIONS are examined; if there are more, truncated is true and a
 * narrower range gives the rest.
 */
@Service("commercialReconciliationService")
public class ReconciliationService {

    static final int MAX_TRANSACTIONS = 20_000;
    private static final int PAGE_SIZE = 500;
    private static final Duration MAX_RANGE = Duration.ofDays(31);

    private final MongoTemplate mongo;

    public ReconciliationService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Map<String, Object> reconcile(CommercialContext ctx, Instant from, Instant to, String provider) {
        if (from == null || to == null) throw CommercialException.validation("from", "from and to are required");
        if (to.isBefore(from)) throw CommercialException.validation("to", "must not be before from");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw CommercialException.validation("to", "the range may not exceed 31 days");
        }

        List<ReconciliationRules.Finding> findings = new ArrayList<>();
        long matched = 0;
        long examined = 0;
        long transactionsCents = 0;
        long ledgerCents = 0;
        boolean truncated = false;
        Instant now = Instant.now();

        Criteria base = Criteria.where("merchantId").is(ctx.merchantId()).and("livemode").is(ctx.livemode());
        if (provider != null && !provider.isBlank()) base = base.and("provider").is(provider.trim().toUpperCase());
        Criteria finished = new Criteria().andOperator(base, Criteria.where("completedAt").gte(from).lte(to));
        Criteria stillOpen = new Criteria().andOperator(base,
                Criteria.where("status").in(TxStatus.OPEN.stream().map(Enum::name).toList()),
                Criteria.where("createdAt").gte(from).lte(to));

        for (Criteria scope : List.of(finished, stillOpen)) {
            for (int page = 0; ; page++) {
                if (examined >= MAX_TRANSACTIONS) {
                    truncated = true;
                    break;
                }
                List<CommercialTransaction> txs = mongo.find(Query.query(scope)
                        .with(PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"))), CommercialTransaction.class);
                if (txs.isEmpty()) break;

                Map<String, List<ReconciliationRules.Posting>> postings = postingsFor(ctx, txs);
                for (CommercialTransaction tx : txs) {
                    examined++;
                    List<ReconciliationRules.Posting> mine = postings.getOrDefault(tx.getId(), List.of());
                    List<ReconciliationRules.Finding> found = ReconciliationRules.check(tx, mine, now);
                    if (found.isEmpty()) matched++;
                    findings.addAll(found);
                    if (tx.getStatus() == TxStatus.SUCCESS && (tx.getType() == TxType.PAYMENT || tx.getType().usesHold())) {
                        transactionsCents += tx.getSettleCents();
                        ledgerCents += moved(mine);
                    }
                }
                if (txs.size() < PAGE_SIZE) break;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("examined", examined);
        out.put("matched", matched);
        out.put("truncated", truncated);
        List<Map<String, Object>> exceptions = new ArrayList<>();
        for (ReconciliationRules.Finding f : findings) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", f.type());
            e.put("objectId", f.objectId());
            e.put("amount", Money.format(f.amountCents()));
            if (f.ledgerCents() != null) e.put("ledgerAmount", Money.format(f.ledgerCents()));
            e.put("note", f.note());
            exceptions.add(e);
        }
        out.put("exceptions", exceptions);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("transactions", Money.format(transactionsCents));
        totals.put("ledger", Money.format(ledgerCents));
        totals.put("difference", Money.format(transactionsCents - ledgerCents));
        totals.put("currency", Money.BASE_CURRENCY);
        out.put("totals", totals);
        out.put("providerComparison", "NOT_AVAILABLE");
        return out;
    }

    /** What the ledger moved for a successful transaction: the credit for a collection, the debit for an outflow. */
    private static long moved(List<ReconciliationRules.Posting> postings) {
        long sum = 0;
        for (ReconciliationRules.Posting p : postings) {
            switch (p.type()) {
                case COLLECTION_CREDIT -> sum += p.availableDelta();
                case PAYOUT_DEBIT -> sum += -p.heldDelta();
                default -> { }
            }
        }
        return sum;
    }

    private Map<String, List<ReconciliationRules.Posting>> postingsFor(CommercialContext ctx, List<CommercialTransaction> txs) {
        List<String> ids = txs.stream().map(CommercialTransaction::getId).toList();
        Query q = Query.query(Criteria.where("walletId").is(ctx.walletId()).and("objectId").in(ids));
        Map<String, List<ReconciliationRules.Posting>> byObject = new HashMap<>();
        for (LedgerLine l : mongo.find(q, LedgerLine.class)) {
            byObject.computeIfAbsent(l.getObjectId(), k -> new ArrayList<>()).add(new ReconciliationRules.Posting(
                    l.getEntryType(), l.getAvailableDeltaCents(), l.getHeldDeltaCents(), l.getFeeCents()));
        }
        return byObject;
    }
}

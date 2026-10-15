package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.dto.RetryInvoiceRequest;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Invoices (sections 8.16 and 8.17). */
@Service("commercialInvoiceService")
public class InvoiceService {

    private final CommercialIdempotencyService idempotency;
    private final BillingInvoiceRepository invoices;
    private final BillingSubscriptionRepository subs;
    private final BillingEngine engine;
    private final MongoTemplate mongo;

    public InvoiceService(CommercialIdempotencyService idempotency, BillingInvoiceRepository invoices,
                          BillingSubscriptionRepository subs, BillingEngine engine, MongoTemplate mongo) {
        this.idempotency = idempotency;
        this.invoices = invoices;
        this.subs = subs;
        this.engine = engine;
        this.mongo = mongo;
    }

    public Page<Invoice> listFor(CommercialContext ctx, String subscriptionId, Map<String, String> f, Pageable pageable) {
        subs.findByIdAndMerchantIdAndLivemode(subscriptionId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Subscription"));
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("subscriptionId").is(subscriptionId));
        String status = f.get("status");
        if (status != null && !status.isBlank()) {
            try {
                all.add(Criteria.where("status").is(Invoice.Status.valueOf(status.trim().toUpperCase(Locale.ROOT)).name()));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("status", "must be OPEN, AWAITING_CUSTOMER, PAID, PAYMENT_FAILED or VOID");
            }
        }
        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, Invoice.class);
        return new PageImpl<>(mongo.find(q.with(pageable), Invoice.class), pageable, total);
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> retry(CommercialContext ctx, String invoiceId,
                                                                  RetryInvoiceRequest req, String idempotencyKey, String bodyHash) {
        invoices.findByIdAndMerchantIdAndLivemode(invoiceId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Invoice"));
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "INVOICE_RETRY",
                () -> {
                    engine.retryManually(ctx, invoiceId, req == null ? null : req.paymentMethodId());
                    return invoiceId;
                },
                id -> BillingViews.invoice(invoices.findById(id).orElseThrow(() -> CommercialException.notFound("Invoice"))));
    }
}

package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.CommercialTransactionRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads and searches a merchant's own transactions. Every query is pinned to merchant and environment. */
@Service("commercialTransactionQueryService")
public class TransactionQueryService {

    private final MongoTemplate mongo;
    private final CommercialTransactionRepository repo;

    public TransactionQueryService(MongoTemplate mongo, CommercialTransactionRepository repo) {
        this.mongo = mongo;
        this.repo = repo;
    }

    /** One transaction, or NOT_FOUND. An id that belongs to another merchant is reported as not found. */
    public CommercialTransaction get(CommercialContext ctx, String id, TxType type) {
        return repo.findByIdAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                .filter(t -> type == null || t.getType() == type)
                .orElseThrow(() -> CommercialException.notFound(type == null ? "Transaction" : label(type)));
    }

    /**
     * Filters (all optional): status, method, provider, merchantReference, customerEmail,
     * customerPhone, destinationType, from, to, minAmount, maxAmount (USD), appId, type.
     */
    public Page<CommercialTransaction> search(CommercialContext ctx, TxType fixedType, Map<String, String> f,
                                              Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));

        TxType type = fixedType != null ? fixedType : parseEnum(f.get("type"), TxType.class, "type");
        if (type != null) all.add(Criteria.where("type").is(type.name()));

        TxStatus status = parseEnum(f.get("status"), TxStatus.class, "status");
        if (status != null) all.add(Criteria.where("status").is(status.name()));
        MethodType method = parseEnum(f.get("method"), MethodType.class, "method");
        if (method != null) all.add(Criteria.where("methodType").is(method.name()));

        eq(all, "provider", upper(f.get("provider")));
        eq(all, "merchantReference", f.get("merchantReference"));
        eq(all, "appId", f.get("appId"));
        eq(all, "customer.email", f.get("customerEmail"));
        eq(all, "customer.phone", f.get("customerPhone"));
        eq(all, "destination.type", upper(f.get("destinationType")));

        Instant from = instant(f.get("from"), "from");
        Instant to = instant(f.get("to"), "to");
        if (from != null) all.add(Criteria.where("createdAt").gte(from));
        if (to != null) all.add(Criteria.where("createdAt").lte(to));

        BigDecimal min = decimal(f.get("minAmount"), "minAmount");
        BigDecimal max = decimal(f.get("maxAmount"), "maxAmount");
        if (min != null) all.add(Criteria.where("amountCents").gte(Money.toCents(min)));
        if (max != null) all.add(Criteria.where("amountCents").lte(Money.toCents(max)));

        Query query = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(query, CommercialTransaction.class);
        List<CommercialTransaction> page = mongo.find(query.with(pageable), CommercialTransaction.class);
        return new PageImpl<>(page, pageable, total);
    }

    private static void eq(List<Criteria> all, String field, String value) {
        if (value != null && !value.isBlank()) all.add(Criteria.where(field).is(value));
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation(field, "is not a valid value");
        }
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
        }
    }

    private static BigDecimal decimal(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw CommercialException.validation(field, "must be a decimal number");
        }
    }

    private static String label(TxType type) {
        return switch (type) {
            case PAYMENT -> "Payment";
            case PAYOUT -> "Payout";
            default -> "Transaction";
        };
    }
}

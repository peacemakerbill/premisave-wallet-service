package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.config.CommercialRefundProperties;
import com.premisave.wallet.commercial.dispute.Dispute;
import com.premisave.wallet.commercial.dto.CreateRefundRequest;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RefundCommand;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.CommercialTransactionStore;
import com.premisave.wallet.commercial.service.HeldDebit;
import com.premisave.wallet.commercial.service.RailRegistry;
import com.premisave.wallet.commercial.service.TransactionOutcomeService;
import com.premisave.wallet.commercial.service.TransactionQueryService;
import com.premisave.wallet.commercial.transfer.PremisaveWalletCredit;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refunds (sections 9.1 to 9.3). A refund is a transaction of type REFUND that follows the same
 * hold, capture or release path as a payout: the amount is held on the merchant balance, then
 * captured when the customer has been paid back or released when that failed.
 *
 * How the customer is paid back depends on the original payment:
 * the provider refunds or reverses it when the rail can, mobile money is sent back to the customer's
 * phone, and a Premisave wallet payment is credited back to the wallet. A refund carries no fee; the
 * fee charged on the original payment is not returned.
 */
@Service("commercialRefundService")
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final long RECOVER_AFTER_SECONDS = 60;

    private final CommercialIdempotencyService idempotency;
    private final TransactionQueryService queries;
    private final RailRegistry rails;
    private final HeldDebit held;
    private final PremisaveWalletCredit credit;
    private final TransactionOutcomeService outcomes;
    private final RefundReservations reservations;
    private final CommercialTransactionStore store;
    private final CommercialRefundProperties props;
    private final MongoTemplate mongo;

    public RefundService(CommercialIdempotencyService idempotency, TransactionQueryService queries, RailRegistry rails,
                         HeldDebit held, PremisaveWalletCredit credit, TransactionOutcomeService outcomes,
                         RefundReservations reservations, CommercialTransactionStore store,
                         CommercialRefundProperties props, MongoTemplate mongo) {
        this.idempotency = idempotency;
        this.queries = queries;
        this.rails = rails;
        this.held = held;
        this.credit = credit;
        this.outcomes = outcomes;
        this.reservations = reservations;
        this.store = store;
        this.props = props;
        this.mongo = mongo;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreateRefundRequest req,
                                                                    String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "REFUND",
                () -> doCreate(ctx, req),
                id -> RefundViews.refund(queries.get(ctx, id, TxType.REFUND)));
    }

    private String doCreate(CommercialContext ctx, CreateRefundRequest req) {
        CommercialTransaction payment = queries.get(ctx, req.paymentId().trim(), TxType.PAYMENT);
        if (payment.getStatus() != TxStatus.SUCCESS) {
            throw CommercialException.invalidState("Only a successful payment can be refunded; this one is " + payment.getStatus());
        }
        if (payment.getRefundedCents() >= payment.getAmountCents()) {
            throw CommercialException.invalidState("This payment has already been refunded in full");
        }
        long active = mongo.count(Query.query(Criteria.where("paymentId").is(payment.getId())
                .and("status").in(Dispute.ACTIVE.stream().map(Enum::name).toList())), Dispute.class);
        if (active > 0) {
            throw CommercialException.invalidState("This payment is under dispute; refund it only after the dispute is decided");
        }

        PaymentRail original = rails.byId(payment.getRail());
        RefundMethod method = RefundMethod.choose(payment.getMethodType(), original.refundMode(payment))
                .orElseThrow(() -> new CommercialException(ErrorCode.METHOD_UNAVAILABLE,
                        "Payments made by " + payment.getMethodType() + " cannot be refunded through this API yet"));

        long remaining = payment.getAmountCents() - payment.getRefundedCents();
        RefundAmounts.Resolved amount = RefundAmounts.resolve(payment.getAmountCents(), payment.getNativeAmount(),
                payment.getNativeCurrency(), remaining, req.amount(), req.currency());
        CommercialPaymentService.checkCallbackUrl(ctx, req.callbackUrl());
        Map<String, String> metadata = RequestValidators.metadata(req.metadata());

        // Everything that can refuse the refund happens before any money is reserved or held.
        Destination dest = destinationFor(payment, method);
        PaymentRail rail = railFor(ctx, payment, method, original, amount);

        String id = Ids.next("rf");
        CommercialTransaction tx = new CommercialTransaction();
        tx.setId(id);
        tx.setMerchantId(ctx.merchantId());
        tx.setAppId(ctx.appId());
        tx.setLivemode(ctx.livemode());
        tx.setType(TxType.REFUND);
        tx.setStatus(TxStatus.PENDING);
        tx.setMerchantReference(req.merchantReference() == null || req.merchantReference().isBlank()
                ? payment.getMerchantReference() : req.merchantReference().trim());
        tx.setDescription(req.reason() == null || req.reason().isBlank() ? null : req.reason().trim());
        tx.setAmountCents(amount.usdCents());
        tx.setFeeCents(0);
        tx.setSettleCents(amount.usdCents());
        tx.setCurrency(Money.BASE_CURRENCY);
        tx.setNativeAmount(amount.nativeAmount());
        tx.setNativeCurrency(payment.getNativeCurrency());
        tx.setFxRate(payment.getFxRate());
        tx.setMethodType(payment.getMethodType());
        tx.setRail(rail == null ? RailId.PREMISAVE : rail.id());
        tx.setProvider(rail == null ? "PREMISAVE" : rail.providerName(payment.getMethodType()));
        tx.setDestination(dest.fields());
        tx.setCallbackUrl(req.callbackUrl() == null || req.callbackUrl().isBlank() ? null : req.callbackUrl().trim());
        tx.setMetadata(metadata);
        Map<String, String> relations = new LinkedHashMap<>();
        relations.put("paymentId", payment.getId());
        relations.put("refundMethod", method.name());
        relations.put("reserved", "true");
        if (amount.usdCents() > Money.toCents(props.approvalThresholdUsd())) relations.put("requiresApproval", "true");
        if (method == RefundMethod.WALLET_CREDIT) {
            relations.put("senderName", ctx.merchant().getDisplayName() != null ? ctx.merchant().getDisplayName()
                    : ctx.merchant().getBusinessName());
        }
        tx.setRelations(relations);

        reserve(payment, amount.usdCents());
        try {
            held.open(ctx.walletId(), tx, "Refund " + tx.getMerchantReference() + " of payment " + payment.getId()
                    + " (amount " + Money.format(amount.usdCents()) + ")");
        } catch (RuntimeException e) {
            // Insufficient balance and similar: nothing was recorded, so the reservation goes back here.
            // If the refund was recorded and then failed, the finish listener returns it instead.
            if (store.find(id).isEmpty()) reservations.releaseUnrecorded(payment.getId(), amount.usdCents());
            throw e;
        }

        switch (method) {
            case PAYOUT_TO_CUSTOMER -> held.dispatch(rail, new PayoutCommand(id, ctx.merchantId(), ctx.livemode(),
                    DestinationType.MOBILE_MONEY, amount.usdCents(), amount.nativeAmount(), nativeCurrencyOf(payment),
                    dest.fields(), tx.getDescription()), tx);
            case PROVIDER_REFUND, PROVIDER_REVERSAL -> held.send(() -> rail.refund(new RefundCommand(id, ctx.merchantId(),
                    ctx.livemode(), payment.getId(), payment.getProviderReference(), amount.usdCents(), amount.nativeAmount(),
                    nativeCurrencyOf(payment), tx.getDescription())), tx);
            case WALLET_CREDIT -> {
                try {
                    completeWalletCredit(tx);
                } catch (RuntimeException e) {
                    // The hold stays and the sweep finishes it; failing here would invite a second refund.
                    log.error("Refund {} was held but could not be completed yet; the sweep will retry", id, e);
                }
            }
        }
        return id;
    }

    private void reserve(CommercialTransaction payment, long cents) {
        if (reservations.reserve(payment.getId(), cents, payment.getAmountCents())) return;
        // The figure may be short because an earlier crash left a reservation behind; fix that once and try again.
        if (reservations.repair(payment.getId(), Instant.now()) && reservations.reserve(payment.getId(), cents, payment.getAmountCents())) {
            return;
        }
        CommercialTransaction now = store.find(payment.getId()).orElse(payment);
        if (now.getStatus() != TxStatus.SUCCESS) {
            throw CommercialException.invalidState("Only a successful payment can be refunded; this one is " + now.getStatus());
        }
        long left = Math.max(0, now.getAmountCents() - now.getRefundedCents());
        if (left == 0) throw CommercialException.invalidState("This payment has already been refunded in full");
        throw new CommercialException(ErrorCode.LIMIT_EXCEEDED, "Amount exceeds the refundable balance of this payment")
                .with("refundable", Money.format(left)).with("currency", Money.BASE_CURRENCY);
    }

    private record Destination(Map<String, Object> fields) {}

    private static Destination destinationFor(CommercialTransaction payment, RefundMethod method) {
        Map<String, Object> d = new LinkedHashMap<>();
        switch (method) {
            case PAYOUT_TO_CUSTOMER -> {
                Object phone = payment.getCustomer().get("phone");
                if (phone == null) throw CommercialException.invalidState("The payment has no customer phone number to send the refund to");
                d.put("type", DestinationType.MOBILE_MONEY.name());
                d.put("phoneNumber", String.valueOf(phone));
            }
            case WALLET_CREDIT -> {
                Object ref = payment.getCustomer().get("accountRef");
                if (ref == null) throw CommercialException.invalidState("The payment has no wallet reference to credit the refund to");
                d.put("type", DestinationType.PREMISAVE_WALLET.name());
                d.put("accountRef", String.valueOf(ref));
            }
            default -> { }
        }
        return new Destination(d);
    }

    /** The rail that carries the money back; null for a wallet credit, which Premisave performs itself. */
    private PaymentRail railFor(CommercialContext ctx, CommercialTransaction payment, RefundMethod method,
                                PaymentRail original, RefundAmounts.Resolved amount) {
        return switch (method) {
            case PROVIDER_REFUND, PROVIDER_REVERSAL -> original;
            case PAYOUT_TO_CUSTOMER -> rails.select(ctx.environment(), Direction.PAYOUT, MethodType.MOBILE_MONEY, null, null,
                    nativeCurrencyOf(payment));
            case WALLET_CREDIT -> null;
        };
    }

    private static String nativeCurrencyOf(CommercialTransaction payment) {
        return payment.getNativeCurrency() != null ? payment.getNativeCurrency() : Money.BASE_CURRENCY;
    }

    /** Credits the customer's wallet (live) and records the result. Safe to run again for the same refund. */
    void completeWalletCredit(CommercialTransaction tx) {
        try {
            if (tx.isLivemode()) {
                String sender = tx.getRelations().get("senderName");
                credit.credit(String.valueOf(tx.getDestination().get("accountRef")), tx.getAmountCents(), tx.getId(),
                        "Refund from " + (sender == null ? "a Premisave merchant" : sender)
                                + (tx.getDescription() == null ? "" : " - " + tx.getDescription()));
            }
            outcomes.apply(tx, new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Refund completed", null, null, null));
        } catch (PremisaveWalletCredit.Refused refused) {
            outcomes.apply(tx, RailOutcome.of(TxStatus.FAILED, refused.code(), refused.getMessage()));
        }
    }

    /** Finishes wallet credit refunds that were held but never completed, for example after a restart. */
    @Scheduled(fixedDelayString = "${commercial.refunds.recovery-interval-ms:30000}")
    public void recover() {
        Criteria c = Criteria.where("type").is(TxType.REFUND.name())
                .and("relations.refundMethod").is(RefundMethod.WALLET_CREDIT.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("createdAt").lt(Instant.now().minusSeconds(RECOVER_AFTER_SECONDS));
        for (CommercialTransaction tx : mongo.find(Query.query(c).with(PageRequest.of(0, 50)), CommercialTransaction.class)) {
            try {
                log.warn("Finishing refund {} that stopped before completing", tx.getId());
                completeWalletCredit(tx);
            } catch (RuntimeException e) {
                log.error("Could not finish refund {}", tx.getId(), e);
            }
        }
    }
}

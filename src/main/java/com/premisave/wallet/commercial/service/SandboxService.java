package com.premisave.wallet.commercial.service;

import com.mongodb.client.result.DeleteResult;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.dto.dispute.DisputeViews;
import com.premisave.wallet.commercial.dto.dispute.ResolveDisputeRequest;
import com.premisave.wallet.commercial.dto.refund.RefundViews;
import com.premisave.wallet.commercial.dto.sandbox.CreateTestDisputeRequest;
import com.premisave.wallet.commercial.dto.sandbox.CreditRequest;
import com.premisave.wallet.commercial.dto.sandbox.ResetRequest;
import com.premisave.wallet.commercial.dto.sandbox.SimulateRequest;
import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.entity.CommercialIdempotencyRecord;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.LedgerLine;
import com.premisave.wallet.commercial.entity.MerchantHold;
import com.premisave.wallet.commercial.entity.Quote;
import com.premisave.wallet.commercial.entity.batch.PayoutBatch;
import com.premisave.wallet.commercial.entity.beneficiary.BeneficiaryCheck;
import com.premisave.wallet.commercial.entity.dispute.Dispute;
import com.premisave.wallet.commercial.entity.link.AccountReference;
import com.premisave.wallet.commercial.entity.link.PaymentLink;
import com.premisave.wallet.commercial.entity.settlement.SettlementSchedule;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.SandboxRail;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.dispute.DisputeService;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Helpers that exist only in the sandbox: pick an outcome, add funds, send sample events, start over. */
@Service("commercialSandboxService")
public class SandboxService {

    private static final BigDecimal MAX_CREDIT_USD = new BigDecimal("100000");
    private static final Set<TxStatus> PAYMENT_OUTCOMES = EnumSet.of(TxStatus.SUCCESS, TxStatus.FAILED, TxStatus.TIMED_OUT, TxStatus.CANCELLED);
    private static final Set<TxStatus> PAYOUT_OUTCOMES = EnumSet.of(TxStatus.SUCCESS, TxStatus.FAILED, TxStatus.TIMED_OUT, TxStatus.REVERSED);

    private final TransactionQueryService queries;
    private final TransactionOutcomeService outcomes;
    private final SandboxRail sandboxRail;
    private final LedgerService ledger;
    private final BalanceService balances;
    private final PricingService pricing;
    private final FxConverter fx;
    private final MongoTemplate mongo;
    private final DisputeService disputes;

    public SandboxService(TransactionQueryService queries, TransactionOutcomeService outcomes, SandboxRail sandboxRail,
                          LedgerService ledger, BalanceService balances, PricingService pricing, FxConverter fx,
                          MongoTemplate mongo, DisputeService disputes) {
        this.queries = queries;
        this.outcomes = outcomes;
        this.sandboxRail = sandboxRail;
        this.ledger = ledger;
        this.balances = balances;
        this.pricing = pricing;
        this.fx = fx;
        this.mongo = mongo;
        this.disputes = disputes;
    }

    /** Applies the chosen outcome now, or after delaySeconds, exactly as a provider result would be applied. */
    public Map<String, Object> simulate(CommercialContext ctx, String id, TxType type, SimulateRequest req) {
        requireSandbox(ctx);
        CommercialTransaction tx = queries.get(ctx, id, type);
        Set<TxStatus> allowed = type == TxType.PAYMENT ? PAYMENT_OUTCOMES : PAYOUT_OUTCOMES;
        if (!allowed.contains(req.outcome())) {
            throw CommercialException.validation("outcome", "must be one of " + allowed);
        }
        boolean reversal = req.outcome() == TxStatus.REVERSED;
        if (reversal ? tx.getStatus() != TxStatus.SUCCESS : tx.getStatus().isFinal()) {
            throw CommercialException.invalidState("The " + type.name().toLowerCase(Locale.ROOT) + " is already " + tx.getStatus());
        }

        RailOutcome outcome = RailOutcome.of(req.outcome(), codeFor(type, req), describe(req.outcome()));
        int delay = req.delaySeconds() == null ? 0 : req.delaySeconds();
        if (delay > 0) {
            sandboxRail.simulate(tx.getId(), outcome, delay);
            return view(tx);
        }
        return view(outcomes.apply(tx, outcome));
    }

    /** Section 13.3: opens a dispute on a successful sandbox payment, as a provider would. */
    public Map<String, Object> createDispute(CommercialContext ctx, CreateTestDisputeRequest req) {
        requireSandbox(ctx);
        CommercialTransaction payment = queries.get(ctx, req.paymentId().trim(), TxType.PAYMENT);
        if (req.amount() != null && Money.hasMoreThanCents(req.amount())) {
            throw CommercialException.validation("amount", "must have at most two decimal places");
        }
        long cents = req.amount() == null ? payment.getAmountCents() : Money.toCents(req.amount());
        return DisputeViews.dispute(disputes.open(payment, cents, req.reason(), null, null));
    }

    /** Decides a sandbox dispute: WON releases the held amount, LOST takes it. */
    public Map<String, Object> resolveDispute(CommercialContext ctx, String id, ResolveDisputeRequest req) {
        requireSandbox(ctx);
        Dispute d = disputes.get(ctx, id);
        String outcome = req.outcome().trim().toUpperCase(Locale.ROOT);
        if (!outcome.equals("WON") && !outcome.equals("LOST")) {
            throw CommercialException.validation("outcome", "must be WON or LOST");
        }
        return DisputeViews.dispute(disputes.resolve(d.getId(), outcome.equals("WON")));
    }

    public Map<String, Object> credit(CommercialContext ctx, CreditRequest req) {
        requireSandbox(ctx);
        String currency = pricing.normaliseCurrency(req.currency());
        BigDecimal usd = currency.equals(Money.BASE_CURRENCY) ? req.amount()
                : req.amount().divide(fx.nativePerUsd(currency), 2, java.math.RoundingMode.HALF_UP);
        if (Money.hasMoreThanCents(req.amount())) throw CommercialException.validation("amount", "must have at most two decimal places");
        if (usd.compareTo(MAX_CREDIT_USD) > 0) throw CommercialException.validation("amount", "may not exceed 100000 USD per call");
        long cents = Money.toCents(usd);
        if (cents <= 0) throw CommercialException.validation("amount", "must be at least 0.01 USD");
        ledger.fund(ctx.walletId(), cents, "Sandbox credit");
        return balances.balance(ctx, null);
    }

    public Map<String, Object> reset(CommercialContext ctx, ResetRequest req) {
        requireSandbox(ctx);
        if (req == null || !"RESET".equals(req.confirm())) {
            throw CommercialException.validation("confirm", "must be the text RESET");
        }
        List<String> keep = req.keep() == null ? List.of() : req.keep();
        for (String k : keep) {
            if (!k.equals("callbacks") && !k.equals("plans") && !k.equals("customers")) {
                throw CommercialException.validation("keep", "'" + k + "' is not one of callbacks, plans, customers");
            }
        }
        String merchant = ctx.merchantId();
        Query byMerchant = Query.query(Criteria.where("merchantId").is(merchant).and("livemode").is(false));

        long payments = count(mongo.remove(Query.query(Criteria.where("merchantId").is(merchant).and("livemode").is(false)
                .and("type").is(TxType.PAYMENT.name())), CommercialTransaction.class));
        long payouts = count(mongo.remove(Query.query(Criteria.where("merchantId").is(merchant).and("livemode").is(false)
                .and("type").is(TxType.PAYOUT.name())), CommercialTransaction.class));
        long other = count(mongo.remove(Query.query(Criteria.where("merchantId").is(merchant).and("livemode").is(false)
                .and("type").nin(TxType.PAYMENT.name(), TxType.PAYOUT.name())), CommercialTransaction.class));
        long eventCount = count(mongo.remove(byMerchant, CommercialEvent.class));
        mongo.remove(byMerchant, Quote.class);
        mongo.remove(byMerchant, CommercialIdempotencyRecord.class);
        mongo.remove(byMerchant, MerchantHold.class);
        mongo.remove(byMerchant, SettlementSchedule.class);
        mongo.remove(byMerchant, PaymentLink.class);
        mongo.remove(byMerchant, AccountReference.class);
        mongo.remove(byMerchant, PayoutBatch.class);
        mongo.remove(byMerchant, BeneficiaryCheck.class);
        mongo.remove(byMerchant, Dispute.class);
        mongo.remove(byMerchant, com.premisave.wallet.commercial.entity.billing.Subscription.class);
        mongo.remove(byMerchant, com.premisave.wallet.commercial.entity.billing.Invoice.class);
        if (!keep.contains("plans")) mongo.remove(byMerchant, com.premisave.wallet.commercial.entity.billing.Plan.class);
        if (!keep.contains("customers")) {
            mongo.remove(byMerchant, com.premisave.wallet.commercial.entity.billing.BillingCustomer.class);
            mongo.remove(byMerchant, com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod.class);
        }
        mongo.remove(Query.query(Criteria.where("walletId").is(ctx.walletId())), LedgerLine.class);
        if (!keep.contains("callbacks")) mongo.remove(byMerchant, CallbackRegistration.class);
        ledger.resetWallet(ctx.walletId());

        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put("payments", payments);
        deleted.put("payouts", payouts);
        deleted.put("other", other);
        deleted.put("events", eventCount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        return out;
    }

    private static void requireSandbox(CommercialContext ctx) {
        if (ctx.environment() != Environment.SANDBOX) throw CommercialException.notFound("Resource");
    }

    private static long count(DeleteResult r) {
        return r == null ? 0 : r.getDeletedCount();
    }

    private static ResultCode codeFor(TxType type, SimulateRequest req) {
        if (req.resultCode() != null) return req.resultCode();
        return switch (req.outcome()) {
            case SUCCESS -> ResultCode.SUCCESS;
            case FAILED -> ResultCode.DECLINED;
            case TIMED_OUT -> type == TxType.PAYMENT ? ResultCode.CUSTOMER_TIMEOUT : ResultCode.TIMED_OUT;
            case CANCELLED -> ResultCode.CUSTOMER_CANCELLED;
            case REVERSED -> ResultCode.REVERSED;
            default -> ResultCode.PROVIDER_ERROR;
        };
    }

    private static String describe(TxStatus s) {
        return "Simulated " + s.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Map<String, Object> view(CommercialTransaction tx) {
        return switch (tx.getType()) {
            case PAYMENT -> Views.payment(tx);
            case REFUND -> RefundViews.refund(tx);
            default -> Views.payout(tx);
        };
    }
}

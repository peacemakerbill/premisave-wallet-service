package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.dto.payment.CreatePayoutRequest;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantHold;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.entity.Quote;
import com.premisave.wallet.commercial.entity.beneficiary.BeneficiaryCheck;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.repository.MerchantHoldRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.beneficiary.BeneficiaryService;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Payouts: sending money from the merchant balance. The amount plus fee is held
 * before anything is sent, so many payouts in flight can never overspend
 * the balance. The hold becomes a debit on success and is released otherwise.
 *
 * Bulk payouts reuse prepare (everything decidable without moving money), then queue
 * (hold and record) and dispatchQueued (send) at a controlled rate.
 */
@Service
public class CommercialPayoutService {

    private final CommercialIdempotencyService idempotency;
    private final PricingService pricing;
    private final QuoteService quotes;
    private final LimitService limits;
    private final RailRegistry rails;
    private final LedgerService ledger;
    private final MerchantHoldRepository holds;
    private final CommercialTransactionStore store;
    private final TransactionOutcomeService outcomes;
    private final TransactionQueryService queries;
    private final BeneficiaryService beneficiaries;
    private final HeldDebit held;

    public CommercialPayoutService(CommercialIdempotencyService idempotency, PricingService pricing, QuoteService quotes,
                                   LimitService limits, RailRegistry rails, LedgerService ledger,
                                   MerchantHoldRepository holds, CommercialTransactionStore store,
                                   TransactionOutcomeService outcomes, TransactionQueryService queries,
                                   BeneficiaryService beneficiaries, HeldDebit held) {
        this.idempotency = idempotency;
        this.pricing = pricing;
        this.quotes = quotes;
        this.limits = limits;
        this.rails = rails;
        this.ledger = ledger;
        this.holds = holds;
        this.store = store;
        this.outcomes = outcomes;
        this.queries = queries;
        this.beneficiaries = beneficiaries;
        this.held = held;
    }

    /**
     * Everything about a payout that can be decided without moving money: the cleaned destination, the
     * price, the rail and the beneficiary check.
     */
    public record Prepared(DestinationType destType, MethodType method, Map<String, Object> destination,
                           Map<String, String> metadata, Quote quote, Priced priced, PaymentRail rail,
                           BeneficiaryCheck beneficiary) {}

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreatePayoutRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "PAYOUT",
                () -> doCreate(ctx, req),
                id -> Views.payout(queries.get(ctx, id, TxType.PAYOUT)));
    }

    /**
     * withLimits is false for bulk items, whose limits are checked one by one as each is recorded
     * (see queue), so earlier items count against later ones.
     */
    public Prepared prepare(CommercialContext ctx, CreatePayoutRequest req, boolean withLimits) {
        DestinationType destType = RequestValidators.destinationType(req.destination());
        MethodType method = destType.method();
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), method)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, method + " is not enabled for this merchant or app");
        }
        Map<String, Object> destination = RequestValidators.destination(destType, req.destination());
        Map<String, String> metadata = RequestValidators.metadata(req.metadata());
        CommercialPaymentService.checkCallbackUrl(ctx, req.callbackUrl());

        Quote quote = req.quoteId() == null ? null
                : quotes.requireUsable(ctx, req.quoteId(), Direction.PAYOUT, method, req.amount(), req.currency());
        Priced priced = pricing.price(Direction.PAYOUT, method, req.amount(), req.currency(), quote);
        if (withLimits) limits.check(ctx, TxType.PAYOUT, priced.usdCents());
        String country = destination.get("country") == null ? null : String.valueOf(destination.get("country"));
        PaymentRail rail = rails.select(ctx.environment(), Direction.PAYOUT, method, req.provider(), country, priced.currency());
        // Last, because it may call a provider: nothing has been held or recorded if this refuses.
        BeneficiaryCheck beneficiary = beneficiaries.gate(ctx, destType, destination, req.beneficiaryCheck());
        return new Prepared(destType, method, destination, metadata, quote, priced, rail, beneficiary);
    }

    /**
     * Holds the money and records a bulk item without sending it. relations marks the item as part of
     * its batch and queued; the dispatcher sends it later with dispatchQueued.
     */
    public CommercialTransaction queue(CommercialContext ctx, CreatePayoutRequest req, Prepared p, Map<String, String> relations) {
        limits.check(ctx, TxType.PAYOUT, p.priced().usdCents());
        CommercialTransaction tx = build(ctx, Ids.next("po"), req, p);
        tx.getRelations().putAll(relations);
        return held.open(ctx.walletId(), tx, "Payout " + req.merchantReference() + " (amount " + Money.format(p.priced().usdCents())
                + ", fee " + Money.format(p.priced().feeCents()) + ")");
    }

    /** Sends a queued item to the rail it was priced for. The item is closed as failed if the rail refuses. */
    public void dispatchQueued(CommercialTransaction tx) {
        PaymentRail rail = rails.byId(tx.getRail());
        DestinationType type = DestinationType.valueOf(String.valueOf(tx.getDestination().get("type")));
        PayoutCommand cmd = new PayoutCommand(tx.getId(), tx.getMerchantId(), tx.isLivemode(), type, tx.getAmountCents(),
                tx.getNativeAmount(), tx.getNativeCurrency(), tx.getDestination(), tx.getRemarks());
        held.dispatch(rail, cmd, tx);
    }

    private String doCreate(CommercialContext ctx, CreatePayoutRequest req) {
        Prepared p = prepare(ctx, req, true);
        Priced priced = p.priced();
        PaymentRail rail = p.rail();

        String id = Ids.next("po");
        String holdId = Ids.next("hold");

        // The hold is the balance check: it either reserves the money or fails with INSUFFICIENT_BALANCE.
        MerchantWallet wallet = ledger.hold(ctx.walletId(), id, priced.settleCents(),
                "Payout " + req.merchantReference() + " (amount " + Money.format(priced.usdCents())
                        + ", fee " + Money.format(priced.feeCents()) + ")").wallet();

        CommercialTransaction tx = build(ctx, id, req, p);
        try {
            tx.setHoldId(holdId);
            store.insert(tx);
            holds.insert(new MerchantHold(holdId, wallet, id, priced.settleCents(), "PAYOUT", Instant.now()));
        } catch (RuntimeException e) {
            ledger.release(ctx.walletId(), id, priced.settleCents(), "Payout " + req.merchantReference() + " could not be recorded");
            throw e;
        }

        PayoutCommand cmd = new PayoutCommand(id, ctx.merchantId(), ctx.livemode(), p.destType(), priced.usdCents(),
                priced.nativeAmount(), priced.nativeCurrency(), p.destination(), req.remarks());
        try {
            RailResponse response = rail.payout(cmd);
            outcomes.applyRailResponse(tx, response);
        } catch (RailException e) {
            outcomes.apply(tx, RailOutcome.of(TxStatus.FAILED, e.code() != null ? e.code() : ResultCode.PROVIDER_ERROR, e.getMessage()));
            if (e.kind() == RailException.Kind.UNAVAILABLE) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "The payout provider is unavailable; nothing was sent");
            }
        }
        return id;
    }

    private static CommercialTransaction build(CommercialContext ctx, String id, CreatePayoutRequest req, Prepared p) {
        CommercialTransaction tx = new CommercialTransaction();
        tx.setId(id);
        tx.setMerchantId(ctx.merchantId());
        tx.setAppId(ctx.appId());
        tx.setLivemode(ctx.livemode());
        tx.setType(TxType.PAYOUT);
        tx.setStatus(TxStatus.PENDING);
        tx.setMerchantReference(req.merchantReference());
        tx.setRemarks(req.remarks());
        CommercialPaymentService.applyPricing(tx, p.priced(), p.quote());
        tx.setMethodType(p.method());
        tx.setRail(p.rail().id());
        tx.setProvider(p.rail().providerName(p.method()));
        tx.setDestination(p.destination());
        tx.setCallbackUrl(CommercialPaymentService.blankToNull(req.callbackUrl()));
        tx.setNotifyRegistered(Boolean.TRUE.equals(req.notifyRegistered()));
        tx.setMetadata(p.metadata());
        if (p.beneficiary() != null) {
            tx.getRelations().put("beneficiaryCheckId", p.beneficiary().getCheckId());
            tx.getRelations().put("beneficiaryMatch", p.beneficiary().getMatch());
            if (p.beneficiary().getRegisteredName() != null) {
                tx.getRelations().put("beneficiaryName", p.beneficiary().getRegisteredName());
            }
        }
        return tx;
    }
}

package com.premisave.wallet.commercial.transfer;

import com.premisave.wallet.commercial.dto.CreateTransferRequest;
import com.premisave.wallet.commercial.dto.SettlementViews;
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
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.HeldDebit;
import com.premisave.wallet.commercial.service.LimitService;
import com.premisave.wallet.commercial.service.MethodPolicy;
import com.premisave.wallet.commercial.service.PricingService;
import com.premisave.wallet.commercial.service.Priced;
import com.premisave.wallet.commercial.service.TransactionOutcomeService;
import com.premisave.wallet.commercial.service.TransactionQueryService;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.commercial.util.RequestValidators;
import com.premisave.wallet.service.UserNameResolver;
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
 * Transfers: moving money from a merchant balance into a Premisave user's
 * wallet (marketplace splits, escrow release). It holds amount plus fee, then
 * credits the wallet at once; success captures the hold and any failure
 * releases it. A sweep finishes any transfer that stopped between the hold and
 * the credit, which is safe to repeat because the credit is applied only once.
 *
 * Sandbox transfers never touch a real wallet: any well formed reference is
 * accepted and the transfer succeeds immediately.
 */
@Service("commercialTransferService")
public class CommercialTransferService {

    private static final Logger log = LoggerFactory.getLogger(CommercialTransferService.class);
    private static final long RECOVER_AFTER_SECONDS = 60;

    private final CommercialIdempotencyService idempotency;
    private final PricingService pricing;
    private final LimitService limits;
    private final HeldDebit held;
    private final PremisaveWalletCredit credit;
    private final TransactionOutcomeService outcomes;
    private final TransactionQueryService queries;
    private final UserNameResolver names;
    private final MongoTemplate mongo;

    public CommercialTransferService(CommercialIdempotencyService idempotency, PricingService pricing, LimitService limits,
                                     HeldDebit held, PremisaveWalletCredit credit, TransactionOutcomeService outcomes,
                                     TransactionQueryService queries, UserNameResolver names, MongoTemplate mongo) {
        this.idempotency = idempotency;
        this.pricing = pricing;
        this.limits = limits;
        this.held = held;
        this.credit = credit;
        this.outcomes = outcomes;
        this.queries = queries;
        this.names = names;
        this.mongo = mongo;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreateTransferRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "TRANSFER",
                () -> doCreate(ctx, req),
                id -> SettlementViews.transfer(queries.get(ctx, id, TxType.TRANSFER)));
    }

    private String doCreate(CommercialContext ctx, CreateTransferRequest req) {
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), MethodType.PREMISAVE_WALLET)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, "PREMISAVE_WALLET is not enabled for this merchant or app");
        }
        if (RequestValidators.destinationType(req.to()) != DestinationType.PREMISAVE_WALLET) {
            throw CommercialException.validation("to.type", "must be PREMISAVE_WALLET");
        }
        Map<String, Object> to = RequestValidators.destination(DestinationType.PREMISAVE_WALLET, req.to());
        String accountRef = String.valueOf(to.get("accountRef"));
        if (!RequestValidators.isEmail(accountRef)) {
            throw CommercialException.validation("to.accountRef", "must be the recipient's account email");
        }

        Priced priced = pricing.price(Direction.PAYOUT, MethodType.PREMISAVE_WALLET, req.amount(), req.currency(), null);
        limits.check(ctx, TxType.TRANSFER, priced.usdCents());

        String recipientName = null;
        if (ctx.livemode()) {
            PremisaveWalletCredit.Recipient recipient = credit.find(accountRef)
                    .orElseThrow(() -> CommercialException.notFound("Recipient wallet"));
            if (recipient.frozen()) {
                throw CommercialException.invalidState("The recipient wallet cannot receive funds right now");
            }
            recipientName = maskName(names.resolveNameSafely(recipient.accountNumber()));
        }

        String id = Ids.next("tr");
        CommercialTransaction tx = new CommercialTransaction();
        tx.setId(id);
        tx.setMerchantId(ctx.merchantId());
        tx.setAppId(ctx.appId());
        tx.setLivemode(ctx.livemode());
        tx.setType(TxType.TRANSFER);
        tx.setStatus(TxStatus.PENDING);
        tx.setMerchantReference(req.merchantReference());
        tx.setRemarks(req.note());
        CommercialPaymentService.applyPricing(tx, priced, null);
        tx.setMethodType(MethodType.PREMISAVE_WALLET);
        tx.setRail(RailId.PREMISAVE);
        tx.setProvider("PREMISAVE");
        tx.setDestination(to);
        Map<String, String> relations = new LinkedHashMap<>();
        relations.put("senderName", ctx.merchant().getDisplayName() != null ? ctx.merchant().getDisplayName()
                : ctx.merchant().getBusinessName());
        if (recipientName != null) relations.put("recipientName", recipientName);
        tx.setRelations(relations);

        held.open(ctx.walletId(), tx, "Transfer " + req.merchantReference() + " (amount " + Money.format(priced.usdCents())
                + ", fee " + Money.format(priced.feeCents()) + ")");

        try {
            complete(tx);
        } catch (RuntimeException e) {
            // The hold stays in place and the sweep finishes the transfer. Failing the request here would
            // invite a retry that creates a second transfer while the first is still being completed.
            log.error("Transfer {} was held but could not be completed yet; the sweep will retry", id, e);
        }
        return id;
    }

    /** Credits the wallet (live) and records the result. Safe to run again for the same transfer. */
    void complete(CommercialTransaction tx) {
        try {
            if (tx.isLivemode()) {
                Object ref = tx.getDestination().get("accountRef");
                String sender = tx.getRelations().get("senderName");
                String note = tx.getRemarks() == null || tx.getRemarks().isBlank() ? "" : " - " + tx.getRemarks();
                credit.credit(String.valueOf(ref), tx.getAmountCents(), tx.getId(),
                        "Transfer from " + (sender == null ? "a Premisave merchant" : sender) + note);
            }
            outcomes.apply(tx, new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Transfer completed", null, null, null));
        } catch (PremisaveWalletCredit.Refused refused) {
            outcomes.apply(tx, RailOutcome.of(TxStatus.FAILED, refused.code(), refused.getMessage()));
        }
    }

    /** Finishes transfers that were held but never completed, for example after a restart. */
    @Scheduled(fixedDelayString = "${commercial.transfers.recovery-interval-ms:30000}")
    public void recover() {
        Criteria c = Criteria.where("type").is(TxType.TRANSFER.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("createdAt").lt(Instant.now().minusSeconds(RECOVER_AFTER_SECONDS));
        for (CommercialTransaction tx : mongo.find(Query.query(c).with(PageRequest.of(0, 50)), CommercialTransaction.class)) {
            try {
                log.warn("Finishing transfer {} that stopped before completing", tx.getId());
                complete(tx);
            } catch (RuntimeException e) {
                log.error("Could not finish transfer {}", tx.getId(), e);
            }
        }
    }

    /** "SELLER ONE" becomes "S***** O**": enough for a merchant to recognise the person, not to learn the name. */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (out.length() > 0) out.append(' ');
            out.append(word.charAt(0)).append("*".repeat(word.length() - 1));
        }
        return out.toString();
    }
}

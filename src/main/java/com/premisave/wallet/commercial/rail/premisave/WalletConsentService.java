package com.premisave.wallet.commercial.rail.premisave;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResultSink;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.service.CommercialTransactionStore;
import com.premisave.wallet.commercial.service.transfer.PremisaveWalletCredit;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything that happens to a wallet consent: asking the wallet owner, recording their answer, and, once
 * a payment is approved, moving the money. Every state change is a guarded update, so two requests that race
 * (an approval and a timeout, a tap and a retry) cannot both win, and every money step is safe to repeat.
 *
 * The one hazard is a payment that closes (expires, is cancelled) between the owner's approval and the debit.
 * The service therefore checks the payment is open before debiting, and after reporting success checks again
 * that the payment really ended as paid; if not, the debit is credited straight back.
 */
@Component("commercialWalletConsentService")
@ConditionalOnProperty(prefix = "commercial.rails.premisave", name = "enabled", havingValue = "true")
public class WalletConsentService {

    private static final Logger log = LoggerFactory.getLogger(WalletConsentService.class);

    public enum Settlement { PAID, INSUFFICIENT_FUNDS, WALLET_UNAVAILABLE, CLOSED }

    public record Settled(Settlement result, String message) {
        static Settled paid() { return new Settled(Settlement.PAID, null); }
        static Settled closed() { return new Settled(Settlement.CLOSED, "This payment is no longer open"); }
    }

    private final WalletConsentRepository consents;
    private final MongoTemplate mongo;
    private final CommercialTransactionStore store;
    private final PremisaveWalletDebit debit;
    private final PremisaveWalletCredit credit;
    private final MerchantProfileRepository merchants;
    private final RailResultSink sink;
    private final CommercialRailsProperties.Premisave props;

    public WalletConsentService(WalletConsentRepository consents, MongoTemplate mongo, CommercialTransactionStore store,
                                PremisaveWalletDebit debit, PremisaveWalletCredit credit, MerchantProfileRepository merchants,
                                @Lazy RailResultSink sink, CommercialRailsProperties props) {
        this.consents = consents;
        this.mongo = mongo;
        this.store = store;
        this.debit = debit;
        this.credit = credit;
        this.merchants = merchants;
        this.sink = sink;
        this.props = props.premisave();
    }

    // ------------------------------------------------------------------ creating

    /**
     * Records a request to debit one payment. Without a mandate it waits for the owner; with an approved
     * mandate it is approved from the start. Asking twice for the same payment returns the same consent.
     */
    public WalletConsent openPayment(String transactionId, String merchantId, String account, long cents, String description,
                                     Instant paymentExpiresAt, String mandateId) {
        Instant now = Instant.now();
        WalletConsent c = new WalletConsent();
        c.setId("wcn_" + transactionId);
        c.setKind(WalletConsent.Kind.PAYMENT);
        c.setStatus(mandateId == null ? WalletConsent.Status.PENDING : WalletConsent.Status.APPROVED);
        c.setAccountNumber(ConsentRules.account(account));
        c.setMerchantId(merchantId);
        c.setMerchantName(merchantName(merchantId));
        c.setTransactionId(transactionId);
        c.setMandateId(mandateId);
        c.setAmountCents(cents);
        c.setDescription(description);
        c.setCreatedAt(now);
        c.setExpiresAt(ConsentRules.paymentExpiry(now, props.consentMinutes(), paymentExpiresAt));
        if (mandateId != null) c.setDecidedAt(now);
        return insertOrExisting(c);
    }

    /** Records a request for a standing approval to charge a wallet, waiting for the owner. */
    public WalletConsent openMandate(String paymentMethodId, String merchantId, String account) {
        Instant now = Instant.now();
        WalletConsent c = new WalletConsent();
        c.setId("wcn_" + Ids.next("m").substring(2));
        c.setKind(WalletConsent.Kind.MANDATE);
        c.setStatus(WalletConsent.Status.PENDING);
        c.setAccountNumber(ConsentRules.account(account));
        c.setMerchantId(merchantId);
        c.setMerchantName(merchantName(merchantId));
        c.setPaymentMethodId(paymentMethodId);
        c.setAmountCents(props.mandateMaxUsdCents());
        c.setDescription("Recurring payments");
        c.setCreatedAt(now);
        c.setExpiresAt(now.plusSeconds(props.mandateHours() * 3600L));
        return insertOrExisting(c);
    }

    private WalletConsent insertOrExisting(WalletConsent c) {
        try {
            return consents.insert(c);
        } catch (DuplicateKeyException e) {
            return consents.findById(c.getId())
                    .or(() -> c.getPaymentMethodId() == null ? Optional.empty() : consents.findByPaymentMethodId(c.getPaymentMethodId()))
                    .orElseThrow(() -> e);
        }
    }

    public Optional<WalletConsent> mandate(String id) {
        return id == null ? Optional.empty() : consents.findById(id).filter(c -> c.getKind() == WalletConsent.Kind.MANDATE);
    }

    public Optional<WalletConsent> forTransaction(String transactionId) {
        return consents.findByTransactionId(transactionId);
    }

    public Optional<WalletConsent> forMethod(String paymentMethodId) {
        return consents.findByPaymentMethodId(paymentMethodId);
    }

    // ------------------------------------------------------------------ the owner's side

    /** Requests the owner still has to answer, and the standing approvals they have given. */
    public List<WalletConsent> openFor(String account) {
        Instant now = Instant.now();
        return consents.findByAccountNumberAndStatusInOrderByCreatedAtDesc(ConsentRules.account(account),
                        List.of(WalletConsent.Status.PENDING, WalletConsent.Status.APPROVED)).stream()
                .filter(c -> c.getStatus() == WalletConsent.Status.PENDING ? !ConsentRules.lapsed(c, now)
                        : c.getKind() == WalletConsent.Kind.MANDATE)
                .toList();
    }

    public record Decision(boolean ok, int httpStatus, String message, WalletConsent consent) {
        static Decision ok(String message, WalletConsent c) { return new Decision(true, 200, message, c); }
        static Decision refused(int status, String message, WalletConsent c) { return new Decision(false, status, message, c); }
    }

    /** The owner says yes. A payment is debited at once; a standing approval becomes usable. */
    public Decision approve(String account, String id) {
        WalletConsent c = owned(account, id);
        if (c == null) return Decision.refused(404, "That request was not found", null);
        if (c.getKind() == WalletConsent.Kind.PAYMENT && c.getMandateId() != null) {
            return Decision.refused(409, "That payment was made under a standing approval", c);
        }
        if (ConsentRules.lapsed(c, Instant.now())) {
            move(c.getId(), Set.of(WalletConsent.Status.PENDING), WalletConsent.Status.EXPIRED);
            return Decision.refused(410, "That request has expired", reload(c));
        }
        if (c.getStatus() != WalletConsent.Status.PENDING && c.getStatus() != WalletConsent.Status.APPROVED) {
            return Decision.refused(409, "That request has already been answered", c);
        }
        if (c.getStatus() == WalletConsent.Status.PENDING
                && move(c.getId(), Set.of(WalletConsent.Status.PENDING), WalletConsent.Status.APPROVED) == 0) {
            return Decision.refused(409, "That request has already been answered", reload(c));
        }
        if (c.getKind() == WalletConsent.Kind.MANDATE) return Decision.ok("Approved", reload(c));

        Settled s = complete(reload(c));
        return switch (s.result()) {
            case PAID -> Decision.ok("Paid", reload(c));
            case INSUFFICIENT_FUNDS -> {
                move(c.getId(), Set.of(WalletConsent.Status.APPROVED), WalletConsent.Status.PENDING);
                yield Decision.refused(422, "Your wallet balance is too low for this payment", reload(c));
            }
            case WALLET_UNAVAILABLE -> {
                move(c.getId(), Set.of(WalletConsent.Status.APPROVED), WalletConsent.Status.PENDING);
                yield Decision.refused(403, s.message() == null ? "Your wallet cannot be used right now" : s.message(), reload(c));
            }
            case CLOSED -> Decision.refused(409, s.message(), reload(c));
        };
    }

    /** The owner says no. The merchant's payment is reported as not approved. */
    public Decision decline(String account, String id) {
        WalletConsent c = owned(account, id);
        if (c == null) return Decision.refused(404, "That request was not found", null);
        if (c.getStatus() != WalletConsent.Status.PENDING
                || move(c.getId(), Set.of(WalletConsent.Status.PENDING), WalletConsent.Status.DECLINED) == 0) {
            return Decision.refused(409, "That request has already been answered", reload(c));
        }
        if (c.getKind() == WalletConsent.Kind.PAYMENT) {
            sink.onOutcome(c.getTransactionId(), RailOutcome.of(TxStatus.FAILED, ResultCode.CUSTOMER_CANCELLED,
                    "The wallet owner declined the payment"));
        }
        return Decision.ok("Declined", reload(c));
    }

    /** The owner withdraws a standing approval. Later charges under it are refused. */
    public Decision revoke(String account, String id) {
        WalletConsent c = owned(account, id);
        if (c == null) return Decision.refused(404, "That request was not found", null);
        if (c.getKind() != WalletConsent.Kind.MANDATE) return Decision.refused(409, "Only a standing approval can be withdrawn", c);
        if (move(c.getId(), Set.of(WalletConsent.Status.APPROVED, WalletConsent.Status.PENDING), WalletConsent.Status.REVOKED) == 0) {
            return Decision.refused(409, "That approval is no longer active", reload(c));
        }
        return Decision.ok("Withdrawn", reload(c));
    }

    // ------------------------------------------------------------------ moving the money

    /**
     * Debits the wallet for an approved payment consent. Safe to repeat. Does not report the result to the
     * payment; complete does that.
     */
    public Settled settle(WalletConsent c) {
        if (c.getKind() != WalletConsent.Kind.PAYMENT) return Settled.closed();
        if (c.getStatus() == WalletConsent.Status.USED) return Settled.paid();
        if (c.getStatus() != WalletConsent.Status.APPROVED) return Settled.closed();

        CommercialTransaction tx = store.find(c.getTransactionId()).orElse(null);
        if (tx == null || !TxStatus.OPEN.contains(tx.getStatus())) {
            move(c.getId(), Set.of(WalletConsent.Status.APPROVED), WalletConsent.Status.CANCELLED);
            return Settled.closed();
        }
        try {
            debit.debit(c.getAccountNumber(), c.getAmountCents(), c.getTransactionId(), "Payment to " + label(c));
        } catch (PremisaveWalletCredit.Refused r) {
            return new Settled(r.code() == ResultCode.INSUFFICIENT_FUNDS ? Settlement.INSUFFICIENT_FUNDS : Settlement.WALLET_UNAVAILABLE,
                    r.getMessage());
        }
        move(c.getId(), Set.of(WalletConsent.Status.APPROVED), WalletConsent.Status.USED);
        return Settled.paid();
    }

    /**
     * Debits the wallet and reports the payment as paid, then makes sure the payment really ended as paid. If
     * it did not (it closed while the debit was happening) the money goes straight back.
     */
    public Settled complete(WalletConsent c) {
        Settled s = settle(c);
        if (s.result() != Settlement.PAID) return s;
        sink.onOutcome(c.getTransactionId(), RailOutcome.of(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid from the Premisave wallet"));
        CommercialTransaction after = store.find(c.getTransactionId()).orElse(null);
        if (after == null || after.getStatus() != TxStatus.SUCCESS) {
            undo(c);
            return Settled.closed();
        }
        return s;
    }

    /** Puts a debit back. Safe to repeat. */
    void undo(WalletConsent c) {
        try {
            credit.credit(c.getAccountNumber(), c.getAmountCents(), c.getTransactionId() + "-undo", "Refund of " + label(c));
            move(c.getId(), Set.of(WalletConsent.Status.USED, WalletConsent.Status.APPROVED), WalletConsent.Status.REVERSED);
            log.warn("Wallet debit for {} was put back because the payment had already closed", c.getTransactionId());
        } catch (RuntimeException e) {
            log.error("Wallet debit for {} could not be put back and needs attention: {}", c.getTransactionId(), e.getMessage());
        }
    }

    /** Closes a consent whose payment was recalled. Returns false when the money has already moved. */
    public boolean cancelPayment(String transactionId) {
        WalletConsent c = consents.findByTransactionId(transactionId).orElse(null);
        if (c == null) return true;
        if (c.getStatus() == WalletConsent.Status.USED) return false;
        if (c.getStatus() == WalletConsent.Status.PENDING || c.getStatus() == WalletConsent.Status.APPROVED) {
            if (move(c.getId(), Set.of(WalletConsent.Status.PENDING, WalletConsent.Status.APPROVED), WalletConsent.Status.CANCELLED) == 0) {
                return reload(c).getStatus() != WalletConsent.Status.USED;
            }
        }
        return true;
    }

    /** Marks a request that ran out of time. */
    public void expire(WalletConsent c) {
        move(c.getId(), Set.of(WalletConsent.Status.PENDING), WalletConsent.Status.EXPIRED);
    }

    // ------------------------------------------------------------------ helpers

    WalletConsent owned(String account, String id) {
        String acct = ConsentRules.account(account);
        return id == null || acct == null ? null : consents.findById(id).filter(c -> acct.equals(c.getAccountNumber())).orElse(null);
    }

    private WalletConsent reload(WalletConsent c) {
        return consents.findById(c.getId()).orElse(c);
    }

    long move(String id, Collection<WalletConsent.Status> from, WalletConsent.Status to) {
        Criteria c = Criteria.where("id").is(id).and("status").in(from.stream().map(Enum::name).toList());
        Document set = new Document("status", to.name()).append("decidedAt", Date.from(Instant.now()));
        return mongo.updateFirst(Query.query(c), new BasicUpdate(new Document("$set", set)), WalletConsent.class).getModifiedCount();
    }

    private String merchantName(String merchantId) {
        return merchants.findById(merchantId).map(MerchantProfile::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .or(() -> merchants.findById(merchantId).map(MerchantProfile::getBusinessName))
                .orElse("a merchant");
    }

    private static String label(WalletConsent c) {
        return c.getMerchantName() == null ? "a merchant" : c.getMerchantName();
    }
}
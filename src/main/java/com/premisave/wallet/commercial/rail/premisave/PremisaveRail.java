package com.premisave.wallet.commercial.rail.premisave;

import com.premisave.wallet.commercial.config.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.CollectCommand;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.RailCapability;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.transfer.PremisaveWalletCredit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Premisave wallets as a payment method and a payout destination, with no outside provider.
 *
 * Collecting: the wallet owner is asked in the Premisave app (WalletConsentController) and the wallet is
 * debited when they approve. A saved method (a standing approval the owner gave for a subscription) is charged
 * at once, within the limit the owner approved. Paying out: the recipient's wallet is credited, once, at once.
 *
 * Results that happen in the app go straight to the payment; the poller covers a crash between an approval
 * and its debit, and closes requests that ran out of time. Refunds of wallet payments are made by the refund
 * service as a credit back to the wallet, so this rail offers none of its own.
 *
 * Serves the live environment only.
 */
@Component("commercialPremisaveRail")
@ConditionalOnProperty(prefix = "commercial.rails.premisave", name = "enabled", havingValue = "true")
public class PremisaveRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(PremisaveRail.class);

    private final WalletConsentService consents;
    private final PremisaveWalletDebit debit;
    private final PremisaveWalletCredit credit;
    private final CommercialRailsProperties.Premisave props;

    public PremisaveRail(WalletConsentService consents, PremisaveWalletDebit debit, PremisaveWalletCredit credit,
                         CommercialRailsProperties props) {
        this.consents = consents;
        this.debit = debit;
        this.credit = credit;
        this.props = props.premisave();
    }

    @Override
    public RailId id() { return RailId.PREMISAVE; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.LIVE); }

    @Override
    public List<RailCapability> capabilities() {
        return List.of(
                new RailCapability(MethodType.PREMISAVE_WALLET, Direction.COLLECTION, List.of("*"), List.of("USD"),
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "CUSTOMER_CONSENT"),
                new RailCapability(MethodType.PREMISAVE_WALLET, Direction.PAYOUT, List.of("*"), List.of("USD"),
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "NONE"));
    }

    @Override
    public String providerName(MethodType method) { return "PREMISAVE"; }

    @Override
    public boolean pollable() { return true; }

    // ------------------------------------------------------------------ collecting

    @Override
    public RailResponse collect(CollectCommand c) {
        if (c.method() != MethodType.PREMISAVE_WALLET) throw rejected(ResultCode.DECLINED, "The Premisave rail takes wallet payments only");
        String account = c.customer() == null || c.customer().get("accountRef") == null ? null : String.valueOf(c.customer().get("accountRef"));
        if (account == null || account.isBlank()) throw rejected(ResultCode.INVALID_DESTINATION, "customer.accountRef is required");
        var wallet = debit.find(account);
        if (wallet.isEmpty() || wallet.get().isFrozen()) {
            throw rejected(ResultCode.INVALID_DESTINATION, "That Premisave wallet cannot be charged");
        }
        Object saved = c.methodOptions() == null ? null : c.methodOptions().get("paymentMethodId");
        if (saved == null || String.valueOf(saved).isBlank()) {
            WalletConsent consent = consents.openPayment(c.transactionId(), c.merchantId(), account, c.usdCents(),
                    c.description(), c.expiresAt(), null);
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("type", "CUSTOMER_CONSENT");
            next.put("consentId", consent.getId());
            next.put("instructions", "Open the Premisave app and approve this payment under Wallet, Payment requests.");
            next.put("expiresAt", consent.getExpiresAt().toString());
            return RailResponse.requiresAction(next, consent.getId());
        }
        return chargeMandate(c, account, String.valueOf(saved));
    }

    private RailResponse chargeMandate(CollectCommand c, String account, String mandateId) {
        WalletConsent mandate = consents.mandate(mandateId).orElse(null);
        ConsentRules.Problem problem = ConsentRules.mandateProblem(mandate, ConsentRules.account(account), c.merchantId(), c.usdCents());
        if (problem != ConsentRules.Problem.NONE) throw rejected(ResultCode.DECLINED, ConsentRules.describe(problem));

        WalletConsent consent = consents.openPayment(c.transactionId(), c.merchantId(), account, c.usdCents(),
                c.description(), c.expiresAt(), mandateId);
        WalletConsentService.Settled s;
        try {
            s = consents.settle(consent);
        } catch (RuntimeException e) {
            // The debit may have happened. Failing the payment here could leave the customer charged for
            // nothing, so it stays pending and the poller finishes it (the debit is safe to repeat).
            log.error("Wallet charge for {} is unconfirmed ({}); keeping it pending", c.transactionId(), e.getMessage());
            return RailResponse.pending(consent.getId());
        }
        switch (s.result()) {
            case PAID:
                return new RailResponse(TxStatus.SUCCESS, null, consent.getId(), ResultCode.SUCCESS, "Paid from the Premisave wallet");
            case INSUFFICIENT_FUNDS:
                consents.cancelPayment(c.transactionId());
                throw rejected(ResultCode.INSUFFICIENT_FUNDS, "The Premisave wallet balance is too low");
            case WALLET_UNAVAILABLE:
                consents.cancelPayment(c.transactionId());
                throw rejected(ResultCode.DECLINED, s.message() == null ? "The Premisave wallet cannot be charged" : s.message());
            default:
                throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "The payment could not be started");
        }
    }

    @Override
    public boolean cancelCollection(CommercialTransaction tx) {
        return consents.cancelPayment(tx.getId());
    }

    // ------------------------------------------------------------------ paying out

    @Override
    public RailResponse payout(PayoutCommand c) {
        if (c.destinationType() != DestinationType.PREMISAVE_WALLET) {
            throw rejected(ResultCode.INVALID_DESTINATION, "The Premisave rail pays Premisave wallets only");
        }
        String account = c.destination() == null || c.destination().get("accountRef") == null ? null
                : String.valueOf(c.destination().get("accountRef"));
        try {
            credit.credit(account, c.usdCents(), c.transactionId(), c.remarks() == null ? "Payment received" : c.remarks());
            return new RailResponse(TxStatus.SUCCESS, null, c.transactionId(), ResultCode.SUCCESS, "Paid to the Premisave wallet");
        } catch (PremisaveWalletCredit.Refused r) {
            throw rejected(r.code(), r.getMessage());
        } catch (RuntimeException e) {
            // The credit is applied exactly once whatever happens, so trying it again is safe; the poller does.
            log.error("Wallet payout {} is unconfirmed ({}); keeping it pending", c.transactionId(), e.getMessage());
            return RailResponse.pending(c.transactionId());
        }
    }

    // ------------------------------------------------------------------ results

    @Override
    public RailOutcome poll(CommercialTransaction tx) {
        if (tx.getType() == TxType.PAYOUT) return pollPayout(tx);
        if (tx.getType() != TxType.PAYMENT) return null;
        WalletConsent c = consents.forTransaction(tx.getId()).orElse(null);
        if (c == null) return null;
        Instant now = Instant.now();
        if (ConsentRules.lapsed(c, now)) {
            consents.expire(c);
            return RailOutcome.of(TxStatus.TIMED_OUT, ResultCode.CUSTOMER_TIMEOUT, "The wallet owner did not answer in time");
        }
        switch (c.getStatus()) {
            case DECLINED:
                return RailOutcome.of(TxStatus.FAILED, ResultCode.CUSTOMER_CANCELLED, "The wallet owner declined the payment");
            case EXPIRED:
                return RailOutcome.of(TxStatus.TIMED_OUT, ResultCode.CUSTOMER_TIMEOUT, "The wallet owner did not answer in time");
            case APPROVED:
            case USED:
                // Approved but not finished: a crash between the approval and the debit, or a lost report.
                // complete reports the result itself, so there is nothing to hand back to the poller.
                WalletConsentService.Settled s = consents.complete(c);
                if (s.result() == WalletConsentService.Settlement.INSUFFICIENT_FUNDS) {
                    return RailOutcome.of(TxStatus.FAILED, ResultCode.INSUFFICIENT_FUNDS, "The Premisave wallet balance is too low");
                }
                return null;
            default:
                return null;
        }
    }

    private RailOutcome pollPayout(CommercialTransaction tx) {
        String account = tx.getDestination() == null || tx.getDestination().get("accountRef") == null ? null
                : String.valueOf(tx.getDestination().get("accountRef"));
        try {
            credit.credit(account, tx.getAmountCents(), tx.getId(), tx.getRemarks() == null ? "Payment received" : tx.getRemarks());
            return RailOutcome.of(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid to the Premisave wallet");
        } catch (PremisaveWalletCredit.Refused r) {
            return RailOutcome.of(TxStatus.FAILED, r.code(), r.getMessage());
        }
    }

    private static RailException rejected(ResultCode code, String message) {
        return new RailException(RailException.Kind.REJECTED, code, message);
    }
}
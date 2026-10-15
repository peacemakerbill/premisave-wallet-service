package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.util.Ids;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The simulated rail behind every /sandbox request. It never contacts a
 * provider. It answers like the real rail for each method (prompt, client
 * secret, redirect and so on) and then resolves the transaction by the test
 * amount rules in SandboxOutcomes, a few seconds later.
 */
@Component("commercialSandboxRail")
public class SandboxRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(SandboxRail.class);

    private final CommercialProperties props;
    private final RailResultSink sink;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "commercial-sandbox-rail");
        t.setDaemon(true);
        return t;
    });

    public SandboxRail(CommercialProperties props, RailResultSink sink) {
        this.props = props;
        this.sink = sink;
    }

    @Override
    public RailId id() { return RailId.SANDBOX; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.SANDBOX); }

    @Override
    public List<RailCapability> capabilities() {
        List<RailCapability> list = new ArrayList<>();
        for (MethodType m : MethodType.values()) {
            for (Direction d : Direction.values()) {
                list.add(new RailCapability(m, d, List.of("*"), List.of("*"), 10, 100_000_000,
                        "INSTANT", d == Direction.COLLECTION ? requiresActionFor(m) : "NONE"));
            }
        }
        return list;
    }

    @Override
    public String providerName(MethodType method) {
        return switch (method) {
            case MOBILE_MONEY -> "MPESA";
            case CARD -> "STRIPE";
            case PAYPAL -> "PAYPAL";
            case BANK_TRANSFER -> "FLUTTERWAVE";
            case CRYPTO -> "NOWPAYMENTS";
            case PREMISAVE_WALLET -> "PREMISAVE";
        };
    }

    @Override
    public RailResponse collect(CollectCommand c) {
        String ref = "sbx_" + c.transactionId();
        // Charging a saved payment method needs nothing from the customer, apart from the mobile money prompt.
        boolean savedMethod = c.methodOptions().containsKey("paymentMethodId") && c.method() != MethodType.MOBILE_MONEY;
        Map<String, Object> next = savedMethod ? null : nextActionFor(c);
        RailResponse response = next == null ? RailResponse.pending(ref) : RailResponse.requiresAction(next, ref);
        scheduleAutoOutcome(c.transactionId(), c.usdCents(), Direction.COLLECTION);
        return response;
    }

    @Override
    public RailResponse payout(PayoutCommand c) {
        String ref = "sbx_" + c.transactionId();
        scheduleAutoOutcome(c.transactionId(), c.usdCents(), Direction.PAYOUT);
        return RailResponse.pending(ref);
    }

    @Override
    public boolean cancelCollection(CommercialTransaction tx) {
        return true;
    }

    /** The sandbox refunds cards and PayPal itself, like their real providers; other methods use the generic fallbacks. */
    @Override
    public RailRefundMode refundMode(CommercialTransaction payment) {
        MethodType m = payment.getMethodType();
        return m == MethodType.CARD || m == MethodType.PAYPAL ? RailRefundMode.REFUND : RailRefundMode.NONE;
    }

    @Override
    public RailResponse refund(RefundCommand c) {
        scheduleAutoOutcome(c.transactionId(), c.usdCents(), Direction.PAYOUT);
        return RailResponse.pending("sbx_" + c.transactionId());
    }

    @Override
    public void submitDisputeEvidence(DisputeEvidenceCommand command) {
        // Accepted without checking: the sandbox decides disputes only when asked to.
    }

    /** Applies a chosen outcome after a delay. Used by the simulate helper endpoints. */
    public void simulate(String transactionId, RailOutcome outcome, int delaySeconds) {
        if (delaySeconds <= 0) {
            sink.onOutcome(transactionId, outcome);
        } else {
            scheduler.schedule(() -> deliver(transactionId, outcome), delaySeconds, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void scheduleAutoOutcome(String transactionId, long usdCents, Direction direction) {
        SandboxOutcomes.forAmount(usdCents, direction).ifPresent(outcome ->
                scheduler.schedule(() -> deliver(transactionId, outcome),
                        props.sandbox().autoResolveDelayMs(), TimeUnit.MILLISECONDS));
    }

    private void deliver(String transactionId, RailOutcome outcome) {
        try {
            sink.onOutcome(transactionId, outcome);
        } catch (RuntimeException e) {
            log.error("Sandbox outcome for {} could not be applied", transactionId, e);
        }
    }

    private static String requiresActionFor(MethodType m) {
        return switch (m) {
            case MOBILE_MONEY -> "CUSTOMER_PROMPT";
            case CARD -> "CLIENT_SECRET";
            case PAYPAL -> "REDIRECT";
            case BANK_TRANSFER -> "BANK_TRANSFER_DETAILS";
            case CRYPTO -> "CRYPTO_ADDRESS";
            case PREMISAVE_WALLET -> "CUSTOMER_CONSENT";
        };
    }

    private Map<String, Object> nextActionFor(CollectCommand c) {
        Map<String, Object> n = new LinkedHashMap<>();
        switch (c.method()) {
            case MOBILE_MONEY -> {
                n.put("type", "CUSTOMER_PROMPT");
                n.put("instructions", "Sandbox: no real prompt is sent. Use the simulate helper or a test amount.");
            }
            case CARD -> {
                n.put("type", "CLIENT_SECRET");
                n.put("clientSecret", "pi_sandbox_" + c.transactionId() + "_secret_" + Ids.next("s"));
                n.put("publishableKey", "pk_sandbox");
            }
            case PAYPAL -> {
                n.put("type", "REDIRECT");
                n.put("url", props.sandbox().redirectBase() + "/paypal/approve/" + c.transactionId());
            }
            case BANK_TRANSFER -> {
                n.put("type", "BANK_TRANSFER_DETAILS");
                n.put("bankName", "Sandbox Bank");
                n.put("accountNumber", "0000000000");
                n.put("accountName", "Sandbox Collections");
                n.put("reference", c.transactionId());
            }
            case CRYPTO -> {
                n.put("type", "CRYPTO_ADDRESS");
                n.put("network", "SANDBOX");
                n.put("payCurrency", c.methodOptions().getOrDefault("payCurrency", "USDT"));
                n.put("payAmount", java.math.BigDecimal.valueOf(c.usdCents(), 2).toPlainString());
                n.put("address", "sandbox-address-" + c.transactionId());
            }
            case PREMISAVE_WALLET -> {
                n.put("type", "CUSTOMER_CONSENT");
                n.put("instructions", "Sandbox: the wallet owner would approve this debit in the Premisave app.");
            }
        }
        return n;
    }
}

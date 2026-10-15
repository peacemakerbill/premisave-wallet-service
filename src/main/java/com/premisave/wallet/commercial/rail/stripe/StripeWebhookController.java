package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.billing.CredentialSetup;
import com.premisave.wallet.commercial.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.billing.PaymentMethodService;
import com.premisave.wallet.commercial.config.CommercialRailsProperties;
import com.premisave.wallet.commercial.dispute.Dispute;
import com.premisave.wallet.commercial.dispute.DisputeRepository;
import com.premisave.wallet.commercial.dispute.DisputeService;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResultSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Receives Stripe's events for commercial payments, refunds, saved cards and disputes. This is its own Stripe
 * webhook destination with its own signing secret (commercial.rails.stripe.webhook-secret), apart from the
 * wallet's. Objects that carry no commercial metadata belong to the wallet service and are ignored.
 *
 * The path is public (Stripe sends no credentials), so the signature is checked before anything is read.
 * A signed event always gets a 200, even when ignored or when applying it failed (logged), because Stripe
 * retries anything else for days; polling covers a missed result.
 */
@RestController("commercialStripeWebhookController")
@RequestMapping("/payments/commercial/rail/webhook")
@ConditionalOnProperty(prefix = "commercial.rails.stripe", name = "enabled", havingValue = "true")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final CommercialRailsProperties.Stripe props;
    private final JsonMapper json;
    private final StripeRail rail;
    private final StripeCardSetup cards;
    private final PaymentMethodService methods;
    private final RailResultSink sink;
    private final MongoTemplate mongo;
    private final DisputeService disputes;
    private final DisputeRepository disputeRepo;

    public StripeWebhookController(CommercialRailsProperties props, JsonMapper json, StripeRail rail, StripeCardSetup cards,
                                   PaymentMethodService methods, RailResultSink sink, MongoTemplate mongo,
                                   DisputeService disputes, DisputeRepository disputeRepo) {
        this.props = props.stripe();
        this.json = json;
        this.rail = rail;
        this.cards = cards;
        this.methods = methods;
        this.sink = sink;
        this.mongo = mongo;
        this.disputes = disputes;
        this.disputeRepo = disputeRepo;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Map<String, Object>> receive(@RequestHeader(value = "Stripe-Signature", required = false) String signature,
                                                       @RequestBody String raw) {
        if (!props.webhookConfigured()) {
            log.error("A Stripe webhook arrived but commercial.rails.stripe.webhook-secret is not set; refusing it");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!StripeSignature.verify(props.webhookSecret(), signature, raw, Instant.now().getEpochSecond(), props.webhookToleranceSeconds())) {
            log.warn("A Stripe webhook failed its signature check");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = json.readValue(raw, Map.class);
            handle(StripeMapper.text(event, "type"), StripeMapper.map(StripeMapper.map(event, "data"), "object"));
        } catch (RuntimeException e) {
            log.error("Could not process a Stripe webhook", e);
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    void handle(String type, Map<String, Object> obj) {
        switch (type) {
            case "payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.canceled" -> paymentIntent(obj);
            case "setup_intent.succeeded", "setup_intent.setup_failed", "setup_intent.canceled" -> setupIntent(obj);
            case "refund.updated", "refund.failed", "charge.refund.updated" -> refund(obj);
            case "charge.dispute.created" -> disputeOpened(obj);
            case "charge.dispute.closed" -> disputeClosed(obj);
            default -> log.debug("Ignoring Stripe event {}", type);
        }
    }

    private void paymentIntent(Map<String, Object> pi) {
        String txId = StripeMapper.text(StripeMapper.map(pi, "metadata"), "commercial_tx");
        if (txId.isBlank()) return;
        RailOutcome outcome = rail.resolveIntent(pi);
        if (outcome != null) sink.onOutcome(txId, outcome);
    }

    private void setupIntent(Map<String, Object> seti) {
        String pmId = StripeMapper.text(StripeMapper.map(seti, "metadata"), "commercial_pm");
        if (pmId.isBlank()) return;
        CredentialSetup.Progress p = cards.progressOf(seti);
        switch (p.kind()) {
            case DONE -> methods.activate(pmId, p.label(), p.token());
            case FAILED -> methods.fail(pmId, p.reason());
            default -> { }
        }
    }

    private void refund(Map<String, Object> refund) {
        String txId = StripeMapper.text(StripeMapper.map(refund, "metadata"), "commercial_tx");
        if (txId.isBlank()) return;
        RailOutcome outcome = StripeMapper.refund(refund);
        if (outcome != null) sink.onOutcome(txId, outcome);
    }

    private void disputeOpened(Map<String, Object> d) {
        String intent = StripeMapper.text(d, "payment_intent");
        if (intent.isBlank()) return;
        CommercialTransaction payment = mongo.findOne(Query.query(Criteria.where("rail").is(RailId.STRIPE.name())
                .and("type").is(TxType.PAYMENT.name()).and("providerReference").is(intent)), CommercialTransaction.class);
        if (payment == null) return;
        if (payment.getStatus() != TxStatus.SUCCESS) {
            log.error("Stripe opened dispute {} on payment {} which is {}; not recorded", StripeMapper.text(d, "id"), payment.getId(), payment.getStatus());
            return;
        }
        long disputed = number(d.get("amount"));
        long charged;
        try {
            charged = payment.getNativeAmount() != null && payment.getNativeCurrency() != null
                    ? StripeMoney.minorUnits(payment.getNativeAmount(), payment.getNativeCurrency()) : payment.getAmountCents();
        } catch (IllegalArgumentException e) {
            charged = payment.getAmountCents();
        }
        long usd = StripeMapper.disputeUsdCents(disputed, charged, payment.getAmountCents());
        long dueEpoch = number(StripeMapper.map(d, "evidence_details").get("due_by"));
        Instant due = dueEpoch > 0 ? Instant.ofEpochSecond(dueEpoch) : Instant.now().plusSeconds(7 * 86400L);
        String reason = StripeMapper.text(d, "reason");
        disputes.open(payment, usd, reason.isBlank() ? "other" : reason, StripeMapper.text(d, "id"), due);
    }

    private void disputeClosed(Map<String, Object> d) {
        String status = StripeMapper.text(d, "status");
        Boolean won = switch (status) {
            case "won", "warning_closed" -> Boolean.TRUE;
            case "lost" -> Boolean.FALSE;
            default -> null;
        };
        if (won == null) return;
        Dispute ours = disputeRepo.findByRailAndProviderDisputeId(RailId.STRIPE, StripeMapper.text(d, "id")).orElse(null);
        if (ours == null) return;
        disputes.resolve(ours.getId(), won);
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}

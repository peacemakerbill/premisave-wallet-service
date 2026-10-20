package com.premisave.wallet.commercial.service.dispute;

import com.premisave.wallet.commercial.config.properties.CommercialDisputeProperties;
import com.premisave.wallet.commercial.dto.dispute.DisputeViews;
import com.premisave.wallet.commercial.dto.dispute.SubmitEvidenceRequest;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantHold;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.entity.dispute.Dispute;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.DisputeEvidenceCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.repository.MerchantHoldRepository;
import com.premisave.wallet.commercial.repository.MerchantWalletRepository;
import com.premisave.wallet.commercial.repository.dispute.DisputeRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.EventService;
import com.premisave.wallet.commercial.service.EventTypes;
import com.premisave.wallet.commercial.service.LedgerService;
import com.premisave.wallet.commercial.service.RailRegistry;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Money;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Disputes (sections 9.4 to 9.6). Providers raise disputes and decide them, so opening and
 * resolving are methods for rails to call (and for the sandbox helpers); merchants list disputes
 * and answer them with evidence.
 *
 * Opening holds the disputed amount on the merchant balance. Winning releases it and losing takes
 * it. A merchant without the balance at the time still gets the dispute; the shortfall is taken when
 * the dispute is lost if the money is there by then, and recorded as unrecovered if not.
 *
 * Both steps record themselves first and do their money and event work after, flagging when they
 * are done, so a sweep can finish whatever a crash interrupted. Each money step and each event is
 * exactly once.
 */
@Service("commercialDisputeService")
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);
    private static final long RECOVER_AFTER_SECONDS = 60;

    private final DisputeRepository disputes;
    private final MongoTemplate mongo;
    private final LedgerService ledger;
    private final MerchantWalletRepository wallets;
    private final MerchantHoldRepository holds;
    private final EventService events;
    private final RailRegistry rails;
    private final CommercialIdempotencyService idempotency;
    private final CommercialDisputeProperties props;

    public DisputeService(DisputeRepository disputes, MongoTemplate mongo, LedgerService ledger,
                          MerchantWalletRepository wallets, MerchantHoldRepository holds, EventService events,
                          RailRegistry rails, CommercialIdempotencyService idempotency, CommercialDisputeProperties props) {
        this.disputes = disputes;
        this.mongo = mongo;
        this.ledger = ledger;
        this.wallets = wallets;
        this.holds = holds;
        this.events = events;
        this.rails = rails;
        this.idempotency = idempotency;
        this.props = props;
    }

    // ------------------------------------------------------------------ reads

    public Dispute get(CommercialContext ctx, String id) {
        return disputes.findByIdAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Dispute"));
    }

    /** Filters (all optional): status, paymentId, from, to. */
    public Page<Dispute> list(CommercialContext ctx, Map<String, String> f, Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));
        String status = f.get("status");
        if (status != null && !status.isBlank()) {
            try {
                all.add(Criteria.where("status").is(Dispute.Status.valueOf(status.trim().toUpperCase(Locale.ROOT)).name()));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("status", "is not a valid value");
            }
        }
        String paymentId = f.get("paymentId");
        if (paymentId != null && !paymentId.isBlank()) all.add(Criteria.where("paymentId").is(paymentId.trim()));
        Instant from = instant(f.get("from"), "from");
        Instant to = instant(f.get("to"), "to");
        if (from != null) all.add(Criteria.where("createdAt").gte(from));
        if (to != null) all.add(Criteria.where("createdAt").lte(to));

        Query query = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(query, Dispute.class);
        List<Dispute> page = mongo.find(query.with(pageable), Dispute.class);
        return new PageImpl<>(page, pageable, total);
    }

    // ------------------------------------------------------------------ opening and resolving (providers, sandbox)

    /** Opens a dispute on a successful payment. A provider's own dispute id makes this safe to repeat. */
    public Dispute open(CommercialTransaction payment, long amountCents, String reason, String providerDisputeId,
                        Instant evidenceDueBy) {
        if (payment.getStatus() != TxStatus.SUCCESS) {
            throw CommercialException.invalidState("Only a successful payment can be disputed; this one is " + payment.getStatus());
        }
        if (amountCents < 1 || amountCents > payment.getAmountCents()) {
            throw CommercialException.validation("amount", "must be between 0.01 and the payment amount of "
                    + Money.format(payment.getAmountCents()));
        }
        if (providerDisputeId != null) {
            Optional<Dispute> existing = disputes.findByRailAndProviderDisputeId(payment.getRail(), providerDisputeId);
            if (existing.isPresent()) return existing.get();
        }
        long active = mongo.count(Query.query(Criteria.where("paymentId").is(payment.getId())
                .and("status").in(Dispute.ACTIVE.stream().map(Enum::name).toList())), Dispute.class);
        if (active > 0) throw CommercialException.invalidState("This payment already has a dispute in progress");

        Instant now = Instant.now();
        Dispute d = new Dispute();
        d.setId(Ids.next("dp"));
        d.setMerchantId(payment.getMerchantId());
        d.setAppId(payment.getAppId());
        d.setLivemode(payment.isLivemode());
        d.setPaymentId(payment.getId());
        d.setAmountCents(amountCents);
        d.setReason(reason == null || reason.isBlank() ? "GENERAL" : reason.trim());
        d.setRail(payment.getRail());
        d.setProviderDisputeId(providerDisputeId);
        d.setEvidenceDueBy(evidenceDueBy != null ? evidenceDueBy : DisputeRules.dueBy(now, props.evidenceWindowDays()));
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        try {
            disputes.insert(d);
        } catch (DuplicateKeyException raced) {
            if (providerDisputeId != null) {
                return disputes.findByRailAndProviderDisputeId(payment.getRail(), providerDisputeId).orElseThrow(() -> raced);
            }
            throw raced;
        }
        completeOpening(d);
        return disputes.findById(d.getId()).orElse(d);
    }

    /** Holds the money and raises dispute.opened. Safe to run again. */
    void completeOpening(Dispute d) {
        MerchantWallet wallet = wallets.findByMerchantIdAndLivemode(d.getMerchantId(), d.isLivemode())
                .orElseThrow(() -> new IllegalStateException("Wallet missing for merchant " + d.getMerchantId()));
        String holdId = "hold_" + d.getId();
        long held = 0;
        try {
            LedgerService.Applied applied = ledger.hold(wallet.getWalletId(), d.getId(), d.getAmountCents(),
                    "Dispute " + d.getId() + " on payment " + d.getPaymentId() + " held");
            held = d.getAmountCents();
            try {
                holds.insert(new MerchantHold(holdId, applied.wallet(), d.getId(), d.getAmountCents(), "DISPUTE", Instant.now()));
            } catch (DuplicateKeyException already) {
                // Recorded by an earlier attempt.
            }
        } catch (CommercialException e) {
            if (e.code() != ErrorCode.INSUFFICIENT_BALANCE) throw e;
            log.warn("Dispute {} opened without a hold: the available balance does not cover {}", d.getId(),
                    Money.format(d.getAmountCents()));
        }

        Document set = new Document("opened", true).append("updatedAt", Date.from(Instant.now()));
        if (held > 0) set.append("holdId", holdId).append("heldCents", held);
        mongo.updateFirst(Query.query(Criteria.where("id").is(d.getId()).and("opened").is(false)),
                new BasicUpdate(new Document("$set", set)), Dispute.class);
        emit(disputes.findById(d.getId()).orElse(d), EventTypes.DISPUTE_OPENED);
    }

    /** Records the provider's decision and settles the money. Repeating the same decision changes nothing. */
    public Dispute resolve(String disputeId, boolean won) {
        Dispute.Status to = won ? Dispute.Status.WON : Dispute.Status.LOST;
        Instant now = Instant.now();
        Document update = new Document("$set", new Document("status", to.name()).append("settled", false)
                .append("resolvedAt", Date.from(now)).append("updatedAt", Date.from(now)))
                .append("$inc", new Document("version", 1L));
        long moved = mongo.updateFirst(Query.query(Criteria.where("id").is(disputeId).and("opened").is(true)
                .and("status").in(Dispute.ACTIVE.stream().map(Enum::name).toList())),
                new BasicUpdate(update), Dispute.class).getModifiedCount();
        Dispute d = disputes.findById(disputeId).orElseThrow(() -> CommercialException.notFound("Dispute"));
        if (moved == 0) {
            if (d.getStatus() == to) return d;
            throw CommercialException.invalidState(d.isOpened()
                    ? "The dispute is already " + d.getStatus()
                    : "The dispute is still being opened; try again in a moment");
        }
        settle(d);
        return disputes.findById(disputeId).orElse(d);
    }

    /** Moves the money for a decided dispute and raises dispute.won or dispute.lost. Safe to run again. */
    void settle(Dispute d) {
        MerchantWallet wallet = wallets.findByMerchantIdAndLivemode(d.getMerchantId(), d.isLivemode())
                .orElseThrow(() -> new IllegalStateException("Wallet missing for merchant " + d.getMerchantId()));
        long unrecovered = 0;
        boolean won = d.getStatus() == Dispute.Status.WON;

        if (won) {
            if (d.getHoldId() != null) {
                ledger.release(wallet.getWalletId(), d.getId(), d.getHeldCents(), "Dispute " + d.getId() + " won, hold released");
                closeHold(d, MerchantHold.RELEASED);
            }
        } else {
            boolean haveMoney = d.getHoldId() != null;
            if (!haveMoney) {
                try {
                    ledger.hold(wallet.getWalletId(), d.getId(), d.getAmountCents(), "Dispute " + d.getId() + " lost");
                    haveMoney = true;
                } catch (CommercialException e) {
                    if (e.code() != ErrorCode.INSUFFICIENT_BALANCE) throw e;
                    unrecovered = d.getAmountCents();
                    log.error("Dispute {} was lost but the balance does not cover {}; recorded as unrecovered",
                            d.getId(), Money.format(d.getAmountCents()));
                }
            }
            if (haveMoney) {
                ledger.capture(wallet.getWalletId(), d.getId(), d.getAmountCents(), 0, "Dispute " + d.getId() + " lost");
                closeHold(d, MerchantHold.CAPTURED);
            }
        }

        emit(d, won ? EventTypes.DISPUTE_WON : EventTypes.DISPUTE_LOST);
        Document set = new Document("settled", true).append("updatedAt", Date.from(Instant.now()));
        if (unrecovered > 0) set.append("unrecoveredCents", unrecovered);
        mongo.updateFirst(Query.query(Criteria.where("id").is(d.getId())), new BasicUpdate(new Document("$set", set)), Dispute.class);
    }

    private void closeHold(Dispute d, String state) {
        mongo.updateFirst(Query.query(Criteria.where("holdId").is(d.getHoldId()).and("state").is(MerchantHold.ACTIVE)),
                new BasicUpdate(new Document("$set", new Document("state", state).append("closedAt", Date.from(Instant.now())))),
                MerchantHold.class);
    }

    private void emit(Dispute d, String type) {
        String id = "evt_" + Hashing.sha256Hex((d.getId() + "|" + type).getBytes()).substring(0, 16);
        events.emitWithId(id, type, d.getMerchantId(), d.getAppId(), d.isLivemode(), d.getId(), DisputeViews.dispute(d),
                events.targetsFor(d.getAppId(), d.isLivemode(), type, null, false));
    }

    /** Finishes disputes whose opening or settling was interrupted, for example by a restart. */
    @Scheduled(fixedDelayString = "${commercial.disputes.recovery-interval-ms:30000}")
    public void recover() {
        Instant cutoff = Instant.now().minusSeconds(RECOVER_AFTER_SECONDS);
        Criteria unopened = Criteria.where("opened").is(false).and("createdAt").lt(cutoff);
        for (Dispute d : mongo.find(Query.query(unopened).with(PageRequest.of(0, 50)), Dispute.class)) {
            try {
                log.warn("Finishing opening of dispute {}", d.getId());
                completeOpening(d);
            } catch (RuntimeException e) {
                log.error("Could not finish opening dispute {}", d.getId(), e);
            }
        }
        Criteria unsettled = Criteria.where("opened").is(true).and("settled").is(false)
                .and("status").in(Dispute.Status.WON.name(), Dispute.Status.LOST.name()).and("updatedAt").lt(cutoff);
        for (Dispute d : mongo.find(Query.query(unsettled).with(PageRequest.of(0, 50)), Dispute.class)) {
            try {
                log.warn("Finishing settlement of dispute {}", d.getId());
                settle(d);
            } catch (RuntimeException e) {
                log.error("Could not settle dispute {}", d.getId(), e);
            }
        }
    }

    // ------------------------------------------------------------------ evidence

    public CommercialIdempotencyService.Outcome<Map<String, Object>> submitEvidence(CommercialContext ctx, String disputeId,
            SubmitEvidenceRequest req, String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "DISPUTE_EVIDENCE",
                () -> doSubmit(ctx, disputeId, req),
                id -> DisputeViews.dispute(get(ctx, id)));
    }

    private String doSubmit(CommercialContext ctx, String disputeId, SubmitEvidenceRequest req) {
        DisputeRules.Clean clean = DisputeRules.clean(req);
        Dispute d = get(ctx, disputeId);
        Instant now = Instant.now();
        DisputeRules.requireOpenForEvidence(d.getStatus(), d.getEvidenceDueBy(), now);
        boolean submit = Boolean.TRUE.equals(req.submit());

        List<Dispute.Evidence> all = new ArrayList<>(d.getEvidence());
        Dispute.Evidence entry = new Dispute.Evidence(Ids.next("ev"), clean.type(), clean.text(), clean.attachments(),
                submit, now, submit ? now : null);

        if (submit) {
            // The provider gets the new entry together with every draft saved earlier.
            List<Map<String, Object>> toSend = new ArrayList<>();
            List<Dispute.Evidence> updated = new ArrayList<>();
            for (Dispute.Evidence e : all) {
                if (e.isSubmitted()) {
                    updated.add(e);
                } else {
                    toSend.add(payload(e));
                    updated.add(e.sentAt(now));
                }
            }
            toSend.add(payload(entry));
            updated.add(entry);
            send(d, toSend);
            write(d, updated, Dispute.Status.UNDER_REVIEW, now);
        } else {
            all.add(entry);
            write(d, all, Dispute.Status.NEEDS_EVIDENCE, now);
        }
        return d.getId();
    }

    private void send(Dispute d, List<Map<String, Object>> evidence) {
        CommercialTransaction payment = mongo.findById(d.getPaymentId(), CommercialTransaction.class);
        PaymentRail rail = rails.byId(d.getRail());
        try {
            rail.submitDisputeEvidence(new DisputeEvidenceCommand(d.getId(), d.getProviderDisputeId(), d.getPaymentId(),
                    payment == null ? null : payment.getProviderReference(), d.isLivemode(), evidence));
        } catch (RailException e) {
            if (e.kind() == RailException.Kind.UNAVAILABLE) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "The provider is unavailable; the evidence was not sent");
            }
            throw CommercialException.invalidState("The provider did not accept the evidence: " + e.getMessage());
        }
    }

    /** Writes the evidence list only if nobody changed the dispute since it was read. */
    private void write(Dispute d, List<Dispute.Evidence> evidence, Dispute.Status status, Instant now) {
        List<Document> docs = new ArrayList<>();
        for (Dispute.Evidence e : evidence) docs.add(DisputeRules.toDocument(e));
        Document update = new Document("$set", new Document("evidence", docs).append("status", status.name())
                .append("updatedAt", Date.from(now))).append("$inc", new Document("version", 1L));
        long changed = mongo.updateFirst(Query.query(Criteria.where("id").is(d.getId())
                .and("status").is(Dispute.Status.NEEDS_EVIDENCE.name()).and("version").is(d.getVersion())),
                new BasicUpdate(update), Dispute.class).getModifiedCount();
        if (changed != 1) {
            throw CommercialException.invalidState("The dispute changed while the evidence was being saved; read it and try again");
        }
    }

    private static Map<String, Object> payload(Dispute.Evidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("type", e.getType());
        m.put("text", e.getText());
        List<Map<String, Object>> files = new ArrayList<>();
        for (Dispute.Attachment a : e.getAttachments()) {
            files.add(new LinkedHashMap<>(Map.of("name", a.getName(), "url", a.getUrl(), "mimeType", a.getMimeType())));
        }
        m.put("attachments", files);
        return m;
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
        }
    }
}

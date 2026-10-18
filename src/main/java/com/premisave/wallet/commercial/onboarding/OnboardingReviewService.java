package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.OnboardingApplicationRepository;
import com.premisave.wallet.commercial.util.Ids;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The staff side of onboarding: picking an application up, verifying documents, sending it back for correction,
 * approving or rejecting it. Who may do each step is decided by RolePolicy. Every step is written to the
 * timeline and the applicant is emailed about the ones that concern them.
 */
@Service("commercialOnboardingReviewService")
public class OnboardingReviewService {

    private final OnboardingApplicationRepository applications;
    private final MerchantProfileRepository merchants;
    private final CommercialOnboardingProperties props;
    private final RolePolicy roles;
    private final OnboardingTimeline timeline;
    private final OnboardingNotifier notifier;
    private final OnboardingService onboarding;

    public OnboardingReviewService(OnboardingApplicationRepository applications, MerchantProfileRepository merchants,
                                   CommercialOnboardingProperties props, RolePolicy roles, OnboardingTimeline timeline,
                                   OnboardingNotifier notifier, OnboardingService onboarding) {
        this.applications = applications;
        this.merchants = merchants;
        this.props = props;
        this.roles = roles;
        this.timeline = timeline;
        this.notifier = notifier;
        this.onboarding = onboarding;
    }

    /**
     * The review queue. applicationId and merchantNumber each find one application exactly; the number exists only for
     * approved applications, so an application still being worked on is found by its id.
     */
    public Page<OnboardingApplication> list(OnboardingStatus status, String merchantNumber, String applicationId, Pageable pageable) {
        if (applicationId != null && !applicationId.isBlank()) {
            var found = applications.findById(applicationId.trim());
            List<OnboardingApplication> one = found.filter(a -> status == null || a.getStatus() == status)
                    .map(List::of).orElse(List.of());
            return new org.springframework.data.domain.PageImpl<>(one, pageable, one.size());
        }
        if (merchantNumber != null && !merchantNumber.isBlank()) {
            var found = applications.findByMerchantNumber(MerchantNumbers.normalise(merchantNumber));
            List<OnboardingApplication> one = found.filter(a -> status == null || a.getStatus() == status)
                    .map(List::of).orElse(List.of());
            return new org.springframework.data.domain.PageImpl<>(one, pageable, one.size());
        }
        return status == null ? applications.findAll(pageable) : applications.findByStatus(status, pageable);
    }

    public Map<String, Object> get(String applicationId) {
        OnboardingApplication a = require(applicationId);
        return OnboardingViews.application(a, true, props.requiredDocuments(), onboarding.missingFor(a));
    }

    public OnboardingService.DocumentFile download(String applicationId, String documentId) {
        return onboarding.fetch(require(applicationId), documentId);
    }

    public Map<String, Object> startReview(String applicationId, Actor actor) {
        roles.require(actor, Permission.REVIEW);
        OnboardingApplication a = require(applicationId);
        if (a.getStatus() == OnboardingStatus.IN_REVIEW && actor.id().equals(a.getReviewerId())) {
            return get(applicationId);
        }
        expect(a, "startReview", "Only a submitted application can be picked up for review");
        OnboardingStatus from = a.getStatus();
        a.setStatus(OnboardingStatus.IN_REVIEW);
        a.setReviewerId(actor.id());
        a.setReviewerName(actor.name());
        a.setReviewerRole(actor.role());
        touch(a);
        applications.save(a);
        timeline.record(a, OnboardingEventType.REVIEW_STARTED, actor, from, OnboardingStatus.IN_REVIEW,
                "Review started", true);
        notifier.toApplicant(a, onboarding.recipient(a), OnboardingEmails.reviewStarted(name(a), OnboardingService.reference(a)));
        return get(applicationId);
    }

    public Map<String, Object> addNote(String applicationId, String note, Actor actor) {
        roles.require(actor, Permission.REVIEW);
        OnboardingApplication a = require(applicationId);
        timeline.record(a, OnboardingEventType.NOTE_ADDED, actor, a.getStatus(), a.getStatus(), note.trim(), false);
        return get(applicationId);
    }

    public Map<String, Object> documentVerdict(String applicationId, String documentId, DocumentVerdictRequest req, Actor actor) {
        roles.require(actor, Permission.REVIEW);
        OnboardingApplication a = require(applicationId);
        if (a.getStatus() != OnboardingStatus.IN_REVIEW) {
            throw CommercialException.invalidState("Documents can be marked only while the application is in review");
        }
        OnboardingDocument d = a.getDocuments().stream().filter(x -> x.getDocumentId().equals(documentId)).findFirst()
                .orElseThrow(() -> CommercialException.notFound("Document"));
        String note = req.note() == null ? null : req.note().trim();
        if (req.verdict() == DocumentVerdict.REJECTED && (note == null || note.isEmpty())) {
            throw CommercialException.validation("note", "is required when a document is rejected");
        }
        d.setVerdict(req.verdict());
        d.setVerdictNote(note);
        d.setVerdictBy(actor.id());
        d.setVerdictAt(Instant.now());
        touch(a);
        applications.save(a);
        timeline.record(a, OnboardingEventType.DOCUMENT_VERDICT, actor, a.getStatus(), a.getStatus(),
                d.getType().name() + " " + d.getFileName() + " marked " + req.verdict().name()
                        + (note == null ? "" : ": " + note), false);
        return get(applicationId);
    }

    public Map<String, Object> returnForCorrection(String applicationId, ReturnApplicationRequest req, Actor actor) {
        roles.require(actor, Permission.RETURN);
        OnboardingApplication a = require(applicationId);
        expect(a, "return", "Only an application in review can be returned");
        if (req.items().isEmpty()) {
            throw CommercialException.validation("items", "list at least one thing the applicant must correct");
        }
        for (ReturnItemRequest i : req.items()) {
            if (!OnboardingRules.validReturnField(i.field())) {
                throw CommercialException.validation("items.field", i.field() + " is not a form field or document:TYPE");
            }
        }
        Instant now = Instant.now();
        OnboardingStatus from = a.getStatus();
        for (ReturnItemRequest i : req.items()) {
            ReturnItem item = new ReturnItem();
            item.setItemId(Ids.next("itm"));
            item.setField(i.field().trim());
            item.setMessage(i.message().trim());
            item.setRaisedBy(actor.id());
            item.setRaisedAt(now);
            a.getReturnItems().add(item);
        }
        a.setStatus(OnboardingStatus.RETURNED);
        touch(a);
        applications.save(a);
        timeline.record(a, OnboardingEventType.RETURNED, actor, from, OnboardingStatus.RETURNED,
                req.message().trim() + " (" + req.items().size() + " item" + (req.items().size() == 1 ? "" : "s") + ")", true);
        List<ReturnItem> open = a.getReturnItems().stream().filter(i -> !i.isAddressed()).toList();
        notifier.toApplicant(a, onboarding.recipient(a),
                OnboardingEmails.returned(name(a), OnboardingService.reference(a), req.message().trim(), open));
        return get(applicationId);
    }

    public Map<String, Object> approve(String applicationId, DecisionRequest req, Actor actor) {
        roles.require(actor, Permission.APPROVE);
        OnboardingApplication a = require(applicationId);
        expect(a, "approve", "Only an application in review can be approved");

        if (a.getReturnItems().stream().anyMatch(i -> !i.isAddressed())) {
            throw new CommercialException(ErrorCode.APPLICATION_INCOMPLETE, "Items sent back to the applicant are still open");
        }
        List<Map<String, String>> missing = onboarding.missingFor(a);
        if (!missing.isEmpty()) {
            throw new CommercialException(ErrorCode.APPLICATION_INCOMPLETE,
                    "The application is missing " + missing.size() + " required " + (missing.size() == 1 ? "item" : "items"))
                    .withDetails(missing);
        }
        List<String> unverified = OnboardingRules.unverifiedRequired(a.getDocuments(), props.requiredDocuments());
        if (!unverified.isEmpty()) {
            throw new CommercialException(ErrorCode.APPLICATION_INCOMPLETE,
                    "These required documents have no accepted version yet: " + String.join(", ", unverified));
        }

        MerchantProfile m = merchants.findById(a.getMerchantId())
                .orElseThrow(() -> new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found"));
        MerchantStatus live = m.statusFor(Environment.LIVE);
        boolean enabling = live == MerchantStatus.PENDING_APPROVAL;
        if (!enabling && live != MerchantStatus.ACTIVE) {
            throw CommercialException.invalidState("Production is " + live.name().toLowerCase(Locale.ROOT)
                    + " for this merchant and cannot be switched on by an approval");
        }

        // The merchant number is issued here and nowhere else. Issuing is repeat safe: a business that already has a number
        // keeps it, so an approval repeated after a failure reuses it. It comes before the switch-on so the merchant is
        // saved with its number.
        m = onboarding.issueNumber(m);
        boolean announceNumber = a.getMerchantNumber() == null;
        a.setMerchantNumber(m.getMerchantNumber());

        // The merchant is switched on first, so if saving the application fails the approval can simply be repeated.
        if (enabling) {
            m.setStatusFor(Environment.LIVE, MerchantStatus.ACTIVE);
            m.setUpdatedAt(Instant.now());
            merchants.save(m);
        }

        String message = req == null || req.message() == null || req.message().isBlank() ? null : req.message().trim();
        OnboardingStatus from = a.getStatus();
        a.setStatus(OnboardingStatus.APPROVED);
        decide(a, actor, message);
        applications.save(a);
        timeline.record(a, OnboardingEventType.APPROVED, actor, from, OnboardingStatus.APPROVED,
                message == null ? "Application approved" : message, true);
        if (announceNumber) {
            timeline.record(a, OnboardingEventType.MERCHANT_NUMBER_ISSUED, Actor.system(), null, null,
                    "Merchant number " + a.getMerchantNumber() + " issued", true);
        }
        if (enabling) {
            timeline.record(a, OnboardingEventType.PRODUCTION_ENABLED, Actor.system(), null, null,
                    "Production switched on for " + a.getMerchantNumber(), true);
        }
        notifier.toApplicant(a, onboarding.recipient(a), OnboardingEmails.approved(name(a), a.getMerchantNumber(), message));
        return get(applicationId);
    }

    public Map<String, Object> reject(String applicationId, DecisionRequest req, Actor actor) {
        roles.require(actor, Permission.REJECT);
        OnboardingApplication a = require(applicationId);
        expect(a, "reject", "Only a submitted application, or one in review, can be rejected");
        String reason = requiredMessage(req, "A reason is required to reject an application");
        OnboardingStatus from = a.getStatus();
        a.setStatus(OnboardingStatus.REJECTED);
        decide(a, actor, reason);
        applications.save(a);
        timeline.record(a, OnboardingEventType.REJECTED, actor, from, OnboardingStatus.REJECTED, reason, true);
        notifier.toApplicant(a, onboarding.recipient(a), OnboardingEmails.rejected(name(a), OnboardingService.reference(a), reason));
        return get(applicationId);
    }

    public Map<String, Object> reopen(String applicationId, DecisionRequest req, Actor actor) {
        roles.require(actor, Permission.REOPEN);
        OnboardingApplication a = require(applicationId);
        expect(a, "reopen", "Only a rejected application can be reopened");
        String reason = requiredMessage(req, "A reason is required to reopen an application");
        OnboardingStatus from = a.getStatus();
        a.setStatus(OnboardingStatus.IN_REVIEW);
        a.setReviewerId(actor.id());
        a.setReviewerName(actor.name());
        a.setReviewerRole(actor.role());
        a.setDecisionBy(null);
        a.setDecisionRole(null);
        a.setDecisionMessage(null);
        a.setDecidedAt(null);
        touch(a);
        applications.save(a);
        timeline.record(a, OnboardingEventType.REOPENED, actor, from, OnboardingStatus.IN_REVIEW, reason, true);
        notifier.toApplicant(a, onboarding.recipient(a), OnboardingEmails.reopened(name(a), OnboardingService.reference(a), reason));
        return get(applicationId);
    }

    public List<Map<String, Object>> timeline(String applicationId) {
        OnboardingApplication a = require(applicationId);
        return timeline.of(a.getApplicationId(), false).stream().map(e -> OnboardingViews.event(e, true)).toList();
    }

    // Helpers

    private OnboardingApplication require(String applicationId) {
        return applications.findById(applicationId)
                .orElseThrow(() -> new CommercialException(ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
    }

    private static void expect(OnboardingApplication a, String action, String message) {
        if (!OnboardingRules.sourcesFor(action).contains(a.getStatus())) {
            throw CommercialException.invalidState(message + " (it is " + a.getStatus().name().toLowerCase(Locale.ROOT) + ")");
        }
    }

    private static String requiredMessage(DecisionRequest req, String error) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw CommercialException.validation("message", error);
        }
        return req.message().trim();
    }

    private static void decide(OnboardingApplication a, Actor actor, String message) {
        a.setDecisionBy(actor.id());
        a.setDecisionRole(actor.role());
        a.setDecisionMessage(message);
        a.setDecidedAt(Instant.now());
        a.setUpdatedAt(a.getDecidedAt());
    }

    private static void touch(OnboardingApplication a) {
        a.setUpdatedAt(Instant.now());
    }

    private static String name(OnboardingApplication a) {
        return a.getForm().getRegisteredName();
    }
}
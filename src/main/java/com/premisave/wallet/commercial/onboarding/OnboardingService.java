package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.OnboardingApplicationRepository;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The applicant's side of onboarding: the form, the documents and the submission. The developer service calls
 * this on behalf of the signed in merchant owner. Staff decisions are in OnboardingReviewService.
 */
@Service("commercialOnboardingService")
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final OnboardingApplicationRepository applications;
    private final MerchantProfileRepository merchants;
    private final CommercialOnboardingProperties props;
    private final DocumentStorage storage;
    private final OnboardingTimeline timeline;
    private final OnboardingNotifier notifier;
    private final MerchantNumberService numbers;
    private final WalletDirectory wallets;

    public OnboardingService(OnboardingApplicationRepository applications, MerchantProfileRepository merchants,
                             CommercialOnboardingProperties props, DocumentStorage storage,
                             OnboardingTimeline timeline, OnboardingNotifier notifier, MerchantNumberService numbers,
                             WalletDirectory wallets) {
        this.applications = applications;
        this.merchants = merchants;
        this.props = props;
        this.storage = storage;
        this.timeline = timeline;
        this.notifier = notifier;
        this.numbers = numbers;
        this.wallets = wallets;
    }

    /** Called when a merchant is created: the application starts as a draft with what is already known. */
    public OnboardingApplication createFor(MerchantProfile p) {
        Instant now = Instant.now();
        OnboardingApplication a = new OnboardingApplication();
        a.setApplicationId(Ids.next("onb"));
        a.setMerchantId(p.getMerchantId());
        a.setMerchantNumber(p.getMerchantNumber());
        a.setStatus(OnboardingStatus.DRAFT);
        BusinessForm f = a.getForm();
        f.setRegisteredName(p.getBusinessName());
        f.setCountry(p.getCountry());
        f.setContactEmail(p.getContactEmail());
        f.setContactPhone(p.getContactPhone());
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        applications.insert(a);
        timeline.record(a, OnboardingEventType.APPLICATION_CREATED, Actor.system(), null, OnboardingStatus.DRAFT,
                "Application started when the merchant was created", true);
        return a;
    }

    /**
     * Starts an application for a merchant that has none, for example one created before onboarding existed.
     * Returns the existing application when there already is one.
     */
    public Started start(String merchantId) {
        var existing = applications.findByMerchantId(merchantId);
        if (existing.isPresent()) {
            OnboardingApplication a = existing.get();
            return new Started(false, OnboardingViews.application(a, false, props.requiredDocuments(), missingFor(a)));
        }
        if (!props.enabled()) {
            throw CommercialException.invalidState("Merchant onboarding is switched off");
        }
        MerchantProfile p = merchants.findById(merchantId)
                .orElseThrow(() -> new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found"));
        OnboardingApplication a = createFor(numbers.issueIfLive(p));
        return new Started(true, OnboardingViews.application(a, false, props.requiredDocuments(), missingFor(a)));
    }

    /**
     * The short form of an application used in a merchant view: where it stands, whether it can be submitted now, and
     * what is still missing. Lets a business list show what each business needs without opening every application.
     */
    public Map<String, Object> applicantSummary(OnboardingApplication a) {
        List<Map<String, String>> missing = missingFor(a);
        Map<String, Object> o = new java.util.LinkedHashMap<>();
        o.put("applicationId", a.getApplicationId());
        o.put("status", a.getStatus().name());
        o.put("readyToSubmit", missing.isEmpty() && a.getStatus().editable());
        o.put("missing", missing);
        o.put("walletAccount", a.getLinkedWalletAccount());
        String contact = a.getForm().getContactEmail();
        o.put("contactEmail", contact == null || contact.isBlank() ? null : contact);
        return o;
    }

    public Map<String, Object> get(String merchantId) {
        OnboardingApplication a = forMerchant(merchantId);
        return OnboardingViews.application(a, false, props.requiredDocuments(), missingFor(a));
    }

    public Map<String, Object> saveForm(String merchantId, OnboardingFormRequest req, Actor actor) {
        OnboardingApplication a = editable(forMerchant(merchantId));
        BusinessForm f = a.getForm();
        if (req.businessType() != null) f.setBusinessType(req.businessType());
        if (req.registeredName() != null) f.setRegisteredName(clean(req.registeredName()));
        if (req.tradingName() != null) f.setTradingName(clean(req.tradingName()));
        if (req.registrationNumber() != null) f.setRegistrationNumber(clean(req.registrationNumber()));
        if (req.taxPin() != null) f.setTaxPin(clean(req.taxPin()));
        if (req.country() != null) f.setCountry(req.country().trim().toUpperCase(Locale.ROOT));
        if (req.city() != null) f.setCity(clean(req.city()));
        if (req.physicalAddress() != null) f.setPhysicalAddress(clean(req.physicalAddress()));
        if (req.website() != null) f.setWebsite(clean(req.website()));
        if (req.industry() != null) f.setIndustry(clean(req.industry()));
        if (req.businessDescription() != null) f.setBusinessDescription(clean(req.businessDescription()));
        if (req.expectedMonthlyVolumeUsd() != null) f.setExpectedMonthlyVolumeUsd(req.expectedMonthlyVolumeUsd());
        if (req.contactName() != null) f.setContactName(clean(req.contactName()));
        if (req.contactEmail() != null) f.setContactEmail(req.contactEmail().trim());
        if (req.contactPhone() != null) f.setContactPhone(clean(req.contactPhone()));
        if (req.walletAccountNumber() != null) linkWallet(a, req.walletAccountNumber());
        if (req.onBehalf() != null) f.setOnBehalf(req.onBehalf());
        if (req.applicantRole() != null) f.setApplicantRole(clean(req.applicantRole()));
        if (req.directors() != null) {
            List<Director> directors = new ArrayList<>();
            for (DirectorDto d : req.directors()) {
                Director x = new Director();
                x.setFullName(clean(d.fullName()));
                x.setIdNumber(clean(d.idNumber()));
                x.setRole(clean(d.role()));
                x.setOwnershipPercent(d.ownershipPercent());
                directors.add(x);
            }
            if (OnboardingRules.ownershipTotal(directors) > 100) {
                throw CommercialException.validation("directors", "ownership adds up to more than 100 percent");
            }
            f.setDirectors(directors);
        }
        a.setUpdatedAt(Instant.now());
        applications.save(a);
        timeline.record(a, OnboardingEventType.FORM_SAVED, actor, a.getStatus(), a.getStatus(), "Application details saved", true);
        return OnboardingViews.application(a, false, props.requiredDocuments(), missingFor(a));
    }

    public Map<String, Object> uploadDocument(String merchantId, String typeText, String fileName, String contentType,
                                              byte[] bytes, Actor actor) {
        OnboardingApplication a = editable(forMerchant(merchantId));
        DocumentType type = parseType(typeText);
        if (bytes == null || bytes.length == 0) throw CommercialException.validation("file", "is empty");
        if (bytes.length > props.maxDocumentBytes()) {
            throw CommercialException.validation("file", "is larger than " + (props.maxDocumentBytes() / (1024 * 1024)) + " MB");
        }
        if (!OnboardingRules.allowedContentType(contentType)) {
            throw CommercialException.validation("file", "must be a PDF, JPEG or PNG");
        }
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        if (!OnboardingRules.matchesSignature(ct, bytes)) {
            throw CommercialException.validation("file", "the content does not match its file type");
        }
        if (a.getDocuments().size() >= props.maxDocuments()) {
            throw CommercialException.validation("file", "an application can hold at most " + props.maxDocuments() + " documents");
        }

        String documentId = Ids.next("doc");
        String safeName = OnboardingRules.safeFileName(fileName);
        String storedName = a.getApplicationId() + "_" + type.name() + "_" + documentId + "_" + safeName;
        DocumentStorage.StoredFile stored = storage.upload(storedName, ct, bytes);

        OnboardingDocument d = new OnboardingDocument();
        d.setDocumentId(documentId);
        d.setType(type);
        d.setFileName(safeName);
        d.setContentType(ct);
        d.setSizeBytes(bytes.length);
        d.setSha256(Hashing.sha256Hex(bytes));
        d.setStorageFileId(stored.fileId());
        d.setUploadedBy(actor.id());
        d.setUploadedAt(Instant.now());
        a.getDocuments().add(d);
        a.setUpdatedAt(Instant.now());
        try {
            applications.save(a);
        } catch (RuntimeException e) {
            discard(stored.fileId());
            throw e;
        }
        timeline.record(a, OnboardingEventType.DOCUMENT_UPLOADED, actor, a.getStatus(), a.getStatus(),
                type.name() + " uploaded: " + safeName, true);
        return OnboardingViews.document(d, false);
    }

    public Map<String, Object> removeDocument(String merchantId, String documentId, Actor actor) {
        OnboardingApplication a = editable(forMerchant(merchantId));
        OnboardingDocument d = a.getDocuments().stream().filter(x -> x.getDocumentId().equals(documentId)).findFirst()
                .orElseThrow(() -> CommercialException.notFound("Document"));
        a.getDocuments().remove(d);
        a.setUpdatedAt(Instant.now());
        applications.save(a);
        discard(d.getStorageFileId());
        timeline.record(a, OnboardingEventType.DOCUMENT_REMOVED, actor, a.getStatus(), a.getStatus(),
                d.getType().name() + " removed: " + d.getFileName(), true);
        return OnboardingViews.application(a, false, props.requiredDocuments(), missingFor(a));
    }

    public DocumentFile download(String merchantId, String documentId) {
        OnboardingApplication a = forMerchant(merchantId);
        return fetch(a, documentId);
    }

    public DocumentFile fetch(OnboardingApplication a, String documentId) {
        OnboardingDocument d = a.getDocuments().stream().filter(x -> x.getDocumentId().equals(documentId)).findFirst()
                .orElseThrow(() -> CommercialException.notFound("Document"));
        return new DocumentFile(d.getFileName(), d.getContentType(), storage.download(d.getStorageFileId()));
    }

    public Map<String, Object> submit(String merchantId, SubmitApplicationRequest req, Actor actor) {
        OnboardingApplication a = forMerchant(merchantId);
        if (!OnboardingRules.sourcesFor("submit").contains(a.getStatus())) {
            throw CommercialException.invalidState("An application that is " + a.getStatus().name().toLowerCase(Locale.ROOT)
                    + " cannot be submitted");
        }
        List<Map<String, String>> missing = missingFor(a);
        if (missing.isEmpty()) missing = lostFiles(a);
        if (!missing.isEmpty()) {
            throw new CommercialException(ErrorCode.APPLICATION_INCOMPLETE,
                    "The application is missing " + missing.size() + " required " + (missing.size() == 1 ? "item" : "items"))
                    .withDetails(missing);
        }

        boolean resubmission = a.getStatus() == OnboardingStatus.RETURNED;
        if (resubmission) {
            Map<String, String> answers = new java.util.HashMap<>();
            if (req != null && req.responses() != null) {
                for (ItemResponse r : req.responses()) {
                    boolean known = a.getReturnItems().stream().anyMatch(i -> i.getItemId().equals(r.itemId()) && !i.isAddressed());
                    if (!known) throw CommercialException.validation("responses", "unknown item " + r.itemId());
                    answers.put(r.itemId(), r.response());
                }
            }
            Instant now = Instant.now();
            for (ReturnItem i : a.getReturnItems()) {
                if (i.isAddressed()) continue;
                i.setAddressed(true);
                i.setAddressedAt(now);
                i.setResponse(answers.get(i.getItemId()));
            }
        }

        OnboardingStatus from = a.getStatus();
        a.setStatus(OnboardingStatus.SUBMITTED);
        a.setSubmissions(a.getSubmissions() + 1);
        a.setSubmittedAt(Instant.now());
        a.setUpdatedAt(a.getSubmittedAt());
        a.setReviewerId(null);
        a.setReviewerName(null);
        a.setReviewerRole(null);
        applications.save(a);

        String note = req != null && req.message() != null && !req.message().isBlank() ? req.message().trim() : null;
        timeline.record(a, resubmission ? OnboardingEventType.RESUBMITTED : OnboardingEventType.SUBMITTED, actor, from,
                OnboardingStatus.SUBMITTED, note == null ? (resubmission ? "Corrections submitted" : "Application submitted") : note, true);

        String name = a.getForm().getRegisteredName();
        notifier.toApplicant(a, recipient(a), OnboardingEmails.received(name, reference(a), resubmission));
        notifier.toStaff(a, OnboardingEmails.staffAlert(name, reference(a), resubmission));
        String owner = a.getLinkedWalletAccount();
        if (!resubmission && owner != null && !owner.equalsIgnoreCase(recipient(a))) {
            notifier.toApplicant(a, owner, OnboardingEmails.walletOwnerNotice(name, a.getForm().isOnBehalf(),
                    a.getForm().getApplicantRole()));
        }
        return OnboardingViews.application(a, false, props.requiredDocuments(), List.of());
    }

    public List<Map<String, Object>> timeline(String merchantId) {
        OnboardingApplication a = forMerchant(merchantId);
        return timeline.of(a.getApplicationId(), true).stream().map(e -> OnboardingViews.event(e, false)).toList();
    }

    // Helpers

    OnboardingApplication forMerchant(String merchantId) {
        return applications.findByMerchantId(merchantId)
                .orElseThrow(() -> new CommercialException(ErrorCode.APPLICATION_NOT_FOUND,
                        "No onboarding application exists for this merchant"));
    }

    String recipient(OnboardingApplication a) {
        String email = a.getForm().getContactEmail();
        if (email != null && !email.isBlank()) return email;
        return merchants.findById(a.getMerchantId()).map(MerchantProfile::getContactEmail).orElse(null);
    }

    /**
     * What an email calls the application: its merchant number once approved, its application id before that, because the
     * number is only issued at approval.
     */
    static String reference(OnboardingApplication a) {
        return a.getMerchantNumber() != null ? a.getMerchantNumber() : a.getApplicationId();
    }

    /** Issues the merchant number if the business has none yet. Called when an application is approved. */
    MerchantProfile issueNumber(MerchantProfile merchant) {
        return numbers.issue(merchant);
    }

    List<Map<String, String>> missingFor(OnboardingApplication a) {
        return OnboardingRules.missing(a.getForm(), a.getDocuments(), props.requiredDocuments(), a.getReturnItems());
    }

    /**
     * Required document types whose uploaded files are no longer in storage, for example because someone deleted them
     * from the shared folder. Only run when the application is submitted, so reading an application never touches the disk.
     * Throws when the storage cannot be reached, because then nothing can be said about the files.
     */
    private List<Map<String, String>> lostFiles(OnboardingApplication a) {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (String type : OnboardingRules.neededTypes(a.getForm(), props.requiredDocuments())) {
            boolean found = a.getDocuments().stream()
                    .filter(d -> d.getType() != null && d.getType().name().equals(type) && d.getVerdict() != DocumentVerdict.REJECTED)
                    .anyMatch(d -> storage.exists(d.getStorageFileId()));
            if (!found) {
                out.add(Map.of("field", "document:" + type,
                        "issue", "the uploaded " + type + " file can no longer be found, please upload it again"));
            }
        }
        return out;
    }

    /** Links the wallet named on the form, or clears the link when the account number is blank. */
    private void linkWallet(OnboardingApplication a, String accountNumber) {
        BusinessForm f = a.getForm();
        if (accountNumber.isBlank()) {
            f.setWalletAccountNumber(null);
            a.setLinkedWalletId(null);
            a.setLinkedWalletAccount(null);
            return;
        }
        WalletDirectory.WalletRef ref = wallets.find(accountNumber.trim()).orElseThrow(() -> CommercialException.validation(
                "walletAccountNumber", "no Premisave wallet has that account number"));
        if (ref.frozen()) {
            throw CommercialException.validation("walletAccountNumber", "that wallet is frozen and cannot be linked");
        }
        String account = ref.accountNumber().trim().toLowerCase(Locale.ROOT);
        f.setWalletAccountNumber(account);
        a.setLinkedWalletId(ref.walletId());
        a.setLinkedWalletAccount(account);
    }

    private OnboardingApplication editable(OnboardingApplication a) {
        if (!a.getStatus().editable()) {
            throw CommercialException.invalidState("An application that is " + a.getStatus().name().toLowerCase(Locale.ROOT)
                    + " cannot be changed");
        }
        return a;
    }

    private static DocumentType parseType(String text) {
        if (text == null || text.isBlank()) throw CommercialException.validation("type", "is required");
        try {
            return DocumentType.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation("type", "must be one of " + java.util.Arrays.toString(DocumentType.values()));
        }
    }

    private void discard(String fileId) {
        try {
            storage.delete(fileId);
        } catch (RuntimeException e) {
            log.warn("Could not remove stored document {}: {}", fileId, e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? null : s.trim();
    }

    /** Whether an application was created by the call, and its applicant view. */
    public record Started(boolean created, Map<String, Object> application) {}

    /** A document ready to send back to a caller. */
    public record DocumentFile(String fileName, String contentType, byte[] bytes) {}
}
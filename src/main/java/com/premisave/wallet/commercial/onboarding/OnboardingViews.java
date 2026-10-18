package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.dto.Views;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON shapes of applications. The applicant view leaves out reviewer identities and anything internal. */
public final class OnboardingViews {

    private OnboardingViews() {}

    public static Map<String, Object> application(OnboardingApplication a, boolean staff, List<String> requiredDocuments,
                                                  List<Map<String, String>> missing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", a.getApplicationId());
        m.put("merchantId", a.getMerchantId());
        m.put("merchantNumber", a.getMerchantNumber());
        m.put("status", a.getStatus().name());
        m.put("submissions", a.getSubmissions());
        m.put("form", form(a.getForm()));
        m.put("documents", a.getDocuments().stream().map(d -> document(d, staff)).toList());
        m.put("requiredDocuments", requiredDocuments);
        m.put("returnItems", a.getReturnItems().stream().map(OnboardingViews::returnItem).toList());
        if (missing != null) {
            m.put("missing", missing);
            m.put("readyToSubmit", missing.isEmpty() && a.getStatus().editable());
        }
        if (a.getDecisionMessage() != null || a.getDecidedAt() != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("message", a.getDecisionMessage());
            d.put("decidedAt", Views.iso(a.getDecidedAt()));
            if (staff) {
                d.put("decidedBy", a.getDecisionBy());
                d.put("decidedByRole", a.getDecisionRole());
            }
            m.put("decision", d);
        }
        if (staff) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", a.getReviewerId());
            r.put("name", a.getReviewerName());
            r.put("role", a.getReviewerRole());
            m.put("reviewer", r);
        }
        m.put("submittedAt", Views.iso(a.getSubmittedAt()));
        m.put("createdAt", Views.iso(a.getCreatedAt()));
        m.put("updatedAt", Views.iso(a.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> summary(OnboardingApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", a.getApplicationId());
        m.put("merchantId", a.getMerchantId());
        m.put("merchantNumber", a.getMerchantNumber());
        m.put("status", a.getStatus().name());
        m.put("businessName", a.getForm().getRegisteredName());
        m.put("country", a.getForm().getCountry());
        m.put("walletAccount", a.getLinkedWalletAccount());
        m.put("onBehalf", a.getForm().isOnBehalf());
        m.put("documents", a.getDocuments().size());
        m.put("submissions", a.getSubmissions());
        m.put("reviewerName", a.getReviewerName());
        m.put("submittedAt", Views.iso(a.getSubmittedAt()));
        m.put("updatedAt", Views.iso(a.getUpdatedAt()));
        return m;
    }

    static Map<String, Object> form(BusinessForm f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessType", f.getBusinessType() == null ? null : f.getBusinessType().name());
        m.put("registeredName", f.getRegisteredName());
        m.put("tradingName", f.getTradingName());
        m.put("registrationNumber", f.getRegistrationNumber());
        m.put("taxPin", f.getTaxPin());
        m.put("country", f.getCountry());
        m.put("city", f.getCity());
        m.put("physicalAddress", f.getPhysicalAddress());
        m.put("website", f.getWebsite());
        m.put("industry", f.getIndustry());
        m.put("businessDescription", f.getBusinessDescription());
        m.put("expectedMonthlyVolumeUsd", f.getExpectedMonthlyVolumeUsd());
        m.put("contactName", f.getContactName());
        m.put("contactEmail", f.getContactEmail());
        m.put("contactPhone", f.getContactPhone());
        m.put("walletAccountNumber", f.getWalletAccountNumber());
        m.put("onBehalf", f.isOnBehalf());
        m.put("applicantRole", f.getApplicantRole());
        List<Map<String, Object>> directors = new ArrayList<>();
        for (Director d : f.getDirectors() == null ? List.<Director>of() : f.getDirectors()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("fullName", d.getFullName());
            dm.put("idNumber", d.getIdNumber());
            dm.put("role", d.getRole());
            dm.put("ownershipPercent", d.getOwnershipPercent());
            directors.add(dm);
        }
        m.put("directors", directors);
        return m;
    }

    public static Map<String, Object> document(OnboardingDocument d, boolean staff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", d.getDocumentId());
        m.put("type", d.getType() == null ? null : d.getType().name());
        m.put("fileName", d.getFileName());
        m.put("contentType", d.getContentType());
        m.put("sizeBytes", d.getSizeBytes());
        m.put("verdict", d.getVerdict().name());
        m.put("verdictNote", d.getVerdictNote());
        m.put("uploadedAt", Views.iso(d.getUploadedAt()));
        if (staff) {
            m.put("sha256", d.getSha256());
            m.put("storageFileId", d.getStorageFileId());
            m.put("verdictBy", d.getVerdictBy());
            m.put("verdictAt", Views.iso(d.getVerdictAt()));
            m.put("uploadedBy", d.getUploadedBy());
        }
        return m;
    }

    static Map<String, Object> returnItem(ReturnItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", i.getItemId());
        m.put("field", i.getField());
        m.put("message", i.getMessage());
        m.put("addressed", i.isAddressed());
        m.put("response", i.getResponse());
        m.put("raisedAt", Views.iso(i.getRaisedAt()));
        m.put("addressedAt", Views.iso(i.getAddressedAt()));
        return m;
    }

    public static Map<String, Object> event(OnboardingEvent e, boolean staff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId());
        m.put("type", e.getType().name());
        m.put("at", Views.iso(e.getAt()));
        m.put("fromStatus", e.getFromStatus() == null ? null : e.getFromStatus().name());
        m.put("toStatus", e.getToStatus() == null ? null : e.getToStatus().name());
        m.put("message", e.getMessage());
        Actor a = new Actor(e.getActorType(), e.getActorId(), e.getActorName(), e.getActorRole());
        if (staff) {
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("type", e.getActorType().name());
            actor.put("id", e.getActorId());
            actor.put("name", e.getActorName());
            actor.put("role", e.getActorRole());
            m.put("actor", actor);
            m.put("visibleToApplicant", e.isVisibleToApplicant());
        } else {
            m.put("by", a.publicLabel());
        }
        return m;
    }
}
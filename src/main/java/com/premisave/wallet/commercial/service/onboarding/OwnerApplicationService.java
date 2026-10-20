package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.dto.onboarding.OnboardingViews;
import com.premisave.wallet.commercial.entity.onboarding.BusinessForm;
import com.premisave.wallet.commercial.entity.onboarding.Director;
import com.premisave.wallet.commercial.entity.onboarding.OnboardingApplication;
import com.premisave.wallet.commercial.entity.onboarding.OnboardingDocument;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingEventType;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.onboarding.OnboardingApplicationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The wallet owner's side of onboarding. A developer may apply for a business and name a Premisave wallet on the form; the
 * owner of that wallet, signed in with it, can follow the application and see the merchant number once it is approved.
 * The owner only reads. What they see leaves out ID numbers and tax pins, and the merchant number stays hidden until the
 * application is approved.
 */
@Service("commercialOwnerApplicationService")
public class OwnerApplicationService {

    private final OnboardingApplicationRepository applications;
    private final MerchantProfileRepository merchants;
    private final OnboardingTimeline timeline;

    public OwnerApplicationService(OnboardingApplicationRepository applications, MerchantProfileRepository merchants,
                                   OnboardingTimeline timeline) {
        this.applications = applications;
        this.merchants = merchants;
        this.timeline = timeline;
    }

    /** Every application that names this wallet, newest first, in short form. */
    public Map<String, Object> list(String ownerEmail) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OnboardingApplication a : mine(ownerEmail)) out.add(summary(a));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applications", out);
        m.put("count", out.size());
        return m;
    }

    /** One application in full. An application that does not name this wallet is not found, as if it did not exist. */
    public Map<String, Object> get(String ownerEmail, String applicationId) {
        OnboardingApplication a = mine(ownerEmail).stream().filter(x -> x.getApplicationId().equals(applicationId)).findFirst()
                .orElseThrow(() -> new CommercialException(ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
        Map<String, Object> m = summary(a);
        BusinessForm f = a.getForm();
        m.put("applicant", applicant(f));
        m.put("form", form(f));
        m.put("documents", a.getDocuments().stream().map(OwnerApplicationService::document).toList());
        m.put("returnItems", a.getReturnItems().stream().map(i -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("field", i.getField());
            r.put("message", i.getMessage());
            r.put("addressed", i.isAddressed());
            return r;
        }).toList());
        if (a.getDecisionMessage() != null || a.getDecidedAt() != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("message", a.getDecisionMessage());
            d.put("decidedAt", Views.iso(a.getDecidedAt()));
            m.put("decision", d);
        }
        m.put("timeline", timeline.of(a.getApplicationId(), true).stream()
                .filter(e -> e.getType() != OnboardingEventType.EMAIL_SENT && e.getType() != OnboardingEventType.EMAIL_FAILED)
                .map(e -> OnboardingViews.event(e, false)).toList());
        return m;
    }

    /** The merchant numbers of approved applications that name this wallet. */
    public Map<String, Object> merchantNumbers(String ownerEmail) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OnboardingApplication a : mine(ownerEmail)) {
            if (a.getStatus() != OnboardingStatus.APPROVED) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantNumber", a.getMerchantNumber());
            m.put("businessName", a.getForm().getRegisteredName());
            m.put("country", a.getForm().getCountry());
            m.put("approvedAt", Views.iso(a.getDecidedAt()));
            m.put("applicationId", a.getApplicationId());
            String production = merchants.findById(a.getMerchantId()).map(p -> p.statusFor(Environment.LIVE).name()).orElse(null);
            m.put("productionStatus", production);
            out.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantNumbers", out);
        m.put("count", out.size());
        return m;
    }

    private List<OnboardingApplication> mine(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) return List.of();
        return applications.findByLinkedWalletAccountOrderByCreatedAtDesc(ownerEmail.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> summary(OnboardingApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", a.getApplicationId());
        m.put("businessName", a.getForm().getRegisteredName());
        m.put("country", a.getForm().getCountry());
        m.put("status", a.getStatus().name());
        m.put("progress", OwnerProgress.of(a.getStatus()));
        m.put("appliedOnBehalf", a.getForm().isOnBehalf());
        m.put("merchantNumber", a.getStatus() == OnboardingStatus.APPROVED ? a.getMerchantNumber() : null);
        m.put("submittedAt", Views.iso(a.getSubmittedAt()));
        m.put("decidedAt", Views.iso(a.getDecidedAt()));
        m.put("updatedAt", Views.iso(a.getUpdatedAt()));
        return m;
    }

    private static Map<String, Object> applicant(BusinessForm f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.getContactName());
        m.put("onBehalf", f.isOnBehalf());
        m.put("role", f.getApplicantRole());
        return m;
    }

    /** The business details, without ID numbers, and with the tax pin cut down to its last characters. */
    static Map<String, Object> form(BusinessForm f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessType", f.getBusinessType() == null ? null : f.getBusinessType().name());
        m.put("registeredName", f.getRegisteredName());
        m.put("tradingName", f.getTradingName());
        m.put("registrationNumber", f.getRegistrationNumber());
        m.put("taxPin", mask(f.getTaxPin()));
        m.put("country", f.getCountry());
        m.put("city", f.getCity());
        m.put("industry", f.getIndustry());
        m.put("website", f.getWebsite());
        m.put("businessDescription", f.getBusinessDescription());
        List<Map<String, Object>> directors = new ArrayList<>();
        for (Director d : f.getDirectors() == null ? List.<Director>of() : f.getDirectors()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("fullName", d.getFullName());
            dm.put("role", d.getRole());
            dm.put("ownershipPercent", d.getOwnershipPercent());
            directors.add(dm);
        }
        m.put("directors", directors);
        return m;
    }

    private static Map<String, Object> document(OnboardingDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.getType() == null ? null : d.getType().name());
        m.put("fileName", d.getFileName());
        m.put("verdict", d.getVerdict().name());
        m.put("uploadedAt", Views.iso(d.getUploadedAt()));
        return m;
    }

    /** All but the last 3 characters hidden. Short values are hidden entirely. */
    static String mask(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() <= 4) return "***";
        return "*".repeat(t.length() - 3) + t.substring(t.length() - 3);
    }
}

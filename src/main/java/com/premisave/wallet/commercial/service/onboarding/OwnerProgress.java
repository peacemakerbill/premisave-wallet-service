package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.enums.onboarding.OnboardingStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The steps of an application in plain words, for the wallet owner who follows it. No storage, so it is tested alone. */
public final class OwnerProgress {

    private OwnerProgress() {}

    private static final String[][] STEPS = {
            {"FILL_IN", "The applicant fills in the application"},
            {"SUBMITTED", "Sent to Premisave"},
            {"IN_REVIEW", "Being reviewed"},
            {"DECISION", "Decision"}};

    /** Index of the step an application is at: 0 filling in, 1 waiting for a reviewer, 2 in review, 3 decided. */
    static int stage(OnboardingStatus status) {
        return switch (status) {
            case DRAFT, RETURNED -> 0;
            case SUBMITTED -> 1;
            case IN_REVIEW -> 2;
            case APPROVED, REJECTED -> 3;
        };
    }

    public static Map<String, Object> of(OnboardingStatus status) {
        int current = stage(status);
        boolean decided = status.finished();
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < STEPS.length; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", STEPS[i][0]);
            s.put("label", STEPS[i][1]);
            String state;
            if (i < current || (decided && i == current && i != 3)) state = "DONE";
            else if (i == current) state = decided ? status.name() : "CURRENT";
            else state = "PENDING";
            s.put("state", state);
            steps.add(s);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("steps", steps);
        m.put("currentStep", STEPS[current][0]);
        m.put("summary", summary(status));
        return m;
    }

    static String summary(OnboardingStatus status) {
        return switch (status) {
            case DRAFT -> "The applicant is still filling in the application.";
            case RETURNED -> "Premisave sent the application back with items to correct. The applicant has to fix them and send it again.";
            case SUBMITTED -> "The application was sent to Premisave and is waiting for a reviewer.";
            case IN_REVIEW -> "A member of the Premisave team is reviewing the application.";
            case APPROVED -> "The application was approved. The merchant number is available.";
            case REJECTED -> "The application was not approved.";
        };
    }
}

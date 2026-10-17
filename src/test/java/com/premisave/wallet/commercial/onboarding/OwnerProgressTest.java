package com.premisave.wallet.commercial.onboarding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four steps the wallet owner sees, for every status. */
class OwnerProgressTest {

    @SuppressWarnings("unchecked")
    private static List<String> states(OnboardingStatus s) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) OwnerProgress.of(s).get("steps");
        return steps.stream().map(m -> (String) m.get("state")).toList();
    }

    @Test
    void aDraftOrReturnedApplicationIsStillBeingFilledIn() {
        assertEquals(List.of("CURRENT", "PENDING", "PENDING", "PENDING"), states(OnboardingStatus.DRAFT));
        assertEquals(List.of("CURRENT", "PENDING", "PENDING", "PENDING"), states(OnboardingStatus.RETURNED));
        assertEquals("FILL_IN", OwnerProgress.of(OnboardingStatus.RETURNED).get("currentStep"));
    }

    @Test
    void submittedAndInReviewMoveTheCursorOn() {
        assertEquals(List.of("DONE", "CURRENT", "PENDING", "PENDING"), states(OnboardingStatus.SUBMITTED));
        assertEquals(List.of("DONE", "DONE", "CURRENT", "PENDING"), states(OnboardingStatus.IN_REVIEW));
    }

    @Test
    void aDecisionClosesTheEarlierStepsAndNamesTheOutcome() {
        assertEquals(List.of("DONE", "DONE", "DONE", "APPROVED"), states(OnboardingStatus.APPROVED));
        assertEquals(List.of("DONE", "DONE", "DONE", "REJECTED"), states(OnboardingStatus.REJECTED));
        assertEquals("DECISION", OwnerProgress.of(OnboardingStatus.APPROVED).get("currentStep"));
    }

    @Test
    void everyStatusHasAPlainSentence() {
        for (OnboardingStatus s : OnboardingStatus.values()) {
            String text = (String) OwnerProgress.of(s).get("summary");
            assertTrue(text != null && text.endsWith("."), s + " needs a sentence");
        }
    }
}

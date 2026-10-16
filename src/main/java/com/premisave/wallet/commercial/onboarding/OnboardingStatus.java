package com.premisave.wallet.commercial.onboarding;

/** Where an application stands. */
public enum OnboardingStatus {
    /** Being filled in by the applicant. */
    DRAFT,
    /** Sent to Premisave, waiting for a reviewer. */
    SUBMITTED,
    /** A reviewer has picked it up. */
    IN_REVIEW,
    /** Sent back to the applicant with things to correct. */
    RETURNED,
    /** Accepted. Production is switched on for the merchant. */
    APPROVED,
    /** Declined. Only an administrator can reopen it. */
    REJECTED;

    /** The applicant may change the form and documents only in these states. */
    public boolean editable() {
        return this == DRAFT || this == RETURNED;
    }

    public boolean finished() {
        return this == APPROVED || this == REJECTED;
    }
}

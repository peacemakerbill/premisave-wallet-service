package com.premisave.wallet.commercial.dto.billing;

/** billNow starts a new billing period immediately and charges it in full. */
public record ResumeSubscriptionRequest(Boolean billNow) {
}

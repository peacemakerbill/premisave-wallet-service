package com.premisave.wallet.commercial.dto.billing;

import jakarta.validation.constraints.Size;

/** paymentMethodId retries on a different method; without it the subscription's method is used. */
public record RetryInvoiceRequest(@Size(max = 40) String paymentMethodId) {
}

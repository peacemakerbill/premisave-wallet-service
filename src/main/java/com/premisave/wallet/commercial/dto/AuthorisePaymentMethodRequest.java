package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.Size;

/** Sandbox only. outcome is SUCCESS (the default) or FAILED. */
public record AuthorisePaymentMethodRequest(@Size(max = 20) String outcome) {
}

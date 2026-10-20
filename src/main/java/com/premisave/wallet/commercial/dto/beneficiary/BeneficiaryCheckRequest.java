package com.premisave.wallet.commercial.dto.beneficiary;

import jakarta.validation.constraints.Size;

/**
 * The beneficiaryCheck part of a payout (section 6.1). required defaults to the merchant's setting.
 * Either expectedName (a fresh check, reusing a cached lookup when there is one) or checkId (an earlier
 * check of the same destination) is needed for a required check.
 */
public record BeneficiaryCheckRequest(
        Boolean required,
        @Size(max = 140) String expectedName,
        @Size(max = 40) String checkId) {
}

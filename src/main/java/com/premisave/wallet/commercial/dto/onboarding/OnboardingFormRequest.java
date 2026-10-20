package com.premisave.wallet.commercial.dto.onboarding;

import com.premisave.wallet.commercial.enums.onboarding.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The application form. Every field is optional here so the applicant can save a draft part way; fields that
 * are left out keep their current value, and directors, when sent, replace the whole list. Completeness is
 * checked when the application is submitted.
 */
public record OnboardingFormRequest(
        BusinessType businessType,
        @Size(max = 160) String registeredName,
        @Size(max = 160) String tradingName,
        @Size(max = 60) String registrationNumber,
        @Size(max = 30) String taxPin,
        @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country,
        @Size(max = 80) String city,
        @Size(max = 300) String physicalAddress,
        @Size(max = 200) String website,
        @Size(max = 120) String industry,
        @Size(max = 2000) String businessDescription,
        @Min(0) @Max(1_000_000_000) Long expectedMonthlyVolumeUsd,
        @Size(max = 120) String contactName,
        @Email @Size(max = 160) String contactEmail,
        @Size(max = 20) String contactPhone,
        List<@Valid DirectorDto> directors,
        @Email @Size(max = 160) String walletAccountNumber,
        Boolean onBehalf,
        @Size(max = 80) String applicantRole) {

    /** The form as it was before the wallet link and on behalf fields, which leave those unchanged. */
    public OnboardingFormRequest(BusinessType businessType, String registeredName, String tradingName,
                                 String registrationNumber, String taxPin, String country, String city,
                                 String physicalAddress, String website, String industry, String businessDescription,
                                 Long expectedMonthlyVolumeUsd, String contactName, String contactEmail,
                                 String contactPhone, List<DirectorDto> directors) {
        this(businessType, registeredName, tradingName, registrationNumber, taxPin, country, city, physicalAddress,
                website, industry, businessDescription, expectedMonthlyVolumeUsd, contactName, contactEmail, contactPhone,
                directors, null, null, null);
    }
}
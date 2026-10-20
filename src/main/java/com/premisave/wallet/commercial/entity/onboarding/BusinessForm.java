package com.premisave.wallet.commercial.entity.onboarding;

import com.premisave.wallet.commercial.enums.onboarding.BusinessType;

import java.util.ArrayList;
import java.util.List;

/** The business details the applicant fills in. */
public class BusinessForm {

    private BusinessType businessType;
    private String registeredName;
    private String tradingName;
    private String registrationNumber;
    private String taxPin;
    /** Two letter country code. */
    private String country;
    private String city;
    private String physicalAddress;
    private String website;
    private String industry;
    private String businessDescription;
    /** Whole US dollars. */
    private Long expectedMonthlyVolumeUsd;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    /** The Premisave wallet account (its owner's email) this business is linked to. Its owner can follow the application. */
    private String walletAccountNumber;
    /** True when the applicant is applying for a business that is not their own, for example an agent or accountant. */
    private boolean onBehalf;
    /** What the applicant is to the business when applying on its behalf, for example Accountant. */
    private String applicantRole;
    private List<Director> directors = new ArrayList<>();

    public BusinessForm() {}

    public BusinessType getBusinessType() { return businessType; }
    public void setBusinessType(BusinessType v) { this.businessType = v; }
    public String getRegisteredName() { return registeredName; }
    public void setRegisteredName(String v) { this.registeredName = v; }
    public String getTradingName() { return tradingName; }
    public void setTradingName(String v) { this.tradingName = v; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String v) { this.registrationNumber = v; }
    public String getTaxPin() { return taxPin; }
    public void setTaxPin(String v) { this.taxPin = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getPhysicalAddress() { return physicalAddress; }
    public void setPhysicalAddress(String v) { this.physicalAddress = v; }
    public String getWebsite() { return website; }
    public void setWebsite(String v) { this.website = v; }
    public String getIndustry() { return industry; }
    public void setIndustry(String v) { this.industry = v; }
    public String getBusinessDescription() { return businessDescription; }
    public void setBusinessDescription(String v) { this.businessDescription = v; }
    public Long getExpectedMonthlyVolumeUsd() { return expectedMonthlyVolumeUsd; }
    public void setExpectedMonthlyVolumeUsd(Long v) { this.expectedMonthlyVolumeUsd = v; }
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { this.contactEmail = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { this.contactPhone = v; }
    public String getWalletAccountNumber() { return walletAccountNumber; }
    public void setWalletAccountNumber(String v) { this.walletAccountNumber = v; }
    public boolean isOnBehalf() { return onBehalf; }
    public void setOnBehalf(boolean v) { this.onBehalf = v; }
    public String getApplicantRole() { return applicantRole; }
    public void setApplicantRole(String v) { this.applicantRole = v; }
    public List<Director> getDirectors() { return directors; }
    public void setDirectors(List<Director> v) { this.directors = v; }
}

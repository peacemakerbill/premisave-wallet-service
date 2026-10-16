package com.premisave.wallet.commercial.onboarding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** The onboarding application attached to a merchant. Every change to it is also written to onboarding_events. */
@Document(collection = "merchant_applications")
public class OnboardingApplication {

    @Id
    private String applicationId;
    @Indexed(unique = true)
    private String merchantId;
    private String merchantNumber;
    @Indexed
    private OnboardingStatus status = OnboardingStatus.DRAFT;
    private BusinessForm form = new BusinessForm();
    private List<OnboardingDocument> documents = new ArrayList<>();
    private List<ReturnItem> returnItems = new ArrayList<>();
    /** How many times the applicant has submitted. */
    private int submissions;
    /** Who is reviewing it now. */
    private String reviewerId;
    private String reviewerName;
    private String reviewerRole;
    private String decisionBy;
    private String decisionRole;
    /** The message the applicant sees with the decision. */
    private String decisionMessage;
    private Instant submittedAt;
    private Instant decidedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public OnboardingApplication() {}

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String v) { this.applicationId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getMerchantNumber() { return merchantNumber; }
    public void setMerchantNumber(String v) { this.merchantNumber = v; }
    public OnboardingStatus getStatus() { return status; }
    public void setStatus(OnboardingStatus v) { this.status = v; }
    public BusinessForm getForm() { return form; }
    public void setForm(BusinessForm v) { this.form = v; }
    public List<OnboardingDocument> getDocuments() { return documents; }
    public void setDocuments(List<OnboardingDocument> v) { this.documents = v; }
    public List<ReturnItem> getReturnItems() { return returnItems; }
    public void setReturnItems(List<ReturnItem> v) { this.returnItems = v; }
    public int getSubmissions() { return submissions; }
    public void setSubmissions(int v) { this.submissions = v; }
    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String v) { this.reviewerId = v; }
    public String getReviewerName() { return reviewerName; }
    public void setReviewerName(String v) { this.reviewerName = v; }
    public String getReviewerRole() { return reviewerRole; }
    public void setReviewerRole(String v) { this.reviewerRole = v; }
    public String getDecisionBy() { return decisionBy; }
    public void setDecisionBy(String v) { this.decisionBy = v; }
    public String getDecisionRole() { return decisionRole; }
    public void setDecisionRole(String v) { this.decisionRole = v; }
    public String getDecisionMessage() { return decisionMessage; }
    public void setDecisionMessage(String v) { this.decisionMessage = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}

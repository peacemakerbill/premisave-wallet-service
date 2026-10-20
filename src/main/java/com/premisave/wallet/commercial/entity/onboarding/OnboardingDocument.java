package com.premisave.wallet.commercial.entity.onboarding;

import com.premisave.wallet.commercial.enums.onboarding.DocumentType;
import com.premisave.wallet.commercial.enums.onboarding.DocumentVerdict;

import java.time.Instant;

/** A file the applicant uploaded. The bytes live in the document folder, only the reference is kept here. */
public class OnboardingDocument {

    private String documentId;
    private DocumentType type;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    /** Hex digest of the stored bytes. */
    private String sha256;
    /** Path of the file inside the document folder. */
    private String storageFileId;
    private DocumentVerdict verdict = DocumentVerdict.PENDING;
    private String verdictNote;
    private String verdictBy;
    private Instant verdictAt;
    private String uploadedBy;
    private Instant uploadedAt;

    public OnboardingDocument() {}

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String v) { this.documentId = v; }
    public DocumentType getType() { return type; }
    public void setType(DocumentType v) { this.type = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getStorageFileId() { return storageFileId; }
    public void setStorageFileId(String v) { this.storageFileId = v; }
    public DocumentVerdict getVerdict() { return verdict; }
    public void setVerdict(DocumentVerdict v) { this.verdict = v; }
    public String getVerdictNote() { return verdictNote; }
    public void setVerdictNote(String v) { this.verdictNote = v; }
    public String getVerdictBy() { return verdictBy; }
    public void setVerdictBy(String v) { this.verdictBy = v; }
    public Instant getVerdictAt() { return verdictAt; }
    public void setVerdictAt(Instant v) { this.verdictAt = v; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String v) { this.uploadedBy = v; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant v) { this.uploadedAt = v; }
}
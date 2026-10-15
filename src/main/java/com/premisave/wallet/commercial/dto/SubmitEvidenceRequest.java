package com.premisave.wallet.commercial.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** One piece of evidence. submit false saves a draft; true sends it, with any drafts, to the provider. */
public record SubmitEvidenceRequest(
        @NotBlank @Size(max = 40) String type,
        @Size(max = 5000) String text,
        @Valid @Size(max = 10) List<Attachment> attachments,
        Boolean submit) {

    public record Attachment(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 2000) String url,
            @NotBlank @Size(max = 100) String mimeType) {
    }
}

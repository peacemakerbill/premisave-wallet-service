package com.premisave.wallet.commercial.dto.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A document sent as base64 inside the JSON body. The file itself may be a PDF, JPEG or PNG. The request is
 * allowed to be larger than other commercial requests, up to 12 MB, which fits a file of about 8 MB.
 */
public record UploadDocumentRequest(
        @NotBlank String type,
        @NotBlank @Size(max = 200) String fileName,
        @NotBlank @Size(max = 100) String contentType,
        @NotBlank String contentBase64) {
}

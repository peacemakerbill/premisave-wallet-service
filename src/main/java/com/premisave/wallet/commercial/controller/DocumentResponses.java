package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.onboarding.OnboardingService;
import org.springframework.http.ResponseEntity;

/** Sends a stored document back as a download. */
final class DocumentResponses {

    private DocumentResponses() {}

    static ResponseEntity<byte[]> of(OnboardingService.DocumentFile file) {
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }
}

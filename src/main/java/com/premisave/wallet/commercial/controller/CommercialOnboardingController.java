package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.onboarding.Actor;
import com.premisave.wallet.commercial.onboarding.OnboardingFormRequest;
import com.premisave.wallet.commercial.onboarding.OnboardingService;
import com.premisave.wallet.commercial.onboarding.SubmitApplicationRequest;
import com.premisave.wallet.commercial.onboarding.UploadDocumentRequest;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * The merchant application form, called by the developer service for the signed in merchant owner. X-Actor-Id
 * is optional and is only recorded in the timeline.
 */
@RestController("commercialOnboardingController")
@RequestMapping("/internal/commercial/v1/merchants/{merchantId}/application")
public class CommercialOnboardingController {

    private final OnboardingService onboarding;
    private final CommercialOnboardingProperties props;

    public CommercialOnboardingController(OnboardingService onboarding, CommercialOnboardingProperties props) {
        this.onboarding = onboarding;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(@PathVariable String merchantId) {
        OnboardingService.Started started = onboarding.start(merchantId);
        return started.created() ? Responses.created("Application started", started.application())
                : Responses.ok("Application already exists", started.application());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String merchantId) {
        return Responses.ok("Application retrieved", onboarding.get(merchantId));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> save(
            @PathVariable String merchantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody OnboardingFormRequest req) {
        return Responses.ok("Application saved", onboarding.saveForm(merchantId, req, Actor.applicant(actorId)));
    }

    @PostMapping("/documents")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @PathVariable String merchantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody UploadDocumentRequest req) {
        if (req.contentBase64().length() > props.maxDocumentBytes() * 4 / 3 + 16) {
            throw CommercialException.validation("contentBase64",
                    "is larger than " + (props.maxDocumentBytes() / (1024 * 1024)) + " MB once decoded");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(req.contentBase64());
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation("contentBase64", "is not valid base64");
        }
        return Responses.created("Document uploaded", onboarding.uploadDocument(merchantId, req.type(), req.fileName(),
                req.contentType(), bytes, Actor.applicant(actorId)));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(
            @PathVariable String merchantId, @PathVariable String documentId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
        return Responses.ok("Document removed", onboarding.removeDocument(merchantId, documentId, Actor.applicant(actorId)));
    }

    @GetMapping("/documents/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String merchantId, @PathVariable String documentId) {
        return DocumentResponses.of(onboarding.download(merchantId, documentId));
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @PathVariable String merchantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody(required = false) SubmitApplicationRequest req) {
        return Responses.ok("Application submitted", onboarding.submit(merchantId, req, Actor.applicant(actorId)));
    }

    @GetMapping("/timeline")
    public ResponseEntity<ApiResponse<Map<String, Object>>> timeline(@PathVariable String merchantId) {
        return Responses.ok("Timeline retrieved", Map.of("events", onboarding.timeline(merchantId)));
    }
}

package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.onboarding.DecisionRequest;
import com.premisave.wallet.commercial.dto.onboarding.DocumentVerdictRequest;
import com.premisave.wallet.commercial.dto.onboarding.NoteRequest;
import com.premisave.wallet.commercial.dto.onboarding.OnboardingViews;
import com.premisave.wallet.commercial.dto.onboarding.ReturnApplicationRequest;
import com.premisave.wallet.commercial.entity.onboarding.Actor;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.service.onboarding.OnboardingReviewService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * Review queue for Premisave staff, called by the developer service for a signed in staff member. Every
 * step needs X-Actor-Id and X-Actor-Role; X-Actor-Name is optional. The role decides what the actor may do
 * (see commercial.onboarding.roles).
 */
@RestController("commercialOnboardingStaffController")
@RequestMapping("/internal/commercial/v1/onboarding/applications")
public class CommercialOnboardingStaffController {

    private final OnboardingReviewService review;

    public CommercialOnboardingStaffController(OnboardingReviewService review) {
        this.review = review;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantNumber,
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        OnboardingStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = OnboardingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("status", "must be one of " + java.util.Arrays.toString(OnboardingStatus.values()));
            }
        }
        return Responses.page("Applications retrieved",
                review.list(parsed, merchantNumber, applicationId, PageViews.pageable(page, size, null)), OnboardingViews::summary);
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String applicationId) {
        return Responses.ok("Application retrieved", review.get(applicationId));
    }

    @GetMapping("/{applicationId}/timeline")
    public ResponseEntity<ApiResponse<Map<String, Object>>> timeline(@PathVariable String applicationId) {
        return Responses.ok("Timeline retrieved", Map.of("events", review.timeline(applicationId)));
    }

    @GetMapping("/{applicationId}/documents/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String applicationId, @PathVariable String documentId) {
        return DocumentResponses.of(review.download(applicationId, documentId));
    }

    @PostMapping("/{applicationId}/start-review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startReview(
            @PathVariable String applicationId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name) {
        return Responses.ok("Review started", review.startReview(applicationId, staff(id, name, role)));
    }

    @PostMapping("/{applicationId}/notes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> note(
            @PathVariable String applicationId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name,
            @Valid @RequestBody NoteRequest req) {
        return Responses.created("Note added", review.addNote(applicationId, req.note(), staff(id, name, role)));
    }

    @PostMapping("/{applicationId}/documents/{documentId}/verdict")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verdict(
            @PathVariable String applicationId, @PathVariable String documentId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name,
            @Valid @RequestBody DocumentVerdictRequest req) {
        return Responses.ok("Document marked", review.documentVerdict(applicationId, documentId, req, staff(id, name, role)));
    }

    @PostMapping("/{applicationId}/return")
    public ResponseEntity<ApiResponse<Map<String, Object>>> returnForCorrection(
            @PathVariable String applicationId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name,
            @Valid @RequestBody ReturnApplicationRequest req) {
        return Responses.ok("Application returned for correction", review.returnForCorrection(applicationId, req, staff(id, name, role)));
    }

    @PostMapping("/{applicationId}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @PathVariable String applicationId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name,
            @Valid @RequestBody(required = false) DecisionRequest req) {
        return Responses.ok("Application approved", review.approve(applicationId, req, staff(id, name, role)));
    }

    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(
            @PathVariable String applicationId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name,
            @Valid @RequestBody DecisionRequest req) {
        return Responses.ok("Application rejected", review.reject(applicationId, req, staff(id, name, role)));
    }

    @PostMapping("/{applicationId}/reopen")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopen(
            @PathVariable String applicationId,
            @RequestHeader(value = "X-Actor-Id", required = false) String id,
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestHeader(value = "X-Actor-Name", required = false) String name,
            @Valid @RequestBody DecisionRequest req) {
        return Responses.ok("Application reopened", review.reopen(applicationId, req, staff(id, name, role)));
    }

    static Actor staff(String id, String name, String role) {
        if (id == null || id.isBlank() || role == null || role.isBlank()) {
            throw new CommercialException(ErrorCode.UNAUTHENTICATED, "X-Actor-Id and X-Actor-Role are required for review steps");
        }
        return Actor.staff(id.trim(), name == null || name.isBlank() ? null : name.trim(), role.trim());
    }
}
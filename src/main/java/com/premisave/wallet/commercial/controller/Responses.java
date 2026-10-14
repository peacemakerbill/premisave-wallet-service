package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.dto.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Function;

/** Small helpers so controllers stay one line per endpoint. */
final class Responses {

    private Responses() {}

    static ResponseEntity<ApiResponse<Map<String, Object>>> ok(String message, Map<String, Object> data) {
        return ResponseEntity.ok(ApiResponse.success(message, data));
    }

    static ResponseEntity<ApiResponse<Map<String, Object>>> created(String message, Map<String, Object> data) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, data));
    }

    static ResponseEntity<ApiResponse<Map<String, Object>>> accepted(String message, Map<String, Object> data) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(message, data));
    }

    static <T> ResponseEntity<ApiResponse<Map<String, Object>>> page(String message, Page<T> page,
                                                                    Function<T, Map<String, Object>> view) {
        return ResponseEntity.ok(ApiResponse.success(message, PageViews.of(page, view)));
    }

    /** 201 on first use of an Idempotency-Key, the same body again with Idempotent-Replayed on a repeat. */
    static ResponseEntity<ApiResponse<Map<String, Object>>> idempotent(String message, boolean replayed,
                                                                       Map<String, Object> data, HttpStatus firstStatus) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(firstStatus);
        if (replayed) b.header("Idempotent-Replayed", "true");
        return b.body(ApiResponse.success(message, data));
    }
}

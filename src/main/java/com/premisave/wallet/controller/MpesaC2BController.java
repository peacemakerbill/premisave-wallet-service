package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.service.MpesaC2BService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-triggered C2B setup. The actual Safaricom-called callback endpoints
 * (validation/confirmation) live in MpesaC2BCallbackController instead,
 * under paths that deliberately avoid the substring "mpesa" — Safaricom's
 * sandbox rejects any registered CallBackURL/ValidationURL/ConfirmationURL
 * containing that word with "400.002.02 Bad Request - Invalid CallBackURL",
 * even when the URL is otherwise valid and publicly reachable.
 *
 * This controller's own path (/payments/mpesa/c2b/register-urls) is fine to
 * keep as-is — it's never called BY Safaricom, only manually by an admin/ops
 * user, so the "mpesa in URL" restriction (which only applies to URLs you
 * register WITH Safaricom) doesn't apply to it.
 */
@Slf4j
@RestController
@RequestMapping("/payments/mpesa/c2b")
@RequiredArgsConstructor
public class MpesaC2BController {

    private final MpesaC2BService c2bService;

    /**
     * One-time registration — call this endpoint manually (or on startup)
     * to register your validation + confirmation URLs with Safaricom.
     * Restricted to ADMIN/OPERATIONS via SecurityConfig.
     */
    @PostMapping("/register-urls")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerUrls() {
        Map<String, Object> result = c2bService.registerUrls();
        return ResponseEntity.ok(ApiResponse.success("C2B URLs registered", result));
    }
}
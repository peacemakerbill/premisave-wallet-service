package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.MpesaCallbackRequest;
import com.premisave.wallet.service.DepositService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Slf4j
@RestController
@RequestMapping("/payments/mpesa")
@RequiredArgsConstructor
public class MpesaCallbackController {

    private final DepositService depositService;

    /**
     * Receives M-Pesa C2B/STK push callback from Safaricom Daraja.
     * This endpoint must be publicly accessible (no JWT required) —
     * secured via IP allowlist at the gateway/firewall level instead.
     */
    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Void>> handleCallback(@RequestBody MpesaCallbackRequest callback) {
        log.info("M-Pesa callback received: transID={} amount={} msisdn={}",
                callback.getTransID(), callback.getTransAmount(), callback.getMSISDN());

        try {
            BigDecimal amount = new BigDecimal(callback.getTransAmount());
            String accountNumber = callback.getBillRefNumber(); // email used as account ref
            String description = "M-Pesa deposit from " + callback.getFirstName()
                    + " (" + callback.getMSISDN() + ")";

            depositService.creditWalletFromCallback(accountNumber, amount,
                    callback.getTransID(), description);

            return ResponseEntity.ok(ApiResponse.success("Callback processed"));
        } catch (Exception e) {
            log.error("Failed to process M-Pesa callback: transID={}", callback.getTransID(), e);
            // Always return 200 to Safaricom — they retry on non-200
            return ResponseEntity.ok(ApiResponse.error("Callback processing failed: " + e.getMessage()));
        }
    }
}
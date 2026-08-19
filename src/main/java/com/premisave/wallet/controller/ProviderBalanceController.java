package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.ProviderBalanceResponse;
import com.premisave.wallet.service.ProviderBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only visibility into Premisave's OWN balance held with each
 * integrated payment gateway (M-Pesa, Stripe, PayPal, Flutterwave,
 * NOWPayments) — not any customer's wallet, the platform's own account
 * with each provider. Lets an admin check all five without logging into
 * five separate dashboards.
 *
 * Same security posture as every other admin controller tonight —
 * ADMIN/FINANCE/OPERATIONS only.
 *
 * M-Pesa is genuinely different from the other four here, not just in
 * this controller's plumbing but in Safaricom's own API design — it has
 * no synchronous balance check at all. Every M-Pesa response from this
 * controller has status=PENDING_ASYNC (or ERROR on submission failure),
 * never AVAILABLE with real numbers — see ProviderBalanceService.
 * getMpesaBalance's javadoc for the full reasoning. Included here anyway
 * for uniformity of the endpoint shape, per explicit request, even
 * though the underlying behavior can't be made uniform — Safaricom's API
 * doesn't support a synchronous balance check no matter how this
 * controller is structured.
 */
@RestController
@RequestMapping("/admin/gateway-balances")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class ProviderBalanceController {

    private final ProviderBalanceService providerBalanceService;

    /** All five providers in one call. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProviderBalanceResponse>>> getAllBalances(Authentication auth) {
        List<ProviderBalanceResponse> balances = providerBalanceService.getAllBalances(auth.getName());
        return ResponseEntity.ok(ApiResponse.success("Gateway balances retrieved", balances));
    }

    @GetMapping("/stripe")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getStripeBalance() {
        return ResponseEntity.ok(ApiResponse.success("Stripe balance retrieved", providerBalanceService.getStripeBalance()));
    }

    @GetMapping("/paypal")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getPaypalBalance() {
        return ResponseEntity.ok(ApiResponse.success("PayPal balance retrieved", providerBalanceService.getPaypalBalance()));
    }

    @GetMapping("/flutterwave")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getFlutterwaveBalance() {
        return ResponseEntity.ok(ApiResponse.success("Flutterwave balance retrieved", providerBalanceService.getFlutterwaveBalance()));
    }

    @GetMapping("/nowpayments")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getNowPaymentsBalance() {
        return ResponseEntity.ok(ApiResponse.success("NOWPayments balance retrieved", providerBalanceService.getNowPaymentsBalance()));
    }

    /**
     * Submits an M-Pesa Account Balance query — does NOT return an actual
     * balance in this response. See class javadoc and
     * ProviderBalanceService.getMpesaBalance for why.
     */
    @GetMapping("/mpesa")
    public ResponseEntity<ApiResponse<ProviderBalanceResponse>> getMpesaBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("M-Pesa balance query submitted",
                providerBalanceService.getMpesaBalance(auth.getName())));
    }
}
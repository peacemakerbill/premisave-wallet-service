package com.premisave.wallet.controller;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.TransferService;
import com.premisave.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransferService transferService;
    private final DepositService depositService;

    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(Authentication auth) {
        String email = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", walletService.getWallet(email)));
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<WalletBalanceResponse>> getBalance(Authentication auth) {
        String email = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Balance retrieved", walletService.getBalance(email)));
    }

    /**
     * Creates wallet using userId from JWT claim (no placeholder needed).
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            Authentication auth,
            HttpServletRequest request) {
        String email  = auth.getName();
        String userId = (String) request.getAttribute("userId"); // set by JwtAuthenticationFilter
        if (userId == null) userId = email; // safe fallback if claim absent
        WalletResponse wallet = walletService.createWallet(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Wallet created", wallet));
    }

    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<PaymentResponse>> deposit(
            @Valid @RequestBody DepositRequest depositRequest,
            Authentication auth,
            HttpServletRequest request) {
        String email  = auth.getName();
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = email;
        PaymentResponse response = depositService.initiateDeposit(userId, email, depositRequest);
        return ResponseEntity.ok(ApiResponse.success("Deposit initiated", response));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody TransferRequest transferRequest,
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String email  = auth.getName();
        if (userId == null) userId = email;
        PaymentResponse response = transferService.transfer(userId, transferRequest);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    /**
     * Get Wallet Statement with date range and summary
     */
    @PostMapping("/statement")
    public ResponseEntity<ApiResponse<WalletStatementResponse>> getStatement(
            @Valid @RequestBody WalletStatementRequest request,
            Authentication auth) {
        String email = auth.getName();
        WalletStatementResponse statement = walletService.getStatement(email, request);
        return ResponseEntity.ok(ApiResponse.success("Wallet statement retrieved successfully", statement));
    }

    @PutMapping("/freeze")
    public ResponseEntity<ApiResponse<WalletResponse>> freeze(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen", walletService.freezeWallet(userId)));
    }

    @PutMapping("/unfreeze")
    public ResponseEntity<ApiResponse<WalletResponse>> unfreeze(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen", walletService.unfreezeWallet(userId)));
    }

    /**
     * Sets/updates the PayPal email PayPal disbursements are sent to.
     * Resolved authoritatively from here by DisbursementService — never
     * taken from a disbursement request itself, same reasoning as M-Pesa's
     * verified-phone-number pattern (eliminates typo/mistargeted-payout risk).
     * PUT /wallet/paypal-email
     */
    @PutMapping("/paypal-email")
    public ResponseEntity<ApiResponse<WalletResponse>> updatePaypalEmail(
            @Valid @RequestBody UpdatePaypalEmailRequest updateRequest,
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        WalletResponse response = walletService.updatePaypalEmail(userId, updateRequest.getPaypalEmail());
        return ResponseEntity.ok(ApiResponse.success("PayPal email updated", response));
    }

    /**
     * Unlinks the wallet's saved PayPal account (vault_id/customer_id/
     * connected email) so future deposits stop auto-reusing it. Not
     * blocked while frozen — see WalletService.disconnectPaypalAccount.
     * Does not revoke the token on PayPal's side, only unlinks it from
     * Premisave's records.
     * PUT /wallet/paypal/disconnect
     */
    @PutMapping("/paypal/disconnect")
    public ResponseEntity<ApiResponse<WalletResponse>> disconnectPaypal(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        WalletResponse response = walletService.disconnectPaypalAccount(userId);
        return ResponseEntity.ok(ApiResponse.success(
                "PayPal account unlinked. Note: this does not revoke access on PayPal's side.", response));
    }
    
    /**
     * Starts a standalone PayPal account link (no payment) — mirrors
     * createStripeSetupIntent. Rejects with 409-style error if a PayPal
     * account is already linked.
     * POST /wallet/paypal/link
     */
    @PostMapping("/paypal/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> createPaypalLink(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        Map<String, String> result = depositService.createPaypalLinkToken(userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal link initiated", result));
    }

    /**
     * Called by the frontend after the user approves the PayPal setup
     * token (returns from PayPal's approval redirect). Saves the linked
     * account onto the wallet.
     * POST /wallet/paypal/link/confirm
     */
    @PostMapping("/paypal/link/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPaypalLink(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {
        String setupTokenId = body.get("setupTokenId");
        if (setupTokenId == null || setupTokenId.isBlank()) {
            throw new IllegalArgumentException("setupTokenId is required");
        }
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        depositService.confirmPaypalLink(setupTokenId, userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal account linked"));
    }

    /**
     * Read-only — returns the wallet's linked PayPal account status for
     * the frontend to display.
     * GET /wallet/paypal
     */
    @GetMapping("/paypal")
    public ResponseEntity<ApiResponse<PaypalAccountResponse>> getPaypalAccount(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("PayPal account status retrieved",
                walletService.getPaypalAccount(userId)));
    }

    /**
     * Sets/updates the M-Pesa phone number used for quick deposits (STK
     * push — no need to type a number every time) and disbursements.
     * Resolved authoritatively from here by DepositService/
     * DisbursementService — never taken from a deposit/disbursement
     * request itself — same reasoning as the PayPal email pattern above
     * (eliminates typo/mistargeted-payout risk).
     * PUT /wallet/mpesa-phone
     */
    @PutMapping("/mpesa-phone")
    public ResponseEntity<ApiResponse<WalletResponse>> updateMpesaPhone(
            @Valid @RequestBody UpdateMpesaPhoneRequest updateRequest,
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        WalletResponse response = walletService.updateMpesaPhoneNumber(userId, updateRequest.getMpesaPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("M-Pesa phone number updated", response));
    }

    /**
     * Sets/updates the phone number the user's Pochi la Biashara business
     * account is registered under. Used by DisbursementService for B2Pochi
     * withdrawals (POST /disbursements/b2pochi) — resolved authoritatively
     * from here, never taken from the withdrawal request itself, same
     * reasoning as PUT /wallet/mpesa-phone above. Falls back to
     * mpesaPhoneNumber if never set.
     * PUT /wallet/pochi-phone
     */
    @PutMapping("/pochi-phone")
    public ResponseEntity<ApiResponse<WalletResponse>> updatePochiPhone(
            @Valid @RequestBody UpdatePochiPhoneRequest updateRequest,
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        WalletResponse response = walletService.updatePochiPhoneNumber(userId, updateRequest.getPochiPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("Pochi phone number updated", response));
    }

    /**
     * Starts a Stripe SetupIntent so the frontend can save a card (via
     * Stripe.js/Elements) without making a payment — e.g. a "manage payment
     * method" settings screen. Lazily creates a Stripe Customer for this
     * wallet if one doesn't exist yet. Follow up with
     * confirmStripeSetupIntent once Stripe.js confirms client-side.
     * POST /wallet/stripe/setup-intent
     */
    @PostMapping("/stripe/setup-intent")
    public ResponseEntity<ApiResponse<Map<String, String>>> createStripeSetupIntent(
            Authentication auth,
            HttpServletRequest request) {
        String email = auth.getName();
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = email;
        Map<String, String> result = depositService.createStripeSetupIntent(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Setup intent created", result));
    }

    /**
     * Called by the frontend after Stripe.js confirms the SetupIntent
     * (stripe.confirmCardSetup). Verifies the setup intent belongs to this
     * user's Stripe Customer, then saves the resulting card as the wallet's
     * default payment method for one-click deposit reloads. The Stripe
     * webhook's setup_intent.succeeded handler is a backstop for this same
     * flow (see PaymentCallbackController), so this call is idempotent
     * against it.
     * POST /wallet/stripe/setup-intent/confirm
     */
    @PostMapping("/stripe/setup-intent/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmStripeSetupIntent(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {
        String setupIntentId = body.get("setupIntentId");
        if (setupIntentId == null || setupIntentId.isBlank()) {
            throw new IllegalArgumentException("setupIntentId is required");
        }
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        depositService.confirmStripeSetupIntent(setupIntentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Card saved"));
    }

    /**
     * Called by the frontend immediately after the user approves the PayPal
     * order (PayPal redirects back with ?token={orderId}&PayerID=...). Captures
     * the order and credits the wallet right away, rather than waiting on the
     * webhook — the webhook (see PaymentCallbackController) is a safety-net
     * backstop and is idempotent against this same call.
     * POST /wallet/deposit/paypal/confirm
     */
    @PostMapping("/deposit/paypal/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmPaypalDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String orderId = body.get("orderId");
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        PaymentResponse response = depositService.confirmPaypalDeposit(orderId, auth.getName());
        return ResponseEntity.ok(ApiResponse.success("PayPal deposit processed", response));
    }

    /**
     * Called by the frontend after Stripe.js confirms the PaymentIntent
     * client-side (stripe.confirmCardPayment). Retrieves the PaymentIntent's
     * real status directly from Stripe and credits the wallet immediately,
     * rather than waiting on the webhook — essential for local/sandbox
     * testing where webhook delivery isn't configured (no `stripe listen`
     * forwarding running), and a safety-net backstop in production, same
     * pattern as the PayPal confirm endpoint above. Idempotent against the
     * webhook — whichever arrives first wins, the other is a no-op.
     * POST /wallet/deposit/stripe/confirm
     */
    @PostMapping("/deposit/stripe/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmStripeDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {
        String paymentIntentId = body.get("paymentIntentId");
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("paymentIntentId is required");
        }
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        PaymentResponse response = depositService.confirmStripeDeposit(paymentIntentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Stripe deposit processed", response));
    }

    /**
     * Called by the frontend after the user returns from Flutterwave's
     * hosted checkout redirect (redirect_url carries tx_ref as a query
     * param — the same value returned as PaymentResponse.transactionId is
     * NOT used here; this field is actually the checkout link during
     * initiation, so the frontend must persist the tx_ref it generated /
     * was given at initiation time and echo it back here). Re-verifies the
     * transaction server-side via Flutterwave's API rather than trusting
     * redirect query params directly — see
     * FlutterwaveService.verifyTransactionByReference's javadoc. The
     * charge.completed webhook (see PaymentCallbackController) is a
     * safety-net backstop and is idempotent against this same call.
     * POST /wallet/deposit/flutterwave/confirm
     */
    @PostMapping("/deposit/flutterwave/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmFlutterwaveDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {
        String txRef = body.get("txRef");
        if (txRef == null || txRef.isBlank()) {
            throw new IllegalArgumentException("txRef is required");
        }
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        PaymentResponse response = depositService.confirmFlutterwaveDeposit(txRef, userId);
        return ResponseEntity.ok(ApiResponse.success("Flutterwave deposit processed", response));
    }
}
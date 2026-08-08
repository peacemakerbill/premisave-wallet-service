package com.premisave.wallet.controller;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.FlutterwaveService;
import com.premisave.wallet.service.TransferService;
import com.premisave.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransferService transferService;
    private final DepositService depositService;
    private final FlutterwaveService flutterwaveService;
    private final WalletRepository walletRepository;

    /**
     * Resolves the caller's real userId from the request attribute stashed
     * by JwtAuthenticationFilter (populated from the JWT "userId" claim).
     *
     * IMPORTANT: this is intentionally NOT auth.getName() — that returns
     * UserDetails.username, which UserDetailsServiceImpl sets to the JWT
     * "sub" (email) claim. Wallet.userId is a separate real identifier
     * (distinct from Wallet.accountNumber, which IS the email and comes
     * from auth-service's user profile). Every method below that looks up
     * a wallet by userId must use this helper; methods that look up a
     * wallet by accountNumber/email must keep using auth.getName().
     */
    private String resolveUserId(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException(
                    "Authenticated request is missing a userId claim — token may be malformed or missing 'userId'.");
        }
        return userId;
    }

    // ─── Endpoints keyed by accountNumber (email) ────────────────

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

    @PostMapping("/statement")
    public ResponseEntity<ApiResponse<WalletStatementResponse>> getStatement(
            @Valid @RequestBody WalletStatementRequest request,
            Authentication auth) {
        String email = auth.getName();
        WalletStatementResponse statement = walletService.getStatement(email, request);
        return ResponseEntity.ok(ApiResponse.success("Wallet statement retrieved successfully", statement));
    }

    // ─── Endpoints keyed by real userId — ───────────────────────────────

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        WalletResponse wallet = walletService.createWallet(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Wallet created", wallet));
    }

    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<PaymentResponse>> deposit(
            @Valid @RequestBody DepositRequest depositRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        PaymentResponse response = depositService.initiateDeposit(userId, email, depositRequest);
        return ResponseEntity.ok(ApiResponse.success("Deposit initiated", response));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody TransferRequest transferRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        PaymentResponse response = transferService.transfer(userId, transferRequest);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    @PutMapping("/freeze")
    public ResponseEntity<ApiResponse<WalletResponse>> freeze(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen", walletService.freezeWallet(userId)));
    }

    @PutMapping("/unfreeze")
    public ResponseEntity<ApiResponse<WalletResponse>> unfreeze(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen", walletService.unfreezeWallet(userId)));
    }

    @PutMapping("/paypal-email")
    public ResponseEntity<ApiResponse<WalletResponse>> updatePaypalEmail(
            @Valid @RequestBody UpdatePaypalEmailRequest updateRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.updatePaypalEmail(userId, updateRequest.getPaypalEmail());
        return ResponseEntity.ok(ApiResponse.success("PayPal email updated", response));
    }

    @PutMapping("/paypal/disconnect")
    public ResponseEntity<ApiResponse<WalletResponse>> disconnectPaypal(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.disconnectPaypalAccount(userId);
        return ResponseEntity.ok(ApiResponse.success(
                "PayPal account unlinked. Note: this does not revoke access on PayPal's side.", response));
    }

    @PostMapping("/paypal/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> createPaypalLink(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        Map<String, String> result = depositService.createPaypalLinkToken(userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal link initiated", result));
    }

    @PostMapping("/paypal/link/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPaypalLink(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String setupTokenId = body.get("setupTokenId");
        if (setupTokenId == null || setupTokenId.isBlank()) {
            throw new IllegalArgumentException("setupTokenId is required");
        }
        String userId = resolveUserId(request);
        depositService.confirmPaypalLink(setupTokenId, userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal account linked"));
    }

    @GetMapping("/paypal/account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPaypalAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        var walletOpt = walletRepository.findByUserId(userId);
        Map<String, Object> info = new HashMap<>();

        if (walletOpt.isPresent()) {
            Wallet wallet = walletOpt.get();
            info.put("linked", wallet.getPaypalVaultId() != null);
            info.put("email", wallet.getPaypalConnectedEmail());
            info.put("vaultId", wallet.getPaypalVaultId());
            info.put("customerId", wallet.getPaypalCustomerId());
        } else {
            info.put("linked", false);
        }

        return ResponseEntity.ok(ApiResponse.success("PayPal account status retrieved", info));
    }

    @PutMapping("/mpesa-phone")
    public ResponseEntity<ApiResponse<WalletResponse>> updateMpesaPhone(
            @Valid @RequestBody UpdateMpesaPhoneRequest updateRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.updateMpesaPhoneNumber(userId, updateRequest.getMpesaPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("M-Pesa phone number updated", response));
    }

    @PutMapping("/pochi-phone")
    public ResponseEntity<ApiResponse<WalletResponse>> updatePochiPhone(
            @Valid @RequestBody UpdatePochiPhoneRequest updateRequest,
            Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        WalletResponse response = walletService.updatePochiPhoneNumber(userId, updateRequest.getPochiPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("Pochi phone number updated", response));
    }

    @PostMapping("/stripe/setup-intent")
    public ResponseEntity<ApiResponse<Map<String, String>>> createStripeSetupIntent(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);
        String email = auth.getName();
        Map<String, String> result = depositService.createStripeSetupIntent(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Setup intent created", result));
    }

    @PostMapping("/stripe/setup-intent/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmStripeSetupIntent(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String setupIntentId = body.get("setupIntentId");
        if (setupIntentId == null || setupIntentId.isBlank()) {
            throw new IllegalArgumentException("setupIntentId is required");
        }
        String userId = resolveUserId(request);
        depositService.confirmStripeSetupIntent(setupIntentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Card saved"));
    }

    @PostMapping("/deposit/paypal/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmPaypalDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String orderId = body.get("orderId");
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        String userId = resolveUserId(request);
        PaymentResponse response = depositService.confirmPaypalDeposit(orderId, userId);
        return ResponseEntity.ok(ApiResponse.success("PayPal deposit processed", response));
    }

    @PostMapping("/deposit/stripe/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmStripeDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String paymentIntentId = body.get("paymentIntentId");
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("paymentIntentId is required");
        }
        String userId = resolveUserId(request);
        PaymentResponse response = depositService.confirmStripeDeposit(paymentIntentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Stripe deposit processed", response));
    }

    @PostMapping("/deposit/flutterwave/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmFlutterwaveDeposit(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String txRef = body.get("txRef");
        if (txRef == null || txRef.isBlank()) {
            throw new IllegalArgumentException("txRef is required");
        }
        String userId = resolveUserId(request);
        PaymentResponse response = depositService.confirmFlutterwaveDeposit(txRef, userId);
        return ResponseEntity.ok(ApiResponse.success("Flutterwave deposit processed", response));
    }

    @PostMapping("/flutterwave/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> linkFlutterwaveAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + userId));

        log.info("Flutterwave account linking initiated: userId={}", userId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Flutterwave account linking initiated");

        return ResponseEntity.ok(ApiResponse.success("Flutterwave linking initiated", response));
    }

    @PostMapping("/flutterwave/link/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmFlutterwaveLink(
            @RequestBody Map<String, String> body,
            Authentication auth, HttpServletRequest request) {
        String customerId = body.get("customerId");
        String paymentMethodId = body.get("paymentMethodId");
        String network = body.get("network");
        String phone = body.get("phone");

        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }

        String userId = resolveUserId(request);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + userId));

        flutterwaveService.linkFlutterwaveAccount(wallet, customerId, paymentMethodId, network, phone);
        walletRepository.save(wallet);

        log.info("Flutterwave account linked: userId={} customerId={}", userId, customerId);

        return ResponseEntity.ok(ApiResponse.success("Flutterwave account linked successfully"));
    }

    @DeleteMapping("/flutterwave/link")
    public ResponseEntity<ApiResponse<Void>> unlinkFlutterwaveAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + userId));

        flutterwaveService.unlinkAccount(wallet);
        walletRepository.save(wallet);

        log.info("Flutterwave account unlinked: userId={}", userId);

        return ResponseEntity.ok(ApiResponse.success("Flutterwave account unlinked"));
    }

    @GetMapping("/flutterwave/account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFlutterwaveAccount(Authentication auth, HttpServletRequest request) {
        String userId = resolveUserId(request);

        var walletOpt = walletRepository.findByUserId(userId);
        Map<String, Object> info = new HashMap<>();

        if (walletOpt.isPresent()) {
            Wallet wallet = walletOpt.get();
            info.put("linked", wallet.getFlutterwaveCustomerId() != null);
            info.put("customerId", wallet.getFlutterwaveCustomerId());
            info.put("paymentMethodId", wallet.getFlutterwavePaymentMethodId());
            info.put("network", wallet.getFlutterwavePaymentMethodNetwork());
            info.put("phone", wallet.getFlutterwavePaymentMethodPhone());
        } else {
            info.put("linked", false);
        }

        return ResponseEntity.ok(ApiResponse.success("Flutterwave account status retrieved", info));
    }
}
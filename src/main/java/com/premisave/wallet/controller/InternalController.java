package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.InternalPaymentRequest;
import com.premisave.wallet.dto.InternalTransferRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.service.PaymentService;
import com.premisave.wallet.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints — authenticated via X-API-Key
 * (InternalApiKeyFilter), not a user JWT. Every route here is already
 * covered by SecurityConfig's existing "/internal/**" -> hasRole
 * ("INTERNAL_SERVICE") matcher and WebConfig's rate-limiter, both of
 * which predate this file — no config changes needed to add new routes
 * here.
 *
 * Unlike every controller under /wallet or /payments, these take an
 * explicit identity (userId/senderUserId, initiatedBy) in the request
 * body rather than resolving one from Authentication/
 * HttpServletRequest's "userId" attribute — InternalApiKeyFilter
 * authenticates the CALLING SERVICE, not an end user, so there's no JWT
 * to pull an identity claim from.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final TransferService transferService;
    private final PaymentService paymentService;

    /**
     * Triggers a wallet-to-wallet transfer on behalf of another Premisave
     * service. Same underlying logic as POST /wallet/transfer — see
     * TransferService.executeTransfer — just with an explicit sender
     * instead of one resolved from a JWT.
     */
    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody InternalTransferRequest request) {
        PaymentResponse response = transferService.transferInternal(request);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    /**
     * Triggers a wallet-to-platform payment on behalf of another
     * Premisave service — e.g. property-service deducting a booking fee
     * or ad-subscription charge from a user's wallet. Same underlying
     * logic as POST /payments/deduct — see PaymentService.executePayment
     * — just with an explicit payer instead of one resolved from a JWT.
     */
    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<PaymentResponse>> payment(
            @Valid @RequestBody InternalPaymentRequest request) {
        PaymentResponse response = paymentService.payInternal(request);
        return ResponseEntity.ok(ApiResponse.success("Payment successful", response));
    }
}
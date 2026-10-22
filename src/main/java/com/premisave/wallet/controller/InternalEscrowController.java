package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.EscrowDetailsResponse;
import com.premisave.wallet.dto.EscrowHoldRequest;
import com.premisave.wallet.dto.EscrowHoldResponse;
import com.premisave.wallet.dto.EscrowPayoutRequest;
import com.premisave.wallet.dto.EscrowPayoutResponse;
import com.premisave.wallet.service.EscrowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Escrow for another Premisave service (the listing service): hold a payment, pay it out later, look it up. Same
 * X-API-Key authentication and response wrapper as the rest of /internal/**, already covered by SecurityConfig's
 * "/internal/**" matcher. See EscrowService for what each call does and why.
 */
@RestController
@RequestMapping("/internal/escrow")
@RequiredArgsConstructor
public class InternalEscrowController {

    private final EscrowService escrowService;

    /** Debit the sender once (amount plus commission) and create the hold. */
    @PostMapping("/hold")
    public ResponseEntity<ApiResponse<EscrowHoldResponse>> hold(@Valid @RequestBody EscrowHoldRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Escrow hold created", escrowService.hold(request)));
    }

    /** Pay part or all of what is left in a hold to its sender (a refund) or its beneficiary. No commission. */
    @PostMapping("/{holdReference}/payouts")
    public ResponseEntity<ApiResponse<EscrowPayoutResponse>> payout(@PathVariable String holdReference,
                                                                    @Valid @RequestBody EscrowPayoutRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Escrow payout successful",
                escrowService.payout(holdReference, request)));
    }

    /** The hold and all its payouts, for confirming what happened after a timeout or a 409. */
    @GetMapping("/{holdReference}")
    public ResponseEntity<ApiResponse<EscrowDetailsResponse>> get(@PathVariable String holdReference) {
        return ResponseEntity.ok(ApiResponse.success("Escrow retrieved", escrowService.get(holdReference)));
    }
}

package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Deposit dispatcher — the shared "can this wallet deposit right now" check
 * (lookup + frozen guard), then delegates to the provider-specific service
 * that actually knows how to talk to that provider. This is deliberately
 * thin: it used to hold every provider's deposit logic directly, but that
 * grew large enough (especially Stripe, once card management and Connect
 * linking landed) that it was split into MpesaDepositService,
 * StripeDepositService, PaypalDepositService, and FlutterwaveDepositService
 * — one per provider, mirroring the existing MpesaService/StripeService/
 * PaypalService/FlutterwaveService split at the API-integration layer one
 * level down.
 *
 * WalletController and PaymentCallbackController call the provider-specific
 * services directly for everything except this top-level dispatch — e.g.
 * card management goes straight to StripeDepositService, PayPal webhook
 * handling goes straight to PaypalDepositService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final WalletRepository walletRepository;
    private final MpesaDepositService mpesaDepositService;
    private final StripeDepositService stripeDepositService;
    private final PaypalDepositService paypalDepositService;
    private final FlutterwaveDepositService flutterwaveDepositService;

    public PaymentResponse initiateDeposit(String userId, String userEmail, DepositRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen — deposits are not allowed until it is unfrozen");
        }

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";
        String idempotencyKey = UUID.randomUUID().toString();

        return switch (provider) {
            case "MPESA" -> mpesaDepositService.initiateMpesaDeposit(userId, request, wallet, idempotencyKey);
            case "MPESA_TILL" -> mpesaDepositService.initiateExpressCheckoutDeposit(userId, request, wallet);
            case "STRIPE" -> stripeDepositService.initiateStripeDeposit(userId, userEmail, request, wallet, idempotencyKey);
            case "PAYPAL" -> paypalDepositService.initiatePaypalDeposit(userId, request, wallet, idempotencyKey);
            case "FLUTTERWAVE" -> flutterwaveDepositService.initiateFlutterwaveDeposit(userId, userEmail, request, wallet, idempotencyKey);
            default -> new PaymentResponse(false, null, "Unsupported deposit provider: " + provider);
        };
    }
}
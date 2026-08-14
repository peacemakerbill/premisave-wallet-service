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
 * that actually knows how to talk to that provider. One per provider:
 * MpesaDepositService, StripeDepositService, PaypalDepositService,
 * FlutterwaveDepositService, and now NowPaymentsDepositService — mirroring
 * MpesaService/StripeService/PaypalService/FlutterwaveService/
 * NowPaymentsService at the API-integration layer one level down.
 *
 * WalletController and PaymentCallbackController call the provider-specific
 * services directly for everything except this top-level dispatch.
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
    private final NowPaymentsDepositService nowPaymentsDepositService;

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
            case "NOWPAYMENTS" -> nowPaymentsDepositService.initiateNowPaymentsDeposit(userId, request, wallet, idempotencyKey);
            default -> new PaymentResponse(false, null, "Unsupported deposit provider: " + provider);
        };
    }
}
package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final DepositRepository depositRepository;
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

    /** GET /deposits/history — every deposit for this user, across all five providers, newest first. */
    public List<DepositRecordResponse> getDepositHistory(String userId) {
        return depositRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DepositService::toRecordResponse)
                .toList();
    }

    /** Admin-only: every deposit across every user, paginated — see AdminFinanceController. */
    public Page<DepositRecordResponse> getAllDeposits(Pageable pageable) {
        return depositRepository.findAll(pageable).map(DepositService::toRecordResponse);
    }

    private static DepositRecordResponse toRecordResponse(Deposit d) {
        DepositRecordResponse r = new DepositRecordResponse();
        r.setId(d.getId());
        r.setUserId(d.getUserId());
        r.setAmount(d.getAmount());
        r.setCurrency(d.getCurrency());
        r.setProvider(d.getProvider());
        r.setChannel(d.getChannel());
        r.setSource(d.getSource());
        r.setStatus(d.getStatus());
        r.setReference(d.getReference());
        r.setProviderReference(d.getProviderReference());
        r.setFailureReason(d.getFailureReason());
        r.setPayAddress(d.getPayAddress());
        r.setPayAmount(d.getPayAmount());
        r.setPayCurrency(d.getPayCurrency());
        r.setPriceAmount(d.getPriceAmount());
        r.setPriceCurrency(d.getPriceCurrency());
        r.setCreatedAt(d.getCreatedAt());
        return r;
    }
}
package com.premisave.wallet.service;

import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.ProviderBalanceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates "what's Premisave's own balance with each payment
 * gateway" across all five providers — the same provider-specific-
 * service + thin-orchestrator pattern used throughout tonight for
 * deposits/disbursements, applied here instead. No new API-integration
 * logic lives here; each provider's own getBalance() (added to
 * StripeService/PaypalService/FlutterwaveService/NowPaymentsService,
 * plus M-Pesa's already-existing async flow reused as-is) does the
 * actual work — this class just calls all five and maps results into
 * one consistent response shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderBalanceService {

    private final StripeService stripeService;
    private final PaypalService paypalService;
    private final FlutterwaveService flutterwaveService;
    private final NowPaymentsService nowPaymentsService;
    private final MpesaOperationsService mpesaOperationsService;

    public List<ProviderBalanceResponse> getAllBalances(String initiatedBy) {
        return List.of(
                getStripeBalance(),
                getPaypalBalance(),
                getFlutterwaveBalance(),
                getNowPaymentsBalance(),
                getMpesaBalance(initiatedBy)
        );
    }

    public ProviderBalanceResponse getStripeBalance() {
        StripeService.BalanceResult result = stripeService.getBalance();
        return toResponse("STRIPE", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()));
    }

    public ProviderBalanceResponse getPaypalBalance() {
        PaypalService.BalanceResult result = paypalService.getBalance();
        return toResponse("PAYPAL", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()));
    }

    public ProviderBalanceResponse getFlutterwaveBalance() {
        FlutterwaveService.BalanceResult result = flutterwaveService.getBalance();
        return toResponse("FLUTTERWAVE", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()));
    }

    public ProviderBalanceResponse getNowPaymentsBalance() {
        NowPaymentsService.BalanceResult result = nowPaymentsService.getBalance();
        return toResponse("NOWPAYMENTS", result.success(), result.message(), mapBalances(
                result.balances().stream().map(b -> Map.entry(b.currency(), b.amounts())).toList()));
    }

    /**
     * M-Pesa has no synchronous "get balance now" call at all — Safaricom's
     * Account Balance API is asynchronous (submit a query, the real
     * balance arrives later via webhook to the existing ResultURL flow,
     * then GET /admin/wallet/mpesa/operations/{conversationId} retrieves
     * it). This method submits that query — reusing the already-built
     * MpesaOperationsService.queryAccountBalance rather than duplicating
     * it — and returns PENDING_ASYNC honestly, rather than pretending a
     * number comes back synchronously the way the other four do.
     *
     * Reads the returned MpesaAsyncResponse's getOriginatorConversationId/
     * getConversationId/isSuccess/getMessage — confirmed real getters,
     * verified directly from MpesaOperationsService's own usage of this
     * exact class (saveOperation calls all four). An earlier version of
     * this method discarded the return value entirely rather than guess
     * at that class's interface without having seen it — now confirmed,
     * so the admin can see the actual conversationId directly in this
     * response instead of having to dig through application logs.
     */
    public ProviderBalanceResponse getMpesaBalance(String initiatedBy) {
        ProviderBalanceResponse response = new ProviderBalanceResponse();
        response.setProvider("MPESA");
        response.setFetchedAt(LocalDateTime.now());
        response.setBalances(List.of());
        try {
            MpesaAsyncResponse submission = mpesaOperationsService.queryAccountBalance(initiatedBy);
            response.setConversationId(submission.getConversationId());
            response.setOriginatorConversationId(submission.getOriginatorConversationId());

            if (submission.isSuccess()) {
                response.setStatus("PENDING_ASYNC");
                response.setMessage(submission.getMessage() != null
                        ? submission.getMessage() : "Account Balance query submitted to Safaricom");
                response.setPollNote("M-Pesa has no synchronous balance check — Safaricom delivers the real "
                        + "balance asynchronously via webhook. Poll GET /admin/wallet/mpesa/operations/"
                        + submission.getConversationId() + " in roughly 30-60 seconds once Safaricom's callback "
                        + "has had time to land.");
            } else {
                response.setStatus("ERROR");
                response.setMessage(submission.getMessage() != null
                        ? submission.getMessage() : "Safaricom rejected the Account Balance query submission");
            }
        } catch (Exception e) {
            log.error("M-Pesa balance query submission failed", e);
            response.setStatus("ERROR");
            response.setMessage("Failed to submit M-Pesa Account Balance query: " + e.getMessage());
        }
        return response;
    }

    private List<ProviderBalanceResponse.CurrencyBalance> mapBalances(
            List<Map.Entry<String, Map<String, BigDecimal>>> entries) {
        return entries.stream().map(e -> {
            ProviderBalanceResponse.CurrencyBalance cb = new ProviderBalanceResponse.CurrencyBalance();
            cb.setCurrency(e.getKey());
            cb.setAmounts(e.getValue());
            return cb;
        }).toList();
    }

    private ProviderBalanceResponse toResponse(String provider, boolean success, String message,
                                                List<ProviderBalanceResponse.CurrencyBalance> balances) {
        ProviderBalanceResponse response = new ProviderBalanceResponse();
        response.setProvider(provider);
        response.setStatus(success ? "AVAILABLE" : "ERROR");
        response.setBalances(balances);
        response.setMessage(message);
        response.setFetchedAt(LocalDateTime.now());
        return response;
    }
}
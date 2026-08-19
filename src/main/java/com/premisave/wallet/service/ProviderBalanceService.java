package com.premisave.wallet.service;

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
     * Deliberately doesn't inspect the returned MpesaAsyncResponse's own
     * fields (e.g. its conversationId) — that class's real structure
     * wasn't available when this was written, and guessing at getter
     * names risked a compile-time mismatch against an unverified class.
     * The admin can get the actual conversationId from the same place
     * they already do for every other M-Pesa async operation tonight —
     * application logs, or by calling the existing
     * POST /admin/wallet/mpesa/balance/query directly, which already
     * returns it in its own response.
     */
    public ProviderBalanceResponse getMpesaBalance(String initiatedBy) {
        ProviderBalanceResponse response = new ProviderBalanceResponse();
        response.setProvider("MPESA");
        response.setFetchedAt(LocalDateTime.now());
        response.setBalances(List.of());
        try {
            mpesaOperationsService.queryAccountBalance(initiatedBy);
            response.setStatus("PENDING_ASYNC");
            response.setMessage("Account Balance query submitted to Safaricom");
            response.setPollNote("M-Pesa has no synchronous balance check — Safaricom delivers the real "
                    + "balance asynchronously via webhook. Check application logs for the conversationId this "
                    + "query was assigned, then poll GET /admin/wallet/mpesa/operations/{conversationId} in "
                    + "roughly 30-60 seconds once Safaricom's callback has had time to land.");
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
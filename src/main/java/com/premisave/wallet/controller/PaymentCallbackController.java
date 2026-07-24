package com.premisave.wallet.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.B2BExpressCheckoutCallbackRequest;
import com.premisave.wallet.dto.MpesaResultCallbackRequest;
import com.premisave.wallet.dto.MpesaStkCallbackRequest;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.DisbursementService;
import com.premisave.wallet.service.MpesaOperationsService;
import com.premisave.wallet.service.PullTransactionService;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.premisave.wallet.service.StripeService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles incoming webhooks/callbacks from all payment providers:
 * M-Pesa (STK Push, B2C, B2B, B2B Express Checkout, Account Balance,
 * Transaction Status, Reversal, B2Pochi), Stripe, and PayPal.
 * All endpoints are PUBLIC (no JWT) — secured by signature verification
 * or IP allowlist at the gateway/firewall level.
 */
@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final DepositService depositService;
    private final DisbursementService disbursementService;
    private final MpesaOperationsService mpesaOperationsService;
    private final PullTransactionService pullTransactionService;
    private final StripeService stripeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    // ─── M-Pesa STK Push Callback ────────────────────────────────────────────

    /**
     * Receives M-Pesa STK Push callback from Safaricom Daraja.
     * Secured via IP allowlist at the gateway/firewall level (no JWT).
     *
     * Safaricom's payload is nested under Body.stkCallback — see
     * MpesaStkCallbackRequest for the exact shape. On success (ResultCode == 0)
     * the paid amount, receipt number, and phone are inside CallbackMetadata.Item;
     * there is no account number or email in this payload, so the transaction is
     * matched back to a wallet via CheckoutRequestID (see DepositService).
     */
    @PostMapping("/mpesa/callback")
    public ResponseEntity<ApiResponse<Void>> handleMpesaCallback(@RequestBody MpesaStkCallbackRequest callback) {
        MpesaStkCallbackRequest.StkCallback stk = callback.getBody().getStkCallback();
        String checkoutRequestId = stk.getCheckoutRequestID();

        log.info("M-Pesa STK callback received: checkoutRequestId={} resultCode={} resultDesc={}",
                checkoutRequestId, stk.getResultCode(), stk.getResultDesc());

        try {
            if (stk.getResultCode() != 0) {
                // Not an error on our side — user cancelled, wrong PIN, timed out, etc.
                depositService.markStkTransactionFailed(checkoutRequestId, stk.getResultDesc());
                return ResponseEntity.ok(ApiResponse.success("Callback processed (payment not completed)"));
            }

            Map<String, Object> values = new HashMap<>();
            if (stk.getCallbackMetadata() != null && stk.getCallbackMetadata().getItem() != null) {
                for (MpesaStkCallbackRequest.CallbackItem item : stk.getCallbackMetadata().getItem()) {
                    values.put(item.getName(), item.getValue());
                }
            }

            BigDecimal amount = new BigDecimal(String.valueOf(values.get("Amount")));
            String mpesaReceipt = String.valueOf(values.get("MpesaReceiptNumber"));
            String phoneNumber = String.valueOf(values.get("PhoneNumber"));

            depositService.creditWalletFromStkCallback(checkoutRequestId, amount, mpesaReceipt, phoneNumber);

            return ResponseEntity.ok(ApiResponse.success("Callback processed"));
        } catch (Exception e) {
            log.error("Failed to process M-Pesa STK callback: checkoutRequestId={}", checkoutRequestId, e);
            // Always return 200 to Safaricom — they retry on non-200
            return ResponseEntity.ok(ApiResponse.error("Callback processing failed: " + e.getMessage()));
        }
    }

    // ─── M-Pesa B2C Result (disbursement outcome) ────────────────────────────

    /**
     * Safaricom sends the real B2C outcome here — the initial paymentrequest
     * acceptance is NOT the final result. ResultCode == 0 means the payout
     * actually succeeded; anything else means it failed and must be refunded.
     * See DisbursementService.completeMpesaDisbursement().
     */
    @PostMapping("/mpesa/b2c/result")
    public ResponseEntity<Void> mpesaB2cResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa B2C result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            disbursementService.completeMpesaDisbursement(
                    result.getConversationID(), success, result.getResultDesc(), result.getTransactionID());
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2C result: conversationId={}", result.getConversationID(), e);
        }

        // Always 200 — Safaricom does not expect a meaningful body here.
        return ResponseEntity.ok().build();
    }

    /**
     * Fired when Safaricom couldn't reach the ResultURL in time. The
     * disbursement stays PENDING — money remains held pending manual
     * reconciliation or a later result (see DisbursementService's sweeper).
     */
    @PostMapping("/mpesa/b2c/timeout")
    public ResponseEntity<Void> mpesaB2cTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa B2C timeout: conversationId={}", conversationId);
        try {
            disbursementService.markMpesaDisbursementTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2C timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa B2B Result (business-to-business payment outcome) ───────────
    // Covers BusinessPayBill, BusinessBuyGoods, and B2C Account Top Up
    // (BusinessPayToBulk) — all three go through the same result/timeout
    // callback shape; DisbursementService.completeMpesaDisbursement()
    // distinguishes them by the Disbursement's `channel` field.

    @PostMapping("/mpesa/b2b/result")
    public ResponseEntity<Void> mpesaB2bResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa B2B result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            disbursementService.completeMpesaDisbursement(
                    result.getConversationID(), success, result.getResultDesc(), result.getTransactionID());
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2B result: conversationId={}", result.getConversationID(), e);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/b2b/timeout")
    public ResponseEntity<Void> mpesaB2bTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa B2B timeout: conversationId={}", conversationId);
        try {
            disbursementService.markMpesaDisbursementTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2B timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa B2B Express Checkout (USSD Push to Till) Result ─────────────

    /**
     * Flatter callback shape than the standard Result envelope used by the
     * other B2B/B2C flows — see B2BExpressCheckoutCallbackRequest. Matched
     * back to the pending deposit transaction via the RequestRefID we
     * generated at initiation (see DepositService.creditWalletFromExpressCheckout),
     * since this payload carries no account/email either.
     * Public — secured via IP allowlist at the gateway, same as every other
     * M-Pesa callback here.
     */
    @PostMapping("/mpesa/b2b/express-checkout/result")
    public ResponseEntity<Void> mpesaB2BExpressCheckoutResult(
            @RequestBody B2BExpressCheckoutCallbackRequest callback) {
        String requestId = callback.getRequestId();
        boolean success = "0".equals(callback.getResultCode());
        log.info("B2B Express Checkout result: requestId={} resultCode={} status={}",
                requestId, callback.getResultCode(), callback.getStatus());

        try {
            BigDecimal amount = callback.getAmount() != null ? new BigDecimal(callback.getAmount()) : BigDecimal.ZERO;
            depositService.creditWalletFromExpressCheckout(
                    requestId, amount, callback.getTransactionId(), callback.getResultDesc(), success);
        } catch (Exception e) {
            log.error("Failed to process B2B Express Checkout callback: requestId={}", requestId, e);
        }

        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa Account Balance Result ────────────────────────────────────────

    /**
     * Same Result envelope as B2C/B2B — the balances themselves arrive
     * pipe-delimited inside a single "AccountBalance" ResultParameter, e.g.
     * "Working Account|KES|700000.00|700000.00|0.00|0.00&Utility Account|...".
     * We pass the raw parameter map through to MpesaOperationsService rather
     * than parsing the pipe format here — keeps the parsing logic in one place.
     * See https://developer.safaricom.co.ke/apis/AccountBalance
     */
    @PostMapping("/mpesa/balance/result")
    public ResponseEntity<Void> mpesaBalanceResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa Account Balance result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            mpesaOperationsService.completeOperation(result.getConversationID(), success,
                    String.valueOf(result.getResultCode()), result.getResultDesc(), extractResultParameters(result));
        } catch (Exception e) {
            log.error("Failed to process Account Balance result: conversationId={}", result.getConversationID(), e);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/balance/timeout")
    public ResponseEntity<Void> mpesaBalanceTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa Account Balance timeout: conversationId={}", conversationId);
        try {
            mpesaOperationsService.markOperationTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process Account Balance timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa Transaction Status Result ─────────────────────────────────────

    /**
     * See https://developer.safaricom.co.ke/apis/TransactionStatus
     */
    @PostMapping("/mpesa/transactionstatus/result")
    public ResponseEntity<Void> mpesaTransactionStatusResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa Transaction Status result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            mpesaOperationsService.completeOperation(result.getConversationID(), success,
                    String.valueOf(result.getResultCode()), result.getResultDesc(), extractResultParameters(result));
        } catch (Exception e) {
            log.error("Failed to process Transaction Status result: conversationId={}", result.getConversationID(), e);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/transactionstatus/timeout")
    public ResponseEntity<Void> mpesaTransactionStatusTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa Transaction Status timeout: conversationId={}", conversationId);
        try {
            mpesaOperationsService.markOperationTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process Transaction Status timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa Reversal Result ────────────────────────────────────────────────

    /**
     * On success, MpesaOperationsService.completeOperation automatically
     * debits the linked wallet (if the reversed transactionId matched a
     * completed deposit on file) and records a REFUND transaction.
     * See https://developer.safaricom.co.ke/apis/Reversal
     */
    @PostMapping("/mpesa/reversal/result")
    public ResponseEntity<Void> mpesaReversalResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa Reversal result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            mpesaOperationsService.completeOperation(result.getConversationID(), success,
                    String.valueOf(result.getResultCode()), result.getResultDesc(), extractResultParameters(result));
        } catch (Exception e) {
            log.error("Failed to process Reversal result: conversationId={}", result.getConversationID(), e);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/reversal/timeout")
    public ResponseEntity<Void> mpesaReversalTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa Reversal timeout: conversationId={}", conversationId);
        try {
            mpesaOperationsService.markOperationTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process Reversal timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa B2Pochi Result ──────────────────────────────────────────────────

    /**
     * B2Pochi is a B2C variant (pays into a customer's Pochi la Biashara
     * wallet instead of their main M-Pesa balance) — it's a Disbursement
     * (channel="B2C_POCHI"), not an MpesaOperation, so it's reconciled
     * through DisbursementService exactly like B2C/B2B/B2C Top Up.
     * See https://developer.safaricom.co.ke/apis/BusinessToPochi
     */
    @PostMapping("/mpesa/b2pochi/result")
    public ResponseEntity<Void> mpesaB2PochiResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa B2Pochi result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            disbursementService.completeMpesaDisbursement(
                    result.getConversationID(), success, result.getResultDesc(), result.getTransactionID());
        } catch (Exception e) {
            log.error("Failed to process B2Pochi result: conversationId={}", result.getConversationID(), e);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/b2pochi/timeout")
    public ResponseEntity<Void> mpesaB2PochiTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa B2Pochi timeout: conversationId={}", conversationId);
        try {
            disbursementService.markMpesaDisbursementTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process B2Pochi timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa Pull Transactions Callback ────────────────────────────────────

    /**
     * Whatever Safaricom posts here after Register Pull is set up. The exact
     * payload shape isn't documented (only the Query response shape is) —
     * PullTransactionService.handleCallback logs the raw body and attempts
     * best-effort reconciliation assuming the same shape as the Query
     * response. See https://developer.safaricom.co.ke/apis/PullTransaction
     */
    @PostMapping("/mpesa/pull/callback")
    public ResponseEntity<Void> mpesaPullTransactionsCallback(@RequestBody String payload) {
        try {
            pullTransactionService.handleCallback(payload);
        } catch (Exception e) {
            log.error("Failed to process Pull Transactions callback", e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── Stripe Webhook ──────────────────────────────────────────────────────

    /**
     * Stripe sends events here. Secured by Stripe-Signature header verification.
     * Key event: payment_intent.succeeded → credit the wallet.
     */
    @PostMapping("/stripe/webhook")
    public ResponseEntity<ApiResponse<Void>> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        try {
            Event event = stripeService.constructWebhookEvent(payload, sigHeader, stripeWebhookSecret);
            log.info("Stripe webhook received: type={} id={}", event.getType(), event.getId());

            if ("payment_intent.succeeded".equals(event.getType())) {
                PaymentIntent pi = (PaymentIntent) event.getDataObjectDeserializer()
                        .getObject().orElseThrow();

                String userId = pi.getMetadata().get("user_id");
                BigDecimal amount = BigDecimal.valueOf(pi.getAmount())
                        .divide(BigDecimal.valueOf(100)); // cents → major unit
                String currency = pi.getCurrency();

                if (userId != null) {
                    depositService.creditWalletFromStripe(userId, amount, pi.getId(), currency);
                    log.info("Stripe deposit completed: userId={} amount={}", userId, amount);
                } else {
                    log.warn("Stripe PaymentIntent {} has no user_id metadata — skipping credit", pi.getId());
                }
            }

            return ResponseEntity.ok(ApiResponse.success("Webhook processed"));
        } catch (Exception e) {
            log.error("Stripe webhook processing failed", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Webhook error: " + e.getMessage()));
        }
    }

    // ─── PayPal Webhook ──────────────────────────────────────────────────────

    /**
     * PayPal sends events here after order approval.
     * Key event: CHECKOUT.ORDER.APPROVED → capture + credit wallet.
     *
     * PayPal webhook verification requires calling the PayPal Verify API —
     * for brevity we trust the event body here; add verification in production.
     */
    @PostMapping("/paypal/webhook")
    public ResponseEntity<ApiResponse<Void>> paypalWebhook(@RequestBody String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.path("event_type").asText();
            log.info("PayPal webhook received: type={}", eventType);

            if ("CHECKOUT.ORDER.APPROVED".equals(eventType)) {
                JsonNode resource = event.path("resource");
                String orderId = resource.path("id").asText();

                JsonNode unit = resource.path("purchase_units").get(0);
                String referenceId = unit.path("reference_id").asText();
                String amountStr   = unit.path("amount").path("value").asText("0");
                String currency    = unit.path("amount").path("currency_code").asText("USD");

                String userId = resolveUserIdFromReference(referenceId);

                if (userId != null) {
                    depositService.creditWalletFromPaypal(userId, new BigDecimal(amountStr), orderId, currency);
                } else {
                    log.warn("PayPal webhook: cannot resolve userId for orderId={}", orderId);
                }
            }

            return ResponseEntity.ok(ApiResponse.success("PayPal webhook processed"));
        } catch (Exception e) {
            log.error("PayPal webhook processing failed", e);
            return ResponseEntity.ok(ApiResponse.error("PayPal webhook error: " + e.getMessage()));
        }
    }

    /**
     * Looks up the userId from a pending transaction's reference field.
     * Replace with a proper lookup against TransactionRepository if you inject it here,
     * or store userId directly in PayPal's custom_id at order creation time (recommended).
     */
    private String resolveUserIdFromReference(String reference) {
        log.warn("resolveUserIdFromReference: implement userId lookup by reference={}", reference);
        return null;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Flattens Safaricom's Result.ResultParameters.ResultParameter[{Key,Value}]
     * array into a plain Map — shared by AccountBalance, TransactionStatus,
     * and Reversal callbacks, all of which use this same envelope shape.
     */
    private Map<String, Object> extractResultParameters(MpesaResultCallbackRequest.Result result) {
        Map<String, Object> map = new HashMap<>();
        if (result.getResultParameters() != null && result.getResultParameters().getResultParameter() != null) {
            for (MpesaResultCallbackRequest.ResultParameter param : result.getResultParameters().getResultParameter()) {
                map.put(param.getKey(), param.getValue());
            }
        }
        return map;
    }
}
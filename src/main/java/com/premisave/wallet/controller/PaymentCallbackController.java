package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.B2BExpressCheckoutCallbackRequest;
import com.premisave.wallet.dto.MpesaResultCallbackRequest;
import com.premisave.wallet.dto.MpesaStkCallbackRequest;
import com.premisave.wallet.dto.PaypalWebhookRequest;
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
    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    // ─── M-Pesa STK Push Callback ────────────────────────────────────────────

    @PostMapping("/mpesa/callback")
    public ResponseEntity<ApiResponse<Void>> handleMpesaCallback(@RequestBody MpesaStkCallbackRequest callback) {
        MpesaStkCallbackRequest.StkCallback stk = callback.getBody().getStkCallback();
        String checkoutRequestId = stk.getCheckoutRequestID();

        log.info("M-Pesa STK callback received: checkoutRequestId={} resultCode={} resultDesc={}",
                checkoutRequestId, stk.getResultCode(), stk.getResultDesc());

        try {
            if (stk.getResultCode() != 0) {
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
            return ResponseEntity.ok(ApiResponse.error("Callback processing failed: " + e.getMessage()));
        }
    }

    // ─── M-Pesa B2C Result (disbursement outcome) ────────────────────────────

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

        return ResponseEntity.ok().build();
    }

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
                // deserializeUnsafe(), NOT getObject(): getObject() only succeeds
                // when this event's pinned API version exactly matches the
                // version stripe-java 26.3.0 was compiled against — that can
                // drift out of sync with your Dashboard webhook endpoint's
                // configured API version and silently stop crediting every
                // deposit (getObject() returns Optional.empty() with no error).
                // The fields we read here (id, amount, currency, metadata) are
                // stable across versions, so force-deserializing is safe.
                PaymentIntent pi = (PaymentIntent) event.getDataObjectDeserializer().deserializeUnsafe();

                String reference = pi.getMetadata() != null ? pi.getMetadata().get("idempotency_key") : null;
                BigDecimal amount = BigDecimal.valueOf(pi.getAmount())
                        .divide(BigDecimal.valueOf(100)); // cents → major unit

                if (reference != null) {
                    depositService.creditWalletFromStripeCallback(reference, amount, pi.getId(), pi.getCurrency());
                    log.info("Stripe deposit completed: reference={} amount={}", reference, amount);
                } else {
                    log.warn("Stripe PaymentIntent {} has no idempotency_key metadata — cannot reconcile", pi.getId());
                }
            }

            return ResponseEntity.ok(ApiResponse.success("Webhook processed"));
        } catch (Exception e) {
            log.error("Stripe webhook processing failed", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Webhook error: " + e.getMessage()));
        }
    }

    // ─── PayPal Webhook ──────────────────────────────────────────────────────

    @PostMapping("/paypal/webhook")
    public ResponseEntity<Void> paypalWebhook(@RequestBody PaypalWebhookRequest payload) {
        log.info("PayPal webhook received: type={}", payload.getEventType());
        try {
            if ("CHECKOUT.ORDER.APPROVED".equals(payload.getEventType()) && payload.getResource() != null) {
                depositService.confirmPaypalDeposit(payload.getResource().getId());
            }
        } catch (Exception e) {
            log.error("PayPal webhook processing error: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
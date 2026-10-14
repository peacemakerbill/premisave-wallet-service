package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResultSink;
import com.premisave.wallet.commercial.service.CommercialTransactionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receives Safaricom's results for commercial M-Pesa transactions. These paths
 * are public (Safaricom sends no credentials), so every one carries a signature
 * made by MpesaCallbackSigner and is refused with 403 when it does not match.
 * Genuine callbacks always get a 200 so Safaricom does not keep retrying; a
 * problem on our side is logged, and the status poller or the expiry job
 * picks the transaction up later.
 */
@RestController("commercialMpesaCallbackController")
@RequestMapping(MpesaCallbackSigner.BASE_PATH)
@ConditionalOnProperty(prefix = "commercial.mpesa", name = "enabled", havingValue = "true")
public class MpesaCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MpesaCallbackController.class);
    private static final Map<String, Object> ACK = Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final MpesaCallbackSigner signer;
    private final RailResultSink sink;
    private final CommercialTransactionStore store;
    private final JsonMapper json;

    public MpesaCallbackController(MpesaCallbackSigner signer, RailResultSink sink, CommercialTransactionStore store,
                                   JsonMapper json) {
        this.signer = signer;
        this.sink = sink;
        this.store = store;
        this.json = json;
    }

    @PostMapping("/" + MpesaCallbackSigner.STK + "/{transactionId}/{signature}")
    public ResponseEntity<Map<String, Object>> stk(@PathVariable String transactionId, @PathVariable String signature,
                                                   @RequestBody String raw) {
        if (!signer.verify(MpesaCallbackSigner.STK, transactionId, signature)) return forbidden();
        try {
            Map<String, Object> callback = child(child(parse(raw), "Body"), "stkCallback");
            String resultCode = text(callback, "ResultCode");
            String resultDesc = text(callback, "ResultDesc");
            String receipt = null;
            BigDecimal paid = null;
            for (Map<String, Object> item : items(child(callback, "CallbackMetadata"), "Item")) {
                String name = text(item, "Name");
                if (name.equals("MpesaReceiptNumber")) receipt = text(item, "Value");
                if (name.equals("Amount")) paid = decimal(item.get("Value"));
            }
            log.info("M-Pesa payment callback for {}: code={} desc={}", transactionId, resultCode, resultDesc);

            if ("0".equals(resultCode.trim()) && !amountMatches(transactionId, paid)) {
                log.error("M-Pesa reported KES {} for {} but a different amount was expected; not applying", paid, transactionId);
                return ack();
            }
            sink.onOutcome(transactionId, MpesaResultMapper.stk(resultCode, resultDesc, blankToNull(receipt)));
        } catch (RuntimeException e) {
            log.error("Could not process the M-Pesa payment callback for {}", transactionId, e);
        }
        return ack();
    }

    @PostMapping("/" + MpesaCallbackSigner.B2C_RESULT + "/{transactionId}/{signature}")
    public ResponseEntity<Map<String, Object>> b2cResult(@PathVariable String transactionId, @PathVariable String signature,
                                                         @RequestBody String raw) {
        if (!signer.verify(MpesaCallbackSigner.B2C_RESULT, transactionId, signature)) return forbidden();
        try {
            Map<String, Object> result = child(parse(raw), "Result");
            String resultCode = text(result, "ResultCode");
            String resultDesc = text(result, "ResultDesc");
            log.info("M-Pesa payout result for {}: code={} desc={}", transactionId, resultCode, resultDesc);
            sink.onOutcome(transactionId, MpesaResultMapper.b2c(resultCode, resultDesc, blankToNull(text(result, "TransactionID"))));
        } catch (RuntimeException e) {
            log.error("Could not process the M-Pesa payout result for {}", transactionId, e);
        }
        return ack();
    }

    @PostMapping("/" + MpesaCallbackSigner.B2C_TIMEOUT + "/{transactionId}/{signature}")
    public ResponseEntity<Map<String, Object>> b2cTimeout(@PathVariable String transactionId, @PathVariable String signature,
                                                          @RequestBody(required = false) String raw) {
        if (!signer.verify(MpesaCallbackSigner.B2C_TIMEOUT, transactionId, signature)) return forbidden();
        try {
            log.warn("M-Pesa payout queue timeout for {}", transactionId);
            RailOutcome outcome = MpesaResultMapper.b2cQueueTimeout();
            sink.onOutcome(transactionId, outcome);
        } catch (RuntimeException e) {
            log.error("Could not process the M-Pesa payout timeout for {}", transactionId, e);
        }
        return ack();
    }

    // ------------------------------------------------------------------ helpers

    /** A success whose amount differs from what was asked is never applied automatically. */
    private boolean amountMatches(String transactionId, BigDecimal paid) {
        Optional<CommercialTransaction> tx = store.find(transactionId);
        if (tx.isEmpty() || tx.get().getType() != TxType.PAYMENT || tx.get().getNativeAmount() == null) return true;
        return paid == null || tx.get().getNativeAmount().compareTo(paid) == 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String raw) {
        Map<String, Object> m = raw == null || raw.isBlank() ? null : json.readValue(raw, Map.class);
        return m == null ? Map.of() : m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof List<?> ? (List<Map<String, Object>>) v : List.of();
    }

    private static String text(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal decimal(Object v) {
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseEntity<Map<String, Object>> ack() {
        return ResponseEntity.ok(ACK);
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}

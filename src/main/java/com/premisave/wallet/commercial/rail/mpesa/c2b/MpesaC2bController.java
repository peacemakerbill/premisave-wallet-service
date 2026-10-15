package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.rail.mpesa.MpesaCallbackSigner;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safaricom's validation and confirmation requests for the commercial paybill. The paths are public
 * (Safaricom sends no credentials), so each carries a signature made by MpesaCallbackSigner and is refused
 * with 403 when it does not match. The validation answer uses Safaricom's own result codes; the confirmation
 * is always acknowledged once the payment is on record, so Safaricom does not retry.
 */
@RestController("commercialMpesaC2bController")
@RequestMapping(MpesaCallbackSigner.BASE_PATH)
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class MpesaC2bController {

    private static final Logger log = LoggerFactory.getLogger(MpesaC2bController.class);

    private final MpesaCallbackSigner signer;
    private final C2bService service;
    private final JsonMapper json;

    public MpesaC2bController(MpesaCallbackSigner signer, C2bService service, JsonMapper json) {
        this.signer = signer;
        this.service = service;
        this.json = json;
    }

    @PostMapping("/" + MpesaCallbackSigner.C2B_VALIDATION + "/{shortcode}/{signature}")
    public ResponseEntity<Map<String, Object>> validation(@PathVariable String shortcode, @PathVariable String signature,
                                                          @RequestBody String raw) {
        if (!signer.verify(MpesaCallbackSigner.C2B_VALIDATION, shortcode, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            C2bService.ValidationAnswer a = service.validate(parse(raw));
            return ResponseEntity.ok(validationBody(a));
        } catch (RuntimeException e) {
            // Never accept money we could not check.
            log.error("Paybill validation failed", e);
            return ResponseEntity.ok(validationBody(new C2bService.ValidationAnswer(false, C2bRules.OTHER_ERROR, "Rejected")));
        }
    }

    @PostMapping("/" + MpesaCallbackSigner.C2B_CONFIRMATION + "/{shortcode}/{signature}")
    public ResponseEntity<Map<String, Object>> confirmation(@PathVariable String shortcode, @PathVariable String signature,
                                                            @RequestBody String raw) {
        if (!signer.verify(MpesaCallbackSigner.C2B_CONFIRMATION, shortcode, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            service.confirm(parse(raw));
        } catch (RuntimeException e) {
            log.error("Could not record a paybill confirmation: {}", raw, e);
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("ResultCode", 0);
        ack.put("ResultDesc", "Success");
        return ResponseEntity.ok(ack);
    }

    static Map<String, Object> validationBody(C2bService.ValidationAnswer a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ResultCode", a.code());
        body.put("ResultDesc", a.description());
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String raw) {
        Map<String, Object> m = raw == null || raw.isBlank() ? null : json.readValue(raw, Map.class);
        return m == null ? Map.of() : m;
    }
}

package com.premisave.wallet.commercial.rail.mpesa.c2b;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * For a paybill shared with the wallet service's own C2B deposits. Safaricom keeps one address pair per paybill,
 * so the wallet service's existing validation and confirmation endpoints stay registered and ask this router
 * first: it answers only for account numbers that are commercial references, and says nothing otherwise so the
 * wallet service carries on as before.
 *
 * In the wallet service's C2B controller, at the top of the validation handler:
 *
 *     Optional&lt;Map&lt;String, Object&gt;&gt; mine = commercialC2bRouter.validation(body);
 *     if (mine.isPresent()) return ResponseEntity.ok(mine.get());
 *
 * and at the top of the confirmation handler:
 *
 *     Optional&lt;Map&lt;String, Object&gt;&gt; mine = commercialC2bRouter.confirmation(body);
 *     if (mine.isPresent()) return ResponseEntity.ok(mine.get());
 *
 * where body is the request parsed to a Map. Set commercial.c2b.shortcode to the shared paybill and leave
 * register-on-startup off, since the addresses are the wallet service's.
 */
@Component("commercialC2bRouter")
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class CommercialC2bRouter {

    private final C2bService service;

    public CommercialC2bRouter(C2bService service) {
        this.service = service;
    }

    public Optional<Map<String, Object>> validation(Map<String, Object> body) {
        if (!service.owns(String.valueOf(body.get("BillRefNumber")))) return Optional.empty();
        return Optional.of(MpesaC2bController.validationBody(service.validate(body)));
    }

    public Optional<Map<String, Object>> confirmation(Map<String, Object> body) {
        if (!service.owns(String.valueOf(body.get("BillRefNumber")))) return Optional.empty();
        service.confirm(body);
        return Optional.of(Map.of("ResultCode", 0, "ResultDesc", "Success"));
    }
}

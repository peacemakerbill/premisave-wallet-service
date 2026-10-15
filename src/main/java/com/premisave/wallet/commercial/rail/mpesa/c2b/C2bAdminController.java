package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.dto.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator calls for the commercial paybill, behind the same internal key as the rest of
 * /internal/commercial. Not environment scoped and never called by merchants.
 *
 * POST /internal/commercial/v1/c2b/register-urls  registers the validation and confirmation addresses
 * GET  /internal/commercial/v1/c2b/unmatched      payments that could not be credited to a merchant
 */
@RestController("commercialC2bAdminController")
@RequestMapping("/internal/commercial/v1/c2b")
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class C2bAdminController {

    private final C2bUrlRegistrar registrar;
    private final MongoTemplate mongo;

    public C2bAdminController(C2bUrlRegistrar registrar, MongoTemplate mongo) {
        this.registrar = registrar;
        this.mongo = mongo;
    }

    @PostMapping("/register-urls")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerUrls() {
        try {
            return ResponseEntity.ok(ApiResponse.success("Paybill addresses", registrar.register()));
        } catch (RailException e) {
            throw CommercialException.invalidState(e.getMessage());
        } catch (com.premisave.wallet.commercial.rail.mpesa.MpesaDarajaClient.NotSent
                 | com.premisave.wallet.commercial.rail.mpesa.MpesaDarajaClient.Unconfirmed e) {
            throw CommercialException.invalidState("Safaricom could not be reached: " + e.getMessage());
        }
    }

    @GetMapping("/unmatched")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> unmatched() {
        Query q = Query.query(Criteria.where("status").is(C2bReceipt.Status.UNMATCHED.name()))
                .with(PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "receivedAt")));
        List<Map<String, Object>> rows = mongo.find(q, C2bReceipt.class).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transId", r.getTransId());
            m.put("accountNumber", r.getBillRef());
            m.put("amountKes", r.getAmount());
            m.put("reason", r.getReason());
            m.put("receivedAt", r.getReceivedAt() == null ? null : r.getReceivedAt().toString());
            m.put("transactionId", r.getTransactionId());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success("Unmatched paybill payments", rows));
    }
}

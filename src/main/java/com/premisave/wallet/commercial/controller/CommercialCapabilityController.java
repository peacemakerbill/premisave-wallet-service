package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.QuoteRequest;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.service.CapabilityService;
import com.premisave.wallet.commercial.service.QuoteService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}")
public class CommercialCapabilityController {

    private final CapabilityService capabilities;
    private final QuoteService quotes;

    public CommercialCapabilityController(CapabilityService capabilities, QuoteService quotes) {
        this.capabilities = capabilities;
        this.quotes = quotes;
    }

    @GetMapping("/payment-methods")
    public ResponseEntity<ApiResponse<Map<String, Object>>> methods(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String direction) {
        return Responses.ok("Payment methods retrieved",
                capabilities.methods(CommercialContextHolder.require(), country, currency, direction));
    }

    @PostMapping("/quotes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quote(@Valid @RequestBody QuoteRequest req) {
        return Responses.ok("Quote created", quotes.create(CommercialContextHolder.require(), req));
    }
}

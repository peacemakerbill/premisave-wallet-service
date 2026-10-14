package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.CreditRequest;
import com.premisave.wallet.commercial.dto.ResetRequest;
import com.premisave.wallet.commercial.dto.SimulateRequest;
import com.premisave.wallet.commercial.dto.TestEventRequest;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.service.EventQueryService;
import com.premisave.wallet.commercial.service.SandboxService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sandbox helpers. Under /live every one of these answers 404 NOT_FOUND. */
@RestController
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}")
public class CommercialSandboxController {

    private final SandboxService sandbox;
    private final EventQueryService events;

    public CommercialSandboxController(SandboxService sandbox, EventQueryService events) {
        this.sandbox = sandbox;
        this.events = events;
    }

    @PostMapping("/payments/{id}/simulate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> simulatePayment(@PathVariable String id,
                                                                            @Valid @RequestBody SimulateRequest req) {
        return Responses.ok("Payment outcome simulated",
                sandbox.simulate(CommercialContextHolder.require(), id, TxType.PAYMENT, req));
    }

    @PostMapping("/payouts/{id}/simulate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> simulatePayout(@PathVariable String id,
                                                                           @Valid @RequestBody SimulateRequest req) {
        return Responses.ok("Payout outcome simulated",
                sandbox.simulate(CommercialContextHolder.require(), id, TxType.PAYOUT, req));
    }

    @PostMapping("/balance/credit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> credit(@Valid @RequestBody CreditRequest req) {
        return Responses.ok("Sandbox balance credited", sandbox.credit(CommercialContextHolder.require(), req));
    }

    @PostMapping("/events/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testEvent(@Valid @RequestBody TestEventRequest req) {
        CommercialContext ctx = CommercialContextHolder.require();
        requireSandbox(ctx);
        return Responses.accepted("Test event queued", events.sendTest(ctx, req));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reset(@RequestBody(required = false) ResetRequest req) {
        return Responses.ok("Sandbox reset", sandbox.reset(CommercialContextHolder.require(), req));
    }

    private static void requireSandbox(CommercialContext ctx) {
        if (ctx.livemode()) throw com.premisave.wallet.commercial.exception.CommercialException.notFound("Resource");
    }
}

package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.dto.event.RegistrationRequest;
import com.premisave.wallet.commercial.dto.event.RegistrationStatusRequest;
import com.premisave.wallet.commercial.dto.event.ResendRequest;
import com.premisave.wallet.commercial.dto.event.TestEventRequest;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.service.CallbackRegistrationService;
import com.premisave.wallet.commercial.service.EventQueryService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Events the merchant can inspect or resend, and the callback URLs they go to. */
@RestController
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}")
public class CommercialEventController {

    private final EventQueryService events;
    private final CallbackRegistrationService registrations;

    public CommercialEventController(EventQueryService events, CallbackRegistrationService registrations) {
        this.events = events;
        this.registrations = registrations;
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listEvents(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Responses.page("Events retrieved",
                events.list(CommercialContextHolder.require(), filters, PageViews.pageable(page, size, null)), Views::event);
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEvent(@PathVariable String eventId) {
        return Responses.ok("Event retrieved", Views.event(events.get(CommercialContextHolder.require(), eventId)));
    }

    @PostMapping("/events/{eventId}/resend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resend(@PathVariable String eventId,
                                                                   @Valid @RequestBody(required = false) ResendRequest req) {
        return Responses.accepted("Event queued for resend", events.resend(CommercialContextHolder.require(), eventId, req));
    }

    @PostMapping("/callbacks/registrations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@Valid @RequestBody RegistrationRequest req) {
        return Responses.created("Callback registered",
                Views.registration(registrations.create(CommercialContextHolder.require(), req)));
    }

    @GetMapping("/callbacks/registrations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listRegistrations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Responses.page("Registrations retrieved",
                registrations.list(CommercialContextHolder.require(), status, type, appId, PageViews.pageable(page, size, null)),
                Views::registration);
    }

    @GetMapping("/callbacks/registrations/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRegistration(@PathVariable String id) {
        return Responses.ok("Registration retrieved",
                Views.registration(registrations.get(CommercialContextHolder.require(), id)));
    }

    @PutMapping("/callbacks/registrations/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateRegistration(@PathVariable String id,
                                                                                @Valid @RequestBody RegistrationRequest req) {
        return Responses.ok("Registration updated",
                Views.registration(registrations.update(CommercialContextHolder.require(), id, req)));
    }

    @DeleteMapping("/callbacks/registrations/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteRegistration(@PathVariable String id) {
        registrations.delete(CommercialContextHolder.require(), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("deleted", true);
        return Responses.ok("Registration deleted", data);
    }

    @PostMapping("/callbacks/registrations/{id}/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(@PathVariable String id) {
        var r = registrations.verify(CommercialContextHolder.require(), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", r.getId());
        data.put("status", r.getStatus().name());
        data.put("verifiedAt", Views.iso(r.getVerifiedAt()));
        return Responses.ok("Callback verified", data);
    }

    @PostMapping("/callbacks/registrations/{id}/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> test(@PathVariable String id,
                                                                 @Valid @RequestBody(required = false) TestEventRequest req) {
        CommercialContext ctx = CommercialContextHolder.require();
        return Responses.accepted("Test event queued", registrations.test(ctx, id, req));
    }

    @PutMapping("/callbacks/registrations/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changeStatus(@PathVariable String id,
                                                                          @Valid @RequestBody RegistrationStatusRequest req) {
        return Responses.ok("Registration status updated",
                Views.registration(registrations.changeStatus(CommercialContextHolder.require(), id, req)));
    }
}

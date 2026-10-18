package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.service.DeveloperServiceClient;
import com.premisave.wallet.dto.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lets staff find a developer account while creating a merchant. The wallet keeps no list of developers, so the
 * search is put to the developer service and its answer is returned unchanged: the same envelope, with a paged
 * data of developer summaries (developerId, email, username, fullName, companyName, country, role, status,
 * emailVerified, businessIds, createdAt, lastLoginAt). The developerId of the chosen row is the developerAccountId
 * that Create merchant expects.
 *
 * When the developer service is offline, slow, refuses the wallet's key, fails or is not configured, the search
 * answers 503 PROVIDER_UNAVAILABLE with a message fit to show a person and data.state naming the cause; no URL, key or
 * exception text is ever returned. GET /status reports the same thing without searching.
 */
@RestController("commercialDeveloperController")
@RequestMapping("/internal/commercial/v1/developers")
public class CommercialDeveloperController {

    static final String SERVICE = "developer-service";
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "DISABLED");

    private final DeveloperServiceClient developers;

    public CommercialDeveloperController(DeveloperServiceClient developers) {
        this.developers = developers;
    }

    /**
     * q is matched anywhere in the email, username, full name or company name, or against the developer id exactly.
     * status narrows the list to PENDING, ACTIVE or DISABLED accounts. Newest accounts come first.
     */
    @GetMapping
    public ResponseEntity<String> search(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        String text = q == null ? null : q.trim();
        if (text != null && text.length() > MAX_QUERY_LENGTH) {
            throw CommercialException.validation("q", "must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        String wanted = null;
        if (status != null && !status.isBlank()) {
            wanted = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(wanted)) throw CommercialException.validation("status", "must be PENDING, ACTIVE or DISABLED");
        }
        if (page != null && page < 0) throw CommercialException.validation("page", "must be 0 or more");
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            throw CommercialException.validation("size", "must be between 1 and " + MAX_PAGE_SIZE);
        }

        DeveloperServiceClient.SearchReply reply = developers.searchDevelopers(text, wanted, page, size);
        if (reply.reach() != DeveloperServiceClient.Reach.ONLINE) throw unavailable(reply.reach());
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    /**
     * Tells the screen whether developers can be searched right now, so it can show a notice before anyone types.
     * Always answers 200: data.online says whether the developer service is up and accepted the wallet's key, data.state
     * is ONLINE, OFFLINE, TIMEOUT, REJECTED, ERROR or NOT_CONFIGURED, and data.message is a sentence fit to show a person.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        DeveloperServiceClient.Reach reach = developers.check();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", SERVICE);
        data.put("online", reach == DeveloperServiceClient.Reach.ONLINE);
        data.put("state", reach.name());
        data.put("message", reach.message());
        return Responses.ok(reach.message(), data);
    }

    private static CommercialException unavailable(DeveloperServiceClient.Reach reach) {
        return new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, reach.message())
                .with("service", SERVICE)
                .with("online", false)
                .with("state", reach.name());
    }
}
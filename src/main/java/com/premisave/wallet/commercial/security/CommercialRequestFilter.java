package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Runs for /internal/commercial/** after the normal security chain has
 * accepted the internal API key. It assigns the request id, buffers the body,
 * records its SHA-256 for idempotency comparison, and checks the HMAC
 * signature when signing is enabled.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CommercialRequestFilter extends OncePerRequestFilter {

    public static final String ATTR_REQUEST_ID = "commercial.requestId";
    public static final String ATTR_BODY_HASH = "commercial.bodyHash";
    private static final String PREFIX = "/internal/commercial/";
    private static final int MAX_BODY_BYTES = 1_048_576;

    private final CommercialProperties props;

    public CommercialRequestFilter(CommercialProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = Ids.requestId();
        }
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, 400, ErrorCode.VALIDATION_FAILED, "Request body is larger than 1 MB", requestId);
            return;
        }
        request.setAttribute(ATTR_BODY_HASH, Hashing.sha256Hex(body));

        CommercialProperties.Signing signing = props.signing();
        if (signing.enabled()) {
            String pathWithQuery = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            Optional<String> problem = RequestSignatureVerifier.verify(signing.secrets(), request.getMethod(),
                    pathWithQuery, body, request.getHeader("X-Timestamp"), request.getHeader("X-Signature"),
                    Instant.now().getEpochSecond(), signing.replayWindowSeconds());
            if (problem.isPresent()) {
                reject(response, 401, ErrorCode.UNAUTHENTICATED, problem.get(), requestId);
                return;
            }
        }

        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static void reject(HttpServletResponse response, int status, ErrorCode code, String message,
                               String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(Json.errorBody(message, code.name(), requestId));
    }
}

package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.service.AppSnapshotService;
import com.premisave.wallet.commercial.util.Ids;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Establishes the CommercialContext for /live and /sandbox routes: environment
 * from the path (cross-checked against X-App-Environment), the merchant from this
 * service's own records, and the app from the context headers the developer service
 * sends, with suspension rules. Reads stay available to a suspended merchant or app;
 * anything that changes state is refused.
 */
@Component
public class CommercialRequestInterceptor implements HandlerInterceptor {

    private static final String BASE = "/internal/commercial/v1/";

    private final CommercialProperties props;
    private final MerchantProfileRepository merchants;
    private final AppSnapshotService snapshots;

    public CommercialRequestInterceptor(CommercialProperties props, MerchantProfileRepository merchants,
                                        AppSnapshotService snapshots) {
        this.props = props;
        this.merchants = merchants;
        this.snapshots = snapshots;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object attr = request.getAttribute(CommercialRequestFilter.ATTR_REQUEST_ID);
        String requestId = attr instanceof String s ? s : Ids.requestId();

        if (!props.enabled()) {
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "The commercial API is switched off");
        }

        Environment env = environmentFromPath(request.getRequestURI());
        if (!props.serves(env)) {
            throw new CommercialException(ErrorCode.ENVIRONMENT_MISMATCH,
                    "This deployment does not serve the " + env.pathSegment() + " environment");
        }

        String assertedEnv = request.getHeader("X-App-Environment");
        if (assertedEnv == null || Environment.fromHeaderValue(assertedEnv).orElse(null) != env) {
            throw new CommercialException(ErrorCode.ENVIRONMENT_MISMATCH,
                    "X-App-Environment must be " + env.headerValue() + " for the /" + env.pathSegment() + " path");
        }

        AppContext app = AppContextHeaders.parse(request::getHeader, env);
        MerchantProfile merchant = merchants.findById(app.merchantId())
                .orElseThrow(() -> new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found"));

        boolean readOnly = request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        MerchantStatus status = merchant.statusFor(env);
        if (status == MerchantStatus.PENDING_APPROVAL) {
            throw new CommercialException(ErrorCode.LIVE_NOT_ENABLED, "Production access has not been approved yet");
        }
        if (!readOnly) {
            if (status != MerchantStatus.ACTIVE) {
                throw new CommercialException(ErrorCode.MERCHANT_SUSPENDED,
                        "The merchant is " + status.name().toLowerCase() + " in this environment");
            }
            if (!app.active()) {
                throw new CommercialException(ErrorCode.APP_SUSPENDED, "The app is suspended");
            }
        }

        snapshots.recordQuietly(app);
        CommercialContextHolder.set(new CommercialContext(env, merchant, app, requestId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        CommercialContextHolder.clear();
    }

    static Environment environmentFromPath(String uri) {
        int start = uri.indexOf(BASE);
        if (start < 0) throw new CommercialException(ErrorCode.NOT_FOUND, "Unknown commercial route");
        String rest = uri.substring(start + BASE.length());
        int slash = rest.indexOf('/');
        String segment = slash < 0 ? rest : rest.substring(0, slash);
        return Environment.fromPathSegment(segment)
                .orElseThrow(() -> new CommercialException(ErrorCode.NOT_FOUND, "Unknown commercial route"));
    }
}

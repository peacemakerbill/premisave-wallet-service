package com.premisave.wallet.commercial.link;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.dto.CreateAccountReferenceRequest;
import com.premisave.wallet.commercial.dto.MethodSpec;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.MethodPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Account references (section 5.9): the text a customer enters as the account number when paying
 * a shared paybill. Issuing one needs a rail that can match incoming payments to it, which
 * is described by a PayToProvider. Until a live rail provides one, only the sandbox can issue references.
 */
@Service("commercialAccountReferenceService")
public class AccountReferenceService {

    private static final Pattern REFERENCE_PART = Pattern.compile("^[A-Za-z0-9-]{1,20}$");

    private final CommercialIdempotencyService idempotency;
    private final AccountReferenceRepository references;
    private final AccountPrefixAllocator prefixes;
    private final List<PayToProvider> payTo;
    private final CommercialProperties props;

    public AccountReferenceService(CommercialIdempotencyService idempotency, AccountReferenceRepository references,
                                   AccountPrefixAllocator prefixes, List<PayToProvider> payTo, CommercialProperties props) {
        this.idempotency = idempotency;
        this.references = references;
        this.prefixes = prefixes;
        this.payTo = payTo;
        this.props = props;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreateAccountReferenceRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "ACCOUNT_REFERENCE",
                () -> doCreate(ctx, req),
                id -> LinkViews.accountReference(references.findByReferenceAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                        .orElseThrow(() -> CommercialException.notFound("Account reference")), Instant.now()));
    }

    private String doCreate(CommercialContext ctx, CreateAccountReferenceRequest req) {
        MethodSpec spec = req.method();
        MethodType method = spec.type();
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), method)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, method + " is not enabled for this merchant or app");
        }
        String part = req.merchantReference() == null ? "" : req.merchantReference().trim();
        if (!REFERENCE_PART.matcher(part).matches()) {
            throw CommercialException.validation("merchantReference", "must be 1 to 20 letters, digits or hyphens");
        }
        BigDecimal expected = null;
        if (req.expectedAmount() != null) {
            if (req.expectedAmount().compareTo(new BigDecimal("0.01")) < 0) {
                throw CommercialException.validation("expectedAmount", "must be at least 0.01");
            }
            expected = req.expectedAmount().setScale(2, RoundingMode.HALF_UP);
        }
        String currency = (req.currency() != null ? req.currency() : ctx.merchant().getDefaultCurrency()).toUpperCase(Locale.ROOT);
        if (!props.supportsCurrency(currency)) throw CommercialException.validation("currency", currency + " is not supported");

        Instant now = Instant.now();
        Instant expiresAt = null;
        if (req.expiresAt() != null && !req.expiresAt().isBlank()) {
            try {
                expiresAt = Instant.parse(req.expiresAt().trim());
            } catch (DateTimeParseException e) {
                throw CommercialException.validation("expiresAt", "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
            }
            if (!expiresAt.isAfter(now)) throw CommercialException.validation("expiresAt", "must be in the future");
        }

        PayToProvider provider = payTo.stream().filter(p -> p.supports(ctx.environment(), method)).findFirst()
                .orElseThrow(() -> new CommercialException(ErrorCode.METHOD_UNAVAILABLE,
                        "Account references are not available for " + method + " in this environment"));
        PayToProvider.PayTo target = provider.payTo(ctx.environment(), method);
        String wanted = spec.provider();
        if (wanted != null && !wanted.isBlank() && !wanted.equalsIgnoreCase("AUTO") && !wanted.equalsIgnoreCase(target.provider())) {
            throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE,
                    "Account references are not available for " + method + " via " + wanted);
        }

        AccountReference r = new AccountReference();
        r.setReference(prefixes.prefixFor(ctx.merchantId(), ctx.livemode()) + "-" + part.toUpperCase(Locale.ROOT));
        r.setMerchantId(ctx.merchantId());
        r.setAppId(ctx.appId());
        r.setLivemode(ctx.livemode());
        r.setMerchantReference(part);
        r.setExpectedAmount(expected);
        r.setCurrency(currency);
        r.setMethodType(method.name());
        r.setProvider(target.provider());
        r.setShortcode(target.shortcode());
        r.setExpiresAt(expiresAt);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        try {
            references.insert(r);
        } catch (DuplicateKeyException e) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST,
                    "An account reference already exists for merchantReference " + part);
        }
        return r.getReference();
    }
}

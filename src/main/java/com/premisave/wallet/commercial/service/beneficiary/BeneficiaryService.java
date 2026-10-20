package com.premisave.wallet.commercial.service.beneficiary;

import com.premisave.wallet.commercial.config.properties.CommercialBeneficiaryProperties;
import com.premisave.wallet.commercial.dto.beneficiary.BeneficiaryCheckRequest;
import com.premisave.wallet.commercial.dto.beneficiary.BeneficiaryViews;
import com.premisave.wallet.commercial.dto.beneficiary.LookupAccountRequest;
import com.premisave.wallet.commercial.dto.beneficiary.VerifyBeneficiaryRequest;
import com.premisave.wallet.commercial.entity.beneficiary.BeneficiaryCheck;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.repository.beneficiary.BeneficiaryCheckRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.EventService;
import com.premisave.wallet.commercial.service.EventTypes;
import com.premisave.wallet.commercial.service.MethodPolicy;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.NameMatcher;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Beneficiary verification (sections 7.1 to 7.3) and the check a payout can demand before any
 * money moves. It fails closed: a destination nobody can look up, or a provider that cannot be reached,
 * is never reported as verified.
 *
 * A provider answer is reused for the same merchant and destination for the cache window. The
 * answer is cached, not the comparison, so a different expectedName is judged afresh.
 */
@Service("commercialBeneficiaryService")
public class BeneficiaryService {

    static final String FORMAT_SOURCE = "FORMAT_CHECK";
    static final String NO_LOOKUP_SOURCE = "NO_LOOKUP_AVAILABLE";

    private final BeneficiaryCheckRepository checks;
    private final MongoTemplate mongo;
    private final List<BeneficiaryResolver> resolvers;
    private final CommercialBeneficiaryProperties props;
    private final EventService events;

    public BeneficiaryService(BeneficiaryCheckRepository checks, MongoTemplate mongo, List<BeneficiaryResolver> resolvers,
                              CommercialBeneficiaryProperties props, EventService events) {
        this.checks = checks;
        this.mongo = mongo;
        this.resolvers = resolvers;
        this.props = props;
        this.events = events;
    }

    /** 7.1 */
    public Map<String, Object> verify(CommercialContext ctx, VerifyBeneficiaryRequest req) {
        DestinationType type = RequestValidators.destinationType(req.destination());
        requireMethod(ctx, type);
        Map<String, Object> destination = RequestValidators.destination(type, req.destination());
        Direction direction = req.direction() != null ? req.direction() : Direction.PAYOUT;
        Performed p = perform(ctx, type, destination, clean(req.expectedName()), direction, false);
        Map<String, Object> view = BeneficiaryViews.check(p.check());
        events.emitDirect(EventTypes.BENEFICIARY_CHECK_COMPLETED, ctx.merchantId(), ctx.appId(), ctx.livemode(),
                p.check().getCheckId(), view,
                events.targetsFor(ctx.appId(), ctx.livemode(), EventTypes.BENEFICIARY_CHECK_COMPLETED, null, false));
        return view;
    }

    /** 7.2 */
    public Map<String, Object> lookupAccount(CommercialContext ctx, LookupAccountRequest req) {
        DestinationType type = RequestValidators.destinationType(req.destination());
        requireMethod(ctx, type);
        Map<String, Object> destination = RequestValidators.destination(type, req.destination());
        Performed p = perform(ctx, type, destination, null, Direction.PAYOUT, false);
        if (p.unsupported() || type == DestinationType.CRYPTO) {
            throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE,
                    "No account lookup is available for " + type + " in this environment");
        }
        return BeneficiaryViews.lookup(p.check());
    }

    /** 7.3 */
    public Map<String, Object> get(CommercialContext ctx, String checkId) {
        return BeneficiaryViews.check(checks.findByCheckIdAndMerchantIdAndLivemode(checkId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Beneficiary check")));
    }

    /**
     * The check a payout asks for (section 6.1). Returns null when no check was required or asked for.
     * Throws BENEFICIARY_NOT_VERIFIED, before anything is held, when a required check does not pass.
     */
    public BeneficiaryCheck gate(CommercialContext ctx, DestinationType type, Map<String, Object> destination,
                                 BeneficiaryCheckRequest bc) {
        boolean required = bc != null && bc.required() != null ? bc.required() : ctx.merchant().isRequireBeneficiaryCheck();
        String expected = bc == null ? null : clean(bc.expectedName());
        String checkId = bc == null ? null : clean(bc.checkId());
        if (!required && expected == null && checkId == null) return null;

        BeneficiaryCheck check;
        if (checkId != null) {
            check = reuse(ctx, checkId, destinationKey(type, destination), expected);
        } else {
            if (required && expected == null) {
                throw CommercialException.validation("beneficiaryCheck.expectedName",
                        "or checkId is required when the beneficiary check is required");
            }
            check = perform(ctx, type, destination, expected, Direction.PAYOUT, true).check();
        }
        if (required && !NameMatcher.Match.valueOf(check.getMatch()).passes()) {
            String why = switch (NameMatcher.Match.valueOf(check.getMatch())) {
                case MISMATCH -> check.isVerified() ? "The registered name does not match the expected name"
                        : "No account was found for this destination";
                case UNAVAILABLE -> "The beneficiary could not be verified for this destination";
                default -> "The beneficiary check did not compare a name";
            };
            throw new CommercialException(ErrorCode.BENEFICIARY_NOT_VERIFIED, why)
                    .with("check", BeneficiaryViews.summary(check));
        }
        return check;
    }

    // internals

    record Performed(BeneficiaryCheck check, boolean unsupported) {}

    private Performed perform(CommercialContext ctx, DestinationType type, Map<String, Object> destination,
                              String expectedName, Direction direction, boolean outageAsUnavailable) {
        Instant now = Instant.now();
        String key = destinationKey(type, destination);
        BeneficiaryCheck c = new BeneficiaryCheck();
        c.setCheckId(Ids.next("bc"));
        c.setMerchantId(ctx.merchantId());
        c.setAppId(ctx.appId());
        c.setLivemode(ctx.livemode());
        c.setDirection(direction.name());
        c.setDestinationType(type.name());
        c.setDestinationKey(key);
        c.setDestination(destination);
        c.setExpectedName(expectedName);
        c.setCreatedAt(now);
        Instant freshUntil = now.plus(props.cacheHours(), ChronoUnit.HOURS);
        boolean unsupported = false;

        if (type == DestinationType.CRYPTO) {
            // An address can be checked for shape only; there is no owner name to compare.
            c.setResolved(true);
            c.setVerified(true);
            c.setSource(FORMAT_SOURCE);
            c.setExpiresAt(freshUntil);
        } else {
            BeneficiaryCheck cached = cachedLookup(ctx, key, now);
            if (cached != null) {
                c.setResolved(true);
                c.setVerified(cached.isVerified());
                c.setRegisteredName(cached.getRegisteredName());
                c.setMatchName(cached.getMatchName());
                c.setSource(cached.getSource());
                c.setExpiresAt(cached.getExpiresAt());
                c.setCached(true);
            } else {
                BeneficiaryResolver resolver = resolvers.stream().filter(r -> r.supports(ctx.environment(), type)).findFirst().orElse(null);
                if (resolver == null) {
                    unsupported = true;
                    c.setSource(NO_LOOKUP_SOURCE);
                    c.setExpiresAt(now);
                } else {
                    try {
                        BeneficiaryResolver.Resolution r = resolver.resolve(ctx.environment(), type, destination);
                        c.setResolved(true);
                        c.setVerified(r.found());
                        c.setRegisteredName(r.displayName());
                        c.setMatchName(r.matchName());
                        c.setSource(r.source());
                        c.setExpiresAt(freshUntil);
                    } catch (RailException e) {
                        if (!outageAsUnavailable) {
                            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                                    "The lookup provider is unavailable; try again shortly");
                        }
                        c.setSource(NO_LOOKUP_SOURCE);
                        c.setExpiresAt(now);
                    }
                }
            }
        }
        c.setMatch(matchFor(c).name());
        checks.insert(c);
        return new Performed(c, unsupported);
    }

    /** What the check concluded: no answer is UNAVAILABLE, an unknown account is a MISMATCH only if a name was expected. */
    static NameMatcher.Match matchFor(BeneficiaryCheck c) {
        if (!c.isResolved()) return NameMatcher.Match.UNAVAILABLE;
        boolean named = c.getExpectedName() != null && !c.getExpectedName().isBlank();
        if (!c.isVerified()) return named ? NameMatcher.Match.MISMATCH : NameMatcher.Match.NOT_REQUESTED;
        return NameMatcher.compare(c.getExpectedName(), c.getMatchName());
    }

    private BeneficiaryCheck cachedLookup(CommercialContext ctx, String key, Instant now) {
        Criteria crit = Criteria.where("merchantId").is(ctx.merchantId()).and("livemode").is(ctx.livemode())
                .and("destinationKey").is(key).and("resolved").is(true).and("expiresAt").gt(now);
        List<BeneficiaryCheck> found = mongo.find(Query.query(crit).with(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"))),
                BeneficiaryCheck.class);
        return found.isEmpty() ? null : found.get(0);
    }

    private BeneficiaryCheck reuse(CommercialContext ctx, String checkId, String key, String expectedName) {
        BeneficiaryCheck c = checks.findByCheckIdAndMerchantIdAndLivemode(checkId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.validation("beneficiaryCheck.checkId", "was not found"));
        Instant now = Instant.now();
        if (!c.isResolved() || !c.getExpiresAt().isAfter(now)) {
            throw CommercialException.validation("beneficiaryCheck.checkId", "has expired or was not conclusive; verify the beneficiary again");
        }
        if (!key.equals(c.getDestinationKey())) {
            throw CommercialException.validation("beneficiaryCheck.checkId", "was made for a different destination");
        }
        if (expectedName == null || expectedName.equals(c.getExpectedName())) return c;

        BeneficiaryCheck again = new BeneficiaryCheck();
        again.setCheckId(Ids.next("bc"));
        again.setMerchantId(c.getMerchantId());
        again.setAppId(ctx.appId());
        again.setLivemode(c.isLivemode());
        again.setDirection(c.getDirection());
        again.setDestinationType(c.getDestinationType());
        again.setDestinationKey(c.getDestinationKey());
        again.setDestination(c.getDestination());
        again.setResolved(true);
        again.setVerified(c.isVerified());
        again.setRegisteredName(c.getRegisteredName());
        again.setMatchName(c.getMatchName());
        again.setSource(c.getSource());
        again.setCached(true);
        again.setExpectedName(expectedName);
        again.setCreatedAt(now);
        again.setExpiresAt(c.getExpiresAt());
        again.setMatch(matchFor(again).name());
        return checks.insert(again);
    }

    private void requireMethod(CommercialContext ctx, DestinationType type) {
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), type.method())) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, type.method() + " is not enabled for this merchant or app");
        }
    }

    /** The same destination always gives the same key, whatever order its fields arrive in. */
    static String destinationKey(DestinationType type, Map<String, Object> destination) {
        TreeMap<String, String> sorted = new TreeMap<>();
        destination.forEach((k, v) -> {
            if (!"type".equals(k) && v != null) sorted.put(k, String.valueOf(v).trim().toLowerCase(Locale.ROOT));
        });
        StringBuilder sb = new StringBuilder(type.name());
        sorted.forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        return Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

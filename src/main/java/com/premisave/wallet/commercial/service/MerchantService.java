package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.dto.ChangeStatusRequest;
import com.premisave.wallet.commercial.dto.CreateMerchantRequest;
import com.premisave.wallet.commercial.dto.LimitsDto;
import com.premisave.wallet.commercial.dto.SettlementDestinationDto;
import com.premisave.wallet.commercial.dto.UpdateConfigRequest;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.onboarding.MerchantNumberService;
import com.premisave.wallet.commercial.onboarding.MerchantNumbers;
import com.premisave.wallet.commercial.onboarding.OnboardingApplication;
import com.premisave.wallet.commercial.onboarding.OnboardingService;
import com.premisave.wallet.commercial.onboarding.OnboardingStatus;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.OnboardingApplicationRepository;
import com.premisave.wallet.commercial.util.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Merchant provisioning, driven by the developer service. Apps are owned by the developer service. */
@Service("commercialMerchantService")
public class MerchantService {

    private final CommercialProperties props;
    private final MerchantProfileRepository merchants;
    private final LedgerService ledger;
    private final MongoTemplate mongo;
    private final MerchantNumberService numbers;
    private final CommercialOnboardingProperties onboardingProps;
    private final OnboardingService onboarding;
    private final OnboardingApplicationRepository applications;

    public MerchantService(CommercialProperties props, MerchantProfileRepository merchants,
                           LedgerService ledger, MongoTemplate mongo, MerchantNumberService numbers,
                           CommercialOnboardingProperties onboardingProps, OnboardingService onboarding,
                           OnboardingApplicationRepository applications) {
        this.props = props;
        this.merchants = merchants;
        this.ledger = ledger;
        this.mongo = mongo;
        this.numbers = numbers;
        this.onboardingProps = onboardingProps;
        this.onboarding = onboarding;
        this.applications = applications;
    }

    /**
     * Adds a business to a developer account. An account can have many, but the same business (same name and country,
     * ignoring case and punctuation) cannot be added twice: that is answered with DUPLICATE_REQUEST and the merchant it
     * already is, which also makes a retry after a lost reply safe.
     */
    public Map<String, Object> create(CreateMerchantRequest req) {
        String key = BusinessKeys.of(req.businessName(), req.country());
        BusinessKeys.findSame(merchants.findByDeveloperAccountIdOrderByCreatedAtAsc(req.developerAccountId()), key)
                .ifPresent(existing -> {
                    throw duplicate(existing);
                });
        String currency = req.defaultCurrency() == null ? "USD" : req.defaultCurrency().toUpperCase(Locale.ROOT);
        if (!props.supportsCurrency(currency)) {
            throw CommercialException.validation("defaultCurrency", currency + " is not supported");
        }

        Instant now = Instant.now();
        MerchantProfile p = new MerchantProfile();
        p.setMerchantId(Ids.next("mer"));
        p.setMerchantNumber(numbers.next());
        p.setDeveloperAccountId(req.developerAccountId());
        p.setApplicationKey(key);
        p.setBusinessName(req.businessName().trim());
        p.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? req.businessName().trim().toUpperCase(Locale.ROOT) : req.displayName().trim());
        p.setCountry(req.country().toUpperCase(Locale.ROOT));
        p.setContactEmail(req.contactEmail().trim());
        p.setContactPhone(req.contactPhone());
        p.setDefaultCurrency(currency);
        p.setEnabledMethods(req.requestedMethods() == null || req.requestedMethods().isEmpty()
                ? EnumSet.noneOf(MethodType.class) : EnumSet.copyOf(req.requestedMethods()));
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p.setSandboxWalletId(ledger.createWallet(p.getMerchantId(), Environment.SANDBOX).getWalletId());
        p.setLiveWalletId(ledger.createWallet(p.getMerchantId(), Environment.LIVE).getWalletId());
        try {
            merchants.insert(p);
        } catch (DuplicateKeyException raced) {
            // Either two requests for the same business raced, or the database still has the old one-merchant-per-account
            // index on developerAccountId (see the migration note in DEVELOPER_SERVICE_SPLIT.md).
            throw BusinessKeys.findSame(merchants.findByDeveloperAccountIdOrderByCreatedAtAsc(req.developerAccountId()), key)
                    .map(MerchantService::duplicate)
                    .orElseThrow(() -> raced);
        }
        Map<String, Object> view = Views.merchant(p);
        if (onboardingProps.enabled()) {
            OnboardingApplication application = onboarding.createFor(p);
            view.put("onboarding", onboarding.applicantSummary(application));
        }
        return view;
    }

    /** Every business of a developer account, oldest first, each with where its onboarding application stands. */
    public Map<String, Object> listForDeveloper(String developerAccountId) {
        List<Map<String, Object>> businesses = new ArrayList<>();
        for (MerchantProfile p : merchants.findByDeveloperAccountIdOrderByCreatedAtAsc(developerAccountId)) {
            businesses.add(withOnboarding(numbers.ensure(p)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businesses", businesses);
        return out;
    }

    public Map<String, Object> get(String merchantId) {
        return withOnboarding(require(merchantId));
    }

    private Map<String, Object> withOnboarding(MerchantProfile p) {
        Map<String, Object> view = Views.merchant(p);
        applications.findByMerchantId(p.getMerchantId()).ifPresent(a -> view.put("onboarding", onboarding.applicantSummary(a)));
        return view;
    }

    public Map<String, Object> getByNumber(String merchantNumber) {
        String n = MerchantNumbers.normalise(merchantNumber);
        MerchantProfile p = merchants.findByMerchantNumber(n)
                .orElseThrow(() -> new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found"));
        return get(p.getMerchantId());
    }

    public Map<String, Object> changeStatus(String merchantId, ChangeStatusRequest req) {
        MerchantProfile p = require(merchantId);
        Environment env = parseEnvironment("environment", req.environment());
        MerchantStatus target = req.status();
        MerchantStatus current = p.statusFor(env);

        if (target == MerchantStatus.PENDING_APPROVAL) {
            throw CommercialException.validation("status", "PENDING_APPROVAL cannot be set");
        }
        if (current == MerchantStatus.CLOSED) {
            throw CommercialException.invalidState("A closed merchant environment cannot be changed");
        }
        if (env == Environment.LIVE && target == MerchantStatus.ACTIVE && current == MerchantStatus.PENDING_APPROVAL) {
            applications.findByMerchantId(merchantId).ifPresent(a -> {
                if (a.getStatus() != OnboardingStatus.APPROVED) {
                    throw CommercialException.invalidState(
                            "Production is switched on by approving the onboarding application, which is " + a.getStatus().name().toLowerCase(Locale.ROOT));
                }
            });
        }
        if (target != MerchantStatus.ACTIVE && (req.reason() == null || req.reason().isBlank())) {
            throw CommercialException.validation("reason", "is required when suspending or closing");
        }
        if (target == MerchantStatus.CLOSED) {
            MerchantWallet w = ledger.wallet(p.walletIdFor(env));
            if (w.totalCents() != 0) {
                throw CommercialException.invalidState("The wallet must have a zero balance before it is closed");
            }
            long open = mongo.count(Query.query(Criteria.where("merchantId").is(merchantId)
                    .and("livemode").is(env.livemode())
                    .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())), CommercialTransaction.class);
            if (open > 0) {
                throw CommercialException.invalidState("There are " + open + " transactions still in progress");
            }
            long disputes = mongo.count(Query.query(Criteria.where("merchantId").is(merchantId)
                    .and("livemode").is(env.livemode())
                    .and("status").in(com.premisave.wallet.commercial.dispute.Dispute.ACTIVE.stream().map(Enum::name).toList())),
                    com.premisave.wallet.commercial.dispute.Dispute.class);
            if (disputes > 0) {
                throw CommercialException.invalidState("There are " + disputes + " disputes still open");
            }
            long subscriptions = mongo.count(Query.query(Criteria.where("merchantId").is(merchantId)
                    .and("livemode").is(env.livemode())
                    .and("state").in(com.premisave.wallet.commercial.billing.Subscription.LIVE.stream().map(Enum::name).toList())),
                    com.premisave.wallet.commercial.billing.Subscription.class);
            if (subscriptions > 0) {
                throw CommercialException.invalidState("There are " + subscriptions + " subscriptions still running; cancel them first");
            }
        }
        p.setStatusFor(env, target);
        p.setUpdatedAt(Instant.now());
        return Views.merchant(merchants.save(p));
    }

    public Map<String, Object> updateConfig(String merchantId, UpdateConfigRequest req) {
        MerchantProfile p = require(merchantId);
        Environment env = parseEnvironment("environment", req.environment());

        if (req.displayName() != null && !req.displayName().isBlank()) p.setDisplayName(req.displayName().trim());
        if (req.defaultCurrency() != null) {
            String c = req.defaultCurrency().toUpperCase(Locale.ROOT);
            if (!props.supportsCurrency(c)) throw CommercialException.validation("defaultCurrency", c + " is not supported");
            p.setDefaultCurrency(c);
        }
        if (req.enabledMethods() != null) {
            p.setEnabledMethods(req.enabledMethods().isEmpty() ? EnumSet.noneOf(MethodType.class) : EnumSet.copyOf(req.enabledMethods()));
        }
        if (req.limits() != null) p.setLimitsFor(env, toLimits(req.limits()));
        if (req.feePlanId() != null && !req.feePlanId().isBlank()) p.setFeePlanId(req.feePlanId());
        if (req.requireBeneficiaryCheck() != null) p.setRequireBeneficiaryCheck(req.requireBeneficiaryCheck());
        if (req.settlementDestinations() != null) p.setDestinationsFor(env, toDestinations(req.settlementDestinations()));
        p.setUpdatedAt(Instant.now());
        return Views.merchant(merchants.save(p));
    }

    // Helpers

    private MerchantProfile require(String merchantId) {
        return numbers.ensure(merchants.findById(merchantId)
                .orElseThrow(() -> new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found")));
    }

    private static CommercialException duplicate(MerchantProfile existing) {
        return new CommercialException(ErrorCode.DUPLICATE_REQUEST, "This developer account already has this business")
                .with("merchantId", existing.getMerchantId());
    }

    /** Accepts live, sandbox or production (the header spelling). */
    public static Environment parseEnvironment(String field, String value) {
        if (value != null) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.equals("live")) return Environment.LIVE;
            var byHeader = Environment.fromHeaderValue(v);
            if (byHeader.isPresent()) return byHeader.get();
        }
        throw CommercialException.validation(field, "must be live, production or sandbox");
    }

    private static Limits toLimits(LimitsDto dto) {
        Limits l = new Limits();
        l.setSingleTransactionUsd(positive("limits.singleTransactionUsd", dto.singleTransactionUsd()));
        l.setDailyVolumeUsd(positive("limits.dailyVolumeUsd", dto.dailyVolumeUsd()));
        l.setMonthlyVolumeUsd(positive("limits.monthlyVolumeUsd", dto.monthlyVolumeUsd()));
        if (dto.dailyPayoutCount() != null && dto.dailyPayoutCount() < 1) {
            throw CommercialException.validation("limits.dailyPayoutCount", "must be at least 1");
        }
        l.setDailyPayoutCount(dto.dailyPayoutCount());
        return l;
    }

    private static BigDecimal positive(String field, BigDecimal v) {
        if (v != null && v.signum() <= 0) throw CommercialException.validation(field, "must be greater than zero");
        return v;
    }

    private static List<SettlementDestination> toDestinations(List<SettlementDestinationDto> dtos) {
        List<SettlementDestination> out = new ArrayList<>();
        for (SettlementDestinationDto d : dtos) {
            SettlementDestination s = new SettlementDestination();
            s.setId(d.id() == null || d.id().isBlank() ? Ids.next("dst") : d.id());
            s.setType(d.type());
            s.setLabel(d.label());
            s.setCountry(d.country() == null ? null : d.country().toUpperCase(Locale.ROOT));
            Map<String, String> details = new LinkedHashMap<>();
            if (d.phoneNumber() != null) details.put("phoneNumber", d.phoneNumber());
            if (d.accountNumber() != null) details.put("accountNumber", d.accountNumber());
            if (d.bankCode() != null) details.put("bankCode", d.bankCode());
            if (d.email() != null) details.put("email", d.email());
            s.setDetails(details);
            out.add(s);
        }
        return out;
    }
}

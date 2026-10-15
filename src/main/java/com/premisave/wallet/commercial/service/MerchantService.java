package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.dto.ChangeStatusRequest;
import com.premisave.wallet.commercial.dto.CreateAppRequest;
import com.premisave.wallet.commercial.dto.CreateMerchantRequest;
import com.premisave.wallet.commercial.dto.LimitsDto;
import com.premisave.wallet.commercial.dto.SettlementDestinationDto;
import com.premisave.wallet.commercial.dto.UpdateAppRequest;
import com.premisave.wallet.commercial.dto.UpdateConfigRequest;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.entity.MerchantApp;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantAppRepository;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.util.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.Set;

/** Merchant and app provisioning, driven by the developer service. */
@Service("commercialMerchantService")
public class MerchantService {

    private final CommercialProperties props;
    private final MerchantProfileRepository merchants;
    private final MerchantAppRepository apps;
    private final LedgerService ledger;
    private final MongoTemplate mongo;

    public MerchantService(CommercialProperties props, MerchantProfileRepository merchants,
                           MerchantAppRepository apps, LedgerService ledger, MongoTemplate mongo) {
        this.props = props;
        this.merchants = merchants;
        this.apps = apps;
        this.ledger = ledger;
        this.mongo = mongo;
    }

    public Map<String, Object> create(CreateMerchantRequest req) {
        merchants.findByDeveloperAccountId(req.developerAccountId()).ifPresent(existing -> {
            throw duplicate(existing);
        });
        String currency = req.defaultCurrency() == null ? "USD" : req.defaultCurrency().toUpperCase(Locale.ROOT);
        if (!props.supportsCurrency(currency)) {
            throw CommercialException.validation("defaultCurrency", currency + " is not supported");
        }

        Instant now = Instant.now();
        MerchantProfile p = new MerchantProfile();
        p.setMerchantId(Ids.next("mer"));
        p.setDeveloperAccountId(req.developerAccountId());
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
            throw duplicate(merchants.findByDeveloperAccountId(req.developerAccountId()).orElse(p));
        }
        return Views.merchant(p);
    }

    public Map<String, Object> get(String merchantId) {
        return Views.merchant(require(merchantId));
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

    public Map<String, Object> createApp(String merchantId, CreateAppRequest req) {
        MerchantProfile p = require(merchantId);
        Environment env = parseEnvironment("environment", req.environment());
        MerchantStatus status = p.statusFor(env);
        if (env == Environment.LIVE && status == MerchantStatus.PENDING_APPROVAL) {
            throw new CommercialException(ErrorCode.LIVE_NOT_ENABLED, "Production access has not been approved for this merchant");
        }
        if (status != MerchantStatus.ACTIVE) {
            throw new CommercialException(ErrorCode.MERCHANT_SUSPENDED, "The merchant is " + status.name().toLowerCase(Locale.ROOT) + " in this environment");
        }
        if (apps.existsById(req.appId())) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "An app with this id is already registered");
        }

        MerchantApp a = new MerchantApp();
        a.setAppId(req.appId());
        a.setMerchantId(merchantId);
        a.setEnvironment(env);
        a.setName(req.name().trim());
        a.setEnabledMethods(narrowedMethods(p, env, req.enabledMethods()));
        a.setLimits(narrowedLimits(p, env, req.limits()));
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        try {
            apps.insert(a);
        } catch (DuplicateKeyException raced) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "An app with this id is already registered");
        }
        return Views.app(a);
    }

    public Page<MerchantApp> listApps(String merchantId, String environment, AppStatus status, Pageable pageable) {
        require(merchantId);
        Environment env = environment == null || environment.isBlank() ? null : parseEnvironment("environment", environment);
        if (env != null && status != null) return apps.findByMerchantIdAndEnvironmentAndStatus(merchantId, env, status, pageable);
        if (env != null) return apps.findByMerchantIdAndEnvironment(merchantId, env, pageable);
        if (status != null) return apps.findByMerchantIdAndStatus(merchantId, status, pageable);
        return apps.findByMerchantId(merchantId, pageable);
    }

    public Map<String, Object> updateApp(String merchantId, String appId, UpdateAppRequest req) {
        MerchantProfile p = require(merchantId);
        MerchantApp a = apps.findById(appId).filter(x -> x.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new CommercialException(ErrorCode.APP_NOT_FOUND, "App not found"));
        if (req.name() != null && !req.name().isBlank()) a.setName(req.name().trim());
        if (req.status() != null) a.setStatus(req.status());
        if (req.enabledMethods() != null) a.setEnabledMethods(narrowedMethods(p, a.getEnvironment(), req.enabledMethods()));
        if (req.limits() != null) a.setLimits(narrowedLimits(p, a.getEnvironment(), req.limits()));
        a.setUpdatedAt(Instant.now());
        return Views.app(apps.save(a));
    }

    // Helpers

    private MerchantProfile require(String merchantId) {
        return merchants.findById(merchantId)
                .orElseThrow(() -> new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found"));
    }

    private static CommercialException duplicate(MerchantProfile existing) {
        return new CommercialException(ErrorCode.DUPLICATE_REQUEST, "A merchant already exists for this developer account")
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

    private static Set<MethodType> narrowedMethods(MerchantProfile p, Environment env, Set<MethodType> requested) {
        if (requested == null) return null;
        Set<MethodType> set = requested.isEmpty() ? EnumSet.noneOf(MethodType.class) : EnumSet.copyOf(requested);
        if (env == Environment.LIVE && !p.getEnabledMethods().containsAll(set)) {
            throw CommercialException.validation("enabledMethods", "an app cannot enable a method the merchant does not have");
        }
        return set;
    }

    private static Limits narrowedLimits(MerchantProfile p, Environment env, LimitsDto dto) {
        if (dto == null) return null;
        Limits l = toLimits(dto);
        if (!l.fitsWithin(p.limitsFor(env))) {
            throw CommercialException.validation("limits", "an app cannot exceed the merchant's limits");
        }
        return l;
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

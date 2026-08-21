package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Deposit dispatcher — the shared "can this wallet deposit right now" check
 * (lookup + frozen guard), then delegates to the provider-specific service
 * that actually knows how to talk to that provider. One per provider:
 * MpesaDepositService, StripeDepositService, PaypalDepositService,
 * FlutterwaveDepositService, and now NowPaymentsDepositService — mirroring
 * MpesaService/StripeService/PaypalService/FlutterwaveService/
 * NowPaymentsService at the API-integration layer one level down.
 *
 * WalletController and PaymentCallbackController call the provider-specific
 * services directly for everything except this top-level dispatch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final WalletRepository walletRepository;
    private final MongoTemplate mongoTemplate;
    private final MpesaDepositService mpesaDepositService;
    private final StripeDepositService stripeDepositService;
    private final PaypalDepositService paypalDepositService;
    private final FlutterwaveDepositService flutterwaveDepositService;
    private final NowPaymentsDepositService nowPaymentsDepositService;

    public PaymentResponse initiateDeposit(String userId, String userEmail, DepositRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen — deposits are not allowed until it is unfrozen");
        }

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";
        String idempotencyKey = UUID.randomUUID().toString();

        return switch (provider) {
            case "MPESA" -> mpesaDepositService.initiateMpesaDeposit(userId, request, wallet, idempotencyKey);
            case "MPESA_TILL" -> mpesaDepositService.initiateExpressCheckoutDeposit(userId, request, wallet);
            case "STRIPE" -> stripeDepositService.initiateStripeDeposit(userId, userEmail, request, wallet, idempotencyKey);
            case "PAYPAL" -> paypalDepositService.initiatePaypalDeposit(userId, request, wallet, idempotencyKey);
            case "FLUTTERWAVE" -> flutterwaveDepositService.initiateFlutterwaveDeposit(userId, userEmail, request, wallet, idempotencyKey);
            case "NOWPAYMENTS" -> nowPaymentsDepositService.initiateNowPaymentsDeposit(userId, request, wallet, idempotencyKey);
            default -> new PaymentResponse(false, null, "Unsupported deposit provider: " + provider);
        };
    }

    /** GET /deposits/history — every deposit for this user, across all five providers, newest first. */
    public List<DepositRecordResponse> getDepositHistory(String userId) {
        return getDepositHistory(userId, null, null, null, null);
    }

    /**
     * Filtered version — status/provider/date-range all optional. Built
     * with a dynamic Criteria rather than a derived repository query
     * method, since Spring Data's method-name derivation doesn't scale to
     * "any combination of optional filters" without a combinatorial
     * explosion of method signatures. With every filter left null, this
     * produces the identical result set the unfiltered overload above
     * always has — no behavior change for existing callers.
     */
    public List<DepositRecordResponse> getDepositHistory(String userId, DepositStatus status, String provider,
                                                           LocalDate fromDate, LocalDate toDate) {
        Criteria criteria = Criteria.where("userId").is(userId);
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (provider != null && !provider.isBlank()) {
            criteria = criteria.and("provider").is(provider.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, Deposit.class).stream()
                .map(DepositService::toRecordResponse)
                .toList();
    }

    /** Admin-only: every deposit across every user, paginated — see AdminFinanceController. */
    public Page<DepositRecordResponse> getAllDeposits(Pageable pageable) {
        return getAllDeposits(null, null, null, null, null, pageable);
    }

    /**
     * Filtered version — userId/status/provider/date-range all optional.
     * Same dynamic-Criteria approach as DisbursementService's own
     * getAllDisbursements filtering, mirrored here: starts from an empty
     * Criteria() (same proven pattern from AdminReportService.
     * getDailyReport), genuinely paginated with an accurate total count
     * via a separate mongoTemplate.count call rather than just this
     * page's own content size. With every filter left null, produces the
     * identical result set the unfiltered overload above always has.
     */
    public Page<DepositRecordResponse> getAllDeposits(String userId, DepositStatus status, String provider,
                                                       LocalDate fromDate, LocalDate toDate, Pageable pageable) {
        Criteria criteria = new Criteria();
        if (userId != null && !userId.isBlank()) {
            criteria = criteria.and("userId").is(userId);
        }
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (provider != null && !provider.isBlank()) {
            criteria = criteria.and("provider").is(provider.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        long total = mongoTemplate.count(new Query(criteria), Deposit.class);
        Query pagedQuery = new Query(criteria).with(pageable);
        List<DepositRecordResponse> content = mongoTemplate.find(pagedQuery, Deposit.class).stream()
                .map(DepositService::toRecordResponse)
                .toList();

        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    private static DepositRecordResponse toRecordResponse(Deposit d) {
        DepositRecordResponse r = new DepositRecordResponse();
        r.setId(d.getId());
        r.setUserId(d.getUserId());
        r.setAmount(d.getAmount());
        r.setCurrency(d.getCurrency());
        r.setProvider(d.getProvider());
        r.setChannel(d.getChannel());
        r.setSource(d.getSource());
        r.setStatus(d.getStatus());
        r.setReference(d.getReference());
        r.setProviderReference(d.getProviderReference());
        r.setFailureReason(d.getFailureReason());
        r.setPayAddress(d.getPayAddress());
        r.setPayAmount(d.getPayAmount());
        r.setPayCurrency(d.getPayCurrency());
        r.setPriceAmount(d.getPriceAmount());
        r.setPriceCurrency(d.getPriceCurrency());
        r.setCreatedAt(d.getCreatedAt());
        return r;
    }
}
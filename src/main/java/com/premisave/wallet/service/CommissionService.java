package com.premisave.wallet.service;

import com.premisave.wallet.config.CommissionConfig;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.repository.CompanyLedgerRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Computes and records commission the company takes as a cut of user
 * money movements — shared by TransferService (internalTransferRate) and,
 * eventually, each provider-specific disbursement service (gatewayRate).
 * Centralized here rather than duplicated per caller, since the actual
 * math and the CompanyLedgerEntry shape are identical regardless of
 * which rate or which source triggered it.
 *
 * ADDED ON TOP, confirmed explicitly: the commission is charged in
 * addition to the stated transfer/withdrawal amount — the
 * sender/withdrawer pays amount + commission, while the recipient (or,
 * for a disbursement, the external gateway destination) receives the
 * full, unaffected, originally-requested amount. This is why every
 * caller needs to debit amount.add(commission) from the payer's wallet
 * while still sending/crediting the original, unmodified amount
 * everywhere else.
 *
 * NOT credited to any real company wallet — confirmed explicitly. This
 * only ever writes a CompanyLedgerEntry for reporting; no Wallet balance
 * is touched by this class at all.
 */
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final CommissionConfig commissionConfig;
    private final CompanyLedgerRepository companyLedgerRepository;
    private final MongoTemplate mongoTemplate;

    public BigDecimal getInternalTransferRate() {
        return commissionConfig.getInternalTransferRate();
    }

    public BigDecimal getGatewayRate() {
        return commissionConfig.getGatewayRate();
    }

    public BigDecimal calculateInternalTransferCommission(BigDecimal amount) {
        return calculate(amount, commissionConfig.getInternalTransferRate());
    }

    public BigDecimal calculateGatewayCommission(BigDecimal amount) {
        return calculate(amount, commissionConfig.getGatewayRate());
    }

    private BigDecimal calculate(BigDecimal amount, BigDecimal rate) {
        if (rate == null) {
            throw new IllegalStateException(
                    "Commission rate is not configured — check commission.internal-transfer-rate / "
                            + "commission.gateway-rate in application.yml");
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Records a commission entry in the company ledger. Does NOT touch
     * any Wallet balance — purely a reporting record. Call this AFTER the
     * actual money movement has already succeeded, same "record only
     * once the real event happened" ordering already used for every
     * other entity in this codebase.
     *
     */
    public void recordCommission(String type, BigDecimal commissionAmount, BigDecimal rate, BigDecimal grossAmount,
                                  String sourceType, String sourceId, String sourceReference, String userId,
                                  String description, Currency currency) {
        CompanyLedgerEntry entry = new CompanyLedgerEntry();
        entry.setAmount(commissionAmount);
        entry.setCurrency(currency);
        entry.setType(type);
        entry.setDescription(description);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setSourceReference(sourceReference);
        entry.setRateApplied(rate);
        entry.setGrossAmount(grossAmount);
        entry.setUserId(userId);
        companyLedgerRepository.save(entry);
    }

    /**
     * Convenience wrapper for recording gateway commission from a
     * confirmed Disbursement — derives the commission amount from
     * totalDebited minus amount rather than requiring each of the five
     * provider-specific completeXDisbursement methods to separately
     * extract and pass the same fields. No-ops cleanly for a
     * Disbursement that never had commission computed at all (admin
     * B2B/B2C top-up, which deliberately don't carry it — see
     * MpesaDisbursementService — or any Disbursement created before this
     * field existed), rather than throwing on a null totalDebited.
     */
    public void recordGatewayCommissionFromDisbursement(Disbursement d) {
        if (d.getTotalDebited() == null || d.getCommissionRate() == null) {
            return;
        }
        BigDecimal commission = d.getTotalDebited().subtract(d.getAmount());
        if (commission.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        recordCommission("COMMISSION_DISBURSEMENT", commission, d.getCommissionRate(), d.getAmount(),
                "DISBURSEMENT", d.getId(), d.getReference(), d.getUserId(),
                "Commission on " + d.getProvider() + " disbursement to " + d.getDestination(),
                Currency.valueOf(d.getCurrency()));
    }

    /**
     * Admin-only: every ledger entry, paginated — see AdminFinanceController.
     * This is the only way to actually see the P&L data recorded by every
     * transfer/disbursement commission until now — CompanyLedgerRepository
     * has existed since Stage 1, but nothing exposed it through the API at
     * all before this.
     */
    public Page<CompanyLedgerEntry> getAllLedgerEntries(Pageable pageable) {
        return getAllLedgerEntries(null, null, null, null, pageable);
    }

    /**
     * Filtered version — userId/type/date-range all optional. Same
     * dynamic-Criteria approach as DepositService/DisbursementService's
     * own admin filtering: starts from an empty Criteria(), genuinely
     * paginated with an accurate total count via a separate
     * mongoTemplate.count call. type is the ledger's primary
     * categorization dimension (COMMISSION_TRANSFER,
     * COMMISSION_DISBURSEMENT, COMPANY_DISBURSEMENT, etc.) — the natural
     * equivalent of "status" on the other admin-filtered endpoints. With
     * every filter left null, produces the identical result set the
     * unfiltered overload above always has.
     */
    public Page<CompanyLedgerEntry> getAllLedgerEntries(String userId, String type, LocalDate fromDate,
                                                         LocalDate toDate, Pageable pageable) {
        Criteria criteria = new Criteria();
        if (userId != null && !userId.isBlank()) {
            criteria = criteria.and("userId").is(userId);
        }
        if (type != null && !type.isBlank()) {
            criteria = criteria.and("type").is(type.toUpperCase());
        }
        criteria = DateRangeCriteriaUtil.applyDateRange(criteria, "createdAt", fromDate, toDate);

        long total = mongoTemplate.count(new Query(criteria), CompanyLedgerEntry.class);
        Query pagedQuery = new Query(criteria).with(pageable);
        List<CompanyLedgerEntry> content = mongoTemplate.find(pagedQuery, CompanyLedgerEntry.class);

        return new PageImpl<>(content, pageable, total);
    }
}
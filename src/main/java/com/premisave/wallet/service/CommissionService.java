package com.premisave.wallet.service;

import com.premisave.wallet.config.CommissionConfig;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.repository.CompanyLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
     */
    public void recordCommission(String type, BigDecimal commissionAmount, BigDecimal rate, BigDecimal grossAmount,
                                  String sourceType, String sourceId, String sourceReference, String userId,
                                  String description) {
        CompanyLedgerEntry entry = new CompanyLedgerEntry();
        entry.setAmount(commissionAmount);
        entry.setCurrency(Currency.KES);
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
                "Commission on " + d.getProvider() + " disbursement to " + d.getDestination());
    }
}
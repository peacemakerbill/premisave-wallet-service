package com.premisave.wallet.service;

import com.premisave.wallet.dto.DailyFinanceReportResponse;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the comprehensive daily financial report — GET
 * /admin/finance/reports/daily (see AdminFinanceController). Cross-entity
 * aggregation genuinely doesn't belong to any single one of
 * DepositService/DisbursementService/TransferService/PaymentService/
 * CommissionService, so it lives here instead, injecting MongoTemplate
 * directly rather than going through each service's own methods (which
 * return DTOs already shaped for history display, not raw entities
 * convenient for aggregation).
 *
 * Uses simple Java-stream grouping/summing over a single day's records
 * rather than a native MongoDB aggregation pipeline — a day's volume is
 * expected to be a small, bounded dataset, and this is easier to read
 * and verify correct than the more verbose Aggregation/GroupOperation
 * API. Worth revisiting if daily volume ever grows large enough for that
 * tradeoff to flip.
 */
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final MongoTemplate mongoTemplate;

    public DailyFinanceReportResponse getDailyReport(LocalDate date) {
        Criteria dayRange = DateRangeCriteriaUtil.applyDateRange(new Criteria(), "createdAt", date, date);

        List<Deposit> deposits = mongoTemplate.find(new Query(dayRange), Deposit.class);
        List<Disbursement> disbursements = mongoTemplate.find(new Query(dayRange), Disbursement.class);
        List<Transfer> transfers = mongoTemplate.find(new Query(dayRange), Transfer.class);
        List<Payment> payments = mongoTemplate.find(new Query(dayRange), Payment.class);
        List<CompanyLedgerEntry> ledgerEntries = mongoTemplate.find(new Query(dayRange), CompanyLedgerEntry.class);

        DailyFinanceReportResponse report = new DailyFinanceReportResponse();
        report.setDate(date);

        // ─── Deposits ───
        report.setDepositCount(deposits.size());
        report.setTotalDepositAmount(sumAmounts(deposits, Deposit::getAmount));
        report.setDepositAmountByProvider(groupAndSum(deposits, Deposit::getProvider, Deposit::getAmount));
        report.setDepositCountByStatus(deposits.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));

        // ─── Disbursements ───
        report.setDisbursementCount(disbursements.size());
        report.setTotalDisbursementAmount(sumAmounts(disbursements, Disbursement::getAmount));
        report.setTotalDisbursementDebited(sumAmounts(disbursements,
                d -> d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount()));
        report.setDisbursementAmountByProvider(groupAndSum(disbursements, Disbursement::getProvider, Disbursement::getAmount));
        report.setDisbursementCountByStatus(disbursements.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));

        // ─── Transfers ───
        report.setTransferCount(transfers.size());
        report.setTotalTransferAmount(sumAmounts(transfers, Transfer::getAmount));
        report.setTotalTransferCommission(sumAmounts(transfers,
                t -> t.getTotalDebited() != null ? t.getTotalDebited().subtract(t.getAmount()) : BigDecimal.ZERO));

        // ─── Payments ───
        report.setPaymentCount(payments.size());
        report.setTotalPaymentAmount(sumAmounts(payments, Payment::getAmount));
        report.setPaymentAmountByService(groupAndSum(payments, Payment::getService, Payment::getAmount));

        // ─── Company revenue — the actual "how much did we make today" answer ───
        BigDecimal commissionFromTransfers = ledgerEntries.stream()
                .filter(e -> "COMMISSION_TRANSFER".equals(e.getType()))
                .map(CompanyLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissionFromDisbursements = ledgerEntries.stream()
                .filter(e -> "COMMISSION_DISBURSEMENT".equals(e.getType()))
                .map(CompanyLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        report.setTotalCommissionFromTransfers(commissionFromTransfers);
        report.setTotalCommissionFromDisbursements(commissionFromDisbursements);
        report.setTotalCommissionRevenue(commissionFromTransfers.add(commissionFromDisbursements));

        return report;
    }

    private <T> BigDecimal sumAmounts(List<T> items, java.util.function.Function<T, BigDecimal> extractor) {
        return items.stream()
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private <T> Map<String, BigDecimal> groupAndSum(List<T> items, java.util.function.Function<T, String> keyFn,
                                                      java.util.function.Function<T, BigDecimal> amountFn) {
        return items.stream()
                .filter(item -> keyFn.apply(item) != null)
                .collect(Collectors.groupingBy(keyFn,
                        Collectors.reducing(BigDecimal.ZERO, amountFn, BigDecimal::add)));
    }
}
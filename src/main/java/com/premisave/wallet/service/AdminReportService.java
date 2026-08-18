package com.premisave.wallet.service;

import com.premisave.wallet.dto.BalanceOverviewResponse;
import com.premisave.wallet.dto.DailyFinanceReportResponse;
import com.premisave.wallet.dto.SystemSummaryResponse;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.repository.WalletRepository;
import com.premisave.wallet.util.DateRangeCriteriaUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final WalletRepository walletRepository;

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

    /**
     * Detailed platform balance snapshot — GET
     * /admin/finance/reports/balance-overview. Fetches every Wallet via
     * findAll() and computes statistics in Java, same "small enough
     * dataset, favor readability over a native aggregation pipeline"
     * tradeoff as getDailyReport above — but worth flagging this one
     * scales differently: wallet COUNT only grows over the platform's
     * whole lifetime, unlike a single day's transactional volume, so this
     * is the more likely of the two to eventually need a real MongoDB
     * aggregation pipeline instead, once the user base is large enough
     * for findAll() over every wallet to become a real cost.
     */
    public BalanceOverviewResponse getBalanceOverview(int topN) {
        List<Wallet> wallets = walletRepository.findAll();

        BalanceOverviewResponse r = new BalanceOverviewResponse();
        r.setTotalWalletCount(wallets.size());
        r.setTotalPlatformBalance(sumBalances(wallets));

        List<Wallet> active = wallets.stream().filter(w -> !w.isFrozen()).toList();
        List<Wallet> frozen = wallets.stream().filter(Wallet::isFrozen).toList();

        r.setActiveWalletCount(active.size());
        r.setActiveBalance(sumBalances(active));
        r.setFrozenWalletCount(frozen.size());
        r.setFrozenBalance(sumBalances(frozen));

        r.setAverageBalance(wallets.isEmpty()
                ? BigDecimal.ZERO
                : r.getTotalPlatformBalance().divide(BigDecimal.valueOf(wallets.size()), 2, RoundingMode.HALF_UP));

        r.setWalletsWithStripeCustomer(wallets.stream().filter(w -> w.getStripeCustomerId() != null).count());
        r.setWalletsWithStripeConnectedAccount(wallets.stream().filter(w -> w.getStripeConnectedAccountId() != null).count());
        r.setWalletsWithPaypalLinked(wallets.stream().filter(w -> w.getPaypalVaultId() != null).count());
        r.setWalletsWithMpesaPhoneLinked(wallets.stream().filter(w -> w.getMpesaPhoneNumber() != null).count());
        r.setWalletsWithFlutterwaveLinked(wallets.stream().filter(w -> w.getFlutterwaveCustomerId() != null).count());

        List<CompanyLedgerEntry> allLedgerEntries = mongoTemplate.findAll(CompanyLedgerEntry.class);
        BigDecimal totalRevenue = allLedgerEntries.stream()
                .map(CompanyLedgerEntry::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        r.setTotalCommissionRevenueAllTime(totalRevenue);

        List<BalanceOverviewResponse.TopWalletEntry> top = wallets.stream()
                .filter(w -> w.getBalance() != null)
                .sorted(Comparator.comparing(Wallet::getBalance).reversed())
                .limit(Math.max(topN, 0))
                .map(w -> {
                    BalanceOverviewResponse.TopWalletEntry entry = new BalanceOverviewResponse.TopWalletEntry();
                    entry.setAccountNumber(w.getAccountNumber());
                    entry.setBalance(w.getBalance());
                    return entry;
                })
                .toList();
        r.setTopWalletsByBalance(top);

        return r;
    }

    /**
     * All-time platform summary — GET /admin/finance/reports/summary.
     * Mostly orchestration, not new logic: reuses sumAmounts/groupAndSum/
     * sumBalances, the same helpers getDailyReport and getBalanceOverview
     * already use, just without a date filter (findAll() per entity
     * rather than a single day's Criteria range).
     *
     * Likely the most expensive of the three admin reports to run at real
     * scale — spans every record ever created across five entities, not
     * one day's activity (getDailyReport) or current wallet state
     * (getBalanceOverview). Same "small enough today, revisit with a real
     * aggregation pipeline once it isn't" flag as the other two.
     */
    public SystemSummaryResponse getSystemSummary() {
        List<Wallet> wallets = walletRepository.findAll();
        List<Deposit> deposits = mongoTemplate.findAll(Deposit.class);
        List<Disbursement> disbursements = mongoTemplate.findAll(Disbursement.class);
        List<Transfer> transfers = mongoTemplate.findAll(Transfer.class);
        List<Payment> payments = mongoTemplate.findAll(Payment.class);
        List<CompanyLedgerEntry> ledgerEntries = mongoTemplate.findAll(CompanyLedgerEntry.class);

        SystemSummaryResponse s = new SystemSummaryResponse();

        // Wallets
        List<Wallet> active = wallets.stream().filter(w -> !w.isFrozen()).toList();
        List<Wallet> frozen = wallets.stream().filter(Wallet::isFrozen).toList();
        s.setTotalWalletCount(wallets.size());
        s.setActiveWalletCount(active.size());
        s.setFrozenWalletCount(frozen.size());
        s.setTotalPlatformBalance(sumBalances(wallets));

        // Deposits
        s.setTotalDepositCount(deposits.size());
        s.setTotalDepositVolume(sumAmounts(deposits, Deposit::getAmount));
        s.setDepositCountByStatus(deposits.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));
        s.setDepositVolumeByProvider(groupAndSum(deposits, Deposit::getProvider, Deposit::getAmount));

        // Disbursements
        s.setTotalDisbursementCount(disbursements.size());
        s.setTotalDisbursementVolume(sumAmounts(disbursements, Disbursement::getAmount));
        s.setTotalDisbursementDebited(sumAmounts(disbursements,
                d -> d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount()));
        s.setDisbursementCountByStatus(disbursements.stream()
                .collect(Collectors.groupingBy(d -> d.getStatus().name(), Collectors.counting())));
        s.setDisbursementVolumeByProvider(groupAndSum(disbursements, Disbursement::getProvider, Disbursement::getAmount));

        // Transfers
        s.setTotalTransferCount(transfers.size());
        s.setTotalTransferVolume(sumAmounts(transfers, Transfer::getAmount));
        s.setTotalTransferCommission(sumAmounts(transfers,
                t -> t.getTotalDebited() != null ? t.getTotalDebited().subtract(t.getAmount()) : BigDecimal.ZERO));

        // Payments
        s.setTotalPaymentCount(payments.size());
        s.setTotalPaymentVolume(sumAmounts(payments, Payment::getAmount));
        s.setPaymentVolumeByService(groupAndSum(payments, Payment::getService, Payment::getAmount));

        // Company revenue
        BigDecimal commissionFromTransfers = ledgerEntries.stream()
                .filter(e -> "COMMISSION_TRANSFER".equals(e.getType()))
                .map(CompanyLedgerEntry::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissionFromDisbursements = ledgerEntries.stream()
                .filter(e -> "COMMISSION_DISBURSEMENT".equals(e.getType()))
                .map(CompanyLedgerEntry::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        s.setTotalCommissionFromTransfers(commissionFromTransfers);
        s.setTotalCommissionFromDisbursements(commissionFromDisbursements);
        s.setTotalCommissionRevenue(commissionFromTransfers.add(commissionFromDisbursements));

        return s;
    }

    private BigDecimal sumBalances(List<Wallet> wallets) {
        return wallets.stream()
                .map(Wallet::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
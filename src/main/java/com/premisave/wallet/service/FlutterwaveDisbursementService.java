package com.premisave.wallet.service;

import com.premisave.wallet.config.FlutterwaveConfig;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Flutterwave disbursement logic — split out of DisbursementService,
 * mirroring FlutterwaveDepositService's role on the deposit side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final FlutterwaveService flutterwaveService;
    private final FlutterwaveConfig flutterwaveConfig;
    private final DisbursementTransactionRecorder transactionRecorder;

    /**
     * Called from DisbursementService.processDisbursement via early
     * return for provider=FLUTTERWAVE — self-contained, same shape as
     * MpesaDisbursementService.disburseMpesa, rather than routing through
     * the shared ProviderResult pattern PayPal/Stripe/NOWPayments use.
     * A transfer destination is two fields (account_bank + account_number),
     * not a single "destination" string — same reason M-Pesa gets its own
     * early return too.
     *
     * Same not-debited-until-confirmed pattern as every other provider —
     * Flutterwave transfers resolve asynchronously via the
     * transfer.disburse webhook (see completeFlutterwaveDisbursement).
     *
     * Branches on flutterwaveTransferType (validated below):
     *  - MOBILE_MONEY → FlutterwaveService.initiateTransfer (msisdn/network body)
     *  - BANK         → FlutterwaveService.initiateBankTransfer (bank code/account_number body)
     * These are genuinely different request shapes — routing a bank account
     * number through the mobile-money body (as msisdn) would either fail
     * outright or, worse, silently route to a wrong/unintended mobile
     * wallet if the digits happen to parse as a valid MSISDN.
     *
     * destination_currency comes from the request (defaults to KES).
     * source_currency comes from FlutterwaveConfig.transfer.sourceCurrency
     * — this is your Flutterwave balance's actual currency, which is NOT
     * necessarily the same as destinationCurrency (confirm this against
     * your dashboard balance before relying on it in production).
     */
    public DisbursementResponse processFlutterwaveDisbursement(String userId, Wallet wallet,
                                                                  DisbursementRequest request) {
        if (request.getFlutterwaveAccountBank() == null || request.getFlutterwaveAccountBank().isBlank()) {
            throw new IllegalArgumentException("flutterwaveAccountBank is required for FLUTTERWAVE disbursements");
        }
        if (request.getFlutterwaveAccountNumber() == null || request.getFlutterwaveAccountNumber().isBlank()) {
            throw new IllegalArgumentException("flutterwaveAccountNumber is required for FLUTTERWAVE disbursements");
        }
        String transferType = request.getFlutterwaveTransferType() != null
                ? request.getFlutterwaveTransferType().toUpperCase() : null;
        if (!"BANK".equals(transferType) && !"MOBILE_MONEY".equals(transferType)) {
            throw new IllegalArgumentException(
                    "flutterwaveTransferType must be BANK or MOBILE_MONEY for FLUTTERWAVE disbursements");
        }

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        // For MOBILE_MONEY: flutterwaveAccountBank = network code (e.g. "Mpesa", "MTN"),
        //                    flutterwaveAccountNumber = msisdn (must include country code).
        // For BANK:         flutterwaveAccountBank = bank code,
        //                    flutterwaveAccountNumber = account number.
        String displayDestination = request.getFlutterwaveAccountBank() + "-" + request.getFlutterwaveAccountNumber();

        // Parse beneficiary name into first/last
        String beneficiaryName = request.getFlutterwaveBeneficiaryName();
        String firstName = "";
        String lastName = "";
        if (beneficiaryName != null && !beneficiaryName.isBlank()) {
            String[] parts = beneficiaryName.trim().split("\\s+", 2);
            firstName = parts[0];
            lastName = parts.length > 1 ? parts[1] : "";
        }

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setDestination(displayDestination);
        disbursement.setProvider("FLUTTERWAVE");
        disbursement.setChannel("FLUTTERWAVE_" + transferType);
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);
        disbursement.setCurrency(Currency.KES);

        // destination_currency = what the recipient actually receives in.
        String destinationCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "KES";
        // source_currency = the currency your Flutterwave balance actually holds
        // — see FlutterwaveConfig.Transfer.sourceCurrency javadoc.
        String sourceCurrency = flutterwaveConfig.getTransfer().getSourceCurrency();

        try {
            FlutterwaveService.TransferResult result;
            if ("BANK".equals(transferType)) {
                result = flutterwaveService.initiateBankTransfer(
                        request.getFlutterwaveAccountNumber(), request.getFlutterwaveAccountBank(),
                        sourceCurrency, destinationCurrency, request.getAmount(), reference,
                        request.getRemarks(), firstName, lastName);
            } else {
                result = flutterwaveService.initiateTransfer(
                        request.getFlutterwaveAccountNumber(), request.getFlutterwaveAccountBank(),
                        sourceCurrency, destinationCurrency, request.getAmount(), reference,
                        request.getRemarks(), firstName, lastName);
            }

            log.info("Flutterwave disbursement: userId={} reference={} type={} amount={} destination={}",
                    userId, reference, transferType, request.getAmount(), displayDestination);

            if (!result.success()) {
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason(result.message());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.message());
            }

            disbursement.setProviderReference(result.transferId());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    "Disbursement queued with Flutterwave — your wallet will be debited once Flutterwave confirms the payout.");

        } catch (Exception e) {
            log.error("Flutterwave transfer threw: userId={} reference={}", userId, reference, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("Flutterwave transfer initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }
    }

    /**
     * Reconciliation from Flutterwave's transfer.disburse webhook. Keyed
     * by transferId (Flutterwave's own numeric id, stored as
     * providerReference at initiation) rather than our own reference,
     * since that's what the webhook payload's data.id carries — see
     * PaymentCallbackController.flutterwaveWebhook.
     */
    @Transactional
    public void completeFlutterwaveDisbursement(String transferId, boolean success, String statusDesc) {
        Disbursement d = disbursementRepository.findByProviderReference(transferId).orElse(null);
        if (d == null) {
            log.warn("Flutterwave transfer webhook for unknown transferId={} — ignoring", transferId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("Flutterwave transfer webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                // First touch of the wallet for this disbursement — same
                // negative-balance handling as every other provider's
                // completeXDisbursement here.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal newBalance = wallet.getBalance().subtract(d.getAmount());
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed Flutterwave disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                transactionRecorder.record(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            } else {
                disbursementRepository.save(d);
            }

            log.info("Flutterwave disbursement completed: id={} transferId={}", d.getId(), transferId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING Flutterwave disbursement (see processFlutterwaveDisbursement above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(statusDesc);
            disbursementRepository.save(d);
            log.warn("Flutterwave disbursement failed: id={} transferId={} reason={}", d.getId(), transferId, statusDesc);
        }
    }
}
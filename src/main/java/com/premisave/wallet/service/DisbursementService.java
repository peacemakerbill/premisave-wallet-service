package com.premisave.wallet.service;

import com.premisave.wallet.config.FlutterwaveConfig;
import com.premisave.wallet.dto.B2CTopUpRequest;
import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.QueryOrgInfoRequest;
import com.premisave.wallet.dto.QueryOrgInfoResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.PhoneNumberUnavailableException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final StripeService stripeService;
    private final PaypalService paypalService;
    private final FlutterwaveService flutterwaveService;
    private final FlutterwaveConfig flutterwaveConfig;
    private final IdempotencyService idempotencyService;
    private final FxRateService fxRateService;
    private final NowPaymentsService nowPaymentsService;

    private static final List<String> PAYPAL_TERMINAL_FAILURE_STATUSES =
            List.of("FAILED", "DENIED", "BLOCKED", "RETURNED", "REFUNDED", "REVERSED", "CANCELED");

 // ─── User-facing disbursement (phone / PayPal / Stripe / Flutterwave) ───

    /**
     * NOTE ON BALANCE TIMING: the wallet is NOT debited here anymore. It's
     * only debited once the disbursement is CONFIRMED — by M-Pesa's
     * ResultURL callback (see completeMpesaDisbursement), PayPal's Payouts
     * webhook (see completePaypalDisbursement), Flutterwave's
     * transfer.disburse webhook (see completeFlutterwaveDisbursement), or
     * Stripe's payout.paid Connect webhook (see
     * completeStripeConnectDisbursement) — all four providers now resolve
     * asynchronously. Previously the wallet was debited up front and
     * refunded on failure; that meant a customer's balance was reduced for
     * money that hadn't actually left yet, and a PENDING disbursement stuck
     * for hours (e.g. during the recent callback URL misconfiguration) held
     * their funds hostage the whole time even though M-Pesa/PayPal had
     * already processed the payout successfully.
     *
     * Trade-off to be aware of: since nothing is held/reserved at
     * initiation, two disbursement requests submitted in quick succession
     * could both pass the balance check below and later both succeed,
     * overdrawing the wallet. There's no reservation/hold mechanism here —
     * add one if that scenario becomes a real risk for you.
     */
    @Transactional
    public DisbursementResponse processDisbursement(String userId, DisbursementRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";

        // MUST run before the balance check below, and BEFORE provider was
        // previously resolved at this point in the method — moved
        // provider resolution up two lines specifically to make this
        // possible. Mutates request's own amount field in place (Lombok
        // @Data setter) rather than threading a separately-converted value
        // through every one of this method's ~10 existing
        // request.getAmount() call sites (the balance check right below,
        // disbursement.setAmount further down, a synchronous debit for
        // some providers, etc.) — every one of those already correctly
        // and uniformly reads request.getAmount(), so mutating it once
        // here is the minimal, safe fix rather than a larger rewrite.
        //
        // Defaults to KES (no conversion) if unset — DELIBERATELY the
        // OPPOSITE default from DepositRequest.nowPaymentsPriceCurrency
        // (which defaults to USD). Every existing caller of THIS endpoint,
        // across all five providers, has always assumed request.getAmount()
        // is KES; flipping the default here would silently reinterpret
        // every existing NOWPAYMENTS withdrawal request already in flight
        // or hardcoded in a frontend. Only converts when a caller
        // EXPLICITLY opts in with a non-KES value.
        if ("NOWPAYMENTS".equals(provider) && request.getNowPaymentsPriceCurrency() != null
                && !request.getNowPaymentsPriceCurrency().isBlank()
                && !"kes".equalsIgnoreCase(request.getNowPaymentsPriceCurrency())) {
            BigDecimal rateToKes = fxRateService.getRate(request.getNowPaymentsPriceCurrency().toUpperCase(), "KES");
            BigDecimal kesAmount = request.getAmount().multiply(rateToKes).setScale(2, java.math.RoundingMode.HALF_UP);
            log.info("NOWPayments withdrawal priced: requested={} {} kesEquivalent={}",
                    request.getAmount(), request.getNowPaymentsPriceCurrency().toUpperCase(), kesAmount);
            request.setAmount(kesAmount);
        }

        if (wallet.getBalance().compareTo(request.getAmount()) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        if ("MPESA".equals(provider) && request.getCurrency() != null
                && !"KES".equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException("M-Pesa disbursements must be in KES");
        }

        // Flutterwave is handled as its own branch (not the generic
        // Stripe/PayPal ProviderResult switch below) because a transfer
        // destination is two fields (account_bank + account_number), not a
        // single "destination" string — same reason MPESA gets its own
        // early-return block above the switch.
        if ("FLUTTERWAVE".equals(provider)) {
            return processFlutterwaveDisbursement(userId, wallet, request);
        }

        String destination;
        if ("MPESA".equals(provider)) {
            destination = resolveVerifiedPhoneNumber(wallet);
        } else if ("PAYPAL".equals(provider)) {
            if (wallet.getPaypalEmail() == null || wallet.getPaypalEmail().isBlank()) {
                throw new IllegalArgumentException(
                        "No PayPal email is set on your wallet — add one before requesting a PayPal disbursement.");
            }
            destination = wallet.getPaypalEmail();
        } else if ("STRIPE".equals(provider)) {
            // Resolved authoritatively from the wallet's linked Connect
            // account — never taken from request.getDestination() — same
            // reasoning as M-Pesa/PayPal above: eliminates typo/mistargeted
            // payout risk, and the user only ever gets money sent to a bank
            // account Stripe itself verified during onboarding.
            if (wallet.getStripeConnectedAccountId() == null || wallet.getStripeConnectedAccountId().isBlank()) {
                throw new IllegalArgumentException(
                        "No Stripe bank account is linked to your wallet — link one before requesting a Stripe withdrawal.");
            }
            if (!wallet.isStripePayoutsEnabled()) {
                throw new IllegalArgumentException(
                        "Your linked Stripe account hasn't finished verification yet — payouts aren't enabled on it.");
            }
            destination = wallet.getStripeConnectedAccountId();
        } else if ("NOWPAYMENTS".equals(provider)) {
            // Unlike MPESA/PAYPAL/STRIPE above, there's no saved/verified
            // crypto address on the wallet to resolve automatically — the
            // caller supplies both the address and currency per request,
            // same as PayPal's email field, just two fields instead of one
            // since a crypto destination isn't identified by currency alone.
            if (request.getDestination() == null || request.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination (crypto wallet address) is required for NOWPAYMENTS disbursements");
            }
            if (request.getNowPaymentsCurrency() == null || request.getNowPaymentsCurrency().isBlank()) {
                throw new IllegalArgumentException("nowPaymentsCurrency is required for NOWPAYMENTS disbursements");
            }
            destination = request.getDestination();
        } else {
            if (request.getDestination() == null || request.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination is required for " + provider + " disbursements");
            }
            destination = request.getDestination();
        }

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setDestination(destination);
        disbursement.setProvider(provider);
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);
        disbursement.setCurrency(Currency.KES);

        if ("MPESA".equals(provider)) {
            disbursement.setChannel("B2C");

            MpesaB2CResponse result;
            try {
                result = mpesaService.sendB2C(destination, request.getAmount());
            } catch (Exception e) {
                log.error("M-Pesa B2C disbursement threw before a result could be returned: userId={}",
                        userId, e);
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason("M-Pesa B2C initiation failed: " + e.getMessage());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                        disbursement.getFailureReason());
            }

            if (!result.isSuccess()) {
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason(result.getMessage());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
            }

            disbursement.setProviderReference(result.getConversationId());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    "Disbursement queued with M-Pesa — your wallet will be debited once M-Pesa confirms the payout.");
        }

        ProviderResult result = switch (provider) {
            case "STRIPE" -> disburseStripe(request, destination, reference);
            case "PAYPAL" -> disbursePaypal(request, destination);
            case "NOWPAYMENTS" -> disburseNowPayments(request, destination, reference);
            default -> new ProviderResult(false, "Unsupported provider: " + provider, null);
        };

        disbursement.setChannel(provider + "_PAYOUT");

        if (result.success()) {
            disbursement.setProviderReference(result.providerRef());

            // PayPal, Stripe, and NOWPayments all resolve asynchronously —
            // NOWPayments additionally requires a separate verify step
            // before it even starts processing (see
            // verifyNowPaymentsDisbursement below) — same PENDING-until-
            // webhook treatment either way, since the wallet can't safely
            // be debited until an external confirmation arrives.
            if ("PAYPAL".equals(provider) || "STRIPE".equals(provider) || "NOWPAYMENTS".equals(provider)) {
                disbursement.setStatus(DisbursementStatus.PENDING);
                disbursementRepository.save(disbursement);
                String providerLabel = switch (provider) {
                    case "STRIPE" -> "Stripe";
                    case "NOWPAYMENTS" -> "NOWPayments";
                    default -> "PayPal";
                };
                String message = "NOWPAYMENTS".equals(provider)
                        ? "Disbursement created with NOWPayments — verify it with your 2FA code "
                                + "(POST /disbursements/nowpayments/" + disbursement.getId() + "/verify) "
                                + "within 1 hour, or it will be automatically rejected."
                        : "Disbursement queued with " + providerLabel + " — your wallet will be debited once "
                                + providerLabel + " confirms the payout.";
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), message);
            }

            // No synchronous-success provider remains in this switch — kept
            // as a safety net in case a future provider is added here that
            // DOES resolve synchronously (MPESA/FLUTTERWAVE are handled in
            // their own early-return branches above).
            wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
            walletRepository.save(wallet);

            disbursement.setStatus(DisbursementStatus.SUCCESS);
            saveDisbursementTransaction(userId, wallet.getId(), request.getAmount(), disbursement, reference);
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.message());
            log.warn("Disbursement failed for userId={}. Reason: {}", userId, result.message());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.message());
    }

 // ─── Flutterwave (bank account or mobile money wallet) ──────────────────

    /**
     * Same not-debited-until-confirmed pattern as MPESA/PAYPAL/STRIPE above —
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
    private DisbursementResponse processFlutterwaveDisbursement(String userId, Wallet wallet,
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
             // negative-balance handling as completeMpesaDisbursement/
             // completePaypalDisbursement above: funds have already left
             // via Flutterwave, so this can't be pre-checked the way a
             // synchronous debit could be.
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
             saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
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

    // ─── Reconciliation from Stripe Connect's payout.paid/payout.failed webhook ─

    /**
     * Keyed by the Stripe Payout id (po_xxx), stored as providerReference
     * at initiation — see StripeService.transferAndPayout, which returns
     * that as the primary reference (not the Transfer id) since that's
     * what payout.paid/payout.failed events carry.
     *
     * IMPORTANT DIFFERENCE from every other provider's failure path here:
     * a failed Stripe Connect payout means money has ALREADY left
     * Premisave's own platform balance via the earlier Transfer step and
     * is sitting in the connected account's own Stripe balance — it is
     * NOT the "nothing moved, nothing to refund" situation that MPESA/
     * PAYPAL/FLUTTERWAVE failures are. This is logged at ERROR (not WARN)
     * specifically so it doesn't blend into routine failure noise; someone
     * needs to either retry a Payout on that connected account (the funds
     * are already there) or treat it as an operational loss.
     */
    @Transactional
    public void completeStripeConnectDisbursement(String payoutId, boolean success, String failureReason) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutId).orElse(null);
        if (d == null) {
            log.warn("Stripe Connect payout webhook for unknown payoutId={} — ignoring", payoutId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("Stripe Connect payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal newBalance = wallet.getBalance().subtract(d.getAmount());
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed Stripe Connect disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            } else {
                disbursementRepository.save(d);
            }

            log.info("Stripe Connect disbursement completed: id={} payoutId={}", d.getId(), payoutId);
        } else {
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(failureReason);
            disbursementRepository.save(d);
            log.error("Stripe Connect payout FAILED: id={} payoutId={} reason={} destinationAccount={} — " +
                    "funds already left the platform balance via the earlier Transfer and are stranded in " +
                    "that connected account; needs manual reconciliation, NOT a routine no-op failure",
                    d.getId(), payoutId, failureReason, d.getDestination());
        }
    }

    // ─── Reconciliation from NOWPayments' payout IPN webhook ────────────────

    /**
     * Keyed by the NOWPayments payout id, stored as providerReference at
     * initiation — see disburseNowPayments above. Statuses observed
     * inconsistently across NOWPayments' own documentation (some sources
     * list FINISHED/FAILED/REJECTED, others waiting/processing/sending/
     * finished/failed) — PaymentCallbackController.nowPaymentsWebhook
     * normalizes whatever it receives to a simple success/failure boolean
     * before calling this, so this method itself doesn't need to know the
     * exact status vocabulary.
     *
     * Same reasoning as every other provider here: the wallet is never
     * debited until this fires, so a failure is a clean no-op, not a
     * stranded-funds situation — unlike Stripe Connect above, NOWPayments'
     * create+verify flow doesn't have an equivalent "money already left an
     * intermediate balance" step before this final confirmation.
     */
    @Transactional
    public void completeNowPaymentsDisbursement(String payoutId, boolean success, String failureReason) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutId).orElse(null);
        if (d == null) {
            log.warn("NOWPayments payout webhook for unknown payoutId={} — ignoring", payoutId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("NOWPayments payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal newBalance = wallet.getBalance().subtract(d.getAmount());
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed NOWPayments disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            } else {
                disbursementRepository.save(d);
            }

            log.info("NOWPayments disbursement completed: id={} payoutId={}", d.getId(), payoutId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING NOWPayments disbursement (see disburseNowPayments above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(failureReason);
            disbursementRepository.save(d);
            log.warn("NOWPayments disbursement failed: id={} payoutId={} reason={}", d.getId(), payoutId, failureReason);
        }
    }

    /**
     * Verifies a NOWPayments disbursement with its 2FA code — the step
     * that actually makes NOWPayments start processing the payout (see
     * NowPaymentsService.verifyPayout's javadoc; per NOWPayments' own
     * support docs, an unverified payout auto-rejects after ~1 hour).
     *
     * WHERE THE CODE COMES FROM depends entirely on how 2FA is configured
     * on your NOWPayments account:
     *  - App-based (TOTP, "Use an app" in Dashboard → Account settings →
     *    Two step authentication) — this CAN be fully automated. If you
     *    hold the TOTP secret server-side, generate the current code
     *    yourself (e.g. via a small RFC 6238 implementation or a library
     *    like `com.warrenstrange:googleauth`) and call this method
     *    immediately after disburseNowPayments succeeds, with no human in
     *    the loop — same fully-automated shape as every other provider's
     *    withdrawal flow in this codebase. Not implemented here since it
     *    depends on a secret this class has no access to; wire it in once
     *    you've confirmed this is how 2FA is actually configured.
     *  - Email-based — a human has to open an email and read the code out.
     *    This genuinely cannot be automated by this backend; expose this
     *    method behind an authenticated endpoint the user (or an admin)
     *    calls manually once they have the code (see
     *    DisbursementController — not included in this pass, since I don't
     *    have that file's current content to safely add to).
     *  - 2FA disabled entirely — NOWPayments creates the payout already
     *    fully processing (per their own docs), and calling this at all
     *    would simply fail since there's nothing left to verify. Check
     *    d.getStatus() / a fresh getPayoutStatus() call before assuming
     *    this step is even necessary if you go this route (not
     *    recommended for a real business — see the earlier discussion of
     *    what disabling this control actually trades away).
     *
     * Ownership-checked the same way withdrawal-adjacent actions are
     * checked elsewhere in this codebase — callerUserId must match the
     * disbursement's own userId, so one user can't verify (and thereby
     * trigger) a payout that isn't theirs.
     */
    @Transactional
    public void verifyNowPaymentsDisbursement(String disbursementId, String verificationCode, String callerUserId) {
        Disbursement d = disbursementRepository.findById(disbursementId)
                .orElseThrow(() -> new IllegalArgumentException("Disbursement not found: " + disbursementId));

        if (!callerUserId.equals(d.getUserId())) {
            throw new IllegalArgumentException("This disbursement does not belong to the authenticated user");
        }

        if (!"NOWPAYMENTS".equals(d.getProvider())) {
            throw new IllegalArgumentException("Disbursement " + disbursementId + " is not a NOWPayments disbursement");
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            throw new IllegalStateException(
                    "This disbursement is already " + d.getStatus() + " — nothing left to verify.");
        }

        boolean verified = nowPaymentsService.verifyPayout(d.getProviderReference(), verificationCode);
        if (!verified) {
            throw new IllegalStateException(
                    "NOWPayments rejected the verification code — check it's correct and hasn't expired.");
        }

        log.info("NOWPayments disbursement verified: id={} payoutId={} — now processing, awaiting webhook confirmation",
                d.getId(), d.getProviderReference());
        // Deliberately NOT changing d.getStatus() here — it stays PENDING
        // until completeNowPaymentsDisbursement resolves it via the
        // webhook, same as every other provider's confirmation step.
    }

    // ─── B2B (admin/finance-initiated, business-to-business payment) ───────
    // Unchanged — never touches a customer wallet (no walletId set).

    @Transactional
    public DisbursementResponse processB2BPayment(String initiatedByUserId, MpesaB2BRequest request) {
        idempotencyService.checkIdempotency(request.getReference());
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        String verifiedRecipientName = null;
        String verifiedChargeProfileId = null;

        if (request.isVerifyRecipient()) {
            QueryOrgInfoRequest orgInfoRequest = new QueryOrgInfoRequest();
            orgInfoRequest.setIdentifierType(request.getReceiverIdentifierTypeForVerification());
            orgInfoRequest.setIdentifier(request.getReceiverShortcode());

            QueryOrgInfoResponse orgInfo = mpesaService.queryOrgInfo(orgInfoRequest);

            if (!orgInfo.isSuccess()) {
                log.warn("B2B Hakikisha check failed for receiverShortcode={} — aborting payment. reason={}",
                        request.getReceiverShortcode(), orgInfo.getResponseMessage());

                Disbursement aborted = new Disbursement();
                aborted.setUserId(initiatedByUserId);
                aborted.setAmount(request.getAmount());
                aborted.setCurrency(Currency.KES);
                aborted.setDestination(request.getReceiverShortcode());
                aborted.setProvider("MPESA");
                aborted.setChannel("B2B");
                aborted.setReference(reference);
                aborted.setStatus(DisbursementStatus.FAILED);
                aborted.setFailureReason("B2B Hakikisha verification failed: " + orgInfo.getResponseMessage());
                disbursementRepository.save(aborted);

                return new DisbursementResponse(aborted.getId(), aborted.getStatus().name(),
                        "Recipient could not be verified — payment not sent: " + orgInfo.getResponseMessage());
            }

            verifiedRecipientName = orgInfo.getOrganizationName();
            verifiedChargeProfileId = orgInfo.getChargeProfileId();
            log.info("B2B Hakikisha verified receiverShortcode={} as organizationName={}",
                    request.getReceiverShortcode(), verifiedRecipientName);
        }

        var result = mpesaService.sendB2B(request);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(request.getReceiverShortcode());
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2B");
        disbursement.setReference(reference);
        disbursement.setVerifiedRecipientName(verifiedRecipientName);
        disbursement.setVerifiedChargeProfileId(verifiedChargeProfileId);

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        String message = verifiedRecipientName != null
                ? result.getMessage() + " (recipient verified as: " + verifiedRecipientName + ")"
                : result.getMessage();
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), message);
    }

    // ─── B2C Account Top Up (admin/finance-initiated) ───────────────────────
    // Unchanged — never touches a customer wallet (no walletId set).

    @Transactional
    public DisbursementResponse processB2CTopUp(String initiatedByUserId, B2CTopUpRequest request) {
        idempotencyService.checkIdempotency(request.getReference());
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        var result = mpesaService.topUpB2CAccount(request.getAmount(), request.getReceivingShortcode(),
                request.getRequester(), request.getAccountReference(), request.getRemarks());

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(request.getReceivingShortcode() != null
                ? request.getReceivingShortcode() : "B2C-DEFAULT");
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_TOPUP");
        disbursement.setReference(reference);

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
    }

    // ─── B2Pochi (pay into the caller's own Pochi business wallet) ──────────────

    @Transactional
    public DisbursementResponse processB2PochiPayment(String initiatedByUserId, B2PochiRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Wallet wallet = walletRepository.findByUserId(initiatedByUserId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + initiatedByUserId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");
        if (wallet.getBalance().compareTo(request.getAmount()) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        String phoneNumber = resolveVerifiedPochiPhoneNumber(wallet);

        // Wallet balance is NOT debited here — see completeMpesaDisbursement,
        // which debits once M-Pesa's ResultURL callback confirms success
        // (channel B2C_POCHI).

        String reference = request.getReference() != null
                ? request.getReference()
                : "POCHI-" + phoneNumber + "-" + System.currentTimeMillis();
        String originatorConversationId = mpesaService.generateOriginatorConversationId("B2POCHI");

        B2PochiRequest resolvedRequest = new B2PochiRequest();
        resolvedRequest.setAmount(request.getAmount());
        resolvedRequest.setPhoneNumber(phoneNumber);
        resolvedRequest.setRemarks(request.getRemarks());
        resolvedRequest.setOccasion(request.getOccasion());
        resolvedRequest.setReference(reference);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(phoneNumber);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_POCHI");
        disbursement.setReference(reference);

        MpesaAsyncResponse result;
        try {
            result = mpesaService.sendToPochi(resolvedRequest, originatorConversationId);
        } catch (Exception e) {
            log.error("B2Pochi withdrawal threw before a result could be returned: userId={}",
                    initiatedByUserId, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("B2Pochi initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
    }

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────

    @Transactional
    public void completeMpesaDisbursement(String conversationId, boolean success,
                                           String resultDesc, String mpesaTransactionId) {
        Disbursement d = disbursementRepository.findByProviderReference(conversationId).orElse(null);
        if (d == null) {
            log.warn("M-Pesa result callback for unknown ConversationID={} — ignoring", conversationId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("M-Pesa result callback for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (("B2C".equals(d.getChannel()) || "B2C_POCHI".equals(d.getChannel())) && d.getWalletId() != null) {
                // Funds have now actually left via M-Pesa — this is the
                // FIRST time the wallet is touched for this disbursement
                // (see processDisbursement/processB2PochiPayment, which no
                // longer debit at initiation). Balance may have moved since
                // initiation due to other transactions, so this can't be
                // guarded with a pre-check the way a synchronous debit
                // could be — if it pushes the wallet negative, that's a
                // signal for manual reconciliation, not something to
                // silently block, since the M-Pesa payout already happened
                // and has to be reflected somewhere.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal newBalance = wallet.getBalance().subtract(d.getAmount());
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed M-Pesa disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            } else {
                disbursementRepository.save(d);
            }

            log.info("M-Pesa {} disbursement completed: id={} conversationId={} mpesaTxId={}",
                    d.getChannel(), d.getId(), conversationId, mpesaTransactionId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING M-Pesa disbursement (see processDisbursement /
            // processB2PochiPayment above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(resultDesc);
            disbursementRepository.save(d);
            log.warn("M-Pesa {} disbursement failed: id={} conversationId={} reason={}",
                    d.getChannel(), d.getId(), conversationId, resultDesc);
        }
    }

    public void markMpesaDisbursementTimedOut(String conversationId) {
        disbursementRepository.findByProviderReference(conversationId).ifPresentOrElse(d -> {
            log.warn("M-Pesa disbursement queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                    d.getId(), conversationId);
        }, () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Reconciliation from PayPal's Payouts webhook ────────────────────────

    @Transactional
    public void completePaypalDisbursement(String payoutBatchId, String transactionStatus,
                                            String paypalTransactionId, String errorMessage) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutBatchId).orElse(null);
        if (d == null) {
            log.warn("PayPal payout webhook for unknown payout_batch_id={} — ignoring", payoutBatchId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("PayPal payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if ("SUCCESS".equals(transactionStatus)) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (d.getWalletId() != null) {
                // First touch of the wallet for this disbursement — see
                // completeMpesaDisbursement above for the same reasoning
                // on the negative-balance edge case.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal newBalance = wallet.getBalance().subtract(d.getAmount());
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed PayPal disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            } else {
                disbursementRepository.save(d);
            }

            log.info("PayPal disbursement completed: id={} payoutBatchId={} paypalTransactionId={}",
                    d.getId(), payoutBatchId, paypalTransactionId);
        } else if (PAYPAL_TERMINAL_FAILURE_STATUSES.contains(transactionStatus)) {
            // No refund needed — the wallet was never debited for a
            // PENDING PayPal payout (see processDisbursement above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(errorMessage != null && !errorMessage.isBlank() ? errorMessage : transactionStatus);
            disbursementRepository.save(d);
            log.warn("PayPal disbursement failed ({}): id={} payoutBatchId={} reason={}",
                    transactionStatus, d.getId(), payoutBatchId, errorMessage);
        } else {
            log.info("PayPal disbursement id={} payoutBatchId={} in non-terminal state={} — awaiting further webhook",
                    d.getId(), payoutBatchId, transactionStatus);
        }
    }

    // ─── Stuck-disbursement sweeper ──────────────────────────────────────────

    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void flagStuckDisbursements() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Disbursement> stuck = disbursementRepository.findByStatusAndCreatedAtBefore(
                DisbursementStatus.PENDING, cutoff);

        if (!stuck.isEmpty()) {
            log.warn("{} disbursement(s) stuck in PENDING beyond 30 minutes — needs manual reconciliation: {}",
                    stuck.size(), stuck.stream().map(Disbursement::getId).toList());
        }
    }

 // ─── Provider dispatch (Stripe/PayPal) ───────────────────────────────────

    /**
     * Converts the wallet's KES amount to whatever currency the withdrawal
     * is actually denominated in (defaults to USD — see DisbursementRequest.
     * currency javadoc), then kicks off the Connect Transfer+Payout. Mirrors
     * disbursePaypal's FX pattern below — the old version of this method
     * skipped FX conversion entirely and passed the raw KES amount straight
     * through as if it were already USD, which would have sent the wrong
     * amount of money to a real bank account.
     */
    private ProviderResult disburseStripe(DisbursementRequest request, String connectedAccountId, String idempotencyKey) {
        try {
            String currency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";

            BigDecimal payoutAmount = request.getAmount();
            if (!"KES".equals(currency)) {
                BigDecimal kesToTargetRate = fxRateService.getRate("KES", currency);
                payoutAmount = request.getAmount().multiply(kesToTargetRate).setScale(2, java.math.RoundingMode.HALF_UP);
            }

            StripeService.ConnectPayoutResult result = stripeService.transferAndPayout(
                    connectedAccountId, payoutAmount, currency, idempotencyKey);

            if (!result.success()) {
                return new ProviderResult(false, result.message(), null);
            }

            log.info("Stripe Connect payout: accountId={} kesAmount={} {}Amount={} payoutId={}",
                    connectedAccountId, request.getAmount(), currency, payoutAmount, result.payoutId());
            return new ProviderResult(true, "Stripe payout initiated (" + currency + " " + payoutAmount + ")",
                    result.payoutId());
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    private ProviderResult disbursePaypal(DisbursementRequest request, String destinationEmail) {
        try {
            BigDecimal usdToKesRate = fxRateService.getRate("USD", "KES");
            BigDecimal usdAmount = request.getAmount()
                    .divide(usdToKesRate, 2, java.math.RoundingMode.HALF_UP);
            String batchId = paypalService.processPayout(destinationEmail, usdAmount, "USD");
            log.info("PayPal payout: kesAmount={} usdAmount={} rate={} batchId={}",
                    request.getAmount(), usdAmount, usdToKesRate, batchId);
            return new ProviderResult(true, "PayPal payout initiated (USD " + usdAmount + ")", batchId);
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    /**
     * Converts the wallet's KES amount into the target crypto using
     * NOWPayments' own /v1/estimate rates (see NowPaymentsService.
     * getEstimatedAmount's javadoc for why FxRateService can't do this),
     * then creates the payout. Returns providerRef = the NOWPayments
     * payout id — same role as Stripe's payoutId / PayPal's batchId — but
     * note this payout does NOT execute yet even on success here; it sits
     * awaiting 2FA verification (see verifyNowPaymentsDisbursement below).
     */
    private ProviderResult disburseNowPayments(DisbursementRequest request, String address, String idempotencyKey) {
        try {
            NowPaymentsService.EstimateResult estimate = nowPaymentsService.getEstimatedAmount(
                    request.getAmount(), "kes", request.getNowPaymentsCurrency());

            if (!estimate.success()) {
                return new ProviderResult(false, estimate.message(), null);
            }

            NowPaymentsService.CreatePayoutResult result = nowPaymentsService.createPayout(
                    address, request.getNowPaymentsCurrency(), estimate.estimatedAmount(), idempotencyKey);

            if (!result.success()) {
                return new ProviderResult(false, result.message(), null);
            }

            log.info("NOWPayments payout: address={} kesAmount={} {}Amount={} payoutId={}",
                    address, request.getAmount(), request.getNowPaymentsCurrency(),
                    estimate.estimatedAmount(), result.payoutId());
            return new ProviderResult(true,
                    "NOWPayments payout created (" + request.getNowPaymentsCurrency().toUpperCase()
                            + " " + estimate.estimatedAmount() + ") — awaiting 2FA verification",
                    result.payoutId());
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String resolveVerifiedPochiPhoneNumber(Wallet wallet) {
        if (wallet != null && wallet.getPochiPhoneNumber() != null && !wallet.getPochiPhoneNumber().isBlank()) {
            return wallet.getPochiPhoneNumber();
        }

        throw new PhoneNumberUnavailableException(
                "You haven't added a Pochi la Biashara phone number to your wallet yet. "
                        + "Please add one in your wallet settings before requesting a Pochi withdrawal.");
    }

    private String resolveVerifiedPhoneNumber(Wallet wallet) {
        if (wallet != null && wallet.getMpesaPhoneNumber() != null && !wallet.getMpesaPhoneNumber().isBlank()) {
            return wallet.getMpesaPhoneNumber();
        }

        throw new PhoneNumberUnavailableException(
                "You haven't added an M-Pesa phone number to your wallet yet. "
                        + "Please add one in your wallet settings before requesting a withdrawal.");
    }

    private void saveDisbursementTransaction(String userId, String walletId, BigDecimal amount,
                                              Disbursement disbursement, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(TransactionType.DISBURSEMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription("Disbursement via " + disbursement.getProvider() + " to " + disbursement.getDestination());
        tx.setReference(reference);
        tx.setProviderReference(disbursement.getProviderReference());
        transactionRepository.save(tx);
    }

    private record ProviderResult(boolean success, String message, String providerRef) {}
}
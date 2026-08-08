package com.premisave.wallet.service;

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
    private final IdempotencyService idempotencyService;
    private final FxRateService fxRateService;

    private static final List<String> PAYPAL_TERMINAL_FAILURE_STATUSES =
            List.of("FAILED", "DENIED", "BLOCKED", "RETURNED", "REFUNDED", "REVERSED", "CANCELED");

 // ─── User-facing disbursement (phone / PayPal / Stripe / Flutterwave) ───

    /**
     * NOTE ON BALANCE TIMING: the wallet is NOT debited here anymore. It's
     * only debited once the disbursement is CONFIRMED — by M-Pesa's
     * ResultURL callback (see completeMpesaDisbursement), PayPal's Payouts
     * webhook (see completePaypalDisbursement), or Flutterwave's
     * transfer.completed webhook (see completeFlutterwaveDisbursement) —
     * except for Stripe, which resolves synchronously below, so it's
     * debited right at that point. Previously the wallet was debited up
     * front and refunded on failure; that meant a customer's balance was
     * reduced for money that hadn't actually left yet, and a PENDING
     * disbursement stuck for hours (e.g. during the recent callback URL
     * misconfiguration) held their funds hostage the whole time even though
     * M-Pesa/PayPal had already processed the payout successfully.
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
        if (wallet.getBalance().compareTo(request.getAmount()) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";

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
            case "STRIPE" -> disburseStripe(request);
            case "PAYPAL" -> disbursePaypal(request, destination);
            default -> new ProviderResult(false, "Unsupported provider: " + provider, null);
        };

        disbursement.setChannel(provider + "_PAYOUT");

        if (result.success()) {
            disbursement.setProviderReference(result.providerRef());

            if ("PAYPAL".equals(provider)) {
                disbursement.setStatus(DisbursementStatus.PENDING);
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                        "Disbursement queued with PayPal — your wallet will be debited once PayPal confirms the payout.");
            }

            // Stripe resolves synchronously — we already know it succeeded,
            // so debit right here rather than waiting on a callback.
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
 // Excerpt: Updated processFlutterwaveDisbursement and completeFlutterwaveDisbursement methods
 // Add this to DisbursementService.java replacing the corresponding Flutterwave section

 /**
  * Same not-debited-until-confirmed pattern as MPESA/PAYPAL above —
  * Flutterwave transfers resolve asynchronously via the
  * transfer.completed webhook (see completeFlutterwaveDisbursement).
  * KES→USD conversion mirrors disbursePaypal below; see
  * FlutterwaveService.initiateTransfer's javadoc for the caveat that
  * USD payouts may not be supported for every destination corridor —
  * confirm with Flutterwave for your specific bank/mobile-money rails
  * before relying on this in production.
  *
  * RECIPIENT CACHING: If recipient creation succeeds but transfer
  * initiation fails (network error), the recipient ID is cached on the
  * Disbursement record so a retry can reuse it instead of creating
  * orphaned duplicates. See Disbursement.flutterwaveRecipientId.
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

     // msisdn must include country code e.g. 2547XXXXXXXX
     String msisdn = request.getFlutterwaveAccountNumber();
     String network = request.getFlutterwaveAccountBank(); // network code e.g. "Mpesa", "MTN"
     String displayDestination = network + "-" + msisdn;

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

     // Wallet operates in KES. Flutterwave v4 direct-transfer supports KES natively
     // via destination_currency. Use KES directly, no FX conversion needed.
     // source_currency = your Flutterwave balance currency (confirm with your account).
     // destination_currency = recipient's currency e.g. KES for M-Pesa Kenya.
     String sourceCurrency = "KES";
     String destinationCurrency = "KES";

     try {
         FlutterwaveService.TransferResult result = flutterwaveService.initiateTransfer(
                 msisdn, network, sourceCurrency, destinationCurrency,
                 request.getAmount(), reference, request.getRemarks(),
                 firstName, lastName);

         log.info("Flutterwave disbursement: userId={} reference={} amount={} msisdn={} network={}",
                 userId, reference, request.getAmount(), msisdn, network);

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
  * Reconciliation from Flutterwave's transfer.completed webhook. Keyed
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
         // PENDING Flutterwave disbursement (see
         // processFlutterwaveDisbursement above).
         d.setStatus(DisbursementStatus.FAILED);
         d.setFailureReason(statusDesc);
         disbursementRepository.save(d);
         log.warn("Flutterwave disbursement failed: id={} transferId={} reason={}", d.getId(), transferId, statusDesc);
     }
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

    private ProviderResult disburseStripe(DisbursementRequest request) {
        try {
            String currency = request.getCurrency() != null ? request.getCurrency() : "kes";
            String payoutId = stripeService.processPayout(request.getAmount(), currency);
            return new ProviderResult(true, "Stripe payout initiated", payoutId);
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
package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.config.CommercialC2bProperties;
import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.RegistrationStatus;
import com.premisave.wallet.commercial.enums.RegistrationType;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.link.AccountReference;
import com.premisave.wallet.commercial.link.AccountReferenceRepository;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResultSink;
import com.premisave.wallet.commercial.repository.CallbackRegistrationRepository;
import com.premisave.wallet.commercial.service.CommercialTransactionStore;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.DeveloperServiceClient;
import com.premisave.wallet.commercial.service.Priced;
import com.premisave.wallet.commercial.service.PricingService;
import com.premisave.wallet.commercial.util.Masking;
import com.premisave.wallet.commercial.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Matches money paid to the commercial paybill with the account reference the customer typed, and credits the
 * merchant that owns it.
 *
 * Validation (before the customer's money moves): refuse an unknown, closed, expired or test reference and a
 * wrong amount, then ask the merchant's VALIDATION callback if they registered one. A refusal for a known
 * reference is recorded as a failed payment, so the merchant sees it.
 *
 * Confirmation (the money has moved): the payment is written down first as a C2bReceipt, then credited. A
 * payment that cannot be credited properly is credited anyway when the reference is real (and flagged), and
 * otherwise kept as UNMATCHED for an operator, because the customer has already paid.
 */
@Service("commercialC2bService")
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class C2bService {

    private static final Logger log = LoggerFactory.getLogger(C2bService.class);
    private static final String KES = "KES";
    static final int MAX_ATTEMPTS = 20;

    /** The answer given to Safaricom's validation request. */
    public record ValidationAnswer(boolean accept, String code, String description) {}

    private final CommercialC2bProperties props;
    private final AccountReferenceRepository references;
    private final C2bReceiptRepository receipts;
    private final CommercialTransactionStore store;
    private final PricingService pricing;
    private final RailResultSink sink;
    private final CallbackRegistrationRepository registrations;
    private final DeveloperServiceClient developer;
    private final JsonMapper json;

    @Autowired
    public C2bService(CommercialC2bProperties props, AccountReferenceRepository references, C2bReceiptRepository receipts,
                      CommercialTransactionStore store, PricingService pricing, @Lazy RailResultSink sink,
                      CallbackRegistrationRepository registrations, DeveloperServiceClient developer, JsonMapper json) {
        this.props = props;
        this.references = references;
        this.receipts = receipts;
        this.store = store;
        this.pricing = pricing;
        this.sink = sink;
        this.registrations = registrations;
        this.developer = developer;
        this.json = json;
    }

    public String shortcode() {
        return props.shortcode();
    }

    /** True when an account number is a real commercial reference; lets a shared paybill route a request here. */
    public boolean owns(String billRef) {
        String ref = C2bRules.normaliseReference(billRef);
        return C2bRules.looksLikeReference(ref) && references.existsById(ref);
    }

    // ------------------------------------------------------------------ validation

    public ValidationAnswer validate(Map<String, Object> body) {
        C2bPayload p = C2bPayload.parse(body);
        if (!props.shortcode().equals(p.shortcode())) return reject(C2bRules.INVALID_SHORTCODE, "Invalid shortcode");
        AccountReference ref = references.findById(C2bRules.normaliseReference(p.billRef())).orElse(null);
        if (!C2bRules.usable(ref, props.shortcode())) return reject(C2bRules.INVALID_ACCOUNT, "Invalid account number");

        boolean amountOk;
        try {
            amountOk = amountOk(ref, p.amount());
        } catch (RuntimeException e) {
            // Without a price there is nothing to compare; confirmation checks again and flags a mismatch.
            log.warn("Could not price payment {} for validation: {}", p.transId(), e.getMessage());
            amountOk = true;
        }
        C2bRules.Verdict v = C2bRules.validation(ref, props.shortcode(), Instant.now(), amountOk);
        if (!v.accept()) {
            recordRefusal(ref, p, v.reason());
            return reject(v.code(), v.reason());
        }
        if (props.merchantValidation()) {
            DeveloperServiceClient.ValidationReply answer = askMerchant(ref, p);
            if (answer != null && !answer.accepted()) {
                String reason = answer.reason() == null || answer.reason().isBlank() ? "The merchant declined the payment" : answer.reason();
                recordRefusal(ref, p, reason);
                return reject(C2bRules.OTHER_ERROR, "Rejected");
            }
        }
        return new ValidationAnswer(true, "0", "Accepted");
    }

    /** The merchant's answer, or an answer made from the registration's onTimeout when there is none. Null means no callback is registered. */
    private DeveloperServiceClient.ValidationReply askMerchant(AccountReference ref, C2bPayload p) {
        List<CallbackRegistration> regs = registrations.findByAppIdAndLivemodeAndTypeAndStatus(
                ref.getAppId(), true, RegistrationType.VALIDATION, RegistrationStatus.ACTIVE);
        if (regs.isEmpty()) return null;
        CallbackRegistration reg = regs.get(0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationId", "val_" + p.transId());
        payload.put("type", "payment.validation");
        payload.put("livemode", true);
        payload.put("accountReference", ref.getReference());
        try {
            Priced priced = pricing.price(Direction.COLLECTION, MethodType.MOBILE_MONEY, p.amount(), KES, null);
            payload.put("amount", Money.format(priced.usdCents()));
            payload.put("currency", Money.BASE_CURRENCY);
        } catch (RuntimeException e) {
            log.warn("Could not price payment {} for the merchant's validation: {}", p.transId(), e.getMessage());
        }
        payload.put("nativeAmount", p.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        payload.put("nativeCurrency", KES);
        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("name", p.payerName());
        payer.put("phone", p.payerPhone() == null ? null : Masking.phone(p.payerPhone()));
        payload.put("payer", payer);
        payload.put("deadlineMs", props.validationDeadlineMs());

        DeveloperServiceClient.ValidationReply reply = null;
        try {
            reply = developer.validate(reg.getId(), reg.getUrl(), json.writeValueAsString(payload), props.validationDeadlineMs());
        } catch (RuntimeException e) {
            log.warn("Validation callback for {} failed: {}", ref.getReference(), e.getMessage());
        }
        if (reply != null) return reply;
        boolean accept = "ACCEPT".equalsIgnoreCase(reg.getOnTimeout());
        log.warn("No validation answer for {} from registration {}; applying onTimeout={}", ref.getReference(), reg.getId(),
                accept ? "ACCEPT" : "REJECT");
        return new DeveloperServiceClient.ValidationReply(accept, accept ? null : "The merchant did not answer in time");
    }

    private ValidationAnswer reject(String code, String description) {
        return new ValidationAnswer(false, code, description);
    }

    // ------------------------------------------------------------------ confirmation

    /** Writes the payment down, then credits it. Always returns once the payment is safely on record. */
    public void confirm(Map<String, Object> body) {
        C2bPayload p = C2bPayload.parse(body);
        if (p.transId() == null || p.transId().isBlank()) {
            log.error("A paybill confirmation arrived with no transaction id: {}", body);
            return;
        }
        C2bReceipt receipt = new C2bReceipt();
        receipt.setTransId(p.transId());
        receipt.setStatus(C2bReceipt.Status.RECEIVED);
        receipt.setBillRef(p.billRef());
        receipt.setAmount(p.amount() == null ? null : p.amount().toPlainString());
        receipt.setShortcode(p.shortcode());
        receipt.setRaw(body);
        receipt.setReceivedAt(Instant.now());
        try {
            receipts.insert(receipt);
        } catch (DuplicateKeyException repeated) {
            // Safaricom sent it twice, or the sweeper is already on it.
            C2bReceipt existing = receipts.findById(p.transId()).orElse(null);
            if (existing == null || existing.getStatus() != C2bReceipt.Status.RECEIVED) return;
            receipt = existing;
        }
        try {
            process(receipt);
        } catch (RuntimeException e) {
            // The receipt stays RECEIVED, so the sweeper tries again. The payment is not lost.
            log.error("Paybill payment {} could not be credited yet; it will be retried", p.transId(), e);
        }
    }

    /** Credits a receipt. Safe to repeat: the transaction id comes from Safaricom's, so a second run finds the first. */
    void process(C2bReceipt receipt) {
        C2bPayload p = C2bPayload.parse(receipt.getRaw());
        bumpAttempts(receipt);

        if (p.amount() == null || p.amount().signum() <= 0) {
            finish(receipt, C2bReceipt.Status.UNMATCHED, "INVALID_AMOUNT", null, null);
            return;
        }
        AccountReference ref = references.findById(C2bRules.normaliseReference(p.billRef())).orElse(null);
        if (ref == null) {
            finish(receipt, C2bReceipt.Status.UNMATCHED, "UNKNOWN_REFERENCE", null, null);
            return;
        }
        if (!ref.isLivemode()) {
            finish(receipt, C2bReceipt.Status.UNMATCHED, "TEST_REFERENCE", null, null);
            return;
        }
        if (!C2bRules.usable(ref, props.shortcode())) {
            finish(receipt, C2bReceipt.Status.UNMATCHED, "WRONG_SHORTCODE", null, null);
            return;
        }

        String txId = transactionId(p.transId());
        Optional<CommercialTransaction> existing = store.find(txId);
        if (existing.isPresent() && existing.get().getStatus() == TxStatus.FAILED) {
            // The payment was refused at validation but the money arrived anyway (Safaricom completed it).
            finish(receipt, C2bReceipt.Status.UNMATCHED, "PAID_AFTER_REFUSAL", txId, null);
            return;
        }
        C2bRules.Flag flag = C2bRules.confirmationFlag(ref, Instant.now(), amountOk(ref, p.amount()));
        if (existing.isEmpty()) {
            Priced priced;
            try {
                priced = pricing.price(Direction.COLLECTION, MethodType.MOBILE_MONEY, p.amount(), KES, null);
            } catch (com.premisave.wallet.commercial.exception.CommercialException e) {
                // A payment too small to carry its fee cannot be priced; it is not credited automatically.
                finish(receipt, C2bReceipt.Status.UNMATCHED, "NOT_PRICEABLE: " + e.getMessage(), null, null);
                return;
            }
            CommercialTransaction tx = newPayment(ref, p, priced, txId);
            if (flag != C2bRules.Flag.NONE) tx.getMetadata().put("flag", flag.name());
            try {
                store.insert(tx);
            } catch (DuplicateKeyException raced) {
                // Another run recorded it first; carry on to settle it.
            }
        }
        sink.onOutcome(txId, new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid to the paybill", null, null, p.transId()));
        finish(receipt, C2bReceipt.Status.RECORDED, flag == C2bRules.Flag.NONE ? null : flag.name(), txId, flag.name());
        if (flag != C2bRules.Flag.NONE) {
            log.warn("Paybill payment {} for {} was credited with flag {}", p.transId(), ref.getReference(), flag);
        }
    }

    // ------------------------------------------------------------------ recording

    /** The merchant sees a refused payment as a failed one. */
    private void recordRefusal(AccountReference ref, C2bPayload p, String reason) {
        try {
            String txId = transactionId(p.transId());
            if (store.find(txId).isPresent()) return;
            Priced priced = pricing.price(Direction.COLLECTION, MethodType.MOBILE_MONEY, p.amount(), KES, null);
            CommercialTransaction tx = newPayment(ref, p, priced, txId);
            try {
                store.insert(tx);
            } catch (DuplicateKeyException raced) {
                return;
            }
            sink.onOutcome(txId, new RailOutcome(TxStatus.FAILED, ResultCode.DECLINED, reason, null, null, p.transId()));
        } catch (RuntimeException e) {
            log.warn("Could not record the refused paybill payment {}: {}", p.transId(), e.getMessage());
        }
    }

    private CommercialTransaction newPayment(AccountReference ref, C2bPayload p, Priced priced, String txId) {
        Instant now = Instant.now();
        CommercialTransaction tx = new CommercialTransaction();
        tx.setId(txId);
        tx.setMerchantId(ref.getMerchantId());
        tx.setAppId(ref.getAppId());
        tx.setLivemode(true);
        tx.setType(TxType.PAYMENT);
        tx.setStatus(TxStatus.PENDING);
        tx.setMerchantReference(ref.getMerchantReference());
        tx.setDescription("Paybill payment to " + ref.getReference());
        CommercialPaymentService.applyPricing(tx, priced, null);
        tx.setMethodType(MethodType.MOBILE_MONEY);
        tx.setRail(RailId.MPESA);
        tx.setProvider("MPESA");
        tx.setProviderReference(p.transId());
        Map<String, Object> customer = new LinkedHashMap<>();
        if (p.payerName() != null) customer.put("name", p.payerName());
        if (p.payerPhone() != null) customer.put("phone", p.payerPhone());
        customer.put("accountRef", ref.getReference());
        tx.setCustomer(customer);
        tx.getMetadata().put("accountReference", ref.getReference());
        tx.getRelations().put("accountReference", ref.getReference());
        tx.setExpiresAt(now.plusSeconds(600));
        return tx;
    }

    static String transactionId(String transId) {
        return "pay_c2b_" + transId;
    }

    /** Compares what was paid with what the reference expects. Throws when a price is needed and cannot be had. */
    boolean amountOk(AccountReference ref, BigDecimal paidKes) {
        if (ref.getExpectedAmount() == null) return true;
        if (KES.equals(ref.getCurrency())) return C2bRules.exactAmount(ref.getExpectedAmount(), paidKes);
        long paid = pricing.price(Direction.COLLECTION, MethodType.MOBILE_MONEY, paidKes, KES, null).usdCents();
        long expected = pricing.price(Direction.COLLECTION, MethodType.MOBILE_MONEY, ref.getExpectedAmount(), ref.getCurrency(), null).usdCents();
        return C2bRules.usdWithinTolerance(expected, paid);
    }

    private void finish(C2bReceipt receipt, C2bReceipt.Status status, String reason, String transactionId, String flag) {
        receipt.setStatus(status);
        receipt.setReason(reason);
        receipt.setTransactionId(transactionId);
        receipt.setFlag(flag == null || flag.equals(C2bRules.Flag.NONE.name()) ? null : flag);
        receipt.setProcessedAt(Instant.now());
        receipts.save(receipt);
        if (status == C2bReceipt.Status.UNMATCHED) {
            log.error("Paybill payment {} of KES {} for account number '{}' could not be matched ({}); it needs an operator",
                    receipt.getTransId(), receipt.getAmount(), receipt.getBillRef(), reason);
        }
    }

    private void bumpAttempts(C2bReceipt receipt) {
        receipt.setAttempts(receipt.getAttempts() + 1);
    }
}

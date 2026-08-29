package com.premisave.wallet.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders and sends transactional emails (deposit confirmations,
 * disbursement success/failure, transfers, payments) from plain HTML
 * templates stored as classpath resources under templates/email/.
 *
 * Every public method here runs asynchronously (@Async, enabled via
 * AsyncConfig) — sending an email involves a real SMTP round-trip, and
 * without this, that latency would sit directly in the HTTP response
 * time for whatever action triggered it. The caller gets its own
 * response back immediately; the email is dispatched on a separate
 * thread shortly after.
 *
 * Templates use simple {{placeholder}} string substitution, not a real
 * templating engine — deliberate, see earlier reasoning. To still
 * support OPTIONAL detail rows (gateway, exchange rate used, sender
 * name, contact info) without a real conditional syntax, each template
 * has a single {{extraRows}} placeholder that this class populates with
 * zero or more pre-built <tr> HTML fragments — see row()/rows() below. A
 * row whose value is null/blank contributes nothing at all, so the
 * placeholder is simply empty when a piece of data doesn't apply (e.g.
 * no exchange rate for a USD-native gateway like Stripe/PayPal).
 *
 * NO MASKING — every value (phone number, email, contact info) is shown
 * in full, exactly as the caller supplies it. Masking was removed on
 * request; callers pass raw values directly into the template vars, no
 * transformation happens here anymore.
 *
 * SENDER NAME: sendDepositConfirmation accepts a senderName parameter,
 * used specifically for M-Pesa C2B deposits — Safaricom's own callback
 * includes FirstName/MiddleName/LastName for the person who paid, and
 * this is the one confirmed case where a real name is genuinely
 * available without any extra lookup. Other gateways (Stripe, PayPal,
 * NOWPayments, Flutterwave) generally have no equivalent field to supply
 * here, so this stays null/blank for them and the row simply doesn't
 * render.
 *
 * Every send is wrapped in try/catch and only logged on failure — a
 * failed email must NEVER fail the transaction it's notifying about.
 *
 * Depends on spring-boot-starter-mail being on the classpath — this
 * config already exists in application.yml (spring.mail.*), which
 * implies the dependency is present, but that wasn't independently
 * confirmed by viewing pom.xml/build.gradle directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    private static final String TEMPLATE_PATH = "templates/email/";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a");

    /** Matches every existing detail row's exact styling in every template — see class javadoc on {{extraRows}}. */
    private static final String ROW_HTML =
            "<tr><td style=\"padding: 10px 0; color:#6b7280; border-bottom: 1px solid #F0F1F3;\">%s</td>"
                    + "<td style=\"padding: 10px 0; color:#1a1a1a; font-weight:600; text-align:right; border-bottom: 1px solid #F0F1F3;\">%s</td></tr>";

    /**
     * @Async here (and on every other public method in this class) — NOT
     * on the private send() helper below, since @Async only takes effect
     * on calls that go through Spring's proxy, and send() is only ever
     * called from within this same class (a self-invocation, which
     * bypasses the proxy entirely and would make @Async silently do
     * nothing). Requires AsyncConfig's @EnableAsync to actually take
     * effect at all.
     *
     * gateway: the provider name (e.g. "M-Pesa", "Flutterwave",
     * "Stripe", "PayPal", "NOWPayments") — pass null/blank if not
     * applicable.
     * exchangeRateInfo: a pre-formatted string, e.g. "1 KES = 0.0077
     * USD" — pass null/blank when no conversion applied (a USD-native
     * gateway).
     * rawSource: the phone number / email / identifier the deposit came
     * from — shown exactly as given, no masking; pass null/blank if not
     * applicable.
     * senderName: the actual paying party's name, when the gateway's own
     * callback provides one (confirmed for M-Pesa C2B) — null/blank for
     * gateways with no equivalent field.
     */
    @Async
    public void sendDepositConfirmation(String toEmail, String amount, String currency,
                                         String reference, String newBalance,
                                         String gateway, String exchangeRateInfo, String rawSource,
                                         String senderName) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("reference", reference);
        vars.put("newBalance", newBalance);
        vars.put("extraRows", rows(
                "Payment Gateway", gateway,
                "Exchange Rate Used", exchangeRateInfo,
                "Sender Name", senderName,
                "Paid From", rawSource
        ));
        send(toEmail, "Deposit Confirmation - Premisave", "deposit-confirmation-email.html", vars);
    }

    /**
     * destination: the destination (phone number, bank account, PayPal
     * email, etc.) — shown exactly as given, no masking.
     * gateway/exchangeRateInfo: same convention as sendDepositConfirmation above.
     */
    @Async
    public void sendDisbursementSuccess(String toEmail, String amount, String currency,
                                         String destination, String reference,
                                         String gateway, String exchangeRateInfo) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("destination", destination);
        vars.put("reference", reference);
        vars.put("extraRows", rows(
                "Payment Gateway", gateway,
                "Exchange Rate Used", exchangeRateInfo
        ));
        send(toEmail, "Withdrawal Successful - Premisave", "disbursement-success-email.html", vars);
    }

    /**
     * gateway/destination added so a failure email still tells the
     * customer WHICH withdrawal attempt this was about (which provider,
     * to where) even though nothing was actually debited. destination
     * shown exactly as given, no masking; either may be null/blank if
     * not known at failure time.
     */
    @Async
    public void sendDisbursementFailed(String toEmail, String amount, String currency, String reason,
                                        String gateway, String destination) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("reason", reason);
        vars.put("extraRows", rows(
                "Payment Gateway", gateway,
                "Intended Destination", destination
        ));
        send(toEmail, "Disbursement Failed - Premisave", "disbursement-failed-email.html", vars);
    }

    /**
     * Sent to BOTH parties of an internal wallet-to-wallet transfer, once
     * each, with different wording per direction — one shared template
     * (transfer-notification-email.html) rather than two near-duplicate
     * files, since the only real difference is a handful of labels, not
     * the overall layout.
     *
     * counterpartyEmail shown exactly as given, no masking.
     * counterpartyName: the other party's full name, resolved via
     * AuthServiceClient by the caller (TransferService) — may still be
     * null/blank if that lookup failed or the account has no name on
     * file; the row simply doesn't render in that case.
     *
     * @param isSenderCopy true for the copy sent to whoever sent the
     *                      money, false for the copy sent to whoever
     *                      received it.
     */
    @Async
    public void sendTransferNotification(String toEmail, String amount, String currency,
                                          String counterpartyEmail, String counterpartyName,
                                          String reference, boolean isSenderCopy) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("counterpartyEmail", counterpartyEmail);
        vars.put("reference", reference);
        vars.put("extraRows", rows(
                isSenderCopy ? "Recipient Name" : "Sender Name", counterpartyName
        ));

        if (isSenderCopy) {
            vars.put("headline", "Money Sent");
            vars.put("amountLabel", "Amount Sent");
            vars.put("counterpartyLabel", "Sent To");
        } else {
            vars.put("headline", "Money Received");
            vars.put("amountLabel", "Amount Received");
            vars.put("counterpartyLabel", "Received From");
        }

        String subject = (isSenderCopy ? "Transfer Sent" : "Transfer Received") + " - Premisave";
        send(toEmail, subject, "transfer-notification-email.html", vars);
    }

    /** For a Payment (wallet deducted for a service — e.g. a booking fee), not a Disbursement — genuinely different wording, hence its own template rather than reusing disbursement-success-email.html's static "Withdrawal Successful" text. No gateway/exchange-rate/contact fields — this is an internal deduction with no external counterparty at all. */
    @Async
    public void sendPaymentConfirmation(String toEmail, String amount, String currency,
                                         String service, String reference) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("service", service);
        vars.put("reference", reference);
        vars.put("extraRows", "");
        send(toEmail, "Payment Confirmation - Premisave", "payment-confirmation-email.html", vars);
    }

    private void send(String toEmail, String subject, String templateFile, Map<String, String> vars) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skipping email '{}' — no recipient email address available", subject);
            return;
        }

        // Added automatically here, not by each caller — the moment the
        // email is actually dispatched, not when the underlying
        // transaction happened (those can differ slightly if sending is
        // ever delayed/retried), and this guarantees every template gets
        // it consistently rather than relying on each call site to
        // remember to pass one.
        vars.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));

        try {
            String html = loadTemplate(templateFile);
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                html = html.replace("{{" + entry.getKey() + "}}", value);
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Email sent: subject='{}' to={}", subject, toEmail);
        } catch (Exception e) {
            // Deliberately caught broadly and only logged — see class javadoc.
            log.error("Failed to send email: subject='{}' to={}", subject, toEmail, e);
        }
    }

    private String loadTemplate(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH + filename);
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ─── Optional-row building (see class javadoc on {{extraRows}}) ────────

    /** Builds zero or more <tr> rows from label/value pairs, in order, skipping any pair whose value is null/blank entirely — no empty row ever renders. */
    private static String rows(String... labelValuePairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labelValuePairs.length - 1; i += 2) {
            sb.append(row(labelValuePairs[i], labelValuePairs[i + 1]));
        }
        return sb.toString();
    }

    private static String row(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return String.format(ROW_HTML, label, value);
    }
}
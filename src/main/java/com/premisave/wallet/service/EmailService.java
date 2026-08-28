package com.premisave.wallet.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders and sends transactional emails (deposit confirmations,
 * disbursement success/failure) from plain HTML templates stored as
 * classpath resources under templates/email/.
 *
 * Templates use simple {{placeholder}} string substitution, not a real
 * templating engine (Thymeleaf/Freemarker) — deliberate: these are flat,
 * self-contained HTML files with no conditionals or loops needed, and
 * pulling in a templating engine for three static templates would be
 * more machinery than the actual need justifies.
 *
 * Every send is wrapped in try/catch and only logged on failure — a
 * failed email must NEVER fail the transaction it's notifying about.
 * By the time this is called, the deposit/disbursement has already
 * succeeded or failed; email is a side effect of that outcome, not a
 * precondition for it.
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

    public void sendDepositConfirmation(String toEmail, String amount, String currency,
                                         String reference, String newBalance) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("reference", reference);
        vars.put("newBalance", newBalance);
        send(toEmail, "Deposit Confirmation - Premisave", "deposit-confirmation-email.html", vars);
    }

    public void sendDisbursementSuccess(String toEmail, String amount, String currency,
                                         String destination, String reference) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("destination", destination);
        vars.put("reference", reference);
        send(toEmail, "Withdrawal Successful - Premisave", "disbursement-success-email.html", vars);
    }

    public void sendDisbursementFailed(String toEmail, String amount, String currency, String reason) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("reason", reason);
        send(toEmail, "Disbursement Failed - Premisave", "disbursement-failed-email.html", vars);
    }

    private void send(String toEmail, String subject, String templateFile, Map<String, String> vars) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skipping email '{}' — no recipient email address available", subject);
            return;
        }

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
}
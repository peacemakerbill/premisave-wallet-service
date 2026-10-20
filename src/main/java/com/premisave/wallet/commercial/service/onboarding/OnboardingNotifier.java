package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.config.properties.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.entity.onboarding.Actor;
import com.premisave.wallet.commercial.entity.onboarding.OnboardingApplication;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingEventType;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends the onboarding emails. Sending runs on a separate thread so the request that triggered it does not wait
 * for the mail server, and a failure never fails the step it reports on. The outcome of every email is written
 * to the application timeline.
 */
@Component("commercialOnboardingNotifier")
public class OnboardingNotifier {

    private static final Logger log = LoggerFactory.getLogger(OnboardingNotifier.class);

    private final JavaMailSender mail;
    private final CommercialOnboardingProperties props;
    private final OnboardingTimeline timeline;

    public OnboardingNotifier(JavaMailSender mail, CommercialOnboardingProperties props, OnboardingTimeline timeline) {
        this.mail = mail;
        this.props = props;
        this.timeline = timeline;
    }

    /** Emails the applicant. The timeline entry is visible to the applicant. */
    @Async
    public void toApplicant(OnboardingApplication app, String to, OnboardingEmails.Email email) {
        deliver(app, List.of(to), email, true);
    }

    /** Emails the configured staff addresses. The timeline entry is internal. */
    @Async
    public void toStaff(OnboardingApplication app, OnboardingEmails.Email email) {
        if (props.staffNotifyEmails().isEmpty()) return;
        deliver(app, props.staffNotifyEmails(), email, false);
    }

    private void deliver(OnboardingApplication app, List<String> recipients, OnboardingEmails.Email email, boolean visible) {
        for (String to : recipients) {
            if (to == null || to.isBlank()) continue;
            try {
                MimeMessage message = mail.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                helper.setTo(to);
                if (props.mailFrom() != null) helper.setFrom(props.mailFrom());
                helper.setSubject(email.subject());
                helper.setText(email.html(), true);
                mail.send(message);
                record(app, OnboardingEventType.EMAIL_SENT, "Email '" + email.subject() + "' sent to " + to, visible);
            } catch (Exception e) {
                log.warn("Onboarding email to {} failed: {}", to, e.getMessage());
                record(app, OnboardingEventType.EMAIL_FAILED, "Email '" + email.subject() + "' to " + to + " failed", false);
            }
        }
    }

    private void record(OnboardingApplication app, OnboardingEventType type, String message, boolean visible) {
        try {
            timeline.record(app, type, Actor.system(), null, null, message, visible);
        } catch (RuntimeException e) {
            log.warn("Could not record onboarding email outcome for {}: {}", app.getApplicationId(), e.getMessage());
        }
    }
}

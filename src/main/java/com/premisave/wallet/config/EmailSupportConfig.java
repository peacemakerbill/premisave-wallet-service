package com.premisave.wallet.config;

import com.premisave.wallet.util.EmailLayout;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Hands the support address to the email layout, which is a plain class with no Spring wiring. */
@Configuration
class EmailSupportConfig {

    @Value("${email.support:thepeacemakerske@gmail.com}")
    private String supportEmail;

    @PostConstruct
    void apply() {
        EmailLayout.setSupportEmail(supportEmail);
    }
}

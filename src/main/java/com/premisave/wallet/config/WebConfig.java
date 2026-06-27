package com.premisave.wallet.config;

import com.premisave.wallet.util.RateLimiterInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiterInterceptor rateLimiterInterceptor;

    public WebConfig(RateLimiterInterceptor rateLimiterInterceptor) {
        this.rateLimiterInterceptor = rateLimiterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimiterInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/system/health",
                    "/system/health/details",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    // Payment provider callbacks should bypass rate limiting
                    // — they come from Safaricom/Stripe/PayPal servers, not end users
                    "/payments/mpesa/callback",
                    "/payments/mpesa/b2c/result",
                    "/payments/mpesa/b2c/timeout",
                    "/payments/mpesa/c2b/validation",
                    "/payments/mpesa/c2b/confirmation",
                    "/payments/stripe/webhook",
                    "/payments/paypal/webhook"
                );
    }
}
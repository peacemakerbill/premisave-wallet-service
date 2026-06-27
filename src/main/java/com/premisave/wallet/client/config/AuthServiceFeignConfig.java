package com.premisave.wallet.client.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign configuration for the Auth Service client.
 * Injects the X-API-Key header on every outbound request so the
 * auth service's ApiKeyFilter accepts the call.
 *
 * Wired via the @FeignClient(configuration = ...) attribute —
 * NOT registered as a global @Configuration to avoid applying to
 * other Feign clients if you add more later.
 */
@Configuration
public class AuthServiceFeignConfig {

    @Value("${auth.service.api-key}")
    private String apiKey;

    @Bean
    public RequestInterceptor apiKeyInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                template.header("X-API-Key", apiKey);
            }
        };
    }
}
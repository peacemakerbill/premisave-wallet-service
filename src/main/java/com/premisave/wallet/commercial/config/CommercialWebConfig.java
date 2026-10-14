package com.premisave.wallet.commercial.config;

import com.premisave.wallet.commercial.security.CommercialRequestInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies the environment and merchant context checks to every environment scoped commercial route. */
@Configuration
@EnableConfigurationProperties(CommercialProperties.class)
public class CommercialWebConfig implements WebMvcConfigurer {

    private final CommercialRequestInterceptor interceptor;

    public CommercialWebConfig(CommercialRequestInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/internal/commercial/v1/live/**", "/internal/commercial/v1/sandbox/**");
    }
}

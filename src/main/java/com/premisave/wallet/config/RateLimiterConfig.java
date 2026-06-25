package com.premisave.wallet.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimiterConfig {

    @Value("${rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @SuppressWarnings("deprecation")
	@Bean
    public Bucket rateLimiterBucket() {
        Refill refill = Refill.intervally(requestsPerMinute, Duration.ofMinutes(1));
        Bandwidth bandwidth = Bandwidth.classic(requestsPerMinute, refill);
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
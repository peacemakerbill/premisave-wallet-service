package com.premisave.wallet.config;

import com.premisave.wallet.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Value("${frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth

                // ── Public: health & docs ──────────────────────────────────
                .requestMatchers(
                    "/system/health",
                    "/system/health/details",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/swagger-ui.html"
                ).permitAll()

                // ── Public: payment provider callbacks (no JWT — IP-secured at gateway) ──
                .requestMatchers(
                    "/payments/mpesa/callback",
                    "/payments/mpesa/b2c/result",
                    "/payments/mpesa/b2c/timeout",
                    "/payments/mpesa/c2b/validation",
                    "/payments/mpesa/c2b/confirmation",
                    "/payments/stripe/webhook",
                    "/payments/paypal/webhook"
                ).permitAll()

                // ── Authenticated users ────────────────────────────────────
                .requestMatchers(
                    "/wallet/**",
                    "/payments/**",
                    "/disbursements/**",
                    "/transactions/**",
                    "/system/test-token"
                ).authenticated()

                // ── Admin / Finance / Operations only ──────────────────────
                .requestMatchers("/admin/**")
                    .hasAnyRole("ADMIN", "FINANCE", "OPERATIONS")

                // ── C2B URL registration — admin-triggered, requires auth ──
                .requestMatchers("/payments/mpesa/c2b/register-urls")
                    .hasAnyRole("ADMIN", "OPERATIONS")

                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendUrl));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
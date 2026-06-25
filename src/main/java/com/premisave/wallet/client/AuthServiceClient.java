package com.premisave.wallet.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Optional;

@FeignClient(name = "auth-service", url = "${auth.service.url}")
public interface AuthServiceClient {

    @GetMapping("/auth/users/{email}/details")
    Optional<UserDetailsDto> getUserDetails(@PathVariable String email);
}

// Inner DTO
record UserDetailsDto(String id, String email, String role, boolean active) {}
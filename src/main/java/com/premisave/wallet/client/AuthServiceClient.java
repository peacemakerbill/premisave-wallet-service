package com.premisave.wallet.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.premisave.wallet.dto.client.UserDetailsDto;

import java.util.Optional;

@FeignClient(name = "auth-service", url = "${auth.service.url}")
public interface AuthServiceClient {

    @GetMapping("/auth/users/{email}/details")
    Optional<UserDetailsDto> getUserDetails(@PathVariable String email);
}
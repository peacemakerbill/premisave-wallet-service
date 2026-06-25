package com.premisave.wallet.security;

import com.premisave.wallet.client.AuthServiceClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AuthServiceClient authServiceClient;

    public UserDetailsServiceImpl(AuthServiceClient authServiceClient) {
        this.authServiceClient = authServiceClient;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Delegate to Auth Service via Feign
        return authServiceClient.getUserDetails(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
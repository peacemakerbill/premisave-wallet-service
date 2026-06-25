package com.premisave.wallet.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds UserDetails purely from JWT claims already validated by JwtAuthFilter.
 * No Feign call to auth service needed — the shared JWT_SECRET means the token
 * is already trusted by the time this is called.
 *
 * Username is passed in as "email::ROLE_X" (packed by JwtAuthFilter).
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final String DELIMITER = "::";

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || !username.contains(DELIMITER)) {
            throw new UsernameNotFoundException("Invalid username format: " + username);
        }

        String[] parts = username.split(DELIMITER, 2);
        String email = parts[0];
        String role  = parts[1]; // already prefixed e.g. ROLE_CLIENT

        return User.builder()
                .username(email)
                .password("")  // no password needed — JWT is already authenticated
                .authorities(List.of(new SimpleGrantedAuthority(role)))
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}
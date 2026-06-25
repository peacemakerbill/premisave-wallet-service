package com.premisave.wallet.dto.client;

import com.premisave.wallet.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * DTO received from Auth Service via Feign.
 * Mirrors the Auth Service's User entity UserDetails implementation exactly.
 */
public record UserDetailsDto(
        String id,
        String email,
        Role role,
        boolean active,
        boolean verified
) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        // Password is never returned from the auth service to other services
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    // Mirrors User entity: all three return true unconditionally
    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    // Mirrors User entity: active AND verified must both be true
    @Override
    public boolean isEnabled() {
        return active && verified;
    }
}
package com.premisave.wallet.service;

import com.premisave.wallet.client.AuthServiceClient;
import com.premisave.wallet.dto.client.UserDetailsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared, best-effort lookup of a real user's full name via the auth
 * service — every deposit, disbursement, payment, and transfer flow
 * needs the exact same thing: resolve a wallet owner's name, given
 * their email (Wallet.accountNumber), for inclusion in a confirmation
 * email and for persisting alongside the transaction record. Originally
 * built inline in TransferService for that one flow; extracted here so
 * every other caller shares the identical, already-tested logic rather
 * than each service re-implementing its own version.
 *
 * A failed or empty lookup must NEVER break the underlying transaction —
 * same principle as email sending itself. Returns null on any failure
 * (network error, account not found, no name on file) — EmailService
 * already treats a null/blank name as "omit this row" everywhere it's
 * used, and every caller here should persist null in that same case
 * rather than a placeholder string.
 *
 * Deliberately exposes ONLY the resolved name — never active, verified,
 * or role from UserDetailsDto. Callers of this class never see the rest
 * of that DTO at all, so there's no risk of a caller accidentally
 * surfacing sensitive account-status fields in an email or a saved
 * record; this class is the one place that talks to AuthServiceClient
 * directly for this purpose, and it only ever hands back a name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNameResolver {

    private final AuthServiceClient authServiceClient;

    /**
     * @param email the account's email (Wallet.accountNumber) to look up.
     * @return the resolved full name, or null if the lookup failed, the
     *         email was null/blank, or the account has no name on file.
     */
    public String resolveNameSafely(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            return authServiceClient.getUserDetails(email)
                    .map(UserDetailsDto::fullName)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Name lookup failed for email={} — proceeding without it: {}", email, e.getMessage());
            return null;
        }
    }
}
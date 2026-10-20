package com.premisave.wallet.commercial.service.link;

import com.premisave.wallet.commercial.entity.link.AccountPrefix;
import com.premisave.wallet.commercial.repository.link.AccountPrefixRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Gives each merchant and environment a short prefix once. A unique index on the prefix is what
 * keeps two merchants from sharing one, so a clash is simply retried with another number.
 */
@Component("commercialAccountPrefixAllocator")
public class AccountPrefixAllocator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ATTEMPTS_PER_WIDTH = 25;

    private final AccountPrefixRepository repo;

    public AccountPrefixAllocator(AccountPrefixRepository repo) {
        this.repo = repo;
    }

    public String prefixFor(String merchantId, boolean livemode) {
        String key = AccountPrefix.keyFor(merchantId, livemode);
        Optional<AccountPrefix> existing = repo.findById(key);
        if (existing.isPresent()) return existing.get().getPrefix();

        for (int digits = 4; digits <= 8; digits++) {
            for (int i = 0; i < ATTEMPTS_PER_WIDTH; i++) {
                String candidate = "PS" + randomDigits(digits);
                try {
                    repo.insert(new AccountPrefix(key, candidate));
                    return candidate;
                } catch (DuplicateKeyException clash) {
                    // Either another request just created this merchant's prefix or the number is taken.
                    Optional<AccountPrefix> now = repo.findById(key);
                    if (now.isPresent()) return now.get().getPrefix();
                }
            }
        }
        throw new IllegalStateException("Could not allocate an account reference prefix");
    }

    static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        sb.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < n; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }
}

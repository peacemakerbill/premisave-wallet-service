package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
    private String id;
    private String accountNumber;
    private String userId;
    private BigDecimal balance;
    private Currency currency;
    private boolean isFrozen;

    /** Null if never set. */
    private String paypalEmail;

    /** True once a card is saved for one-click deposit reloads. */
    private boolean hasSavedCard;

    /** Display-only, null if hasSavedCard is false. Never expose raw Stripe IDs here. */
    private String cardBrand;
    private String cardLast4;
}
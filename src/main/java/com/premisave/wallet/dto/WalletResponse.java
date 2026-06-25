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
}
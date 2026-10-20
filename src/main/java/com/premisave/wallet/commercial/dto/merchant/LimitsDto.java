package com.premisave.wallet.commercial.dto.merchant;

import java.math.BigDecimal;

public record LimitsDto(BigDecimal singleTransactionUsd, BigDecimal dailyVolumeUsd, BigDecimal monthlyVolumeUsd,
                        Integer dailyPayoutCount) {
}

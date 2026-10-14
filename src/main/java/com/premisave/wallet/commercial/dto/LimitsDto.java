package com.premisave.wallet.commercial.dto;

import java.math.BigDecimal;

public record LimitsDto(BigDecimal singleTransactionUsd, BigDecimal dailyVolumeUsd, BigDecimal monthlyVolumeUsd,
                        Integer dailyPayoutCount) {
}

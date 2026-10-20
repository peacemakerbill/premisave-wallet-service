package com.premisave.wallet.commercial.dto.batch;

import com.premisave.wallet.commercial.entity.batch.PayoutBatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** callbackUrl receives the payout.batch.completed event; the items' own events go to the registered callbacks. */
public record BulkPayoutRequest(
        @NotBlank @Size(max = 64) String merchantReference,
        @NotNull PayoutBatch.Mode mode,
        @Size(max = 2048) String callbackUrl,
        @NotNull @Size(min = 1, max = 500) @Valid List<BulkPayoutItem> items) {
}

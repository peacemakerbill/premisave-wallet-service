package com.premisave.wallet.commercial.rail;

import java.util.List;
import java.util.Map;

/** Evidence for a dispute, sent to the provider that raised it. Each entry has type, text and attachments. */
public record DisputeEvidenceCommand(String disputeId, String providerDisputeId, String paymentId,
                                     String paymentProviderReference, boolean livemode,
                                     List<Map<String, Object>> evidence) {
}

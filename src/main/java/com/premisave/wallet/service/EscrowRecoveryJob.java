package com.premisave.wallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles escrow holds and payouts that a request left PENDING (for example because the service restarted in the
 * middle of one), so none stays pending forever. See EscrowService.recoverStale. Safe on several instances at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscrowRecoveryJob {

    private final EscrowService escrowService;

    @Scheduled(fixedDelayString = "${escrow.recovery-interval-ms:60000}", initialDelayString = "${escrow.recovery-interval-ms:60000}")
    public void run() {
        try {
            escrowService.recoverStale();
        } catch (RuntimeException e) {
            log.error("Escrow recovery sweep failed; it will run again", e);
        }
    }
}

package com.premisave.wallet.exception;

/**
 * The calling service's initiatedBy is not in escrow.allowed-initiators (see EscrowProperties). Maps to 403 in
 * GlobalExceptionHandler, with this message, so the caller can tell why it was refused.
 */
@SuppressWarnings("serial")
public class EscrowCallerNotAllowedException extends RuntimeException {
    public EscrowCallerNotAllowedException(String message) {
        super(message);
    }
}

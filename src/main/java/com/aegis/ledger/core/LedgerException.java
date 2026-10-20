package com.aegis.ledger.core;

public class LedgerException extends RuntimeException {

    private final ErrorCode code;
    private final boolean replayed;

    public LedgerException(ErrorCode code, String message) {
        this(code, message, false);
    }

    public LedgerException(ErrorCode code, String message, boolean replayed) {
        super(message);
        this.code = code;
        this.replayed = replayed;
    }

    public ErrorCode code() {
        return code;
    }

    /** True when this is a stored rejection being returned for a retried idempotency key. */
    public boolean replayed() {
        return replayed;
    }
}

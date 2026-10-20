package com.aegis.ledger.core;

/**
 * Stable error codes returned to clients.
 *
 * {@code cachedForIdempotency} marks outcomes that depend on ledger state at
 * the time of the request. Those are stored against the idempotency key so a
 * retry gets the same answer. Validation errors aren't stored because the same
 * payload always fails the same way anyway, and transient errors (contention,
 * clock skew) must stay retryable.
 */
public enum ErrorCode {
    VALIDATION_FAILED(400, false),
    UNBALANCED(400, false),
    LEDGER_NOT_FOUND(404, false),
    ACCOUNT_NOT_FOUND(404, true),
    TRANSACTION_NOT_FOUND(404, false),
    DUPLICATE_NAME(409, false),
    IDEMPOTENCY_KEY_REUSED(409, false),
    ALREADY_REVERSED(409, true),
    LEDGER_MISMATCH(422, true),
    INSUFFICIENT_FUNDS(422, true),
    CONTENTION(503, false),
    CLOCK_SKEW(503, false);

    private final int httpStatus;
    private final boolean cachedForIdempotency;

    ErrorCode(int httpStatus, boolean cachedForIdempotency) {
        this.httpStatus = httpStatus;
        this.cachedForIdempotency = cachedForIdempotency;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean cachedForIdempotency() {
        return cachedForIdempotency;
    }
}

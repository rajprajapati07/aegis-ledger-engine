package com.aegis.ledger.core;

/**
 * Checks that don't need the database. Run before anything touches a
 * connection, so a malformed request never claims an idempotency key.
 */
public final class PostingValidator {

    public static final int MAX_ENTRIES = 64;
    public static final int MAX_KEY_LENGTH = 128;

    private PostingValidator() {
    }

    public static void validate(PostingRequest request) {
        if (request == null) {
            throw invalid("request body is required");
        }
        if (isBlank(request.ledgerId())) {
            throw invalid("ledger_id is required");
        }
        String key = request.idempotencyKey();
        if (isBlank(key)) {
            throw invalid("idempotency_key is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw invalid("idempotency_key longer than " + MAX_KEY_LENGTH + " characters");
        }

        var entries = request.entries();
        if (entries.size() < 2) {
            throw invalid("a transaction needs at least two entries");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw invalid("a transaction can have at most " + MAX_ENTRIES + " entries");
        }

        long debits = 0;
        long credits = 0;
        for (int i = 0; i < entries.size(); i++) {
            EntryRequest e = entries.get(i);
            if (e == null || isBlank(e.accountId())) {
                throw invalid("entries[" + i + "].account_id is required");
            }
            if (e.direction() == null) {
                throw invalid("entries[" + i + "].direction must be DEBIT or CREDIT");
            }
            if (e.amountMinor() <= 0) {
                throw invalid("entries[" + i + "].amount must be greater than zero");
            }
            try {
                if (e.direction() == Direction.DEBIT) {
                    debits = Math.addExact(debits, e.amountMinor());
                } else {
                    credits = Math.addExact(credits, e.amountMinor());
                }
            } catch (ArithmeticException overflow) {
                throw invalid("entry amounts overflow");
            }
        }

        if (debits != credits) {
            throw new LedgerException(ErrorCode.UNBALANCED,
                    "debits (%d) and credits (%d) must be equal, in minor units".formatted(debits, credits));
        }
    }

    private static LedgerException invalid(String message) {
        return new LedgerException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

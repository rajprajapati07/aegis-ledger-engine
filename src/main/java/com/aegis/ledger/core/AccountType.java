package com.aegis.ledger.core;

/**
 * Account categories and their normal side.
 *
 * Balances are stored in the account's natural orientation: a debit to an
 * ASSET makes its balance go up, a debit to a LIABILITY makes it go down.
 * That way "allow_negative = false" just means the stored number can't
 * drop below zero, whatever the account type.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    EXPENSE(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    public Direction normalSide() {
        return normalSide;
    }

    /** Signed change to the natural-orientation balance for one entry. */
    public long naturalDelta(Direction direction, long amountMinor) {
        return direction == normalSide ? amountMinor : -amountMinor;
    }

    /**
     * Converts a natural-orientation balance to debit-positive orientation.
     * Summing this over every account in a ledger must give zero.
     */
    public long toDebitOriented(long naturalBalance) {
        return normalSide == Direction.DEBIT ? naturalBalance : -naturalBalance;
    }

    public static AccountType parse(String raw) {
        if (raw == null) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "account type is required");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "unknown account type: " + raw);
        }
    }
}

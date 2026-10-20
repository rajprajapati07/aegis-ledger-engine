package com.aegis.ledger.core;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Converts between the API's decimal strings and the int64 minor units the
 * engine works in. The exponent comes from ISO 4217 (USD 2, JPY 0, BHD 3).
 *
 * Deliberately strict: "150.001" in USD is an error, not a rounding question,
 * and so is "1e2". Anything the client sends that we'd have to guess about
 * gets rejected.
 */
public final class Amounts {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d{1,19}(\\.\\d{1,6})?");

    private Amounts() {
    }

    public static int exponent(String currencyCode) {
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "unknown currency: " + currencyCode);
        }
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            // XAU, XDR and friends have no minor unit; not something a payments ledger books.
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "currency has no minor unit: " + currencyCode);
        }
        return digits;
    }

    public static long toMinor(String amount, String currencyCode) {
        if (amount == null || !PLAIN_DECIMAL.matcher(amount).matches()) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED,
                    "amount must be a positive plain decimal string, got: " + amount);
        }
        int exp = exponent(currencyCode);
        BigDecimal value = new BigDecimal(amount);
        if (value.stripTrailingZeros().scale() > exp) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED,
                    "%s allows %d decimal places, got %s".formatted(currencyCode, exp, amount));
        }
        long minor;
        try {
            minor = value.movePointRight(exp).longValueExact();
        } catch (ArithmeticException e) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "amount out of range: " + amount);
        }
        if (minor <= 0) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "amount must be greater than zero");
        }
        return minor;
    }

    public static String format(long minor, String currencyCode) {
        return BigDecimal.valueOf(minor, exponent(currencyCode)).toPlainString();
    }
}

package com.aegis.ledger.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmountsTest {

    @Test
    void usesTheCurrencysMinorUnit() {
        assertEquals(15000, Amounts.toMinor("150.00", "USD"));
        assertEquals(15000, Amounts.toMinor("150", "USD"));
        assertEquals(150, Amounts.toMinor("150", "JPY"));
        assertEquals(1500, Amounts.toMinor("1.500", "BHD"));
    }

    @Test
    void trailingZerosBeyondTheExponentAreFine() {
        // "1.50" and "1.500" are the same amount; only real extra precision is an error
        assertEquals(150, Amounts.toMinor("1.500", "USD"));
    }

    @Test
    void refusesToRoundExtraPrecision() {
        var e = assertThrows(LedgerException.class, () -> Amounts.toMinor("150.001", "USD"));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-5.00", "0", "0.00", "1e2", "1,000.00", " 10", "", "abc", "10."})
    void rejectsAnythingThatIsntAPositivePlainDecimal(String raw) {
        assertThrows(LedgerException.class, () -> Amounts.toMinor(raw, "USD"));
    }

    @Test
    void rejectsAmountsThatDontFitInALong() {
        assertThrows(LedgerException.class, () -> Amounts.toMinor("9999999999999999999", "USD"));
    }

    @Test
    void rejectsUnknownAndUnbookableCurrencies() {
        assertThrows(LedgerException.class, () -> Amounts.exponent("ABC"));
        assertThrows(LedgerException.class, () -> Amounts.exponent("XAU"));
    }

    @Test
    void formatsBackToTheSameString() {
        assertEquals("150.00", Amounts.format(15000, "USD"));
        assertEquals("0.05", Amounts.format(5, "USD"));
        assertEquals("150", Amounts.format(150, "JPY"));
    }
}

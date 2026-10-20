package com.aegis.ledger.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.aegis.ledger.core.Direction.CREDIT;
import static com.aegis.ledger.core.Direction.DEBIT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostingValidatorTest {

    private static PostingRequest req(EntryRequest... entries) {
        return new PostingRequest("led_1", "key-1", List.of(entries));
    }

    @Test
    void balancedTransactionPasses() {
        assertDoesNotThrow(() -> PostingValidator.validate(req(
                new EntryRequest("acc_a", DEBIT, 15000),
                new EntryRequest("acc_b", CREDIT, 15000))));
    }

    @Test
    void splitLegsCanBalance() {
        // one debit, three credits: payment, platform fee, FX spread
        assertDoesNotThrow(() -> PostingValidator.validate(req(
                new EntryRequest("wallet", DEBIT, 10000),
                new EntryRequest("merchant", CREDIT, 9700),
                new EntryRequest("fees", CREDIT, 250),
                new EntryRequest("spread", CREDIT, 50))));
    }

    @Test
    void unbalancedIsItsOwnErrorCode() {
        var e = assertThrows(LedgerException.class, () -> PostingValidator.validate(req(
                new EntryRequest("acc_a", DEBIT, 15000),
                new EntryRequest("acc_b", CREDIT, 14999))));
        assertEquals(ErrorCode.UNBALANCED, e.code());
    }

    @Test
    void singleEntryIsRejected() {
        var e = assertThrows(LedgerException.class, () -> PostingValidator.validate(req(
                new EntryRequest("acc_a", DEBIT, 100))));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() {
        assertThrows(LedgerException.class, () -> PostingValidator.validate(req(
                new EntryRequest("acc_a", DEBIT, 0),
                new EntryRequest("acc_b", CREDIT, 0))));
        // negative amounts would let a "debit" act as a credit and sneak past direction rules
        assertThrows(LedgerException.class, () -> PostingValidator.validate(req(
                new EntryRequest("acc_a", DEBIT, -100),
                new EntryRequest("acc_b", CREDIT, -100))));
    }

    @Test
    void missingKeyOrLedgerIsRejected() {
        var entries = List.of(new EntryRequest("a", DEBIT, 1), new EntryRequest("b", CREDIT, 1));
        assertThrows(LedgerException.class, () -> PostingValidator.validate(new PostingRequest("led", " ", entries)));
        assertThrows(LedgerException.class, () -> PostingValidator.validate(new PostingRequest(null, "k", entries)));
    }

    @Test
    void entryCountIsCapped() {
        List<EntryRequest> many = new ArrayList<>();
        for (int i = 0; i < PostingValidator.MAX_ENTRIES; i++) {
            many.add(new EntryRequest("acc_" + i, i % 2 == 0 ? DEBIT : CREDIT, 1));
        }
        assertDoesNotThrow(() -> PostingValidator.validate(new PostingRequest("led", "k", many)));

        many.add(new EntryRequest("acc_x", DEBIT, 1));
        many.add(new EntryRequest("acc_y", CREDIT, 1));
        assertThrows(LedgerException.class, () -> PostingValidator.validate(new PostingRequest("led", "k", many)));
    }

    @Test
    void overflowIsCaughtNotWrapped() {
        var e = assertThrows(LedgerException.class, () -> PostingValidator.validate(req(
                new EntryRequest("a", DEBIT, Long.MAX_VALUE),
                new EntryRequest("b", DEBIT, 1),
                new EntryRequest("c", CREDIT, 1))));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    }
}

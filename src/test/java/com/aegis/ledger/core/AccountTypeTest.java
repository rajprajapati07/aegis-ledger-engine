package com.aegis.ledger.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.aegis.ledger.core.Direction.CREDIT;
import static com.aegis.ledger.core.Direction.DEBIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTypeTest {

    @Test
    void walletsGoUpOnCreditAndDownOnDebit() {
        assertEquals(100, AccountType.LIABILITY.naturalDelta(CREDIT, 100));
        assertEquals(-100, AccountType.LIABILITY.naturalDelta(DEBIT, 100));
    }

    @Test
    void reservesGoUpOnDebit() {
        assertEquals(100, AccountType.ASSET.naturalDelta(DEBIT, 100));
        assertEquals(-100, AccountType.ASSET.naturalDelta(CREDIT, 100));
    }

    @Test
    void aBalancedPostingNetsToZeroInDebitOrientation() {
        // fund a wallet from bank reserves: DEBIT asset 500, CREDIT liability 500
        long asset = AccountType.ASSET.naturalDelta(DEBIT, 500);
        long wallet = AccountType.LIABILITY.naturalDelta(CREDIT, 500);
        assertEquals(0, AccountType.ASSET.toDebitOriented(asset) + AccountType.LIABILITY.toDebitOriented(wallet));
    }

    @Test
    void parseIsCaseInsensitive() {
        assertEquals(AccountType.REVENUE, AccountType.parse(" revenue "));
    }

    @Test
    void idsArePrefixedTimeOrderedAndUnique() {
        Set<String> seen = new HashSet<>();
        String prev = Ids.next("tx");
        for (int i = 0; i < 10_000; i++) {
            String id = Ids.next("tx");
            assertTrue(id.startsWith("tx_"));
            assertEquals(29, id.length());
            assertTrue(seen.add(id));
            // first 10 chars are the timestamp, so they never go backwards
            assertTrue(id.substring(3, 13).compareTo(prev.substring(3, 13)) >= 0);
            prev = id;
        }
    }
}

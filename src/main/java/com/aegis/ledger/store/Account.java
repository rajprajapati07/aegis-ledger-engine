package com.aegis.ledger.store;

import com.aegis.ledger.core.AccountType;

public record Account(
        String accountId,
        String ledgerId,
        String currency,
        AccountType type,
        boolean allowNegative,
        int bucketCount) {
}

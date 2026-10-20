package com.aegis.ledger.core;

public record EntryRequest(String accountId, Direction direction, long amountMinor) {
}

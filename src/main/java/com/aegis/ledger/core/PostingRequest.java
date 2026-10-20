package com.aegis.ledger.core;

import java.util.List;

public record PostingRequest(String ledgerId, String idempotencyKey, List<EntryRequest> entries) {

    public PostingRequest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}

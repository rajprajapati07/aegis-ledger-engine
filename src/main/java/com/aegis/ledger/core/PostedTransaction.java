package com.aegis.ledger.core;

import java.util.List;

/**
 * A committed transaction as stored in the journal.
 *
 * @param replayed true when this came back from an idempotency key that was
 *                 already used, i.e. nothing new was written on this call
 */
public record PostedTransaction(
        String transactionId,
        String ledgerId,
        long commitHlc,
        String reversesTransactionId,
        List<EntryRequest> entries,
        boolean replayed) {

    public PostedTransaction asReplay() {
        return new PostedTransaction(transactionId, ledgerId, commitHlc, reversesTransactionId, entries, true);
    }
}

package com.aegis.ledger.api;

import java.util.List;

/**
 * Request/response shapes. Field names follow the SRS (snake_case on the
 * wire via Jackson's SNAKE_CASE strategy in application.yml). Amounts are
 * decimal strings at the edge and int64 minor units everywhere inside.
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateLedgerRequest(String name, String currency) {
    }

    public record LedgerResponse(String ledgerId, String name, String currency) {
    }

    public record TrialBalanceResponse(String ledgerId, String currency, String netDebitMinusCredit, boolean balanced) {
    }

    public record CreateAccountRequest(String ledgerId, String type, Boolean allowNegative, Integer buckets) {
    }

    public record AccountResponse(String accountId, String ledgerId, String type, boolean allowNegative,
                                  int buckets, String currency, String balance) {
    }

    public record EntryDto(String accountId, String direction, String amount) {
    }

    public record PostTransactionRequest(String idempotencyKey, String ledgerId, List<EntryDto> entries) {
    }

    public record TransactionResponse(String transactionId, String ledgerId, String status, long commitHlc,
                                      String reversesTransactionId, List<EntryDto> entries) {
    }

    public record ErrorResponse(String error, String message) {
    }
}

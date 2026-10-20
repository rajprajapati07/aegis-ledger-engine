package com.aegis.ledger.api;

import com.aegis.ledger.api.Dtos.EntryDto;
import com.aegis.ledger.api.Dtos.PostTransactionRequest;
import com.aegis.ledger.api.Dtos.TransactionResponse;
import com.aegis.ledger.core.Amounts;
import com.aegis.ledger.core.Direction;
import com.aegis.ledger.core.EntryRequest;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.core.PostedTransaction;
import com.aegis.ledger.core.PostingRequest;
import com.aegis.ledger.store.Ledger;
import com.aegis.ledger.store.LedgerStore;
import com.aegis.ledger.store.PostingEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PostingEngine engine;
    private final LedgerStore store;

    public TransactionController(PostingEngine engine, LedgerStore store) {
        this.engine = engine;
        this.store = store;
    }

    /**
     * 201 for a new transaction, 200 with Idempotent-Replayed: true for a
     * retry. The SRS returns 200 for both, which leaves clients unable to
     * tell whether their call did anything.
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> post(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String headerKey,
            @RequestBody PostTransactionRequest body) {
        if (body == null) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "request body is required");
        }
        String key = headerKey != null && !headerKey.isBlank() ? headerKey : body.idempotencyKey();
        Ledger ledger = loadLedger(body.ledgerId());

        List<EntryRequest> entries = new ArrayList<>();
        List<EntryDto> raw = body.entries() == null ? List.of() : body.entries();
        for (int i = 0; i < raw.size(); i++) {
            EntryDto e = raw.get(i);
            if (e == null) {
                throw new LedgerException(ErrorCode.VALIDATION_FAILED, "entries[" + i + "] is null");
            }
            entries.add(new EntryRequest(e.accountId(), parseDirection(e.direction(), i),
                    Amounts.toMinor(e.amount(), ledger.currency())));
        }

        PostedTransaction tx = engine.post(new PostingRequest(ledger.ledgerId(), key, entries));
        return respond(tx, ledger.currency());
    }

    @GetMapping("/{transactionId}")
    public TransactionResponse get(@PathVariable("transactionId") String transactionId) {
        PostedTransaction tx = store.findTransaction(transactionId)
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND, "no transaction " + transactionId));
        return toResponse(tx, loadLedger(tx.ledgerId()).currency());
    }

    @PostMapping("/{transactionId}/reversals")
    public ResponseEntity<TransactionResponse> reverse(
            @PathVariable("transactionId") String transactionId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        PostedTransaction tx = engine.reverse(transactionId, key);
        return respond(tx, loadLedger(tx.ledgerId()).currency());
    }

    private ResponseEntity<TransactionResponse> respond(PostedTransaction tx, String currency) {
        if (tx.replayed()) {
            return ResponseEntity.status(HttpStatus.OK)
                    .header(REPLAYED_HEADER, "true")
                    .body(toResponse(tx, currency));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tx, currency));
    }

    private Ledger loadLedger(String ledgerId) {
        if (ledgerId == null || ledgerId.isBlank()) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "ledger_id is required");
        }
        return store.findLedger(ledgerId)
                .orElseThrow(() -> new LedgerException(ErrorCode.LEDGER_NOT_FOUND, "no ledger " + ledgerId));
    }

    private static Direction parseDirection(String raw, int index) {
        if (raw != null) {
            switch (raw.trim().toUpperCase()) {
                case "DEBIT" -> {
                    return Direction.DEBIT;
                }
                case "CREDIT" -> {
                    return Direction.CREDIT;
                }
                default -> {
                }
            }
        }
        throw new LedgerException(ErrorCode.VALIDATION_FAILED,
                "entries[" + index + "].direction must be DEBIT or CREDIT");
    }

    private static TransactionResponse toResponse(PostedTransaction tx, String currency) {
        List<EntryDto> entries = tx.entries().stream()
                .map(e -> new EntryDto(e.accountId(), e.direction().name(), Amounts.format(e.amountMinor(), currency)))
                .toList();
        return new TransactionResponse(tx.transactionId(), tx.ledgerId(), "COMMITTED", tx.commitHlc(),
                tx.reversesTransactionId(), entries);
    }
}

package com.aegis.ledger.api;

import com.aegis.ledger.api.Dtos.CreateLedgerRequest;
import com.aegis.ledger.api.Dtos.LedgerResponse;
import com.aegis.ledger.api.Dtos.TrialBalanceResponse;
import com.aegis.ledger.core.Amounts;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.store.Ledger;
import com.aegis.ledger.store.LedgerStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers")
public class LedgerController {

    private final LedgerStore store;

    public LedgerController(LedgerStore store) {
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<LedgerResponse> create(@RequestBody CreateLedgerRequest body) {
        if (body == null) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "request body is required");
        }
        Ledger ledger = store.createLedger(body.name(), body.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(ledger));
    }

    @GetMapping("/{ledgerId}")
    public LedgerResponse get(@PathVariable("ledgerId") String ledgerId) {
        return toResponse(load(ledgerId));
    }

    /** Should always come back balanced. If it doesn't, something is very wrong and we want to know. */
    @GetMapping("/{ledgerId}/trial-balance")
    public TrialBalanceResponse trialBalance(@PathVariable("ledgerId") String ledgerId) {
        Ledger ledger = load(ledgerId);
        long net = store.trialBalance(ledgerId);
        String formatted = (net < 0 ? "-" : "") + Amounts.format(Math.abs(net), ledger.currency());
        return new TrialBalanceResponse(ledgerId, ledger.currency(), formatted, net == 0);
    }

    private Ledger load(String ledgerId) {
        return store.findLedger(ledgerId)
                .orElseThrow(() -> new LedgerException(ErrorCode.LEDGER_NOT_FOUND, "no ledger " + ledgerId));
    }

    private static LedgerResponse toResponse(Ledger l) {
        return new LedgerResponse(l.ledgerId(), l.name(), l.currency());
    }
}

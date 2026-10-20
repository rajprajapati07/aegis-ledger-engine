package com.aegis.ledger.api;

import com.aegis.ledger.api.Dtos.AccountResponse;
import com.aegis.ledger.api.Dtos.CreateAccountRequest;
import com.aegis.ledger.core.AccountType;
import com.aegis.ledger.core.Amounts;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.store.Account;
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
@RequestMapping("/v1/accounts")
public class AccountController {

    private final LedgerStore store;

    public AccountController(LedgerStore store) {
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@RequestBody CreateAccountRequest body) {
        if (body == null) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "request body is required");
        }
        if (body.allowNegative() == null) {
            // No default on purpose. Getting this wrong on a wallet is how
            // users end up overdrawn, so the caller has to say which they want.
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "allow_negative is required");
        }
        int buckets = body.buckets() == null ? 1 : body.buckets();
        Account account = store.createAccount(body.ledgerId(), AccountType.parse(body.type()),
                body.allowNegative(), buckets);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(account, 0L));
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable("accountId") String accountId) {
        Account account = store.findAccount(accountId)
                .orElseThrow(() -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + accountId));
        return toResponse(account, store.balance(accountId));
    }

    private static AccountResponse toResponse(Account a, long balanceMinor) {
        String balance = (balanceMinor < 0 ? "-" : "") + Amounts.format(Math.abs(balanceMinor), a.currency());
        return new AccountResponse(a.accountId(), a.ledgerId(), a.type().name(), a.allowNegative(),
                a.bucketCount(), a.currency(), balance);
    }
}

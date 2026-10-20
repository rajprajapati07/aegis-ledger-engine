package com.aegis.ledger.store;

/** Unexpected database failure. Anything a client can act on is a LedgerException instead. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

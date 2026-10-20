package com.aegis.ledger.api;

import com.aegis.ledger.api.Dtos.ErrorResponse;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.store.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ErrorResponse> ledger(LedgerException e) {
        var builder = ResponseEntity.status(e.code().httpStatus());
        if (e.replayed()) {
            builder = builder.header(TransactionController.REPLAYED_HEADER, "true");
        }
        return builder.body(new ErrorResponse(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", "request body is not valid JSON"));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> storage(StorageException e) {
        log.error("storage failure", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("STORAGE_UNAVAILABLE", "the ledger store is unavailable, retry with the same idempotency key"));
    }
}

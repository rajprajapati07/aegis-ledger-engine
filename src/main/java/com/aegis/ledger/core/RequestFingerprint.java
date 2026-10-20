package com.aegis.ledger.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of the parts of a request that decide what it does. Stored with the
 * idempotency key so that reusing a key for a different payload is caught
 * instead of silently returning the earlier result.
 *
 * Entry order is part of the hash on purpose. Clients that build the same
 * request the same way get the same hash; we don't try to be clever about
 * equivalent-but-reordered payloads.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static byte[] ofPosting(PostingRequest request) {
        StringBuilder sb = new StringBuilder(64 + request.entries().size() * 48);
        sb.append("post\n").append(request.ledgerId()).append('\n');
        for (EntryRequest e : request.entries()) {
            sb.append(e.accountId()).append('|')
              .append(e.direction()).append('|')
              .append(e.amountMinor()).append('\n');
        }
        return sha256(sb.toString());
    }

    public static byte[] ofReversal(String transactionId) {
        return sha256("reverse\n" + transactionId);
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JDK", e);
        }
    }
}

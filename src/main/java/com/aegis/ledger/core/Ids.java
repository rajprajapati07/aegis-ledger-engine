package com.aegis.ledger.core;

import java.security.SecureRandom;

/**
 * Prefixed, time-ordered IDs like {@code tx_01JB6Z3K8Q5N2W7XGQ4Y9R1T0M}.
 *
 * 48 bits of milliseconds followed by 80 random bits, Crockford base32.
 * Same layout as a ULID. Time-ordered keys keep B-tree inserts on the right
 * edge of the index instead of scattering them, which matters a lot for the
 * journal tables.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        long millis = System.currentTimeMillis();
        byte[] rand = new byte[10];
        RANDOM.nextBytes(rand);

        char[] out = new char[26];
        // 48-bit timestamp → 10 chars (50 bits, top 2 always zero)
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (millis & 31)];
            millis >>>= 5;
        }
        // 80 random bits → 16 chars
        long hi = 0;
        for (int i = 0; i < 5; i++) {
            hi = (hi << 8) | (rand[i] & 0xFF);
        }
        long lo = 0;
        for (int i = 5; i < 10; i++) {
            lo = (lo << 8) | (rand[i] & 0xFF);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return prefix + "_" + new String(out);
    }
}

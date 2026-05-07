package com.specter.extraction.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HKDF-SHA256 (RFC 5869) key derivation utility.
 *
 * Contract §2.2 — All sub-keys are derived from the master SPECTER_WM_KEY:
 *   - HKDF salt  : ASCII "project-specter-v1"
 *   - auth_key   : info = "payload-auth"       → 32 bytes
 *   - prng_key   : info = "block-selection"    → 32 bytes
 *   - interleave : info = "bit-interleaver"    → 32 bytes
 *
 * Java trap: byte is signed. Use (& 0xFF) when treating bytes as unsigned values.
 */
public final class KeyDerivationUtils {

    public static final String HKDF_SALT        = "project-specter-v1";
    public static final String INFO_AUTH        = "payload-auth";
    public static final String INFO_PRNG        = "block-selection";
    public static final String INFO_INTERLEAVER = "bit-interleaver";

    private static final String HMAC_ALG  = "HmacSHA256";
    private static final int    HASH_LEN  = 32; // SHA-256 output length in bytes

    private KeyDerivationUtils() {}

    // ─────────────────────────────────────────────────────────────────
    // Master key loading
    // ─────────────────────────────────────────────────────────────────

    /**
     * Loads the master key from the SPECTER_WM_KEY environment variable.
     * Contract §9: Key must be exactly 64 hex characters (256 bits).
     * Throws IllegalStateException if missing or malformed — causes 503 at startup.
     */
    public static byte[] loadMasterKey() {
        String raw = System.getenv("SPECTER_WM_KEY");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("SPECTER_WM_KEY");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "SPECTER_WM_KEY environment variable is not set. " +
                    "Service cannot start without the master watermark key.");
        }
        raw = raw.trim().toLowerCase();
        if (raw.length() != 64) {
            throw new IllegalStateException(
                    "SPECTER_WM_KEY must be exactly 64 hex characters (256 bits), " +
                    "but got " + raw.length() + " characters.");
        }
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(raw.charAt(i * 2),     16);
            int lo = Character.digit(raw.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalStateException(
                        "SPECTER_WM_KEY contains invalid (non-hex) character at position " + (i * 2));
            }
            key[i] = (byte) ((hi << 4) | lo);
        }
        return key;
    }

    // ─────────────────────────────────────────────────────────────────
    // Sub-key derivation helpers (contract §2.2)
    // ─────────────────────────────────────────────────────────────────

    /** Derives the 32-byte auth_key used for HMAC auth tag generation. */
    public static byte[] deriveAuthKey(byte[] masterKey) {
        return hkdf(masterKey, INFO_AUTH, HASH_LEN);
    }

    /** Derives the 32-byte prng_key used for cell/pair PRNG selection. */
    public static byte[] derivePrngKey(byte[] masterKey) {
        return hkdf(masterKey, INFO_PRNG, HASH_LEN);
    }

    /** Derives the 32-byte interleave_key used for bit permutation. */
    public static byte[] deriveInterleaverKey(byte[] masterKey) {
        return hkdf(masterKey, INFO_INTERLEAVER, HASH_LEN);
    }

    // ─────────────────────────────────────────────────────────────────
    // HKDF-SHA256 (RFC 5869)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Generic HKDF-SHA256 (RFC 5869)
     *
     * @param salt Optional salt (if null/empty, HashLen zeros are used)
     * @param ikm  Input Key Material
     * @param info Context information
     * @param len  Desired output length in bytes
     * @return     Derived key material
     */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len) {
        if (salt == null || salt.length == 0) {
            salt = new byte[HASH_LEN];
        }
        byte[] prk = hkdfExtract(salt, ikm);
        return hkdfExpand(prk, info != null ? info : new byte[0], len);
    }

    /**
     * Full HKDF-SHA256 with the fixed contract salt "project-specter-v1".
     *
     * @param ikm  Input Key Material (master key bytes)
     * @param info Context string (ASCII); distinguishes key purposes
     * @param len  Desired output length in bytes (≤ 255 × 32)
     * @return     Derived key material
     */
    public static byte[] hkdf(byte[] ikm, String info, int len) {
        byte[] salt    = HKDF_SALT.getBytes(StandardCharsets.US_ASCII);
        byte[] infoBytes = info.getBytes(StandardCharsets.US_ASCII);
        return hkdf(salt, ikm, infoBytes, len);
    }

    /** HKDF-Extract: PRK = HMAC-SHA256(salt, IKM) */
    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        return hmacSha256(salt, ikm);
    }

    /** HKDF-Expand: produces exactly `len` pseudo-random bytes from PRK. */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int len) {
        int n = (int) Math.ceil((double) len / HASH_LEN);
        byte[] okm = new byte[len];
        byte[] t   = new byte[0]; // T(0) = empty string
        int offset = 0;

        for (int i = 1; i <= n; i++) {
            // T(i) = HMAC-SHA256(PRK, T(i-1) || info || 0x{i})
            byte[] input = new byte[t.length + info.length + 1];
            System.arraycopy(t,    0, input, 0,              t.length);
            System.arraycopy(info, 0, input, t.length,       info.length);
            input[input.length - 1] = (byte) i;

            t = hmacSha256(prk, input);
            int copyLen = Math.min(HASH_LEN, len - offset);
            System.arraycopy(t, 0, okm, offset, copyLen);
            offset += copyLen;
        }
        return okm;
    }

    // ─────────────────────────────────────────────────────────────────
    // Raw HMAC-SHA256 (used internally and by other utils)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Computes HMAC-SHA256(key, data).
     * Exposed as package-visible so HmacAuthUtils and SpectralPrng can reuse it.
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }
}

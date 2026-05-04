package com.specter.embedder.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** RFC 5869 HKDF-SHA256 implementasyonu (extract + expand). Contract section 2.2. */
public final class Hkdf {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
        return expand(extract(salt, ikm), info, length);
    }

    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return hmac(effectiveSalt, ikm);
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("HKDF expand length too large: " + length);
        }
        ByteArrayOutputStream okm = new ByteArrayOutputStream(length);
        byte[] previous = new byte[0];
        int blocks = (length + HASH_LEN - 1) / HASH_LEN;
        for (int i = 1; i <= blocks; i++) {
            byte[] input = new byte[previous.length + info.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(info, 0, input, previous.length, info.length);
            input[input.length - 1] = (byte) i;
            previous = hmac(prk, input);
            okm.write(previous, 0, previous.length);
        }
        return Arrays.copyOf(okm.toByteArray(), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

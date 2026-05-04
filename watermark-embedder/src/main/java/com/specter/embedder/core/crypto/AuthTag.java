package com.specter.embedder.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

/**
 * 16-bit auth tag uretimi. Contract section 1:
 *   auth_tag = HMAC-SHA256(auth_key, uint32_be(watermark_id))[0:16 bit]
 */
public final class AuthTag {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final int TAG_BITS = 16;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private AuthTag() {
    }

    public static byte[] compute(byte[] authKey, long watermarkId) {
        byte[] idBytes = ByteBuffer.allocate(4).putInt((int) watermarkId).array();
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(authKey, HMAC_ALG));
            byte[] full = mac.doFinal(idBytes);
            byte[] truncated = new byte[TAG_BYTES];
            System.arraycopy(full, 0, truncated, 0, TAG_BYTES);
            return truncated;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

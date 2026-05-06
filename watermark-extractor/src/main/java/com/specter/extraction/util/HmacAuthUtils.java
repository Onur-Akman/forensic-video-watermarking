package com.specter.extraction.util;

/**
 * HMAC-based authentication tag generation and verification.
 *
 * Contract §1:
 *   auth_tag = HMAC-SHA256(auth_key, uint32_be(watermark_id))[0:16 bit]
 *
 * The first 16 bits (= first 2 bytes, MSB-first) of the HMAC output.
 *
 * Java trap: `int` is signed. watermark_id (0x00000000 .. 0xFFFFFFFF) must be
 * stored as `long` to avoid negative values (e.g. 0xA3F21B04 overflows int).
 * Always use `& 0xFFFFFFFFL` when reading from int-typed sources.
 */
public final class HmacAuthUtils {

    private HmacAuthUtils() {}

    // ─────────────────────────────────────────────────────────────────
    // Auth tag generation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Generate the 16-bit auth tag for a given watermark ID.
     *
     * @param authKey     32-byte key derived via HKDF (INFO_AUTH)
     * @param watermarkId unsigned 32-bit watermark ID, stored in the lower 32
     *                    bits of a long (range 0x00000000 .. 0xFFFFFFFF)
     * @return 16-bit auth tag as int (only lower 16 bits are meaningful)
     */
    public static int generateAuthTag(byte[] authKey, long watermarkId) {
        byte[] payload = uint32Be(watermarkId);
        byte[] hmac    = KeyDerivationUtils.hmacSha256(authKey, payload);

        // First 2 bytes of HMAC output, MSB-first → 16-bit unsigned value
        // Java `byte` is signed, so mask with 0xFF before shifting
        int hi = hmac[0] & 0xFF;
        int lo = hmac[1] & 0xFF;
        return (hi << 8) | lo;
    }

    // ─────────────────────────────────────────────────────────────────
    // Auth tag validation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Validate an extracted auth tag against the expected one.
     *
     * Contract §5.1 step 8:
     *   expected_tag = HMAC-SHA256(auth_key, uint32_be(watermark_id))[0:16 bit]
     *   compare with received tag — must match exactly.
     *
     * @param authKey      32-byte auth key
     * @param watermarkId  extracted watermark ID (unsigned 32-bit, stored in long)
     * @param receivedTag  16-bit tag extracted from the video (lower 16 bits used)
     * @return true if tags match; false → confidence is forced to 0.0
     */
    public static boolean validateAuthTag(byte[] authKey, long watermarkId, int receivedTag) {
        int expectedTag = generateAuthTag(authKey, watermarkId);
        return expectedTag == (receivedTag & 0xFFFF);
    }

    // ─────────────────────────────────────────────────────────────────
    // Byte / integer serialization helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Serialize a 32-bit unsigned integer as big-endian 4 bytes.
     *
     * Contract §1.2: "Serialization: uint32_be, MSB first."
     *
     * @param value unsigned 32-bit value in the lower 32 bits of a long
     * @return 4-byte big-endian representation
     */
    public static byte[] uint32Be(long value) {
        long v = value & 0xFFFFFFFFL;
        return new byte[]{
            (byte) ((v >> 24) & 0xFF),
            (byte) ((v >> 16) & 0xFF),
            (byte) ((v >>  8) & 0xFF),
            (byte) ( v        & 0xFF)
        };
    }

    /**
     * Read a 32-bit unsigned integer from big-endian bytes.
     *
     * @param bytes  source byte array
     * @param offset starting position (reads 4 bytes from offset..offset+3)
     * @return unsigned value in the lower 32 bits of a long
     */
    public static long readUint32Be(byte[] bytes, int offset) {
        long b0 = bytes[offset    ] & 0xFFL;
        long b1 = bytes[offset + 1] & 0xFFL;
        long b2 = bytes[offset + 2] & 0xFFL;
        long b3 = bytes[offset + 3] & 0xFFL;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /**
     * Read a 16-bit unsigned integer from big-endian bytes.
     *
     * Used for extracting the auth_tag from the 48-bit raw_packet.
     *
     * @param bytes  source byte array
     * @param offset starting position (reads 2 bytes)
     * @return unsigned 16-bit value as int
     */
    public static int readUint16Be(byte[] bytes, int offset) {
        int hi = bytes[offset    ] & 0xFF;
        int lo = bytes[offset + 1] & 0xFF;
        return (hi << 8) | lo;
    }
}

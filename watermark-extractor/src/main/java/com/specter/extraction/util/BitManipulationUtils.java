package com.specter.extraction.util;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Utility class for bit-level operations used in watermark extraction.
 * Handles conversion between bit arrays, byte arrays, and UUIDs.
 */
public final class BitManipulationUtils {

    private BitManipulationUtils() {
        // Utility class — no instantiation
    }

    /**
     * Convert a bit array to a byte array.
     * Bits are packed MSB-first (most significant bit first).
     *
     * @param bits array of 0s and 1s
     * @return packed byte array
     */
    public static byte[] bitsToBytes(int[] bits) {
        int byteCount = (bits.length + 7) / 8;
        byte[] bytes = new byte[byteCount];

        for (int i = 0; i < bits.length; i++) {
            if (bits[i] == 1) {
                bytes[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }

        return bytes;
    }

    /**
     * Convert a byte array to a bit array.
     *
     * @param bytes the byte array
     * @return array of 0s and 1s (MSB first)
     */
    public static int[] bytesToBits(byte[] bytes) {
        int[] bits = new int[bytes.length * 8];

        for (int i = 0; i < bytes.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                bits[i * 8 + bit] = (bytes[i] >> (7 - bit)) & 1;
            }
        }

        return bits;
    }

    /**
     * Reconstruct a UUID from a 32-bit payload.
     * The 32 bits represent the least significant bits of the UUID.
     * The most significant bits are set to a fixed prefix for identification.
     *
     * @param bits 32-bit payload array
     * @return reconstructed UUID
     */
    public static UUID bitsToUuid(int[] bits) {
        if (bits.length != 32) {
            throw new IllegalArgumentException("UUID payload must be exactly 32 bits, got " + bits.length);
        }

        byte[] bytes = bitsToBytes(bits);

        // 32-bit payload → use as the lower 4 bytes of UUID
        // Upper 12 bytes are set to a recognizable prefix
        ByteBuffer buffer = ByteBuffer.allocate(16);
        // Fixed prefix: "SPCT" (Specter) + 8 zero bytes
        buffer.putInt(0x53504354); // "SPCT" in hex
        buffer.putInt(0x00000000);
        buffer.putInt(0x00000000);
        buffer.put(bytes);         // 32-bit payload as last 4 bytes

        buffer.flip();
        long msb = buffer.getLong();
        long lsb = buffer.getLong();

        return new UUID(msb, lsb);
    }

    /**
     * Convert a UUID to a 32-bit payload (extract the least significant 32 bits).
     *
     * @param uuid the UUID to convert
     * @return 32-bit array
     */
    public static int[] uuidToBits(UUID uuid) {
        long lsb = uuid.getLeastSignificantBits();
        int lower32 = (int) (lsb & 0xFFFFFFFFL);

        int[] bits = new int[32];
        for (int i = 0; i < 32; i++) {
            bits[i] = (lower32 >> (31 - i)) & 1;
        }

        return bits;
    }

    /**
     * Convert a 32-bit integer to a bit array.
     *
     * @param value the integer value
     * @return 32-bit array (MSB first)
     */
    public static int[] intToBits(int value) {
        int[] bits = new int[32];
        for (int i = 0; i < 32; i++) {
            bits[i] = (value >> (31 - i)) & 1;
        }
        return bits;
    }

    /**
     * Convert a bit array back to an integer.
     *
     * @param bits bit array (MSB first)
     * @return integer value
     */
    public static int bitsToInt(int[] bits) {
        int value = 0;
        for (int i = 0; i < Math.min(32, bits.length); i++) {
            value = (value << 1) | (bits[i] & 1);
        }
        return value;
    }
}

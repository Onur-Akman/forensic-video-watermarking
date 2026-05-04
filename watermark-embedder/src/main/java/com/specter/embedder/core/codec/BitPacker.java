package com.specter.embedder.core.codec;

/** MSB-first bit <-> byte donusumleri. Tum kontrat byte buffer'lari big-endian. */
public final class BitPacker {

    private BitPacker() {
    }

    public static byte[] bytesToBits(byte[] bytes, int bitCount) {
        if (bitCount > bytes.length * 8) {
            throw new IllegalArgumentException("bitCount " + bitCount + " exceeds buffer of " + bytes.length + " bytes");
        }
        byte[] bits = new byte[bitCount];
        for (int i = 0; i < bitCount; i++) {
            int byteIndex = i / 8;
            int shift = 7 - (i % 8);
            bits[i] = (byte) ((bytes[byteIndex] >>> shift) & 1);
        }
        return bits;
    }

    public static byte[] bitsToBytes(byte[] bits) {
        int byteLen = (bits.length + 7) / 8;
        byte[] bytes = new byte[byteLen];
        for (int i = 0; i < bits.length; i++) {
            int byteIndex = i / 8;
            int shift = 7 - (i % 8);
            bytes[byteIndex] |= (byte) ((bits[i] & 1) << shift);
        }
        return bytes;
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

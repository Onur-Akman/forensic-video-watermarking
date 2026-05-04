package com.specter.embedder.core.codec;

/**
 * Hamming(7,4) even-parity encoder per contract section 1.
 * Codeword bit layout (1-indexed): p1 p2 d1 p4 d2 d3 d4.
 * Veri nibble bitleri MSB->LSB: d1 d2 d3 d4.
 */
public final class Hamming74 {

    private Hamming74() {
    }

    /** Tek bir 4-bit nibble (low nibble) -> 7-bit codeword (low 7 bit, MSB first p1). */
    public static byte encodeNibble(int nibble) {
        int d1 = (nibble >>> 3) & 1;
        int d2 = (nibble >>> 2) & 1;
        int d3 = (nibble >>> 1) & 1;
        int d4 = nibble & 1;
        int p1 = d1 ^ d2 ^ d4;
        int p2 = d1 ^ d3 ^ d4;
        int p4 = d2 ^ d3 ^ d4;
        return (byte) ((p1 << 6) | (p2 << 5) | (d1 << 4) | (p4 << 3) | (d2 << 2) | (d3 << 1) | d4);
    }

    /**
     * 4'un kati uzunlugundaki bit dizisini (her eleman 0/1) Hamming(7,4) ile genisletir.
     * 48 bit girdiye 84 bit cikti uretilir.
     */
    public static byte[] encodePacket(byte[] packetBits) {
        if (packetBits.length % 4 != 0) {
            throw new IllegalArgumentException("packet length must be a multiple of 4 bits");
        }
        int nibbleCount = packetBits.length / 4;
        byte[] out = new byte[nibbleCount * 7];
        for (int i = 0; i < nibbleCount; i++) {
            int nibble = ((packetBits[i * 4]     & 1) << 3)
                       | ((packetBits[i * 4 + 1] & 1) << 2)
                       | ((packetBits[i * 4 + 2] & 1) << 1)
                       |  (packetBits[i * 4 + 3] & 1);
            byte codeword = encodeNibble(nibble);
            for (int b = 0; b < 7; b++) {
                out[i * 7 + b] = (byte) ((codeword >>> (6 - b)) & 1);
            }
        }
        return out;
    }
}

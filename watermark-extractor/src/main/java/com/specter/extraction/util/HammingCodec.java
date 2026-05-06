package com.specter.extraction.util;

/**
 * Hamming(7,4) nibble encoder/decoder — contract §1, §1.2.
 *
 * Bit layout (1-indexed positions):  p1 p2 d1 p4 d2 d3 d4
 * Even parity:
 *   p1 = d1 XOR d2 XOR d4   (covers pos 1,3,5,7)
 *   p2 = d1 XOR d3 XOR d4   (covers pos 2,3,6,7)
 *   p4 = d2 XOR d3 XOR d4   (covers pos 4,5,6,7)
 *
 * Worked example from §1.2 — nibble 0xA = 1010:
 *   d1=1, d2=0, d3=1, d4=0
 *   p1 = 1^0^0 = 1
 *   p2 = 1^1^0 = 0
 *   p4 = 0^1^0 = 1
 *   Codeword: 1 0 1 1 0 1 0 = "1011010" ✓
 */
public final class HammingCodec {

    private HammingCodec() {}

    /**
     * Encode a 4-bit nibble to a 7-bit Hamming(7,4) codeword.
     *
     * @param nibble 4-bit value (0-15), d1=bit3 (MSB), d4=bit0 (LSB)
     * @return 7-element int[] {p1, p2, d1, p4, d2, d3, d4}
     */
    public static int[] encodeNibble(int nibble) {
        int d1 = (nibble >> 3) & 1;
        int d2 = (nibble >> 2) & 1;
        int d3 = (nibble >> 1) & 1;
        int d4 = (nibble     ) & 1;

        int p1 = d1 ^ d2 ^ d4;  // XOR of positions 1,3,5,7
        int p2 = d1 ^ d3 ^ d4;  // XOR of positions 2,3,6,7
        int p4 = d2 ^ d3 ^ d4;  // XOR of positions 4,5,6,7

        // Codeword: pos1=p1, pos2=p2, pos3=d1, pos4=p4, pos5=d2, pos6=d3, pos7=d4
        return new int[]{p1, p2, d1, p4, d2, d3, d4};
    }

    /**
     * Decode a 7-bit Hamming codeword to a 4-bit nibble with single-bit error correction.
     *
     * Syndrome:
     *   s1 = pos1 ^ pos3 ^ pos5 ^ pos7
     *   s2 = pos2 ^ pos3 ^ pos6 ^ pos7
     *   s4 = pos4 ^ pos5 ^ pos6 ^ pos7
     *   error_position (1-indexed) = s4*4 + s2*2 + s1
     *
     * @param cw 7-element int[] {p1, p2, d1, p4, d2, d3, d4} (0-indexed = pos1-7)
     * @return corrected 4-bit nibble (0-15)
     */
    public static int decodeNibble(int[] cw) {
        if (cw.length != 7) throw new IllegalArgumentException("Codeword must be 7 bits, got " + cw.length);

        int[] bits = cw.clone(); // mutable copy for in-place correction

        // Syndrome (1-indexed positions map to 0-indexed array: pos_k = bits[k-1])
        int s1 = bits[0] ^ bits[2] ^ bits[4] ^ bits[6]; // pos 1,3,5,7
        int s2 = bits[1] ^ bits[2] ^ bits[5] ^ bits[6]; // pos 2,3,6,7
        int s4 = bits[3] ^ bits[4] ^ bits[5] ^ bits[6]; // pos 4,5,6,7

        int syndrome = (s4 << 2) | (s2 << 1) | s1; // error position (1-indexed)
        if (syndrome != 0 && syndrome <= 7) {
            bits[syndrome - 1] ^= 1; // flip the erroneous bit (convert to 0-indexed)
        }

        // Data bits: d1=pos3=bits[2], d2=pos5=bits[4], d3=pos6=bits[5], d4=pos7=bits[6]
        return (bits[2] << 3) | (bits[4] << 2) | (bits[5] << 1) | bits[6];
    }

    /**
     * Decode from soft-decision values (positive = 1, non-positive = 0).
     * Converts to hard bits then calls decodeNibble.
     *
     * @param softCw 7-element double[] in codeword order {p1, p2, d1, p4, d2, d3, d4}
     * @return corrected 4-bit nibble (0-15)
     */
    public static int decodeNibbleSoft(double[] softCw) {
        if (softCw.length != 7) throw new IllegalArgumentException("Soft codeword must be 7 elements");
        int[] hard = new int[7];
        for (int i = 0; i < 7; i++) hard[i] = softCw[i] > 0 ? 1 : 0;
        return decodeNibble(hard);
    }
}

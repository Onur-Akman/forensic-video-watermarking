package com.specter.embedder.m1;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.core.codec.BitPacker;
import com.specter.embedder.core.crypto.AuthTag;
import com.specter.embedder.core.crypto.Prng;
import com.specter.embedder.core.dct.Dct2D;
import com.specter.embedder.core.dct.PairModulator;
import com.specter.embedder.core.grid.GridMapper;
import com.specter.embedder.service.DctEmbedder;
import com.specter.embedder.service.KeyManager;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Test-only minimal blind extractor; M1 PoC roundtrip dogrulamasi icin (contract section 5.1, 5.2).
 *
 * <p>Bu sinif extractor servisinin yerine GECMEZ; production extraction Yaren'in
 * `watermark-extractor` repository'sinin sorumlulugundadir. Burada amac sadece
 * embedder tarafinin self-consistency'sini gostermek: encoder + decoder ayni
 * spec'ten dogru turetilmisse, bit-perfect roundtrip saglar.
 */
final class Roundtrip {

    private Roundtrip() {
    }

    record Result(boolean authTagValid, long watermarkId, double bitConfidence) {
    }

    static Result extract(byte[] yPlane, int width, int height, KeyManager keyManager) {
        // 1. Cell + pair plani (embedder ile aynen)
        Prng cellPrng = new Prng(keyManager.prngKey(), ContractConstants.PRNG_CTX_CELL_MAP);
        int[] cells = cellPrng.sampleWithoutReplacement(
                ContractConstants.GRID_CELLS, ContractConstants.EMBED_POINTS_PER_FRAME);
        Prng pairPrng = new Prng(keyManager.prngKey(), ContractConstants.PRNG_CTX_PAIR_MAP);
        int[] pairs = new int[ContractConstants.EMBED_POINTS_PER_FRAME];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = pairPrng.boundedInt(ContractConstants.DCT_PAIRS.length);
        }

        GridMapper grid = new GridMapper(width, height);
        Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);

        // 2. Her cell'de DCT al, (a-b) ham fark soft-vote (contract section 5.1 step 3)
        double[] votes = new double[ContractConstants.EMBED_POINTS_PER_FRAME];
        for (int i = 0; i < ContractConstants.EMBED_POINTS_PER_FRAME; i++) {
            int[] xy = grid.cellToBlock(cells[i]);
            double[] block = DctEmbedder.extractBlock(yPlane, width, xy[0], xy[1]);
            dct.forward(block);
            votes[i] = PairModulator.readDifference(block, pairs[i]);
        }

        // 3. 3 tekrar uzerinden vote'lari topla (contract section 5.1 step 4)
        double[] sumVote = new double[ContractConstants.CODEWORD_BITS];
        double[] sumAbsVote = new double[ContractConstants.CODEWORD_BITS];
        for (int i = 0; i < ContractConstants.EMBED_POINTS_PER_FRAME; i++) {
            int bitPos = i % ContractConstants.CODEWORD_BITS;
            sumVote[bitPos] += votes[i];
            sumAbsVote[bitPos] += Math.abs(votes[i]);
        }

        // 4. Hard decision + bit_confidence (contract section 5.2)
        byte[] interleavedBits = new byte[ContractConstants.CODEWORD_BITS];
        double bitConfSum = 0.0;
        for (int b = 0; b < ContractConstants.CODEWORD_BITS; b++) {
            interleavedBits[b] = (byte) (sumVote[b] > 0 ? 1 : 0);
            if (sumAbsVote[b] > 0) {
                bitConfSum += Math.abs(sumVote[b]) / sumAbsVote[b];
            }
        }
        double bitConfidence = bitConfSum / ContractConstants.CODEWORD_BITS;

        // 5. Deinterleave: gather forward (interleaved[i] = codeword[perm[i]]) -> inverse:
        //    codeword[perm[i]] = interleaved[i]   (contract section 1 step 5)
        Prng intPrng = new Prng(keyManager.interleaveKey(), ContractConstants.PRNG_CTX_INTERLEAVER);
        int[] perm = intPrng.permutation(ContractConstants.CODEWORD_BITS);
        byte[] codewordBits = new byte[ContractConstants.CODEWORD_BITS];
        for (int i = 0; i < ContractConstants.CODEWORD_BITS; i++) {
            codewordBits[perm[i]] = interleavedBits[i];
        }

        // 6. Hamming(7,4) syndrome decode -> 48-bit raw_packet (contract section 1.4)
        byte[] rawPacketBits = new byte[ContractConstants.RAW_PACKET_BITS];
        for (int n = 0; n < 12; n++) {
            int[] cw = new int[7];
            for (int b = 0; b < 7; b++) cw[b] = codewordBits[n * 7 + b] & 1;
            // Standart Hamming(7,4): 1-indexed positions p1 p2 d1 p4 d2 d3 d4.
            int s1 = cw[0] ^ cw[2] ^ cw[4] ^ cw[6];
            int s2 = cw[1] ^ cw[2] ^ cw[5] ^ cw[6];
            int s4 = cw[3] ^ cw[4] ^ cw[5] ^ cw[6];
            int syndrome = (s4 << 2) | (s2 << 1) | s1;
            if (syndrome != 0) {
                cw[syndrome - 1] ^= 1;  // 1-indexed pozisyonu flip
            }
            rawPacketBits[n * 4]     = (byte) cw[2]; // d1
            rawPacketBits[n * 4 + 1] = (byte) cw[4]; // d2
            rawPacketBits[n * 4 + 2] = (byte) cw[5]; // d3
            rawPacketBits[n * 4 + 3] = (byte) cw[6]; // d4
        }

        // 7. raw_packet = id || auth_tag (contract section 5.1 step 7)
        byte[] rawPacketBytes = BitPacker.bitsToBytes(rawPacketBits);
        ByteBuffer bb = ByteBuffer.wrap(rawPacketBytes, 0, 4);
        long watermarkId = bb.getInt() & 0xFFFFFFFFL;
        byte[] tag = new byte[]{rawPacketBytes[4], rawPacketBytes[5]};

        // 8. expected_tag karsilastirilir (contract section 5.1 step 8)
        byte[] expectedTag = AuthTag.compute(keyManager.authKey(), watermarkId);
        boolean authValid = Arrays.equals(tag, expectedTag);

        return new Result(authValid, watermarkId, bitConfidence);
    }
}

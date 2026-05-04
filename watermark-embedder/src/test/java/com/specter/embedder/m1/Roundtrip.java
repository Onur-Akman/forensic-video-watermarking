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
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * Test-only minimal blind extractor (contract section 5.1, 5.2). Hem M1 PoC tek-frame
 * roundtrip'i hem M2 video roundtrip'i bu siniftan icra eder.
 *
 * <p>Production extraction Yaren'in `watermark-extractor` mikroservisi sorumluluğunda;
 * bu sinif sadece embedder side'in self-consistency'sini gostermek icin.
 */
public final class Roundtrip {

    private Roundtrip() {
    }

    public record Result(boolean authTagValid, long watermarkId, double bitConfidence) {
    }

    /** M1: tek frame Y duzleminden extract. */
    public static Result extract(byte[] yPlane, int width, int height, KeyManager keyManager) {
        Accumulator acc = new Accumulator();
        int[] cells = planCells(keyManager);
        int[] pairs = planPairs(keyManager);
        accumulateFrameVotes(yPlane, width, height, cells, pairs, acc);
        return finishExtraction(acc, keyManager);
    }

    /** M2: video uzerindeki tum frame'lerden vote'lari topla, tek payload uret. */
    public static Result extractFromVideo(Path input, KeyManager keyManager) throws IOException {
        Accumulator acc = new Accumulator();
        int[] cells = planCells(keyManager);
        int[] pairs = planPairs(keyManager);

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input.toFile());
        grabber.setImageMode(FrameGrabber.ImageMode.RAW);
        grabber.setPixelFormat(AV_PIX_FMT_YUV420P);
        grabber.start();
        try {
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            Frame frame;
            while ((frame = grabber.grabFrame(false, true, true, false)) != null) {
                if (frame.image == null) {
                    continue;
                }
                ByteBuffer yBuffer = (ByteBuffer) frame.image[0];
                int yStride = frame.imageStride;
                byte[] yPlane = new byte[width * height];
                int origPos = yBuffer.position();
                try {
                    for (int row = 0; row < height; row++) {
                        yBuffer.position(row * yStride);
                        yBuffer.get(yPlane, row * width, width);
                    }
                } finally {
                    yBuffer.position(origPos);
                }
                accumulateFrameVotes(yPlane, width, height, cells, pairs, acc);
            }
        } finally {
            grabber.stop();
            grabber.release();
        }
        return finishExtraction(acc, keyManager);
    }

    // --------- shared internals ---------

    private static int[] planCells(KeyManager km) {
        return new Prng(km.prngKey(), ContractConstants.PRNG_CTX_CELL_MAP)
                .sampleWithoutReplacement(ContractConstants.GRID_CELLS, ContractConstants.EMBED_POINTS_PER_FRAME);
    }

    private static int[] planPairs(KeyManager km) {
        Prng prng = new Prng(km.prngKey(), ContractConstants.PRNG_CTX_PAIR_MAP);
        int[] out = new int[ContractConstants.EMBED_POINTS_PER_FRAME];
        for (int i = 0; i < out.length; i++) {
            out[i] = prng.boundedInt(ContractConstants.DCT_PAIRS.length);
        }
        return out;
    }

    private static void accumulateFrameVotes(byte[] yPlane, int width, int height,
                                             int[] cells, int[] pairs, Accumulator acc) {
        GridMapper grid = new GridMapper(width, height);
        Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);
        for (int i = 0; i < ContractConstants.EMBED_POINTS_PER_FRAME; i++) {
            int[] xy = grid.cellToBlock(cells[i]);
            double[] block = DctEmbedder.extractBlock(yPlane, width, xy[0], xy[1]);
            dct.forward(block);
            double vote = PairModulator.readDifference(block, pairs[i]);
            int bitPos = i % ContractConstants.CODEWORD_BITS;
            acc.sumVote[bitPos] += vote;
            acc.sumAbsVote[bitPos] += Math.abs(vote);
        }
    }

    private static Result finishExtraction(Accumulator acc, KeyManager keyManager) {
        // 1) Hard decision per bit + bit_confidence (contract section 5.2)
        byte[] interleavedBits = new byte[ContractConstants.CODEWORD_BITS];
        double bitConfSum = 0.0;
        int contributingBits = 0;
        for (int b = 0; b < ContractConstants.CODEWORD_BITS; b++) {
            interleavedBits[b] = (byte) (acc.sumVote[b] > 0 ? 1 : 0);
            if (acc.sumAbsVote[b] > 0) {
                bitConfSum += Math.abs(acc.sumVote[b]) / acc.sumAbsVote[b];
                contributingBits++;
            }
        }
        double bitConfidence = contributingBits > 0
                ? bitConfSum / ContractConstants.CODEWORD_BITS
                : 0.0;

        // 2) Deinterleave: gather forward (interleaved[i] = codeword[perm[i]]) -> inverse:
        //    codeword[perm[i]] = interleaved[i]   (contract section 1 step 5)
        Prng intPrng = new Prng(keyManager.interleaveKey(), ContractConstants.PRNG_CTX_INTERLEAVER);
        int[] perm = intPrng.permutation(ContractConstants.CODEWORD_BITS);
        byte[] codewordBits = new byte[ContractConstants.CODEWORD_BITS];
        for (int i = 0; i < ContractConstants.CODEWORD_BITS; i++) {
            codewordBits[perm[i]] = interleavedBits[i];
        }

        // 3) Hamming(7,4) syndrome decode -> 48-bit raw_packet (contract section 1.4)
        byte[] rawPacketBits = new byte[ContractConstants.RAW_PACKET_BITS];
        for (int n = 0; n < 12; n++) {
            int[] cw = new int[7];
            for (int b = 0; b < 7; b++) cw[b] = codewordBits[n * 7 + b] & 1;
            int s1 = cw[0] ^ cw[2] ^ cw[4] ^ cw[6];
            int s2 = cw[1] ^ cw[2] ^ cw[5] ^ cw[6];
            int s4 = cw[3] ^ cw[4] ^ cw[5] ^ cw[6];
            int syndrome = (s4 << 2) | (s2 << 1) | s1;
            if (syndrome != 0) {
                cw[syndrome - 1] ^= 1;
            }
            rawPacketBits[n * 4]     = (byte) cw[2]; // d1
            rawPacketBits[n * 4 + 1] = (byte) cw[4]; // d2
            rawPacketBits[n * 4 + 2] = (byte) cw[5]; // d3
            rawPacketBits[n * 4 + 3] = (byte) cw[6]; // d4
        }

        // 4) raw_packet = id || auth_tag (contract section 5.1 step 7)
        byte[] rawPacketBytes = BitPacker.bitsToBytes(rawPacketBits);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(rawPacketBytes, 0, 4);
        long watermarkId = bb.getInt() & 0xFFFFFFFFL;
        byte[] tag = new byte[]{rawPacketBytes[4], rawPacketBytes[5]};

        // 5) expected_tag karsilastirilir (contract section 5.1 step 8)
        byte[] expectedTag = AuthTag.compute(keyManager.authKey(), watermarkId);
        boolean authValid = Arrays.equals(tag, expectedTag);

        return new Result(authValid, watermarkId, bitConfidence);
    }

    private static final class Accumulator {
        final double[] sumVote = new double[ContractConstants.CODEWORD_BITS];
        final double[] sumAbsVote = new double[ContractConstants.CODEWORD_BITS];
    }
}

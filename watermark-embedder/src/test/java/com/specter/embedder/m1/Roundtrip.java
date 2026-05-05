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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * Test-only minimal blind extractor (contract section 5.1, 5.2, 5.3).
 * <ul>
 *   <li>{@link #extract} — M1 tek-frame Y duzleminden extract.</li>
 *   <li>{@link #extractFromVideo} — M2 multi-frame video, default alignment.</li>
 *   <li>{@link #extractFromVideoWithAlignmentSearch} — M3 robustness: scale/offset
 *       arama ile crop saldirilarinin uretirdgi kucuk hizalama hatalarini telafi eder.</li>
 * </ul>
 *
 * <p>Production extraction Yaren'in `watermark-extractor` mikroservisi sorumlulugundadir;
 * bu sinif sadece embedder side'in self-consistency'sini gostermek icin.
 */
public final class Roundtrip {

    private static final int M3_MAX_FRAMES = 90;
    private static final int M3_PROBE_FRAMES = 30;
    private static final double[] M3_SCALES = {0.85, 0.90, 0.95, 1.00, 1.05};
    private static final double[] M3_OFFSETS = {-0.05, -0.025, 0.0, 0.025, 0.05};
    /** Assumed embed-time frame dimensions for M3 alignment-aware mapping.
     *  Contract section 3.1 snaps to 8-pixel block at embed frame grid; under
     *  scale/crop attacks the extract frame's 8-pixel grid does not align with
     *  the embed frame's grid. We reconstruct the embed-time snapped position
     *  (assumes embed was 1080p — true for our M3 test asset; production
     *  extractor would carry this dimension as side-channel metadata or probe
     *  it via correlation). */
    private static final int M3_ASSUMED_EMBED_W = 1920;
    private static final int M3_ASSUMED_EMBED_H = 1080;

    private Roundtrip() {
    }

    public record Result(boolean authTagValid, long watermarkId, double bitConfidence) {
    }

    public record Alignment(double scale, double offsetX, double offsetY) {
        public static final Alignment IDENTITY = new Alignment(1.0, 0.0, 0.0);
    }

    public record VideoExtractResult(Result extraction, Alignment alignment, int framesScanned) {
    }

    /** M1: tek frame Y duzleminden extract. */
    public static Result extract(byte[] yPlane, int width, int height, KeyManager keyManager) {
        Accumulator acc = new Accumulator();
        int[] cells = planCells(keyManager);
        int[] pairs = planPairs(keyManager);
        GridMapper grid = new GridMapper(width, height);
        Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);
        accumulateFrameVotes(yPlane, width, cells, pairs, grid, dct, acc);
        return finishExtraction(acc, keyManager);
    }

    /** M2: video uzerindeki tum frame'lerden vote'lari topla, default alignment. */
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
            GridMapper grid = new GridMapper(width, height);
            Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);
            Frame frame;
            while ((frame = grabber.grabFrame(false, true, true, false)) != null) {
                if (frame.image == null) {
                    continue;
                }
                byte[] yPlane = copyYFromFrame(frame, width, height);
                accumulateFrameVotes(yPlane, width, cells, pairs, grid, dct, acc);
            }
        } finally {
            grabber.stop();
            grabber.release();
        }
        return finishExtraction(acc, keyManager);
    }

    /**
     * M3: alignment search ile robust extract.
     *
     * <p>1) Ilk N frame'i bellege okur (RAM-friendly bound: M3_MAX_FRAMES). 2) Adday
     * scale/offset kombinasyonlari uzerinde first-K frame'le bit_confidence olcer
     * (cheap probe). 3) En yuksek confidence veren alignment ile tum cached
     * frame'lerden full extraction yapar.
     *
     * <p>Crop saldirilarinin urettigi normalize-coordinate sapmasi ({@code (norm_embed
     * - offset) / scale}) bu arama ile telafi edilir; non-crop saldirilarda
     * (bitrate, scale-down, brightness/contrast) identity {@code (1.0, 0, 0)}
     * dogal kazanir.
     */
    public static VideoExtractResult extractFromVideoWithAlignmentSearch(
            Path input, KeyManager keyManager) throws IOException {
        // 1) Cache frames
        int[] dims = new int[2];
        List<byte[]> cached = readFirstFrames(input, M3_MAX_FRAMES, dims);
        if (cached.isEmpty()) {
            return new VideoExtractResult(
                    new Result(false, 0L, 0.0), Alignment.IDENTITY, 0);
        }
        int width = dims[0];
        int height = dims[1];
        int[] cells = planCells(keyManager);
        int[] pairs = planPairs(keyManager);
        Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);

        // 2) Pre-compute embed-time snapped block positions (assume 1080p embed).
        //    Reading 8x8 at exact extract pixel (no extract-side snap) — extract grid
        //    does not align with embed grid under scale/crop attacks.
        int[][] embedSnappedXY = new int[ContractConstants.EMBED_POINTS_PER_FRAME][2];
        for (int i = 0; i < cells.length; i++) {
            int col = cells[i] % ContractConstants.GRID_COLS;
            int row = cells[i] / ContractConstants.GRID_COLS;
            double safeRange = 1.0 - 2.0 * ContractConstants.SAFE_MARGIN;
            double xNormEmbed = ContractConstants.SAFE_MARGIN + (col + 0.5) * safeRange / ContractConstants.GRID_COLS;
            double yNormEmbed = ContractConstants.SAFE_MARGIN + (row + 0.5) * safeRange / ContractConstants.GRID_ROWS;
            int xPix = (int) Math.round(xNormEmbed * M3_ASSUMED_EMBED_W);
            int yPix = (int) Math.round(yNormEmbed * M3_ASSUMED_EMBED_H);
            embedSnappedXY[i][0] = (xPix / ContractConstants.DCT_BLOCK_SIZE) * ContractConstants.DCT_BLOCK_SIZE;
            embedSnappedXY[i][1] = (yPix / ContractConstants.DCT_BLOCK_SIZE) * ContractConstants.DCT_BLOCK_SIZE;
        }

        // 3) Alignment search using first M3_PROBE_FRAMES
        int probeCount = Math.min(M3_PROBE_FRAMES, cached.size());
        Alignment best = Alignment.IDENTITY;
        double bestConfidence = -1.0;
        for (double s : M3_SCALES) {
            for (double ox : M3_OFFSETS) {
                for (double oy : M3_OFFSETS) {
                    Accumulator acc = new Accumulator();
                    for (int i = 0; i < probeCount; i++) {
                        accumulateFrameVotesEmbedAware(cached.get(i), width, height,
                                embedSnappedXY, pairs, s, ox, oy, dct, acc);
                    }
                    double conf = peekBitConfidence(acc);
                    if (conf > bestConfidence) {
                        bestConfidence = conf;
                        best = new Alignment(s, ox, oy);
                    }
                }
            }
        }

        // 4) Full extraction at best alignment, all cached frames
        Accumulator acc = new Accumulator();
        for (byte[] yPlane : cached) {
            accumulateFrameVotesEmbedAware(yPlane, width, height,
                    embedSnappedXY, pairs, best.scale(), best.offsetX(), best.offsetY(), dct, acc);
        }
        Result result = finishExtraction(acc, keyManager);
        return new VideoExtractResult(result, best, cached.size());
    }

    /** Embed-aware: cells were snapped at 1920x1080. Map to extract frame via
     *  alignment (scale, offset) without extract-side snap. */
    private static void accumulateFrameVotesEmbedAware(byte[] yPlane, int extractW, int extractH,
                                                       int[][] embedSnappedXY, int[] pairs,
                                                       double scale, double offsetX, double offsetY,
                                                       Dct2D dct, Accumulator acc) {
        int B = ContractConstants.DCT_BLOCK_SIZE;
        int maxX = extractW - B;
        int maxY = extractH - B;
        for (int i = 0; i < ContractConstants.EMBED_POINTS_PER_FRAME; i++) {
            // Embed-snapped position in normalized space
            double xNormSnap = embedSnappedXY[i][0] / (double) M3_ASSUMED_EMBED_W;
            double yNormSnap = embedSnappedXY[i][1] / (double) M3_ASSUMED_EMBED_H;
            // Apply alignment to extract frame
            double xNormExtract = (xNormSnap - offsetX) / scale;
            double yNormExtract = (yNormSnap - offsetY) / scale;
            int xPix = (int) Math.round(xNormExtract * extractW);
            int yPix = (int) Math.round(yNormExtract * extractH);
            // Clamp (no extract-side snap — read at exact pixel)
            if (xPix > maxX) xPix = maxX;
            if (yPix > maxY) yPix = maxY;
            if (xPix < 0) xPix = 0;
            if (yPix < 0) yPix = 0;
            double[] block = DctEmbedder.extractBlock(yPlane, extractW, xPix, yPix);
            dct.forward(block);
            double vote = PairModulator.readDifference(block, pairs[i]);
            int bitPos = i % ContractConstants.CODEWORD_BITS;
            acc.sumVote[bitPos] += vote;
            acc.sumAbsVote[bitPos] += Math.abs(vote);
        }
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

    private static void accumulateFrameVotes(byte[] yPlane, int width, int[] cells, int[] pairs,
                                             GridMapper grid, Dct2D dct, Accumulator acc) {
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

    private static List<byte[]> readFirstFrames(Path input, int maxFrames, int[] dimsOut) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input.toFile())) {
            grabber.setImageMode(FrameGrabber.ImageMode.RAW);
            grabber.setPixelFormat(AV_PIX_FMT_YUV420P);
            grabber.start();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            dimsOut[0] = width;
            dimsOut[1] = height;
            Frame frame;
            while (out.size() < maxFrames && (frame = grabber.grabFrame(false, true, true, false)) != null) {
                if (frame.image == null) {
                    continue;
                }
                out.add(copyYFromFrame(frame, width, height));
            }
        }
        return out;
    }

    private static byte[] copyYFromFrame(Frame frame, int width, int height) {
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
        return yPlane;
    }

    private static double peekBitConfidence(Accumulator acc) {
        double sum = 0.0;
        int contrib = 0;
        for (int b = 0; b < ContractConstants.CODEWORD_BITS; b++) {
            if (acc.sumAbsVote[b] > 0) {
                sum += Math.abs(acc.sumVote[b]) / acc.sumAbsVote[b];
                contrib++;
            }
        }
        return contrib > 0 ? sum / ContractConstants.CODEWORD_BITS : 0.0;
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

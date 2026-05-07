package com.specter.extraction;

import com.specter.extraction.config.AppConfig;
import com.specter.extraction.config.SpectreKeyProvider;
import com.specter.extraction.config.WatermarkConfig;
import com.specter.extraction.model.FrameData;
import com.specter.extraction.service.impl.ConfidenceScoreServiceImpl;
import com.specter.extraction.service.impl.FrameSynchronizationServiceImpl;
import com.specter.extraction.service.impl.VideoDecoderServiceImpl;
import com.specter.extraction.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

/**
 * Debug test to isolate crop alignment issue.
 */
public class CropDebugTest {

    @BeforeAll
    static void setupEnv() {
        String key = System.getenv("SPECTER_WM_KEY");
        if (key == null || key.isBlank()) {
            System.setProperty("SPECTER_WM_KEY",
                    "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        }
    }

    @Test
    void debugCropExtraction() throws Exception {
        File videoFile = new File("../attack_crop.mp4");
        if (!videoFile.exists()) videoFile = new File("attack_crop.mp4");
        assert videoFile.exists() : "Video not found";

        SpectreKeyProvider keyProvider = new SpectreKeyProvider();
        keyProvider.init();
        WatermarkConfig wc = new WatermarkConfig();
        AppConfig appConfig = new AppConfig();
        VideoDecoderServiceImpl vds = new VideoDecoderServiceImpl(appConfig);
        FrameSynchronizationServiceImpl frameSync = new FrameSynchronizationServiceImpl(wc);

        List<FrameData> allFrames = vds.decodeToFrames(videoFile.getAbsolutePath());
        List<FrameData> syncFrames = frameSync.synchronize(allFrames);
        System.out.println("Decoded " + allFrames.size() + " frames, synced " + syncFrames.size() + " frames");
        System.out.println("Frame 0: " + syncFrames.get(0).getWidth() + "x" + syncFrames.get(0).getHeight());

        byte[] prngKey = keyProvider.getPrngKey();
        byte[] interleaveKey = keyProvider.getInterleaverKey();
        byte[] authKey = keyProvider.getAuthKey();

        SpectralPrng cp = new SpectralPrng(prngKey, WatermarkConfig.CTX_CELL_MAP);
        SpectralPrng pp = new SpectralPrng(prngKey, WatermarkConfig.CTX_PAIR_MAP);
        int[] cellIndices = cp.selectDistinct(WatermarkConfig.GRID_TOTAL_CELLS, WatermarkConfig.EMBED_POINTS_PER_FRAME);
        int[] pairIndices = new int[WatermarkConfig.EMBED_POINTS_PER_FRAME];
        for (int i = 0; i < pairIndices.length; i++) pairIndices[i] = pp.nextInt(4);

        int N = WatermarkConfig.EMBED_POINTS_PER_FRAME;
        double[][] block = new double[8][8];
        double[][] dct = new double[8][8];

        int[][] frameConfigs = { {0, 30}, {0, 45}, {0, allFrames.size()}, {1, Math.min(45, syncFrames.size())} };
        String[] labels = { "first30_allFrames", "first45_allFrames", "allFrames(" + allFrames.size() + ")", "first45_sync(" + Math.min(45, syncFrames.size()) + ")" };

        for (int fi = 0; fi < frameConfigs.length; fi++) {
            int src = frameConfigs[fi][0]; // 0=allFrames, 1=syncFrames
            int count = frameConfigs[fi][1];
            List<FrameData> frames = (src == 0) ? allFrames : syncFrames;
            if (count > frames.size()) count = frames.size();

            for (GridUtils.MappingMode mode : GridUtils.MappingMode.values()) {
                double[] soft = new double[N];
                double[] absSoft = new double[N];
                for (int f = 0; f < count; f++) {
                    FrameData frame = frames.get(f);
                    double[][] lum = frame.getLuminanceChannel();
                    for (int i = 0; i < N; i++) {
                        double[] coords = GridUtils.getBlockCoordinates(cellIndices[i], frame.getWidth(), frame.getHeight(), 1.0, 0, 0, mode);
                        GridUtils.extractBlockBilinear(lum, coords[0], coords[1], block);
                        DctUtils.forwardDct8x8(block, dct);
                        int[][] pair = WatermarkConfig.DCT_PAIRS[pairIndices[i]];
                        double v = dct[pair[0][0]][pair[0][1]] - dct[pair[1][0]][pair[1][1]];
                        soft[i] += v;
                        absSoft[i] += Math.abs(v);
                    }
                }

                int[] perm = InterleaverUtils.buildPermutation(interleaveKey);
                double[] combined = new double[WatermarkConfig.CODEWORD_BITS];
                for (int rep = 0; rep < 3; rep++)
                    for (int i = 0; i < 84; i++) combined[i] += soft[rep * 84 + i];
                double[] codewordSoft = InterleaverUtils.deinterleave(combined, perm);

                int[] nibbles = new int[12];
                double[] nibbleSoft = new double[7];
                for (int n = 0; n < 12; n++) {
                    System.arraycopy(codewordSoft, n * 7, nibbleSoft, 0, 7);
                    nibbles[n] = HammingCodec.decodeNibbleSoft(nibbleSoft);
                }

                long packet48 = 0L;
                for (int n : nibbles) packet48 = (packet48 << 4) | (n & 0xF);
                long watermarkId = (packet48 >> 16) & 0xFFFFFFFFL;
                int authTag = (int) (packet48 & 0xFFFFL);
                boolean valid = HmacAuthUtils.validateAuthTag(authKey, watermarkId, authTag);

                System.out.printf("Frames=%-25s | Mode=%-20s | ID=0x%08X | valid=%s%n",
                        labels[fi] + "(" + count + ")", mode, watermarkId, valid);
            }
        }
    }
}

package com.specter.embedder.tools;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.m1.Roundtrip;
import com.specter.embedder.service.DctEmbedder;
import com.specter.embedder.service.KeyManager;
import com.specter.embedder.service.PayloadEncoder;
import com.specter.embedder.service.PsnrCalculator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Kullanicinin kendi imajina watermark gomme + roundtrip dogrulama CLI tool'u.
 *
 * <p>Cikti color PNG'dir: input RGB -> BT.601 YCbCr (full-range / JFIF) -> Y'ye
 * watermark goml -> Y' + orijinal Cb,Cr -> RGB -> PNG. Roundtrip dogrulamasi
 * kaydedilen PNG'yi geri yukleyip Y'yi yeniden hesaplar (RGB↔YCbCr round-trip
 * 8-bit kuantizasyon nedeniyle ~1-2 piksel gurultu ekler; DELTA=12 buna fazlasiyla
 * dayanir).
 *
 * <p>Kullanim:
 * <pre>
 *   ./mvnw -q test-compile exec:java \
 *     -Dexec.mainClass=com.specter.embedder.tools.WatermarkImageTool \
 *     -Dexec.classpathScope=test \
 *     -Dexec.args="input/watermarktestfoto.jpg [output.png] [0xA3F21B04]"
 * </pre>
 *
 * Argumanlar:
 *   1) input image (zorunlu)            — JPG/PNG/BMP, herhangi bir boyut
 *   2) output PNG (opsiyonel)           — default: <input>_watermarked.png yan-yana
 *   3) watermark_id (opsiyonel hex/dec) — default: 0x5C2A91FE (test seed)
 */
public final class WatermarkImageTool {

    private static final String DUMMY_MASTER_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final long DEFAULT_ID = 0x5C2A91FEL;

    private WatermarkImageTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: WatermarkImageTool <input_image> [output_png] [watermark_id]");
            System.exit(1);
        }
        Path inputPath = Paths.get(args[0]).toAbsolutePath();
        Path outputPath = (args.length >= 2 && !args[1].isBlank())
                ? Paths.get(args[1]).toAbsolutePath()
                : defaultOutputPath(inputPath);
        long watermarkId = (args.length >= 3) ? parseId(args[2]) : DEFAULT_ID;

        System.out.printf("[wm-image] input  = %s%n", inputPath);
        System.out.printf("[wm-image] output = %s%n", outputPath);
        System.out.printf("[wm-image] id     = 0x%08X (decimal %d)%n", watermarkId, watermarkId);

        BufferedImage src = ImageIO.read(inputPath.toFile());
        if (src == null) {
            throw new IOException("Could not decode image (unsupported format?): " + inputPath);
        }
        int W = src.getWidth();
        int H = src.getHeight();
        System.out.printf("[wm-image] dimensions = %dx%d (%.2f MP)%n", W, H, (W * H) / 1_000_000.0);

        EmbedderProperties props = new EmbedderProperties(
                DUMMY_MASTER_HEX, ".", ContractConstants.H264_CRF_DEFAULT, ContractConstants.CONTRACT_VERSION);
        KeyManager keyManager = new KeyManager(props);
        DctEmbedder dctEmbedder = new DctEmbedder(keyManager, new PsnrCalculator());
        PayloadEncoder payloadEncoder = new PayloadEncoder(keyManager);
        byte[] codeword = payloadEncoder.encode(watermarkId);

        // Hizli pixel okuma: int[] RGB array
        int[] rgbPixels = src.getRGB(0, 0, W, H, null, 0, W);

        // RGB -> YCbCr (BT.601 JFIF full-range)
        byte[] yPlane = new byte[W * H];
        byte[] cbPlane = new byte[W * H];
        byte[] crPlane = new byte[W * H];
        for (int i = 0; i < rgbPixels.length; i++) {
            int rgb = rgbPixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int yVal = clamp((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b), 0, 255);
            int cbVal = clamp((int) Math.round(-0.168736 * r - 0.331264 * g + 0.5 * b + 128), 0, 255);
            int crVal = clamp((int) Math.round(0.5 * r - 0.418688 * g - 0.081312 * b + 128), 0, 255);
            yPlane[i] = (byte) yVal;
            cbPlane[i] = (byte) cbVal;
            crPlane[i] = (byte) crVal;
        }

        // Embed
        long t0 = System.nanoTime();
        DctEmbedder.EmbedResult result = dctEmbedder.embedIntoYPlane(
                yPlane, W, H, codeword, ContractConstants.DELTA);
        double embedSec = (System.nanoTime() - t0) / 1_000_000_000.0;
        System.out.printf("[wm-image] embed: embedded=%s psnr_db=%.4f mse=%.4f (took %.3fs)%n",
                result.embedded(), result.psnrDb(), result.mse(), embedSec);
        if (!result.embedded()) {
            System.err.printf("[wm-image] WARNING: PSNR %.2f dB < floor %.2f — frame skipped, "
                            + "no watermark embedded. Skip path active (contract section 10.2 path b).%n",
                    result.psnrDb(), ContractConstants.PSNR_FLOOR_DB);
        }

        // YCbCr -> RGB ve PNG yaz
        int[] outPixels = new int[W * H];
        for (int i = 0; i < outPixels.length; i++) {
            int yVal = yPlane[i] & 0xFF;
            int cbVal = (cbPlane[i] & 0xFF) - 128;
            int crVal = (crPlane[i] & 0xFF) - 128;
            int r = clamp((int) Math.round(yVal + 1.402 * crVal), 0, 255);
            int g = clamp((int) Math.round(yVal - 0.344136 * cbVal - 0.714136 * crVal), 0, 255);
            int b = clamp((int) Math.round(yVal + 1.772 * cbVal), 0, 255);
            outPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        BufferedImage outImg = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        outImg.setRGB(0, 0, W, H, outPixels, 0, W);
        outputPath.getParent().toFile().mkdirs();
        if (!ImageIO.write(outImg, "PNG", outputPath.toFile())) {
            throw new IOException("ImageIO PNG writer not available");
        }
        long pngBytes = outputPath.toFile().length();
        System.out.printf("[wm-image] wrote %s (%.2f MB)%n", outputPath, pngBytes / 1024.0 / 1024.0);

        // Roundtrip dogrulama: yuklenen PNG'den Y'yi yeniden hesapla, extract et
        BufferedImage loaded = ImageIO.read(outputPath.toFile());
        int[] loadedRgb = loaded.getRGB(0, 0, W, H, null, 0, W);
        byte[] loadedY = new byte[W * H];
        for (int i = 0; i < loadedRgb.length; i++) {
            int rgb = loadedRgb[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            loadedY[i] = (byte) clamp((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b), 0, 255);
        }
        Roundtrip.Result rt = Roundtrip.extract(loadedY, W, H, keyManager);
        System.out.printf("[wm-image] roundtrip: extracted_id=0x%08X confidence=%.4f auth_tag_valid=%s%n",
                rt.watermarkId(), rt.bitConfidence(), rt.authTagValid());
        if (rt.authTagValid() && rt.watermarkId() == watermarkId) {
            System.out.println("[wm-image] SUCCESS: watermark RGB<->YCbCr roundtrip'i atlatti, "
                    + "extracted ID embed edilenle eslesti.");
        } else {
            System.err.println("[wm-image] FAIL: roundtrip ID/auth_tag mismatch.");
            System.exit(2);
        }
    }

    private static Path defaultOutputPath(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot < 0) ? name : name.substring(0, dot);
        Path repoRoot = Paths.get(System.getProperty("user.dir")).resolve("..").normalize();
        return repoRoot.resolve("output").resolve(base + "_watermarked.png");
    }

    private static long parseId(String s) {
        String t = s.trim().toLowerCase();
        if (t.startsWith("0x")) return Long.parseUnsignedLong(t.substring(2), 16);
        return Long.parseUnsignedLong(t, 10);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

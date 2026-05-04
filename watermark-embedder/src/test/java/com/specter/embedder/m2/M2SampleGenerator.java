package com.specter.embedder.m2;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * One-shot generator for the M2 test asset:
 *   shared/test-vectors/m2_sample/clean_30s_1080p30.mp4
 *
 * <p>Synthetic content: vertical gradient + scattered grayscale dots (texture)
 * + bouncing colored rectangle + frame counter overlay. Eklenen yuksek-frekansli
 * unsurlar DCT pair modulasyonunun "zaten margin var" yolundan onemli oranda
 * faydalanmasini saglar; tamamen smooth icerikten kacinilir.
 *
 * <p>Encoded at CRF 28 to keep repo size manageable. Embedder'in DELTA=12 / CRF 18
 * cikti parametreleri girdinin kalitesinden bagimsizdir.
 */
public final class M2SampleGenerator {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final int FPS = 30;
    private static final int DURATION_SEC = 30;
    private static final int CRF = 28;

    private M2SampleGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(System.getProperty("user.dir"))
                .resolve("../shared/test-vectors/m2_sample").normalize();
        Files.createDirectories(outDir);
        Path output = outDir.resolve("clean_30s_1080p30.mp4");
        System.out.println("[m2-sample] writing " + output);

        int totalFrames = FPS * DURATION_SEC;
        long startNanos = System.nanoTime();

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), W, H, 0)) {
            recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
            recorder.setFormat("mp4");
            recorder.setFrameRate(FPS);
            recorder.setVideoOption("crf", String.valueOf(CRF));
            recorder.setVideoOption("preset", "medium");
            recorder.start();

            Java2DFrameConverter conv = new Java2DFrameConverter();
            for (int i = 0; i < totalFrames; i++) {
                BufferedImage img = renderFrame(i, totalFrames);
                Frame frame = conv.convert(img);
                recorder.record(frame);
                if (i % FPS == 0) {
                    System.out.printf("[m2-sample] frame %d / %d%n", i, totalFrames);
                }
            }
        }
        long elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000L;
        long bytes = Files.size(output);
        System.out.printf("[m2-sample] done: %d frames, %.2f MB, encoded in %ds%n",
                totalFrames, bytes / 1024.0 / 1024.0, elapsedSec);
    }

    private static BufferedImage renderFrame(int idx, int total) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            // Vertical gradient base
            g.setPaint(new GradientPaint(0, 0, new Color(60, 60, 70), 0, H, new Color(190, 200, 210)));
            g.fillRect(0, 0, W, H);

            // Scattered grayscale dots (deterministic per-frame seed)
            Random rng = new Random(idx * 31L + 7L);
            for (int n = 0; n < 4000; n++) {
                int x = rng.nextInt(W);
                int y = rng.nextInt(H);
                int dotSize = 3 + rng.nextInt(5);
                int v = 100 + rng.nextInt(120);
                g.setColor(new Color(v, v, v));
                g.fillRect(x, y, dotSize, dotSize);
            }

            // Bouncing rectangle
            int rectW = 280, rectH = 200;
            double t = (double) idx / total;
            int rectX = (int) ((W - rectW) * 0.5 * (1 + Math.sin(t * 4 * Math.PI)));
            int rectY = (int) ((H - rectH) * (0.3 + 0.4 * Math.cos(t * 6 * Math.PI)));
            g.setColor(new Color(220, 100, 60));
            g.fillRect(rectX, rectY, rectW, rectH);
            g.setColor(new Color(60, 30, 20));
            g.setStroke(new BasicStroke(4));
            g.drawRect(rectX, rectY, rectW, rectH);

            // Frame counter (sharp text edges = high-frequency content)
            g.setColor(Color.WHITE);
            g.setFont(new Font("Monospaced", Font.BOLD, 48));
            g.drawString(String.format("Specter M2 frame %d / %d", idx, total), 80, 80);
        } finally {
            g.dispose();
        }
        return img;
    }
}

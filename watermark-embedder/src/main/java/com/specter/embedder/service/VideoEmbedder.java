package com.specter.embedder.service;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.dto.EmbedMetrics;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M2 video embed pipeline (contract sections 4.3, 4.4, 10.2).
 *
 * <p>Hybrid pipeline:
 * <ul>
 *   <li>JavaCV {@link FFmpegFrameGrabber} sadece metadata icin (width, height, fps).</li>
 *   <li>Decode: system {@code ffmpeg} subprocess input.mp4'i raw YUV420P olarak stdout'a yazar.</li>
 *   <li>Java loop: her frame'in Y duzlemini DctEmbedder ile modifiye eder.</li>
 *   <li>Encode: ikinci {@code ffmpeg} subprocess raw YUV stdin'i alir, libx264 ile MP4'e kodlar.</li>
 * </ul>
 *
 * <p>Bu yaklasim secildi cunku JavaCV'nin bundled FFmpeg'i (a) libx264 icermez ve (b) bizim setup'ta
 * YUV420P frame'lerini packed/multi-plane olarak farkli sekilde sunabiliyor; subprocess pipeline
 * tum platform variantlarinda determinisik. Kullanici talimatindaki "fall back to direct FFmpeg
 * subprocess invocation" yolu.
 *
 * <p>Skip path (section 10.2 path b): PSNR &lt; 40 dB olan modifiye frame'lerde DCT degisiklikleri
 * DctEmbedder icinde geri alinir; biz orijinal Y'yi encoder'a gondeririz (video continuity korunur,
 * watermark sadece o frame'de yok).
 *
 * <p>Audio: input stream varsa encoder komutunda ikinci input olarak baglanir ve
 * {@code -c:a copy} ile pass-through yapilir.
 *
 * <p>M3 scale robustness: 1920x1080 videolarda ana 1080p DCT markasina ek olarak
 * 1280x720 luma projeksiyonuna ikinci bir kopya gomulur ve bu low-res delta 1080p
 * frame'e geri tasinir. PSNR bu ek isaretten sonra tekrar olculur.
 */
@Component
public class VideoEmbedder {

    private static final Logger log = LoggerFactory.getLogger(VideoEmbedder.class);
    private static final int M3_SCALE_WIDTH = 1280;
    private static final int M3_SCALE_HEIGHT = 720;
    private static final double M3_SCALE_SHADOW_GAIN = 2.0;

    private final KeyManager keyManager;
    private final PayloadEncoder payloadEncoder;
    private final DctEmbedder dctEmbedder;
    private final PsnrCalculator psnrCalculator;

    public VideoEmbedder(KeyManager keyManager,
                         PayloadEncoder payloadEncoder,
                         DctEmbedder dctEmbedder,
                         PsnrCalculator psnrCalculator) {
        this.keyManager = keyManager;
        this.payloadEncoder = payloadEncoder;
        this.dctEmbedder = dctEmbedder;
        this.psnrCalculator = psnrCalculator;
    }

    public VideoEmbedResult embed(Path input, Path output, long watermarkId) throws IOException {
        if (!keyManager.isReady()) {
            throw new IllegalStateException("KeyManager not initialized; SPECTER_WM_KEY missing or invalid");
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        // 1) Metadata via JavaCV grabber (no frame I/O — cheap)
        int width;
        int height;
        double frameRate;
        int audioChannels;
        try (FFmpegFrameGrabber probe = new FFmpegFrameGrabber(input.toFile())) {
            probe.start();
            width = probe.getImageWidth();
            height = probe.getImageHeight();
            frameRate = probe.getVideoFrameRate();
            audioChannels = probe.getAudioChannels();
        }
        if (frameRate <= 0) frameRate = 30.0;
        if (width % 2 != 0 || height % 2 != 0) {
            throw new IllegalStateException("YUV420P requires even dimensions; got " + width + "x" + height);
        }

        byte[] codeword = payloadEncoder.encode(watermarkId);
        long startNanos = System.nanoTime();

        int yPlaneSize = width * height;
        int uvPlaneSize = (width / 2) * (height / 2);
        int frameBytes = yPlaneSize + 2 * uvPlaneSize;

        log.info("video embed: input={} output={} watermark_id=0x{} {}x{}@{}fps "
                        + "subprocess=ffmpeg libx264 crf={} pix_fmt=yuv420p container=mp4 audio_copy={}",
                input, output, String.format("%08X", watermarkId),
                width, height, String.format("%.2f", frameRate),
                ContractConstants.H264_CRF_DEFAULT, audioChannels > 0);
        if (audioChannels > 0) {
            log.info("input has {} audio channel(s); passing audio through with -c:a copy", audioChannels);
        }

        int framesProcessed = 0;
        int framesPsnrViolation = 0;
        int framesSkipped = 0;
        double psnrSum = 0.0;
        double psnrMin = Double.POSITIVE_INFINITY;
        double mseSum = 0.0;
        double mseMax = 0.0;

        Process decoder = startFfmpegDecoder(input);
        StringBuilder decStderr = new StringBuilder();
        Thread decDrain = drainStderr(decoder, decStderr, "ffmpeg-decoder-stderr");

        Process encoder = startFfmpegEncoder(output, width, height, frameRate, input, audioChannels > 0);
        StringBuilder encStderr = new StringBuilder();
        Thread encDrain = drainStderr(encoder, encStderr, "ffmpeg-encoder-stderr");

        try {
            InputStream decOut = decoder.getInputStream();
            OutputStream encIn = encoder.getOutputStream();
            byte[] yuv = new byte[frameBytes];
            byte[] yPlane = new byte[yPlaneSize];

            try {
                while (true) {
                    int read = readFully(decOut, yuv, 0, frameBytes);
                    if (read == 0) {
                        break; // EOF
                    }
                    if (read < frameBytes) {
                        throw new IOException("decoder produced incomplete frame: " + read + "/" + frameBytes);
                    }
                    framesProcessed++;

                    System.arraycopy(yuv, 0, yPlane, 0, yPlaneSize);
                    byte[] originalY = yPlane.clone();
                    DctEmbedder.EmbedResult result = dctEmbedder.embedIntoYPlane(
                            yPlane, width, height, codeword, ContractConstants.DELTA);

                    if (result.embedded()) {
                        embedScaleShadowIntoYPlane(yPlane, width, height, codeword, ContractConstants.DELTA);
                        double mse = psnrCalculator.mse(originalY, yPlane);
                        double psnr = psnrCalculator.psnrDb(mse);
                        if (psnr < ContractConstants.PSNR_FLOOR_DB) {
                            System.arraycopy(originalY, 0, yPlane, 0, yPlane.length);
                            framesSkipped++;
                            encIn.write(yuv);
                            continue;
                        }
                        // Modifiye Y'yi yuv buffer'ina geri yaz (U/V dokunulmamis kalir)
                        System.arraycopy(yPlane, 0, yuv, 0, yPlaneSize);
                        psnrSum += psnr;
                        if (psnr < psnrMin) psnrMin = psnr;
                        mseSum += mse;
                        if (mse > mseMax) mseMax = mse;
                        if (psnr <= ContractConstants.PSNR_FLOOR_DB) {
                            framesPsnrViolation++;
                        }
                    } else {
                        // Skip: yuv buffer'i orijinal Y'yi tasiyor (yPlane reverted, geri yazma yok)
                        framesSkipped++;
                    }

                    encIn.write(yuv);
                }
            } finally {
                encIn.close();
            }

            int decExit;
            int encExit;
            try {
                decExit = decoder.waitFor();
                encExit = encoder.waitFor();
                decDrain.join(10_000);
                encDrain.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for ffmpeg subprocesses", e);
            }
            if (decExit != 0) {
                throw new IOException("ffmpeg decoder exited with code " + decExit
                        + "; stderr tail:\n" + tailOf(decStderr, 4000));
            }
            if (encExit != 0) {
                throw new IOException("ffmpeg encoder exited with code " + encExit
                        + "; stderr tail:\n" + tailOf(encStderr, 4000));
            }
        } finally {
            if (decoder.isAlive()) decoder.destroyForcibly();
            if (encoder.isAlive()) encoder.destroyForcibly();
        }

        double processingSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double durationSec = framesProcessed / frameRate;
        int framesEmitted = framesProcessed - framesSkipped;
        double psnrAvg = framesEmitted > 0 ? psnrSum / framesEmitted : Double.POSITIVE_INFINITY;
        double mseAvg = framesEmitted > 0 ? mseSum / framesEmitted : 0.0;

        EmbedMetrics metrics = new EmbedMetrics(
                psnrAvg, psnrMin, mseAvg, mseMax,
                framesProcessed, framesPsnrViolation,
                durationSec, processingSec);

        log.info("video embed done: frames_processed={} frames_skipped={} (informational) "
                        + "frames_psnr_violation={} psnr_avg_db={} psnr_min_db={} duration_sec={} processing_sec={}",
                framesProcessed, framesSkipped, framesPsnrViolation,
                String.format("%.2f", psnrAvg), String.format("%.2f", psnrMin),
                String.format("%.2f", durationSec), String.format("%.2f", processingSec));

        return new VideoEmbedResult(output, metrics, framesSkipped);
    }

    private void embedScaleShadowIntoYPlane(byte[] yPlane, int width, int height,
                                            byte[] codeword84, double delta) {
        if (width != 1920 || height != 1080) {
            return;
        }
        byte[] lowBefore = resizeYPlane(yPlane, width, height, M3_SCALE_WIDTH, M3_SCALE_HEIGHT);
        byte[] lowMarked = lowBefore.clone();
        DctEmbedder.EmbedResult lowResult = dctEmbedder.embedIntoYPlane(
                lowMarked, M3_SCALE_WIDTH, M3_SCALE_HEIGHT, codeword84, delta);
        if (!lowResult.embedded()) {
            return;
        }
        for (int y = 0; y < height; y++) {
            double srcY = y * (M3_SCALE_HEIGHT - 1.0) / (height - 1.0);
            for (int x = 0; x < width; x++) {
                double srcX = x * (M3_SCALE_WIDTH - 1.0) / (width - 1.0);
                double before = bilinear(lowBefore, M3_SCALE_WIDTH, M3_SCALE_HEIGHT, srcX, srcY);
                double after = bilinear(lowMarked, M3_SCALE_WIDTH, M3_SCALE_HEIGHT, srcX, srcY);
                int idx = y * width + x;
                int adjusted = (int) Math.round((yPlane[idx] & 0xFF)
                        + M3_SCALE_SHADOW_GAIN * (after - before));
                if (adjusted < 0) adjusted = 0;
                else if (adjusted > 255) adjusted = 255;
                yPlane[idx] = (byte) adjusted;
            }
        }
    }

    private static byte[] resizeYPlane(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            double srcY = y * (srcH - 1.0) / (dstH - 1.0);
            for (int x = 0; x < dstW; x++) {
                double srcX = x * (srcW - 1.0) / (dstW - 1.0);
                dst[y * dstW + x] = (byte) Math.round(bilinear(src, srcW, srcH, srcX, srcY));
            }
        }
        return dst;
    }

    private static double bilinear(byte[] src, int width, int height, double x, double y) {
        x = Math.max(0.0, Math.min(width - 1.0, x));
        y = Math.max(0.0, Math.min(height - 1.0, y));
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        double fx = x - x0;
        double fy = y - y0;
        double p00 = src[y0 * width + x0] & 0xFF;
        double p10 = src[y0 * width + x1] & 0xFF;
        double p01 = src[y1 * width + x0] & 0xFF;
        double p11 = src[y1 * width + x1] & 0xFF;
        return (1.0 - fy) * ((1.0 - fx) * p00 + fx * p10)
                + fy * ((1.0 - fx) * p01 + fx * p11);
    }

    private static Process startFfmpegDecoder(Path input) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-loglevel", "error",
                "-i", input.toString(),
                "-f", "rawvideo",
                "-pix_fmt", "yuv420p",
                "-an",
                "-"
        );
        pb.redirectErrorStream(false);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to launch ffmpeg decoder. System `ffmpeg` must be on PATH "
                    + "(macOS: `brew install ffmpeg`). Original: " + e.getMessage(), e);
        }
    }

    private static Process startFfmpegEncoder(Path output, int width, int height, double frameRate,
                                              Path inputForAudio, boolean copyAudio)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y",
                "-loglevel", "error",
                "-f", "rawvideo",
                "-pixel_format", "yuv420p",
                "-video_size", width + "x" + height,
                "-framerate", String.valueOf(frameRate),
                "-i", "-"
        ));
        if (copyAudio) {
            command.add("-i");
            command.add(inputForAudio.toString());
            command.add("-map");
            command.add("0:v:0");
            command.add("-map");
            command.add("1:a?");
        }
        command.addAll(List.of(
                "-c:v", "libx264",
                "-crf", String.valueOf(ContractConstants.H264_CRF_DEFAULT),
                "-pix_fmt", "yuv420p",
                "-preset", "medium"
        ));
        if (copyAudio) {
            command.addAll(List.of("-c:a", "copy"));
        }
        command.addAll(List.of(
                "-f", "mp4",
                output.toString()
        ));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectErrorStream(false);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to launch ffmpeg encoder. Contract section 4.4 requires libx264 "
                    + "via system `ffmpeg` and audio copy uses `-c:a copy` when present. "
                    + "macOS: `brew install ffmpeg`. Original: " + e.getMessage(), e);
        }
    }

    private static Thread drainStderr(Process p, StringBuilder collected, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (collected) {
                        collected.append(line).append('\n');
                    }
                    log.debug("[{}] {}", name, line);
                }
            } catch (IOException ignored) {
                // process closed stream on shutdown
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) {
                return total;
            }
            total += n;
        }
        return total;
    }

    private static String tailOf(StringBuilder sb, int maxChars) {
        synchronized (sb) {
            int len = sb.length();
            return len <= maxChars ? sb.toString() : sb.substring(len - maxChars);
        }
    }

    /** Service-internal result. EmbedMetrics §6.1 schema'sina sadik; framesSkipped sibling
     *  (informational, compliance metric degil — contract §10.0 not). */
    public record VideoEmbedResult(
            Path output,
            EmbedMetrics metrics,
            int framesSkipped
    ) {
    }
}

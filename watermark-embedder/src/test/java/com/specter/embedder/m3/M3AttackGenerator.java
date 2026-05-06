package com.specter.embedder.m3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Contract section 10.3 / 11 — 4 adversarial attack variant'ini system `ffmpeg`
 * ile uretir. Her cikti yine libx264/yuv420p/mp4'tur (codec round-trip de
 * saldirinin parcasi).
 *
 * <p>Attack files cached: dosya zaten varsa regenerate edilmez. M3 testi tekrar
 * tekrar kosturulurken sadece ilk kosturmada ffmpeg cagrisi yapilir.
 */
public final class M3AttackGenerator {

    private static final Logger log = LoggerFactory.getLogger(M3AttackGenerator.class);

    public static final String FILE_BITRATE = "attack_bitrate.mp4";
    public static final String FILE_SCALE = "attack_scale.mp4";
    public static final String FILE_CROP = "attack_crop.mp4";
    public static final String FILE_COLOR = "attack_color.mp4";

    private M3AttackGenerator() {
    }

    public record VideoMetadata(int width, int height, double fps, long videoBitrate, double durationSec) {
    }

    public static boolean hasAudio(Path input) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name",
                "-of", "default=nokey=1:noprint_wrappers=1",
                input.toString()
        );
        return !run(pb).trim().isEmpty();
    }

    public static VideoMetadata probe(Path input) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate,bit_rate,duration",
                "-of", "default=noprint_wrappers=1",
                input.toString()
        );
        String stdout = run(pb);
        int width = 0, height = 0;
        long bitrate = 0;
        double fps = 0, duration = 0;
        for (String line : stdout.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            switch (k) {
                case "width" -> width = Integer.parseInt(v);
                case "height" -> height = Integer.parseInt(v);
                case "r_frame_rate" -> fps = parseRational(v);
                case "bit_rate" -> bitrate = "N/A".equals(v) ? 0 : Long.parseLong(v);
                case "duration" -> duration = "N/A".equals(v) ? 0 : Double.parseDouble(v);
                default -> { /* ignore */ }
            }
        }
        if (bitrate <= 0) {
            // Fall back to format-level bitrate
            ProcessBuilder pb2 = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=bit_rate",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    input.toString()
            );
            String fb = run(pb2).trim();
            if (!fb.isEmpty() && !"N/A".equals(fb)) {
                bitrate = Long.parseLong(fb);
            }
        }
        return new VideoMetadata(width, height, fps, bitrate, duration);
    }

    public static void generateAll(Path input, Path outDir, long videoBitrate, boolean hasAudio) throws IOException {
        Files.createDirectories(outDir);
        long halfBitrate = Math.max(videoBitrate / 2, 100_000); // sanity floor

        // a) Bitrate -50%
        runIfMissing(input, outDir.resolve(FILE_BITRATE), () -> {
            List<String> cmd = baseCmd(input);
            cmd.addAll(List.of("-b:v", String.valueOf(halfBitrate)));
            cmd.addAll(encodeOpts(hasAudio));
            cmd.add(outDir.resolve(FILE_BITRATE).toString());
            return cmd;
        });

        // b) 1080p -> 720p
        runIfMissing(input, outDir.resolve(FILE_SCALE), () -> {
            List<String> cmd = baseCmd(input);
            cmd.addAll(List.of("-vf", "scale=1280:720"));
            cmd.addAll(encodeOpts(hasAudio));
            cmd.add(outDir.resolve(FILE_SCALE).toString());
            return cmd;
        });

        // c) 5% border crop (iw*0.9 x ih*0.9 = 5% from each side, centered)
        runIfMissing(input, outDir.resolve(FILE_CROP), () -> {
            List<String> cmd = baseCmd(input);
            cmd.addAll(List.of("-vf", "crop=iw*0.9:ih*0.9"));
            cmd.addAll(encodeOpts(hasAudio));
            cmd.add(outDir.resolve(FILE_CROP).toString());
            return cmd;
        });

        // d) brightness +0.1 / contrast 1.1 (~+10%)
        runIfMissing(input, outDir.resolve(FILE_COLOR), () -> {
            List<String> cmd = baseCmd(input);
            cmd.addAll(List.of("-vf", "eq=brightness=0.1:contrast=1.1"));
            cmd.addAll(encodeOpts(hasAudio));
            cmd.add(outDir.resolve(FILE_COLOR).toString());
            return cmd;
        });
    }

    private static List<String> baseCmd(Path input) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-i");
        cmd.add(input.toString());
        return cmd;
    }

    private static List<String> encodeOpts(boolean hasAudio) {
        List<String> opts = new ArrayList<>(List.of(
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-preset", "medium"
        ));
        if (hasAudio) {
            opts.addAll(List.of("-c:a", "copy"));
        } else {
            opts.add("-an");
        }
        return opts;
    }

    private static void runIfMissing(Path source, Path target,
                                     java.util.function.Supplier<List<String>> cmdSupplier) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0
                && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            log.info("[m3-attack] cached: {} ({} bytes)", target.getFileName(), Files.size(target));
            return;
        }
        List<String> cmd = cmdSupplier.get();
        log.info("[m3-attack] generating {}: {}", target.getFileName(), String.join(" ", cmd));
        run(new ProcessBuilder(cmd));
        log.info("[m3-attack] wrote {} ({} bytes)", target.getFileName(), Files.size(target));
    }

    private static String run(ProcessBuilder pb) throws IOException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("subprocess exited " + code + ":\n" + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return out.toString();
    }

    private static double parseRational(String v) {
        int slash = v.indexOf('/');
        if (slash < 0) return Double.parseDouble(v);
        return Double.parseDouble(v.substring(0, slash)) / Double.parseDouble(v.substring(slash + 1));
    }
}

package com.specter.embedder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Video I/O cephesi: upload'i temp'e yazar, decode -> Y embed -> H.264 (libx264) yuv420p MP4
 * encode pipeline'ini kosturur. Audio passthrough (contract section 4.4).
 *
 * Contract section 4.4: Embedder FFmpeg parametrelerini logsuna yazmak zorundadir.
 *
 * Skeleton: gercek decode/encode JavaCV (FFmpegFrameGrabber/Recorder) ile M2'de eklenecek.
 */
@Component
public class VideoIO {

    private static final Logger log = LoggerFactory.getLogger(VideoIO.class);

    public Path saveUpload(MultipartFile file, UUID requestId) throws IOException {
        String suffix = ".mp4";
        String original = file.getOriginalFilename();
        if (original != null) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && dot < original.length() - 1) {
                suffix = original.substring(dot);
            }
        }
        Path tmp = Files.createTempFile("specter-embed-" + requestId + "-", suffix);
        file.transferTo(tmp);
        log.info("Saved upload {} bytes -> {}", file.getSize(), tmp);
        return tmp;
    }

    public EmbedRunResult embedVideo(Path input, Path output, byte[] codeword84,
                                     DctEmbedder embedder, int h264Crf) {
        log.info("ffmpeg params: -c:v libx264 -crf {} -pix_fmt yuv420p -c:a copy in={} out={}",
                h264Crf, input, output);
        // TODO(M2): JavaCV decode/encode loop:
        //   FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input.toFile());
        //   FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), w, h);
        //   recorder.setVideoCodec(AV_CODEC_ID_H264);
        //   recorder.setVideoOption("crf", String.valueOf(h264Crf));
        //   recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
        //   loop frames -> YCbCr split -> embedder.embedIntoYPlane -> merge -> recorder.record
        //   accumulate PSNR metrics, count frames_psnr_violation (PSNR < 40 dB)
        throw new UnsupportedOperationException("VideoIO.embedVideo: not yet implemented (M2)");
    }

    /** Pipeline ic ciktisi - service katmani bunu EmbedMetrics'e cevirir. */
    public record EmbedRunResult(
            int framesProcessed,
            int framesPsnrViolation,
            double psnrAvgDb,
            double psnrMinDb,
            double mseAvg,
            double mseMax,
            double durationSec
    ) {
    }
}

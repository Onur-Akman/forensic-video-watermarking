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
 * Video I/O cephesi: upload'i temp'e yazar, decode -> Y embed -> H.264
 * (libx264) yuv420p MP4
 * encode pipeline'ini kosturur. Audio passthrough (contract section 4.4).
 *
 * Contract section 4.4: Embedder FFmpeg parametrelerini logsuna yazmak
 * zorundadir.
 *
 * Skeleton: gercek decode/encode JavaCV (FFmpegFrameGrabber/Recorder) ile M2'de
 * eklenecek.
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

}

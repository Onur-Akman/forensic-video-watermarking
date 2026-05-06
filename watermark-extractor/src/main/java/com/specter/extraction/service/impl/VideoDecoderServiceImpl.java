package com.specter.extraction.service.impl;

import com.specter.extraction.config.AppConfig;
import com.specter.extraction.exception.VideoProcessingException;
import com.specter.extraction.model.FrameData;
import com.specter.extraction.service.VideoDecoderService;
import com.specter.extraction.util.ColorSpaceUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JavaCV-based VideoDecoderService.
 *
 * Two operating modes:
 *  1. decodeToFrames()  — buffers all frames in a List (for images / short clips).
 *  2. processFrames()   — streaming: each frame is decoded, handed to a Consumer,
 *                         then immediately GC-eligible.
 *                         Avoids OOM when processing 90 × 1080p frames simultaneously.
 */
@Service
public class VideoDecoderServiceImpl implements VideoDecoderService {

    private static final Logger log = LoggerFactory.getLogger(VideoDecoderServiceImpl.class);

    private final AppConfig appConfig;

    public VideoDecoderServiceImpl(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    // ─────────────────────────────────────────────────────────────────
    // Buffered decode — keeps all frames in memory
    // ─────────────────────────────────────────────────────────────────

    @Override
    public List<FrameData> decodeToFrames(MultipartFile videoFile) {
        log.info("Buffered decode — file: {}, size: {} bytes",
                videoFile.getOriginalFilename(), videoFile.getSize());
        try (InputStream is = videoFile.getInputStream();
             FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(is)) {
            List<FrameData> buf = new ArrayList<>();
            streamGrab(grabber, appConfig.getMaxFrames(), buf::add);
            return buf;
        } catch (IOException e) {
            throw new VideoProcessingException("Failed to read upload stream", e);
        } catch (Exception e) {
            throw new VideoProcessingException("JavaCV decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<FrameData> decodeToFrames(String videoFilePath) {
        log.info("Buffered decode from path: {}", videoFilePath);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFilePath)) {
            List<FrameData> buf = new ArrayList<>();
            streamGrab(grabber, appConfig.getMaxFrames(), buf::add);
            return buf;
        } catch (Exception e) {
            throw new VideoProcessingException("Failed to decode from path: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Streaming decode — zero-copy accumulation, no OOM
    // ─────────────────────────────────────────────────────────────────

    @Override
    public int processFrames(MultipartFile videoFile, Consumer<FrameData> consumer) {
        log.info("Streaming decode — file: {}, size: {} bytes",
                videoFile.getOriginalFilename(), videoFile.getSize());
        try (InputStream is = videoFile.getInputStream();
             FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(is)) {
            return streamGrab(grabber, appConfig.getMaxFrames(), consumer);
        } catch (IOException e) {
            throw new VideoProcessingException("Failed to read upload stream", e);
        } catch (Exception e) {
            throw new VideoProcessingException("JavaCV streaming failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Core grab loop — shared by all entry points
    // ─────────────────────────────────────────────────────────────────

    /**
     * Decodes frames one by one, passing each to {@code consumer}.
     * The luminance array is the only retained data; all RGB pixel buffers
     * are discarded immediately after conversion, keeping GC pressure low.
     *
     * @return total number of successfully processed frames
     */
    private int streamGrab(FFmpegFrameGrabber grabber, int maxFrames, Consumer<FrameData> consumer)
            throws Exception {
        grabber.start();

        int videoWidth  = grabber.getImageWidth();
        int videoHeight = grabber.getImageHeight();
        double frameRate = grabber.getFrameRate();
        if (frameRate <= 0) frameRate = 30.0;

        log.info("Video: {}x{} @ {:.2f} fps, maxFrames={}", videoWidth, videoHeight, frameRate, maxFrames);

        Java2DFrameConverter converter = new Java2DFrameConverter();
        int frameIndex = 0;
        int processed  = 0;
        Frame rawFrame;

        while ((rawFrame = grabber.grabImage()) != null && frameIndex < maxFrames) {
            try {
                BufferedImage image = converter.convert(rawFrame);
                if (image == null) {
                    log.warn("Null frame at index {}, skipping", frameIndex);
                    frameIndex++;
                    continue;
                }

                int w = image.getWidth();
                int h = image.getHeight();

                // Extract packed ARGB, convert to luminance — RGB buffer is then GC-eligible.
                int[] rgbPixels = image.getRGB(0, 0, w, h, null, 0, w);
                double[][] luminance = ColorSpaceUtils.extractLuminance(rgbPixels, w, h);

                consumer.accept(FrameData.builder()
                        .frameIndex(frameIndex)
                        .width(w)
                        .height(h)
                        .luminanceChannel(luminance)
                        .timestampSeconds((double) frameIndex / frameRate)
                        .build());

                processed++;
            } catch (Exception e) {
                log.warn("Failed to process frame {}: {}", frameIndex, e.getMessage());
            }
            frameIndex++;
        }

        grabber.stop();

        if (processed == 0) {
            throw new VideoProcessingException("No frames could be decoded from the video");
        }

        log.info("Decoded {} frames (cap={})", processed, maxFrames);
        return processed;
    }
}

package com.specter.extraction.service.impl;

import com.specter.extraction.config.AppConfig;
import com.specter.extraction.exception.VideoProcessingException;
import com.specter.extraction.model.FrameData;
import com.specter.extraction.service.VideoDecoderService;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY8;

/**
 * JavaCV-based VideoDecoderService.
 *
 * Decodes video to luminance (Y) plane directly using AV_PIX_FMT_GRAY8,
 * bypassing the lossy BGR → RGB → BT.601 Y conversion pipeline.
 */
@Service
public class VideoDecoderServiceImpl implements VideoDecoderService {

    private static final Logger log = LoggerFactory.getLogger(VideoDecoderServiceImpl.class);

    private final AppConfig appConfig;

    public VideoDecoderServiceImpl(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

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

    private int streamGrab(FFmpegFrameGrabber grabber, int maxFrames, Consumer<FrameData> consumer)
            throws Exception {

        // Request single-channel grayscale output → FFmpeg extracts Y plane directly
        grabber.setPixelFormat(AV_PIX_FMT_GRAY8);
        grabber.start();

        int videoWidth  = grabber.getImageWidth();
        int videoHeight = grabber.getImageHeight();
        double frameRate = grabber.getFrameRate();
        if (frameRate <= 0) frameRate = 30.0;

        log.info("Video: {}x{} @ {} fps, maxFrames={}, pixelFormat=GRAY8",
                videoWidth, videoHeight, String.format("%.2f", frameRate), maxFrames);

        int frameIndex = 0;
        int processed  = 0;
        Frame rawFrame;

        while ((rawFrame = grabber.grabFrame(false, true, true, false)) != null && frameIndex < maxFrames) {
            try {
                if (rawFrame.image == null || rawFrame.image.length == 0) {
                    frameIndex++;
                    continue;
                }

                int stride = rawFrame.imageStride;
                ByteBuffer yBuffer = (ByteBuffer) rawFrame.image[0];
                int origPos = yBuffer.position();
                
                try {
                    double[][] luminance = new double[videoHeight][videoWidth];
                    for (int y = 0; y < videoHeight; y++) {
                        int rowOffset = y * stride;
                        for (int x = 0; x < videoWidth; x++) {
                            luminance[y][x] = yBuffer.get(rowOffset + x) & 0xFF;
                        }
                    }

                    consumer.accept(FrameData.builder()
                            .frameIndex(frameIndex)
                            .width(videoWidth)
                            .height(videoHeight)
                            .luminanceChannel(luminance)
                            .timestampSeconds((double) frameIndex / frameRate)
                            .build());

                    processed++;
                } finally {
                    yBuffer.position(origPos);
                }
            } catch (Exception e) {
                log.warn("Failed to process frame {}: {}", frameIndex, e.getMessage());
            }
            frameIndex++;
        }

        grabber.stop();
        if (processed == 0) throw new VideoProcessingException("No frames decoded");

        log.info("Decoded {} frames", processed);
        return processed;
    }
}

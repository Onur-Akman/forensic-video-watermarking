package com.specter.extraction.service;

import com.specter.extraction.model.FrameData;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.function.Consumer;


/**
 * Service interface for decoding video files into individual frames.
 * Uses JavaCV (FFmpegFrameGrabber) for in-memory, disk-free frame extraction.
 */
public interface VideoDecoderService {

    /**
     * Decode a video file into a list of individual frames.
     * Each frame contains the luminance (Y) channel data for DCT processing.
     *
     * @param videoFile the uploaded video file
     * @return ordered list of decoded frames
     */
    List<FrameData> decodeToFrames(MultipartFile videoFile);

    /**
     * Decode a video file from a file path into individual frames.
     *
     * @param videoFilePath absolute path to the video file
     * @return ordered list of decoded frames
     */
    List<FrameData> decodeToFrames(String videoFilePath);

    /**
     * Streaming decode: processes each frame immediately via the consumer,
     * then discards it. Avoids holding all frames in memory at once.
     * Returns the total number of frames processed.
     *
     * @param videoFile the uploaded video file
     * @param consumer  called once per decoded frame
     * @return total frames processed
     */
    int processFrames(MultipartFile videoFile, Consumer<FrameData> consumer);
}

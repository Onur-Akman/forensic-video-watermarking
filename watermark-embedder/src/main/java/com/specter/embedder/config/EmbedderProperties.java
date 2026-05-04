package com.specter.embedder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml `specter.*` bind. */
@ConfigurationProperties(prefix = "specter")
public record EmbedderProperties(
        String wmKey,
        String outputDir,
        int h264Crf,
        String contractVersion
) {
}

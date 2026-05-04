package com.specter.embedder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EmbedderApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbedderApplication.class, args);
    }
}

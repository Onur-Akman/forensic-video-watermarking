package com.specter.extraction.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

public abstract class BaseVectorTest {

    protected static final ObjectMapper mapper = new ObjectMapper();

    protected JsonNode loadTestVector(String filename) throws IOException {
        // Maven test runs in the module directory, so ".." goes to the project root.
        File file = Paths.get("..", "shared", "test-vectors", filename).toFile();
        if (!file.exists()) {
            throw new RuntimeException("Test vector not found: " + file.getAbsolutePath());
        }
        return mapper.readTree(file);
    }

    protected int[] bitStringToBits(String bitsStr) {
        int[] bits = new int[bitsStr.length()];
        for (int i = 0; i < bitsStr.length(); i++) {
            bits[i] = bitsStr.charAt(i) - '0';
        }
        return bits;
    }

    protected String bitsToBitString(int[] bits) {
        StringBuilder sb = new StringBuilder();
        for (int b : bits) {
            sb.append(b);
        }
        return sb.toString();
    }
}

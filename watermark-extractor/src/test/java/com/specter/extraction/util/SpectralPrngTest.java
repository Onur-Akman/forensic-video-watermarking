package com.specter.extraction.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpectralPrngTest extends BaseVectorTest {

    private byte[] parseHex(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    public void testPrngStream() throws IOException {
        JsonNode root = loadTestVector("prng_stream.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0);

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            JsonNode input = vector.get("input");
            
            byte[] key = parseHex(input.get("key_hex").asText());
            String context = input.get("context_ascii").asText();
            int byteCount = input.get("byte_count").asInt();
            String expectedBytesHex = vector.get("expected").get("bytes_hex").asText();
            
            SpectralPrng prng = new SpectralPrng(key, context);
            byte[] actualBytes = new byte[byteCount];
            for (int i = 0; i < byteCount; i++) {
                actualBytes[i] = (byte) prng.nextByte();
            }
            
            assertEquals(expectedBytesHex, toHex(actualBytes), "PRNG stream mismatch for vector " + id);
        }
    }

    @Test
    public void testPrngPermutation() throws IOException {
        JsonNode root = loadTestVector("prng_permutation.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0);

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            JsonNode input = vector.get("input");
            
            byte[] key = parseHex(input.get("key_hex").asText());
            String context = input.get("context_ascii").asText();
            int n = input.get("n").asInt();
            
            JsonNode expectedPermutation = vector.get("expected").get("permutation");
            
            SpectralPrng prng = new SpectralPrng(key, context);
            int[] perm = prng.generatePermutation(n);
            
            assertEquals(expectedPermutation.size(), perm.length, "Permutation length mismatch for vector " + id);
            for (int i = 0; i < n; i++) {
                assertEquals(expectedPermutation.get(i).asInt(), perm[i], "Permutation element mismatch for vector " + id + " at index " + i);
            }
        }
    }

    @Test
    public void testPrngPairMap() throws IOException {
        JsonNode root = loadTestVector("prng_pair_map.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0);

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            JsonNode input = vector.get("input");
            
            byte[] key = parseHex(input.get("key_hex").asText());
            String context = input.get("context_ascii").asText();
            int count = input.get("count").asInt();
            int bound = input.get("bound").asInt();
            
            JsonNode expectedDraws = vector.get("expected").get("draws");
            
            SpectralPrng prng = new SpectralPrng(key, context);
            int[] draws = new int[count];
            for (int i = 0; i < count; i++) {
                draws[i] = prng.nextInt(bound);
            }
            
            assertEquals(expectedDraws.size(), draws.length, "Draws length mismatch for vector " + id);
            for (int i = 0; i < count; i++) {
                assertEquals(expectedDraws.get(i).asInt(), draws[i], "Draws element mismatch for vector " + id + " at index " + i);
            }
        }
    }
}

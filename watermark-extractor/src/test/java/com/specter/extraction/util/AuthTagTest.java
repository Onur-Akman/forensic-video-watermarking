package com.specter.extraction.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AuthTagTest extends BaseVectorTest {

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
    public void testAuthTag() throws IOException {
        JsonNode root = loadTestVector("auth_tag.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0);

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            JsonNode input = vector.get("input");
            
            byte[] authKey = parseHex(input.get("auth_key_hex").asText());
            String wmIdHex = input.get("watermark_id_hex").asText();
            long wmIdLong = Long.parseLong(wmIdHex.substring(2), 16);
            int wmId = (int) wmIdLong; // 32-bit ID
            
            String expectedTagHex = vector.get("expected").get("tag_hex").asText();
            
            // Expected length is 4 hex chars = 16 bits = short
            int tag = HmacAuthUtils.generateAuthTag(authKey, wmId);
            
            // Format to 4 hex chars
            String actualTagHex = String.format("%04x", tag & 0xFFFF);
            
            assertEquals(expectedTagHex, actualTagHex, "AuthTag mismatch for vector " + id);
        }
    }
}

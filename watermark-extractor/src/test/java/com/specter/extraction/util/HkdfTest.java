package com.specter.extraction.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HkdfTest extends BaseVectorTest {

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
    public void testRfc5869Vectors() throws IOException {
        JsonNode root = loadTestVector("hkdf_rfc5869.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0);

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            JsonNode input = vector.get("input");
            
            byte[] ikm = parseHex(input.get("ikm_hex").asText());
            byte[] salt = parseHex(input.get("salt_hex").asText());
            byte[] info = parseHex(input.get("info_hex").asText());
            int length = input.get("length").asInt();

            byte[] expectedOkm = parseHex(vector.get("expected").get("okm_hex").asText());
            
            // We can't easily test PRK from the outside, but we can test OKM
            byte[] okm = KeyDerivationUtils.hkdf(salt.length == 0 ? new byte[0] : salt, ikm, info.length == 0 ? new byte[0] : info, length);
            
            assertEquals(toHex(expectedOkm), toHex(okm), "RFC5869 mismatch for vector " + id);
        }
    }

    @Test
    public void testSpecterSubkeys() throws IOException {
        JsonNode root = loadTestVector("hkdf_specter_subkeys.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0);

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            JsonNode input = vector.get("input");
            
            byte[] ikm = parseHex(input.get("ikm_hex").asText());
            byte[] expectedOkm = parseHex(vector.get("expected").get("okm_hex").asText());
            
            byte[] okm = null;
            if (id.equals("specter_auth_key")) {
                okm = KeyDerivationUtils.deriveAuthKey(ikm);
            } else if (id.equals("specter_prng_key")) {
                okm = KeyDerivationUtils.derivePrngKey(ikm);
            } else if (id.equals("specter_interleave_key")) {
                okm = KeyDerivationUtils.deriveInterleaverKey(ikm);
            }

            assertEquals(toHex(expectedOkm), toHex(okm), "Specter subkey mismatch for vector " + id);
        }
    }
}

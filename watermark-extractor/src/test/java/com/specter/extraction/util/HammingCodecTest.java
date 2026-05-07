package com.specter.extraction.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HammingCodecTest extends BaseVectorTest {

    @Test
    public void testHammingVectors() throws IOException {
        JsonNode root = loadTestVector("hamming74.json");
        JsonNode vectors = root.get("vectors");
        assertTrue(vectors.isArray() && vectors.size() > 0, "Vectors array should not be empty");

        for (JsonNode vector : vectors) {
            String id = vector.get("id").asText();
            
            // We only process individual nibble tests here
            if (id.startsWith("nibble_")) {
                int nibble = Integer.parseInt(vector.get("input").get("nibble_hex").asText(), 16);
                String expectedCodewordBitsMsb = vector.get("expected").get("codeword_bits_msb").asText();
                
                int[] encoded = HammingCodec.encodeNibble(nibble);
                String actualCodewordBits = bitsToBitString(encoded);
                
                assertEquals(expectedCodewordBitsMsb, actualCodewordBits, "Encoding mismatch for vector " + id);
                
                // Test decode
                int decoded = HammingCodec.decodeNibble(encoded);
                assertEquals(nibble, decoded, "Decoding mismatch for vector " + id);
                
                // Test 1-bit error correction
                for (int errorBit = 0; errorBit < 7; errorBit++) {
                    int[] corrupted = encoded.clone();
                    corrupted[errorBit] ^= 1; // flip 1 bit
                    int recovered = HammingCodec.decodeNibble(corrupted);
                    assertEquals(nibble, recovered, "Error correction failed for vector " + id + " at bit " + errorBit);
                }
            } else if (id.equals("packet_id_0x5C2A91FE_synthetic_tag_0xBEEF")) {
                // Ignore the packet test for now since it requires BitInterleaver/BitPacker which isn't just HammingCodec.
                // Or we can manually encode the 48 bits to 84 bits to test.
                String rawHex = vector.get("input").get("raw_packet_bytes_hex").asText();
                String expectedCodeword = vector.get("expected").get("codeword_bits_msb").asText();
                
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rawHex.length(); i++) {
                    int nibble = Integer.parseInt(rawHex.substring(i, i+1), 16);
                    int[] encoded = HammingCodec.encodeNibble(nibble);
                    sb.append(bitsToBitString(encoded));
                }
                assertEquals(expectedCodeword, sb.toString(), "Packet encoding mismatch");
            }
        }
    }
}

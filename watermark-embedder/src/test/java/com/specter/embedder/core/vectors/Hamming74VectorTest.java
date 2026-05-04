package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.specter.embedder.core.codec.Hamming74;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract section 1.4 — Hamming(7,4) even-parity per nibble + full 48->84 bit packet. */
class Hamming74VectorTest {

    static Stream<Arguments> nibbleVectors() {
        return VectorLoader.vectors("hamming74")
                .filter(v -> v.path("input").has("nibble_hex"))
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    static Stream<Arguments> packetVectors() {
        return VectorLoader.vectors("hamming74")
                .filter(v -> v.path("input").has("raw_packet_bits_msb"))
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nibbleVectors")
    void encodeNibble_matchesFixture(String id, JsonNode v) {
        int nibble = Integer.parseInt(v.path("input").path("nibble_hex").asText(), 16);
        byte cw = Hamming74.encodeNibble(nibble);
        StringBuilder bits = new StringBuilder(7);
        for (int b = 0; b < 7; b++) {
            bits.append((cw >>> (6 - b)) & 1);
        }
        assertEquals(v.path("expected").path("codeword_bits_msb").asText(), bits.toString(), id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("packetVectors")
    void encodePacket_matchesFixture(String id, JsonNode v) {
        byte[] packetBits = VectorLoader.bitsMsb(v.path("input").path("raw_packet_bits_msb").asText());
        byte[] codewordBits = Hamming74.encodePacket(packetBits);
        assertEquals(v.path("expected").path("codeword_bits_msb").asText(),
                VectorLoader.bitsMsb(codewordBits), id);
    }
}

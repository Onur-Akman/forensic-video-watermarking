package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.specter.embedder.core.crypto.AuthTag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract section 1 — 16-bit truncated HMAC-SHA256 auth tag for representative watermark ids. */
class AuthTagVectorTest {

    static Stream<Arguments> authTagVectors() {
        return VectorLoader.vectors("auth_tag")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authTagVectors")
    void compute_matchesFixture(String id, JsonNode v) {
        byte[] authKey = VectorLoader.hex(v.path("input").path("auth_key_hex").asText());
        String idHex = v.path("input").path("watermark_id_hex").asText();
        // "0x..." prefix kontrat REST API ile ayni format; parseUnsignedLong icin strip.
        long watermarkId = Long.parseUnsignedLong(idHex.substring(2), 16);

        byte[] tag = AuthTag.compute(authKey, watermarkId);
        assertEquals(v.path("expected").path("tag_hex").asText(), VectorLoader.hex(tag), id);
    }
}

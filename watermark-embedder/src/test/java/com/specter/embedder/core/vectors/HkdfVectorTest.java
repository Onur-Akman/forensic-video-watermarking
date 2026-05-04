package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.specter.embedder.core.crypto.Hkdf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract section 2.2 — HKDF-SHA256. RFC 5869 standart vektörler + Specter dummy master subkey'leri. */
class HkdfVectorTest {

    static Stream<Arguments> rfc5869Vectors() {
        return VectorLoader.vectors("hkdf_rfc5869")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    static Stream<Arguments> specterSubkeyVectors() {
        return VectorLoader.vectors("hkdf_specter_subkeys")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rfc5869Vectors")
    void deriveKey_matchesRfc5869(String id, JsonNode v) {
        byte[] ikm = VectorLoader.hex(v.path("input").path("ikm_hex").asText());
        byte[] salt = VectorLoader.hex(v.path("input").path("salt_hex").asText());
        byte[] info = VectorLoader.hex(v.path("input").path("info_hex").asText());
        int length = v.path("input").path("length").asInt();

        byte[] prk = Hkdf.extract(salt, ikm);
        assertEquals(v.path("expected").path("prk_hex").asText(), VectorLoader.hex(prk), id + " PRK");

        byte[] okm = Hkdf.deriveKey(ikm, salt, info, length);
        assertEquals(v.path("expected").path("okm_hex").asText(), VectorLoader.hex(okm), id + " OKM");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specterSubkeyVectors")
    void deriveKey_matchesSpecterSubkey(String id, JsonNode v) {
        byte[] ikm = VectorLoader.hex(v.path("input").path("ikm_hex").asText());
        byte[] salt = VectorLoader.hex(v.path("input").path("salt_hex").asText());
        byte[] info = VectorLoader.hex(v.path("input").path("info_hex").asText());
        int length = v.path("input").path("length").asInt();
        byte[] okm = Hkdf.deriveKey(ikm, salt, info, length);
        assertEquals(v.path("expected").path("okm_hex").asText(), VectorLoader.hex(okm), id);
    }
}

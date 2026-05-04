package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.specter.embedder.core.crypto.Prng;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract section 2.3 / 2.4 — HMAC-SHA256 counter stream PRNG: stream, permutation, sample, bounded draws. */
class PrngVectorTest {

    static Stream<Arguments> streamVectors() {
        return VectorLoader.vectors("prng_stream")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    static Stream<Arguments> permutationVectors() {
        return VectorLoader.vectors("prng_permutation")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    static Stream<Arguments> sampleVectors() {
        return VectorLoader.vectors("prng_sample")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    static Stream<Arguments> pairMapVectors() {
        return VectorLoader.vectors("prng_pair_map")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streamVectors")
    void byteStream_matchesFixture(String id, JsonNode v) {
        byte[] key = VectorLoader.hex(v.path("input").path("key_hex").asText());
        String ctx = v.path("input").path("context_ascii").asText();
        int byteCount = v.path("input").path("byte_count").asInt();

        Prng prng = new Prng(key, ctx);
        byte[] bytes = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) bytes[i] = prng.nextByte();

        assertEquals(v.path("expected").path("bytes_hex").asText(), VectorLoader.hex(bytes), id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permutationVectors")
    void permutation_matchesFixture(String id, JsonNode v) {
        byte[] key = VectorLoader.hex(v.path("input").path("key_hex").asText());
        String ctx = v.path("input").path("context_ascii").asText();
        int n = v.path("input").path("n").asInt();

        int[] perm = new Prng(key, ctx).permutation(n);
        int[] expected = VectorLoader.intArray(v.path("expected").path("permutation"));
        assertArrayEquals(expected, perm, id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleVectors")
    void sample_matchesFixture(String id, JsonNode v) {
        byte[] key = VectorLoader.hex(v.path("input").path("key_hex").asText());
        String ctx = v.path("input").path("context_ascii").asText();
        int n = v.path("input").path("n").asInt();
        int k = v.path("input").path("k").asInt();

        int[] sample = new Prng(key, ctx).sampleWithoutReplacement(n, k);
        int[] expected = VectorLoader.intArray(v.path("expected").path("permutation"));
        assertArrayEquals(expected, sample, id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairMapVectors")
    void pairMap_matchesFixture(String id, JsonNode v) {
        byte[] key = VectorLoader.hex(v.path("input").path("key_hex").asText());
        String ctx = v.path("input").path("context_ascii").asText();
        int count = v.path("input").path("count").asInt();
        int bound = v.path("input").path("bound").asInt();

        Prng prng = new Prng(key, ctx);
        int[] draws = new int[count];
        for (int i = 0; i < count; i++) draws[i] = prng.boundedInt(bound);
        int[] expected = VectorLoader.intArray(v.path("expected").path("draws"));
        assertArrayEquals(expected, draws, id);
    }
}

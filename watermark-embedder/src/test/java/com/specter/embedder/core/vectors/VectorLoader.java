package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Test fixture yukleyicisi. `/test-vectors/<name>.json` classpath path'inden
 * okur (`pom.xml` testResource ile `../shared/test-vectors` map edildi) ve
 * top-level `contract_version == "v1"` dogrular.
 */
public final class VectorLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private VectorLoader() {
    }

    public static JsonNode load(String name) {
        String path = "/test-vectors/" + name + ".json";
        try (InputStream in = VectorLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Test vector resource not found on classpath: " + path
                        + " (verify pom.xml <testResources> includes ../shared/test-vectors, "
                        + "or run TestVectorGenerator if the fixture is generator-produced)");
            }
            JsonNode root = MAPPER.readTree(in);
            String contractVersion = root.path("contract_version").asText();
            if (!"v1".equals(contractVersion)) {
                throw new IllegalStateException(path + " has contract_version=" + contractVersion
                        + " but tests expect v1");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    public static Stream<JsonNode> vectors(String name) {
        JsonNode arr = load(name).path("vectors");
        if (!arr.isArray()) {
            throw new IllegalStateException(name + ".json has no vectors[] array");
        }
        Stream.Builder<JsonNode> builder = Stream.builder();
        arr.forEach(builder::add);
        return builder.build();
    }

    public static byte[] hex(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        return HEX.parseHex(s.toLowerCase());
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** "0101" -> {0,1,0,1} byte array (each entry 0/1, MSB-first). */
    public static byte[] bitsMsb(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '0' && c != '1') {
                throw new IllegalArgumentException("non-bit char at " + i + ": '" + c + "'");
            }
            out[i] = (byte) (c - '0');
        }
        return out;
    }

    public static String bitsMsb(byte[] bits) {
        StringBuilder sb = new StringBuilder(bits.length);
        for (byte b : bits) sb.append((char) ('0' + (b & 1)));
        return sb.toString();
    }

    public static int[] intArray(JsonNode arr) {
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).asInt();
        return out;
    }
}

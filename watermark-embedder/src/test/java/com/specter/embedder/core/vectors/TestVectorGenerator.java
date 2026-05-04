package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.core.crypto.AuthTag;
import com.specter.embedder.core.crypto.Hkdf;
import com.specter.embedder.core.crypto.Prng;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HexFormat;

/**
 * Specter-specific test vector generator. `core/` impl'i contract'taki dummy master
 * key (§2.1) ile çalıştırır ve `shared/test-vectors/` altına 6 generator-produced
 * fixture yazar. Hand-authored 2 dosyaya (`hamming74.json`, `hkdf_rfc5869.json`) DOKUNMAZ.
 *
 * Cıkti deterministiktir: arka arkaya iki kosturma `git diff` bos vermeli.
 *
 * Calistirma:
 * <pre>
 * cd watermark-embedder
 * mvn -q test-compile exec:java \
 *   -Dexec.mainClass=com.specter.embedder.core.vectors.TestVectorGenerator \
 *   -Dexec.classpathScope=test
 * </pre>
 */
public final class TestVectorGenerator {

    /** Contract section 2.1 — published dummy master key. NOT a production key. */
    private static final String DUMMY_MASTER_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private static final String GENERATED_BY = "TestVectorGenerator";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final HexFormat HEX = HexFormat.of();

    private TestVectorGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = resolveOutDir(args);
        if (!Files.isDirectory(outDir)) {
            throw new IOException("Output directory not found: " + outDir);
        }
        System.out.println("[generator] writing fixtures to " + outDir.toAbsolutePath());

        byte[] master = HEX.parseHex(DUMMY_MASTER_HEX);
        byte[] authKey = Hkdf.deriveKey(master,
                ContractConstants.HKDF_SALT, ContractConstants.HKDF_INFO_AUTH, ContractConstants.KEY_BYTES);
        byte[] prngKey = Hkdf.deriveKey(master,
                ContractConstants.HKDF_SALT, ContractConstants.HKDF_INFO_PRNG, ContractConstants.KEY_BYTES);
        byte[] interleaveKey = Hkdf.deriveKey(master,
                ContractConstants.HKDF_SALT, ContractConstants.HKDF_INFO_INTERLEAVER, ContractConstants.KEY_BYTES);

        writeHkdfSpecterSubkeys(outDir, master, authKey, prngKey, interleaveKey);
        writePrngStream(outDir, prngKey, interleaveKey);
        writePrngPermutation(outDir, interleaveKey);
        writePrngSample(outDir, prngKey);
        writePrngPairMap(outDir, prngKey);
        writeAuthTag(outDir, authKey);

        System.out.println("[generator] done");
    }

    private static Path resolveOutDir(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--out".equals(args[i])) {
                return Paths.get(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        return Paths.get(System.getProperty("user.dir")).resolve("../shared/test-vectors").normalize();
    }

    // --------- hkdf_specter_subkeys ---------

    private static void writeHkdfSpecterSubkeys(Path outDir, byte[] master, byte[] authKey,
                                                byte[] prngKey, byte[] interleaveKey) throws IOException {
        ObjectNode root = baseRoot("hkdf_specter_subkeys",
                "Specter HKDF-SHA256 sub-key derivations from contract dummy master "
                        + "(contract section 2.1, 2.2). Salt and info stored as ASCII bytes hex.");
        ArrayNode vectors = root.putArray("vectors");
        vectors.add(makeHkdfVector("specter_auth_key",
                "auth_key with info=\"payload-auth\"",
                master, ContractConstants.HKDF_SALT, ContractConstants.HKDF_INFO_AUTH,
                ContractConstants.KEY_BYTES, authKey));
        vectors.add(makeHkdfVector("specter_prng_key",
                "prng_key with info=\"block-selection\"",
                master, ContractConstants.HKDF_SALT, ContractConstants.HKDF_INFO_PRNG,
                ContractConstants.KEY_BYTES, prngKey));
        vectors.add(makeHkdfVector("specter_interleave_key",
                "interleave_key with info=\"bit-interleaver\"",
                master, ContractConstants.HKDF_SALT, ContractConstants.HKDF_INFO_INTERLEAVER,
                ContractConstants.KEY_BYTES, interleaveKey));
        write(outDir, "hkdf_specter_subkeys.json", root);
    }

    private static ObjectNode makeHkdfVector(String id, String description,
                                             byte[] ikm, byte[] salt, byte[] info,
                                             int length, byte[] okm) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("description", description);
        ObjectNode input = node.putObject("input");
        input.put("ikm_hex", HEX.formatHex(ikm));
        input.put("salt_hex", HEX.formatHex(salt));
        input.put("info_hex", HEX.formatHex(info));
        input.put("length", length);
        ObjectNode expected = node.putObject("expected");
        expected.put("okm_hex", HEX.formatHex(okm));
        return node;
    }

    // --------- prng_stream ---------

    private static void writePrngStream(Path outDir, byte[] prngKey, byte[] interleaveKey) throws IOException {
        ObjectNode root = baseRoot("prng_stream",
                "HMAC-SHA256 counter stream output for each Specter PRNG context "
                        + "(contract section 2.3, 2.4). byte_count > 32 forces counter increment past 1 HMAC block.");
        ArrayNode vectors = root.putArray("vectors");
        int byteCount = 128;
        vectors.add(makePrngStreamVector("interleaver_first128",
                interleaveKey, ContractConstants.PRNG_CTX_INTERLEAVER, byteCount));
        vectors.add(makePrngStreamVector("cell_map_first128",
                prngKey, ContractConstants.PRNG_CTX_CELL_MAP, byteCount));
        vectors.add(makePrngStreamVector("pair_map_first128",
                prngKey, ContractConstants.PRNG_CTX_PAIR_MAP, byteCount));
        write(outDir, "prng_stream.json", root);
    }

    private static ObjectNode makePrngStreamVector(String id, byte[] key, String ctx, int byteCount) {
        Prng prng = new Prng(key, ctx);
        byte[] out = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) out[i] = prng.nextByte();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        ObjectNode input = node.putObject("input");
        input.put("key_hex", HEX.formatHex(key));
        input.put("context_ascii", ctx);
        input.put("byte_count", byteCount);
        ObjectNode expected = node.putObject("expected");
        expected.put("bytes_hex", HEX.formatHex(out));
        return node;
    }

    // --------- prng_permutation ---------

    private static void writePrngPermutation(Path outDir, byte[] interleaveKey) throws IOException {
        ObjectNode root = baseRoot("prng_permutation",
                "Fisher-Yates permutation outputs (contract section 2.3) using interleave_key + interleaver context.");
        ArrayNode vectors = root.putArray("vectors");
        vectors.add(makePermVector("permutation_84_interleaver",
                interleaveKey, ContractConstants.PRNG_CTX_INTERLEAVER, ContractConstants.CODEWORD_BITS));
        vectors.add(makePermVector("permutation_10_interleaver_eyeball",
                interleaveKey, ContractConstants.PRNG_CTX_INTERLEAVER, 10));
        write(outDir, "prng_permutation.json", root);
    }

    private static ObjectNode makePermVector(String id, byte[] key, String ctx, int n) {
        int[] perm = new Prng(key, ctx).permutation(n);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        ObjectNode input = node.putObject("input");
        input.put("key_hex", HEX.formatHex(key));
        input.put("context_ascii", ctx);
        input.put("n", n);
        ObjectNode expected = node.putObject("expected");
        ArrayNode arr = expected.putArray("permutation");
        for (int p : perm) arr.add(p);
        return node;
    }

    // --------- prng_sample ---------

    private static void writePrngSample(Path outDir, byte[] prngKey) throws IOException {
        ObjectNode root = baseRoot("prng_sample",
                "sampleWithoutReplacement(GRID_CELLS, EMBED_POINTS_PER_FRAME) using prng_key + cell-map context "
                        + "(contract section 3.2).");
        ArrayNode vectors = root.putArray("vectors");
        int n = ContractConstants.GRID_CELLS;
        int k = ContractConstants.EMBED_POINTS_PER_FRAME;
        int[] sample = new Prng(prngKey, ContractConstants.PRNG_CTX_CELL_MAP).sampleWithoutReplacement(n, k);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "sample_" + n + "_" + k + "_cellmap");
        ObjectNode input = node.putObject("input");
        input.put("key_hex", HEX.formatHex(prngKey));
        input.put("context_ascii", ContractConstants.PRNG_CTX_CELL_MAP);
        input.put("n", n);
        input.put("k", k);
        ObjectNode expected = node.putObject("expected");
        ArrayNode arr = expected.putArray("permutation");
        for (int v : sample) arr.add(v);
        vectors.add(node);
        write(outDir, "prng_sample.json", root);
    }

    // --------- prng_pair_map ---------

    private static void writePrngPairMap(Path outDir, byte[] prngKey) throws IOException {
        ObjectNode root = baseRoot("prng_pair_map",
                "EMBED_POINTS_PER_FRAME draws of boundedInt(DCT_PAIRS.length) using prng_key + pair-map context "
                        + "(contract section 4.1).");
        ArrayNode vectors = root.putArray("vectors");
        int count = ContractConstants.EMBED_POINTS_PER_FRAME;
        int bound = ContractConstants.DCT_PAIRS.length;
        Prng prng = new Prng(prngKey, ContractConstants.PRNG_CTX_PAIR_MAP);
        int[] draws = new int[count];
        for (int i = 0; i < count; i++) draws[i] = prng.boundedInt(bound);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "pair_map_" + count + "_x_bounded" + bound);
        ObjectNode input = node.putObject("input");
        input.put("key_hex", HEX.formatHex(prngKey));
        input.put("context_ascii", ContractConstants.PRNG_CTX_PAIR_MAP);
        input.put("count", count);
        input.put("bound", bound);
        ObjectNode expected = node.putObject("expected");
        ArrayNode arr = expected.putArray("draws");
        for (int v : draws) arr.add(v);
        vectors.add(node);
        write(outDir, "prng_pair_map.json", root);
    }

    // --------- auth_tag ---------

    private static void writeAuthTag(Path outDir, byte[] authKey) throws IOException {
        ObjectNode root = baseRoot("auth_tag",
                "16-bit truncated HMAC-SHA256 auth tag (contract section 1) for representative watermark ids.");
        ArrayNode vectors = root.putArray("vectors");
        long[] ids = {0x5C2A91FEL, 0x00000000L, 0xFFFFFFFFL};
        String[] descriptions = {
                "test seed (contract section 11)",
                "min unsigned 32-bit",
                "max unsigned 32-bit (verifies long->int two's complement cast)"
        };
        for (int i = 0; i < ids.length; i++) {
            byte[] tag = AuthTag.compute(authKey, ids[i]);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", String.format("authtag_id_0x%08X", ids[i]));
            node.put("description", descriptions[i]);
            ObjectNode input = node.putObject("input");
            input.put("auth_key_hex", HEX.formatHex(authKey));
            input.put("watermark_id_hex", String.format("0x%08X", ids[i]));
            ObjectNode expected = node.putObject("expected");
            expected.put("tag_hex", HEX.formatHex(tag));
            vectors.add(node);
        }
        write(outDir, "auth_tag.json", root);
    }

    // --------- common ---------

    private static ObjectNode baseRoot(String name, String description) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 1);
        root.put("contract_version", "v1");
        root.put("name", name);
        root.put("description", description);
        root.put("generated_by", GENERATED_BY);
        return root;
    }

    private static void write(Path outDir, String filename, ObjectNode root) throws IOException {
        Path target = outDir.resolve(filename);
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(target, json + "\n", StandardCharsets.UTF_8);
        System.out.println("[generator] wrote " + filename);
    }
}

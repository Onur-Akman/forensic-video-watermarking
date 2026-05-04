package com.specter.embedder.m1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.service.DctEmbedder;
import com.specter.embedder.service.KeyManager;
import com.specter.embedder.service.PayloadEncoder;
import com.specter.embedder.service.PsnrCalculator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * One-shot generator for the M1 golden artifact pair:
 *   shared/test-vectors/m1_sample/watermarked.png  (1920x1080 8-bit grayscale,
 *                                                   ID 0x5C2A91FE embedded)
 *   shared/test-vectors/m1_sample/expected.json   (extraction expectations
 *                                                   + 252 per-cell traces)
 *
 * <p>Yaren'in extractor M1 self-validation amaci: extract(watermarked.png) ciktisini
 * expected.json ile karsilastirir. Embedder kodunu okumadan kendi blind decoder'ini
 * bagimsiz olarak dogrulayabilir.
 */
public final class M1SampleGenerator {

    private static final String DUMMY_MASTER_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final long TEST_ID = 0x5C2A91FEL;
    private static final int W = 1920;
    private static final int H = 1080;
    private static final int Y_FILL = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private M1SampleGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(System.getProperty("user.dir"))
                .resolve("../shared/test-vectors/m1_sample").normalize();
        Files.createDirectories(outDir);
        Path pngFile = outDir.resolve("watermarked.png");
        Path expectedFile = outDir.resolve("expected.json");

        // Manuel DI (Spring yok)
        EmbedderProperties props = new EmbedderProperties(
                DUMMY_MASTER_HEX, ".", ContractConstants.H264_CRF_DEFAULT, ContractConstants.CONTRACT_VERSION);
        KeyManager keyManager = new KeyManager(props);
        DctEmbedder dctEmbedder = new DctEmbedder(keyManager, new PsnrCalculator());
        PayloadEncoder payloadEncoder = new PayloadEncoder(keyManager);

        // 1) Uniform gray Y duzlemi + embed (M1RoundtripTest ile aynen ayni input)
        byte[] yPlane = new byte[W * H];
        Arrays.fill(yPlane, (byte) Y_FILL);
        byte[] codeword = payloadEncoder.encode(TEST_ID);
        DctEmbedder.EmbedResult result = dctEmbedder.embedIntoYPlane(
                yPlane, W, H, codeword, ContractConstants.DELTA);
        if (!result.embedded()) {
            throw new IllegalStateException("Unexpected: uniform gray @ DELTA=12 PSNR floor altinda kaldi");
        }

        // 2) PNG yaz (8-bit grayscale)
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        byte[] imgData = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(yPlane, 0, imgData, 0, yPlane.length);
        if (!ImageIO.write(img, "PNG", pngFile.toFile())) {
            throw new IOException("ImageIO PNG writer not available");
        }
        long pngBytes = Files.size(pngFile);
        System.out.printf("[m1-sample] wrote %s (%d bytes)%n", pngFile, pngBytes);

        // 3) Sanity: PNG'yi geri yukle, in-memory roundtrip ile karsilastir
        BufferedImage loaded = ImageIO.read(pngFile.toFile());
        byte[] loadedY = ((DataBufferByte) loaded.getRaster().getDataBuffer()).getData();
        if (loadedY.length != W * H) {
            throw new IllegalStateException("PNG geri okuma length mismatch: " + loadedY.length);
        }
        if (!Arrays.equals(loadedY, yPlane)) {
            throw new IllegalStateException("PNG round-trip Y plane mismatch (encoder/decoder lossiness?)");
        }
        Roundtrip.Result rt = Roundtrip.extract(loadedY, W, H, keyManager);
        if (!rt.authTagValid()) {
            throw new IllegalStateException("PNG roundtrip auth_tag invalid");
        }
        if (rt.watermarkId() != TEST_ID) {
            throw new IllegalStateException(String.format(
                    "PNG roundtrip ID mismatch: expected 0x%08X got 0x%08X", TEST_ID, rt.watermarkId()));
        }
        System.out.printf("[m1-sample] roundtrip OK: id=0x%08X confidence=%.4f auth_tag_valid=%s%n",
                rt.watermarkId(), rt.bitConfidence(), rt.authTagValid());

        // 4) expected.json olustur
        int[] cells = dctEmbedder.planCells();
        int[] pairs = dctEmbedder.planPairs();
        byte[] bits = DctEmbedder.repeatCodeword(codeword);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 1);
        root.put("contract_version", "v1");
        root.put("name", "m1_sample");
        root.put("description",
                "M1 golden artifact for extractor self-validation. Uniform gray (Y=128) 1920x1080 "
                        + "Y plane with watermark id 0x5C2A91FE embedded via DCT pair modulation "
                        + "(DELTA=12). Extractor implementations should: (1) load watermarked.png as "
                        + "8-bit luma, (2) extract via blind decoder per contract section 5, "
                        + "(3) match expected.watermark_id_hex bit-perfectly with auth_tag_valid=true. "
                        + "The cells[] array gives per-cell traces (cell index, DCT pair, embedded bit, "
                        + "expected vote sign) for finer diagnostic checks. expected_vote_sign is the "
                        + "sign of (a - b) at the embedder's chosen DCT pair: +1 for embedded bit=1, "
                        + "-1 for bit=0 (contract section 4.2 / 5.1). For uniform-gray input the "
                        + "magnitude is exactly DELTA = 12.0; on real content magnitude varies but "
                        + "sign should match.");
        root.put("generated_by", "M1SampleGenerator");

        ObjectNode input = root.putObject("input");
        input.put("image_file", "watermarked.png");
        input.put("image_width", W);
        input.put("image_height", H);
        input.put("image_format", "PNG, 8-bit grayscale (TYPE_BYTE_GRAY)");
        input.put("scenario", "uniform_gray");
        input.put("y_fill", Y_FILL);
        input.put("specter_wm_key_hex", DUMMY_MASTER_HEX);
        input.put("delta", ContractConstants.DELTA);

        ObjectNode expected = root.putObject("expected");
        expected.put("watermark_id_hex", String.format("0x%08X", TEST_ID));
        expected.put("watermark_id_decimal", TEST_ID);
        expected.put("auth_tag_valid", true);
        expected.put("bit_confidence_min", 0.95);
        expected.put("psnr_db", result.psnrDb());

        ArrayNode cellsArr = expected.putArray("cells");
        for (int i = 0; i < ContractConstants.EMBED_POINTS_PER_FRAME; i++) {
            int bit = bits[i] & 1;
            ObjectNode cell = MAPPER.createObjectNode();
            cell.put("i", i);
            cell.put("cell_index", cells[i]);
            cell.put("pair_index", pairs[i]);
            cell.put("embedded_bit", bit);
            cell.put("expected_vote_sign", bit == 1 ? 1 : -1);
            cellsArr.add(cell);
        }

        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(expectedFile, json + "\n", StandardCharsets.UTF_8);
        System.out.printf("[m1-sample] wrote %s (%d bytes, %d cells)%n",
                expectedFile, Files.size(expectedFile), ContractConstants.EMBED_POINTS_PER_FRAME);
    }
}

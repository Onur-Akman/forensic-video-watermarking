package com.specter.extraction;

import com.specter.extraction.util.*;
import com.specter.extraction.config.WatermarkConfig;
import java.util.*;
import java.nio.ByteBuffer;

public class PrngHypothesisScanner {
    public static void main(String[] args) {
        byte[] key = parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        byte[] prngKey = KeyDerivationUtils.derivePrngKey(key);
        
        System.out.println("Testing hypotheses for PRNG...");
        
        // Target: first 5 cells of frame 0 should be 130, 1072, 816, 1036, 738
        int[] target = {130, 1072, 816, 1036, 738};
        
        test("H1: CTX only (fixed)", prngKey, WatermarkConfig.CTX_CELL_MAP, target);
        test("H2: CTX + /0", prngKey, WatermarkConfig.CTX_CELL_MAP + "/0", target);
        test("H3: CTX + :0", prngKey, WatermarkConfig.CTX_CELL_MAP + ":0", target);
        test("H4: CTX + 0", prngKey, WatermarkConfig.CTX_CELL_MAP + "0", target);
        
        byte[] ctxBytes = WatermarkConfig.CTX_CELL_MAP.getBytes();
        byte[] t0 = new byte[4]; // 0 in uint32_be
        byte[] combined = new byte[ctxBytes.length + 4];
        System.arraycopy(ctxBytes, 0, combined, 0, ctxBytes.length);
        System.arraycopy(t0, 0, combined, ctxBytes.length, 4);
        test("H5: CTX || uint32_be(0)", prngKey, new String(combined, java.nio.charset.StandardCharsets.ISO_8859_1), target);
    }

    private static void test(String name, byte[] key, String context, int[] target) {
        SpectralPrng prng = new SpectralPrng(key, context);
        int[] cells = prng.selectDistinct(1296, 252);
        boolean match = true;
        for (int i = 0; i < target.length; i++) {
            if (cells[i] != target[i]) {
                match = false;
                break;
            }
        }
        System.out.println(name + ": " + (match ? "MATCH!" : "No match (First: " + cells[0] + ")"));
    }

    private static byte[] parseHex(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}

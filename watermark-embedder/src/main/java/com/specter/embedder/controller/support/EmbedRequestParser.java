package com.specter.embedder.controller.support;

import com.specter.embedder.exception.EmbedException;
import com.specter.embedder.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * `POST /api/v1/embed` request parametreleri icin parsing + validation.
 *
 * <p>Controller HTTP routing'e odaklanabilsin diye string-to-domain donusumleri,
 * range check'leri ve null/empty kontrolleri burada toplanir. Tum hatalar
 * {@link EmbedException} olarak yukseltilir; {@link com.specter.embedder.controller.GlobalExceptionHandler}
 * bunlari contract section 6.3 formatına cevirir.
 */
public final class EmbedRequestParser {

    private EmbedRequestParser() {
    }

    /** {@code request_id} param'ini cozer; bos/null ise yeni UUID uretir. */
    public static UUID resolveRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new EmbedException(ErrorCode.MISSING_FIELD, "request_id is not a valid UUID");
        }
    }

    /** {@code watermark_id} param'ini hex (`0x...`) veya decimal kabul eder; 32-bit unsigned aralik kontrolu. */
    public static long parseWatermarkId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EmbedException(ErrorCode.MISSING_FIELD, "watermark_id is required");
        }
        String s = raw.trim().toLowerCase();
        long value;
        try {
            value = s.startsWith("0x")
                    ? Long.parseUnsignedLong(s.substring(2), 16)
                    : Long.parseUnsignedLong(s, 10);
        } catch (NumberFormatException e) {
            throw new EmbedException(ErrorCode.INVALID_WATERMARK_ID,
                    "watermark_id must be hex (0x...) or decimal");
        }
        if (value < 0L || value > 0xFFFFFFFFL) {
            throw new EmbedException(ErrorCode.INVALID_WATERMARK_ID,
                    "watermark_id must be a 32-bit unsigned integer (0..0xFFFFFFFF)");
        }
        return value;
    }

    /** Multipart {@code file} kismini dogrular; bos/null ise MISSING_FIELD. */
    public static void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new EmbedException(ErrorCode.MISSING_FIELD, "file is required");
        }
    }
}

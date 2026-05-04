# watermark-embedder

Project Specter - Embedding (Watermark Encoder) Service.

Implementation reference: [`docs/contract-new.md`](../docs/contract-new.md) v1.

## Stack

- **Runtime:** Java 21 (Temurin)
- **Framework:** Spring Boot 3.4.x (web, actuator, validation)
- **Build:** Maven 3.9+ (mvnw wrapper dahil)
- **Video metadata:** JavaCV 1.5.11 (FFmpeg + OpenCV bindings) — sadece probe icin (genislik, yukseklik, fps)
- **Video decode/encode:** **system `ffmpeg` subprocess** (libx264 ile) — contract §4.4 H.264/libx264/yuv420p/CRF18 zorunlulugu icin. JavaCV'nin bundled FFmpeg dagitimi libx264'u **icermez** (lisans nedeniyle), bu yuzden gercek video pipeline'inda system ffmpeg'e duser. Kullanici talimatindaki "fall back to direct FFmpeg subprocess" yolu (M2'de implemente edildi).
- **DCT:** JTransforms 3.1
- **Crypto:** JDK `javax.crypto` (HMAC-SHA256), in-house HKDF

### Dependencies (system)

- Java 21 JDK
- `ffmpeg` (with libx264 enabled) on PATH — macOS: `brew install ffmpeg`

## Running locally

`SPECTER_WM_KEY` env var (64 hex chars, contract section 2.1) is required.

Maven kurmaya gerek yok — projeyle birlikte gelen `mvnw` wrapper ilk kosturmada
Maven 3.9.15'i indirir (sadece Java 21 JDK gerekir).

```sh
SPECTER_WM_KEY=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff \
  ./mvnw spring-boot:run
```

Default port: `8081` (override with `EMBEDDER_PORT`).

## Endpoints

- `POST /api/v1/embed` - multipart/form-data: `file`, `watermark_id`, optional `request_id`
- `GET /api/v1/health`

Tam request/response sekli icin contract section 6.

## Layout

| Package | Role |
|---|---|
| `controller/` | `@RestController` + `@ControllerAdvice` |
| `service/`    | `@Service` orchestration (KeyManager, VideoIO, EmbeddingService) |
| `dto/`        | request/response records |
| `exception/`  | error codes + custom exceptions |
| `config/`     | `@Configuration`, properties, contract sabitleri |
| `core/`       | framework-bagimsiz saf algoritma (codec, crypto, dct, grid) |

## Testing

```sh
./mvnw test
```

Unit testler `core/` paketinde Spring olmadan calisir; entegrasyon testleri
`@SpringBootTest` ile context yukler. Test-vector regenerate icin:

```sh
./mvnw -q test-compile exec:java \
  -Dexec.mainClass=com.specter.embedder.core.vectors.TestVectorGenerator \
  -Dexec.classpathScope=test
```

# watermark-embedder

Project Specter - Embedding (Watermark Encoder) Service.

Implementation reference: [`docs/contract-new.md`](../docs/contract-new.md) v1.

## Stack

- **Runtime:** Java 21 (Temurin)
- **Framework:** Spring Boot 3.4.x (web, actuator, validation)
- **Build:** Maven 3.9+
- **Video I/O:** JavaCV 1.5.11 (FFmpeg + OpenCV bindings)
- **DCT:** JTransforms 3.1
- **Crypto:** JDK `javax.crypto` (HMAC-SHA256), in-house HKDF

## Running locally

`SPECTER_WM_KEY` env var (64 hex chars, contract section 2.1) is required.

```sh
SPECTER_WM_KEY=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff \
  mvn spring-boot:run
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
mvn test
```

Unit testler `core/` paketinde Spring olmadan calisir; entegrasyon testleri
`@SpringBootTest` ile context yukler.

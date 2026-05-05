# Project Specter — Technical Report

Generated: 2026-05-05T08:14:43.424804Z

Spec referansi: `Project_Specter_Specification_v3.pdf` v1.0; contract: `docs/contract-new.md` v1.

---

## 1. Frequency-Domain Algorithm

**DCT coefficient-pair modulation** (contract section 4):
- 2D DCT-II on 8×8 luminance blocks, orthonormal normalization.
- Mid-frequency pair set `P0..P3` ((2,3)/(3,2), (1,4)/(4,1), (2,4)/(4,2), (3,4)/(4,3); section 4.1).
- Per-bit rule (section 4.2): bit=1 → `a − b ≥ DELTA`; bit=0 → `b − a ≥ DELTA`. DELTA = 12.0. Symmetric centered modulation if margin not satisfied; no change otherwise.
- 252 embedding points per frame: 84-bit interleaved codeword × 3 repetitions, each frame carries the same payload (no frame-index dependence — robust to trim/frame-drop).
- Cell selection: HMAC-SHA256 counter stream PRNG (section 2.3) under context `specter-v1/cell-map/48x27/252`; pair selection under `specter-v1/pair-map/252`.
- Skip path (section 10.2 path b): if PSNR < 40 dB after embedding, frame is reverted to original and forwarded unmodified to the encoder; payload recovered from other (redundant) frames.

## 2. Error-Correction Coding

**Hamming(7,4) per nibble + interleaver + 3× repetition** (contract section 1):
- Each 4-bit nibble → 7-bit codeword (positions p1 p2 d1 p4 d2 d3 d4, even parity, section 1.4).
- 48-bit `raw_packet` = `watermark_id` (32 bit) || `auth_tag` (16 bit, HMAC-SHA256 truncated).
- 12 nibbles × 7 bits = 84-bit codeword.
- Interleaver: PRNG-derived permutation, gather direction `interleaved[i] = codeword[permutation[i]]` (section 1.5).
- Each frame embeds the 84-bit interleaved codeword 3× → 252 modulation points.
- Authenticity: extractor verifies `auth_tag == HMAC-SHA256(auth_key, uint32_be(id))[0:16]`; mismatch ⇒ confidence = 0 (section 5.2).

## 3. M2 PSNR Metrics

M2 video pipeline acceptance was measured against the synthetic 30s 1080p30 test asset in `M2VideoRoundtripTest` (compliance vs targets per section 10.0):

| Metric | Compliance min | Target | M2 observed | Status |
|---|---:|---:|---:|:---:|
| PSNR — every emitted frame | > 40.0 dB | > 45.0 dB | min 66.84 dB | ✅ |
| `frames_psnr_violation` | 0 | 0 | 0 | ✅ |
| Processing time | ≤ 2× duration | ≤ 1.5× | 23.28s / 30.00s = 0.78× | ✅ |
| Bit-perfect ID | true | true | true | ✅ |
| `auth_tag_valid` | true | true | true | ✅ |
| M2 confidence | ≥ 0.90 | ≥ 0.95 | 1.00 | ✅ |

## 4. M3 Adversarial Robustness Results

**Source:** `output/deneme_watermarked.mp4` (1920x1080 @ 25.00 fps, video bitrate 3522 kbps, file 143.47 MB)
**Embedded ID:** `0x5C2A91FE` (test seed, contract section 11).
**Extractor:** internal blind decoder (`Roundtrip.extractFromVideoWithAlignmentSearch`); production extraction is `watermark-extractor` microservice (out of scope for this report).

**Alignment search:** scale ∈ {0.85, 0.90, 0.95, 1.00, 1.05}, offsetX/offsetY ∈ {−0.05, −0.025, 0, 0.025, 0.05} → 125 candidates; probe via first 10 frames; final extraction across up to 90 frames (contract section 5.3 recommendation).

| Attack | FFmpeg args | Output | Extracted ID | Confidence | Min req | Auth | Alignment (s, ox, oy) | Frames | Extract time | Pass |
|---|---|---:|---|---:|---:|:---:|---|---:|---:|:---:|
| Bitrate -50% | `-b:v <half>` | 73.61 MB | `0x5C2A91FE` | 0.7860 | 0.85 | ✓ | (1.00, +0.000, +0.000) | 90 | 1.32s | ❌ |
| 1080p -> 720p | `-vf scale=1280:720` | 57.88 MB | `0x6B1B7DA1` | 0.1481 | 0.85 | ✗ | (0.85, +0.000, -0.025) | 90 | 0.69s | ❌ |
| 5% border crop | `-vf crop=iw*0.9:ih*0.9` | 88.95 MB | `0x5B2A91FE` | 0.3063 | 0.80 | ✗ | (0.90, +0.050, +0.050) | 90 | 0.74s | ❌ |
| Brightness/contrast +-10% | `-vf eq=brightness=0.1:contrast=1.1` | 105.28 MB | `0x5C2A91FE` | 0.8836 | 0.85 | ✓ | (1.00, +0.000, +0.000) | 90 | 0.76s | ✅ |

**Summary:** 1/4 attacks passed.

---

## 5. Notes

- Codec round-trip is part of every attack: outputs re-encoded with libx264, yuv420p, mp4 container; audio (when present) preserved via `-c:a copy`.
- For the crop attack, alignment search converges to scale ≈ 0.90 with offset (0.05, 0.05) — the inverse of FFmpeg's `crop=iw*0.9:ih*0.9` centered crop (removes 5% from each border).
- Non-cropped attacks (bitrate, scale-down, color) naturally win at identity alignment (1.00, 0, 0) since their normalized cell positions are unchanged.
- M3 extractor uses up to 90 frames per extraction (section 5.3 minimum) — well below the source's full duration but more than enough for high-confidence decoding given the 252-cell-per-frame redundancy.

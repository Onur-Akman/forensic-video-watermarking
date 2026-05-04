# Project Specter Inter-Service Contract v1

Bu dokuman, Embedding Service ile Extraction Service arasindaki baglayici teknik
sozlesmedir. Iki servis de bu dosyada tanimlanan sabitleri, bit siralamasini, anahtar
turetimini ve algoritmayi aynen uygulamak zorundadir. Herhangi bir parametre degisikligi
icin once bu dosyanin surumu artirilmali ve iki servis birlikte guncellenmelidir.

Spec referansi: `Project_Specter_Specification_v3.pdf` v1.0.

---

## 0. Taraflar ve sorumluluklar

| Rol | Servis adi | Sorumlu | Branch |
|---|---|---|---|
| Embedding (gomme) | `watermark-embedder` | Onur | `feature/embedding` |
| Extraction (cikarma) | `watermark-extractor` | Yaren | `feature/extraction` |

Her iki servis ayni programlama dili ile yazilmak zorunda degildir; kontrat tum
algoritmik karar ve byte-level cikti formatlarini sabitledigi icin diller arasi 
interop garanti altindadir. Tercih edilen ve dokumante edilmesi gereken stack
secimi her servisin kendi `README.md` dosyasinda belirtilir.

---

## 1. Gizli numara ve paket yapisi

PDF spesifikasyonu gizli kimligi **32-bit unique identifier** olarak tanimliyor.
Standart 128-bit UUID degil, **unsigned 32-bit integer** kullanilir.

- `watermark_id`: 32 bit, unsigned integer.
- Serializasyon: **big-endian** (`uint32_be`), MSB once.
- Gecerli aralik: `0x00000000` - `0xFFFFFFFF`.
- Dis sistemde daha uzun bir UUID kullanilirsa servis disinda 32-bit
  `watermark_id` degerine deterministik olarak map edilmelidir (orn: UUID'nin
  ilk 4 byte'i).

Gomulmeden once kimlik dogrulama ve hata duzeltme uygulanir:

1. **Auth tag uretimi**:
   `auth_tag = HMAC-SHA256(auth_key, uint32_be(watermark_id))[0:16 bit]`
   (HMAC ciktisinin ilk 16 biti, MSB-first.)
2. **Ham paket**: `raw_packet = watermark_id || auth_tag`, toplam **48 bit**.
3. **Hata duzeltme (FEC)**: `raw_packet` her 4-bit nibble icin Hamming(7,4) ile
   kodlanir. 12 nibble x 7 bit = **84 bit codeword**.
4. **Hamming(7,4) bit duzeni**:
   - Nibble bitleri MSB -> LSB: `d1 d2 d3 d4`.
   - Codeword pozisyonlari (1-indexed): `p1 p2 d1 p4 d2 d3 d4`.
   - **Even parity** kullanilir.
   - `p1` = pozisyon 1, 3, 5, 7'nin XOR'u.
   - `p2` = pozisyon 2, 3, 6, 7'nin XOR'u.
   - `p4` = pozisyon 4, 5, 6, 7'nin XOR'u.
5. **Interleaver**: 84 bit codeword, `interleave_key`'den turetilen PRNG ile
   uretilen sabit permutation araciligiyla yeniden siralanir. Bu, DCT
   bolgesindeki burst error'larin tek bir nibble'a yigilmasini engeller.

Ozet:

| Asama | Bit uzunlugu |
|---|---|
| Asil gizli numara | **32 bit** |
| Dogrulama sonrasi ham paket | **48 bit** |
| FEC sonrasi gomulen codeword | **84 bit** |
| Frame basina embedding noktasi (84 x 3 tekrar) | **252** |

Her uygun frame ayni 84-bit codeword'u 3 kere tasir. Boylece tek bir frame
kaybinda dahi paket okunabilir; trim, framerate conversion ve frame drop
saldirilarinda dayanim icin gereklidir.

### 1.1 Tasarim notu: FEC secimi (Hamming vs Reed-Solomon)

Spec FEC olarak Reed-Solomon **veya** Hamming kabul ediyor. Bu kontratta
**Hamming(7,4)** tercih edildi:

- Per-nibble basit yapi tum dillerde (Java/Python/Go/C++) minimum
  bagimlilikla, sabit-sureli implementasyon imkani verir.
- Tek-bit FEC'in zayifligi katman katman kompanze edilir: nibble basina
  Hamming + 3x frame-ici tekrar + PRNG-permute interleaver +
  multi-frame soft voting.
- Reed-Solomon'in sembol genisligi (GF(2^m)), generator polynomial ve
  shortening parametreleri tartismasi contract'tan elenir; iki taraf
  arasi yorum farki riski sifirlanir.

M3 adversarial testlerinde paket kaybi pratik sorun cikarirsa contract
v2'de RS(15,11) veya RS(255,239) gecisi degerlendirilir; ayni interleaver
ve repetition cercevesi korunur.

### 1.2 Bit siralamasi worked example

Endianness ve bit numaralama interop bug'larinin en yaygin kaynagi
oldugu icin asagida tum donusumlerin somut bir ornegi verilmistir.
Hem embedder hem extractor **bire bir** ayni siralamayi uretmek zorundadir.

#### `watermark_id` (32 bit) → bit dizisi

Ornek: `watermark_id = 0xA3F21B04`.

```text
Byte sirasi (big-endian, MSB once):
  byte[0] = 0xA3 = 10100011
  byte[1] = 0xF2 = 11110010
  byte[2] = 0x1B = 00011011
  byte[3] = 0x04 = 00000100

Bit indeksleme: bit[i] = (watermark_id >> (31 - i)) & 1

  bit[0]  = 1   (MSB, byte[0]'in 7. biti)
  bit[1]  = 0
  bit[2]  = 1
  bit[3]  = 0
  bit[4]  = 0
  bit[5]  = 0
  bit[6]  = 1
  bit[7]  = 1   (byte[0]'in 0. biti)
  bit[8]  = 1   (byte[1] basliyor)
  ...
  bit[31] = 0   (LSB, byte[3]'un 0. biti)
```

Dil tuzaklari:
- Java: `int` signed; `0xA3F21B04` literal'i `int` icin negatif gorunur.
  `Integer.toUnsignedString` veya `long` ile sakla. `Integer.toBinaryString`
  zero-pad yapmaz; 32-bit garantisi icin manuel pad gerek.
- Python: `int` arbitrary precision; `value & 0xFFFFFFFF` ile 32 bit'e zorla.

#### HMAC ciktisindan `auth_tag` cikarma

`auth_tag` HMAC-SHA256 ciktisinin **ilk 16 bitidir** — yani ilk 2 byte,
her byte MSB-first.

```text
hmac_full = HMAC-SHA256(auth_key, uint32_be(watermark_id))   # 32 byte

Ornek (anahtara bagli; sadece format gostermek icin):
  hmac_full[0] = 0x5C = 01011100
  hmac_full[1] = 0x2A = 00101010

  auth_tag (16 bit) = 01011100 00101010 = 0x5C2A
    auth_tag bit[0]  = 0  (MSB, hmac_full[0]'in 7. biti)
    auth_tag bit[15] = 0  (LSB, hmac_full[1]'in 0. biti)
```

Tuzak: Java'da `byte` signed; bit operasyonu icin `& 0xFF` masklemesi zorunlu.
Python'da `bytes` zaten unsigned int dizisi.

#### Nibble → Hamming(7,4) codeword

48-bit `raw_packet`, soldan saga 12 adet 4-bit nibble olarak okunur
(her nibble MSB-first). Her nibble bagimsiz olarak kodlanir.

Ornek nibble: `0xA = 1010`.

```text
Nibble bitleri (d1 = MSB, d4 = LSB):
  d1 = 1, d2 = 0, d3 = 1, d4 = 0

Even parity hesabi (XOR):
  p1 = d1 XOR d2 XOR d4 = 1 XOR 0 XOR 0 = 1
  p2 = d1 XOR d3 XOR d4 = 1 XOR 1 XOR 0 = 0
  p4 = d2 XOR d3 XOR d4 = 0 XOR 1 XOR 0 = 1

Codeword (1-indexed pozisyonlar: p1 p2 d1 p4 d2 d3 d4):
  pos1 = p1 = 1
  pos2 = p2 = 0
  pos3 = d1 = 1
  pos4 = p4 = 1
  pos5 = d2 = 0
  pos6 = d3 = 1
  pos7 = d4 = 0

Codeword bit dizisi: "1011010"

Dogrulama (her parity grubunun XOR'u 0 olmali):
  pos 1,3,5,7 = 1,1,0,0  → XOR = 0  ✓
  pos 2,3,6,7 = 0,1,1,0  → XOR = 0  ✓
  pos 4,5,6,7 = 1,0,1,0  → XOR = 0  ✓
```

12 nibble bu sekilde kodlandiktan sonra `12 × 7 = 84` bitlik codeword
stream elde edilir; bu stream §2'deki interleaver'a girer.

---

## 2. Anahtar ve PRNG sozlesmesi

### 2.1 Master anahtar

Tek ortak sir: `SPECTER_WM_KEY` ortam degiskeni.

- Format: 64 hex karakter (case insensitive).
- Uzunluk: 256 bit / 32 byte.
- **Gercek anahtar repoya, dokumana, log'a, error mesajina veya HTTP response'a
  yazilamaz.**
- Lokal gelistirme icin `.env.example` dosyasina sadece dummy key konur:
  `SPECTER_WM_KEY=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff`
- Production'da secret manager (AWS SM, Vault, vs.) tarafindan inject edilir.

### 2.2 Alt anahtar turetimi (HKDF-SHA256)

Tum alt anahtarlar HKDF-SHA256 ile master anahtardan turetilir.

- HKDF salt: ASCII `project-specter-v1`
- `auth_key` (32 byte): info = ASCII `payload-auth`
- `prng_key` (32 byte): info = ASCII `block-selection`
- `interleave_key` (32 byte): info = ASCII `bit-interleaver`

### 2.3 Deterministik PRNG

PRNG **kriptografik HMAC-SHA256 counter stream** olmak zorundadir. Hicbir
servis `java.util.Random`, `Math.random()`, Python `random`, C `rand()` veya
benzeri non-cryptographic LCG kullanmayacaktir. Sebep: hem dilden bagimsiz
bit-aynilik hem de saldirgan tarafindan seed brute-force'unu engellemek.

Uretim formulu:

```text
prng_block(key, context, counter) =
    HMAC-SHA256(key, context_bytes || uint32_be(counter))
```

- Cikti her cagrida 32 byte ham bayt akisi verir.
- Tuketici (cell secimi, pair secimi, permutation) bu akistan ihtiyac duydugu
  sayida bayt okur ve `counter`'i artirir.
- **Permutasyon** Fisher-Yates ile uretilir.
- **Modulo bias** olusmamasi icin **rejection sampling** kullanilir
  (uint32 cekilir; `M = 2^32 - (2^32 mod n)` esiginden buyukse atilir).

### 2.4 Context stringleri

Her PRNG kullanim noktasi farkli context kullanmak zorundadir. Boylece ayni
`prng_key` ile ayni stream'i bir kere kullanilmis olur.

| Amac | Context (ASCII) | Cagiran taraf |
|---|---|---|
| Bit interleaver | `specter-v1/interleaver/84` | Embed + Extract |
| Grid cell secimi (frame basi) | `specter-v1/cell-map/48x27/252` | Embed + Extract |
| Coefficient pair secimi | `specter-v1/pair-map/252` | Embed + Extract |

Frame-bazli secimlerde frame index'in PRNG context'ine eklenip eklenmedigi
asagidaki gibi sabittir: **eklenmez**. Her uygun frame ayni 252 cell + ayni
pair sirasini kullanir; cunku her frame ayni paketi tasir ve extractor frame
index'ini bilmeyecek (trim/frame drop hipotezi).

---

## 3. Video icinde nereye gomulecek

Watermark **sadece luminance kanalina** gomulur.

- Renk uzayi: **YCbCr** (BT.601 katsayilari).
- Kanal: **sadece `Y`**.
- RGB veya chroma kanallarina watermark gomulmez.
- Spatial-domain LSB **yasaktir**.

> Notasyon uyarisi: OpenCV'de `cv2.COLOR_BGR2YCrCb` siralamasi `Y, Cr, Cb`'dir
> (Cr once). Sadece Y kullanildigi icin pratikte sorun degil; ama ileri donuk
> bir gelistirme Cb/Cr'ye dokunursa siralamaya dikkat edilmelidir.

### 3.1 Grid

Her frame 16:9 orana uygun normalize edilmis bir grid ile islenir.

- Grid: `48 x 27 = 1296` cell.
- Kullanilan guvenli bolge: frame'in merkezi **%80**.
- Sol/sag/ust/alt dis margin: **%10**.
- Bu tercih, spec'teki **%5 border crop** testine karsi pay birakmak icindir.

Her grid cell icin merkez normalize koordinati:

```text
x_norm = 0.10 + (col + 0.5) * 0.80 / 48
y_norm = 0.10 + (row + 0.5) * 0.80 / 27
```

Bu normalize koordinatlar mevcut frame boyutuna (W x H pixel) uygulanir ve en
yakin 8x8 DCT block'una snap edilir:

```text
x_pix = round(x_norm * W)
y_pix = round(y_norm * H)
block_x = (x_pix // 8) * 8
block_y = (y_pix // 8) * 8
```

Embedder ve extractor **aynen ayni** snap fonksiyonunu kullanmalidir.

### 3.2 Frame basina nokta secimi

Her uygun frame icin PRNG ile **252 farkli grid cell** secilir
(`specter-v1/cell-map/48x27/252` context'i ile). Ayni cell iki kere
secilemez (Fisher-Yates bunu garanti eder). Bu 252 nokta interleave
edilmis 84-bit codeword'un 3 tekrarini tasir.

### 3.3 Hangi frame'ler kullanilir

**Tum decoded frame'ler watermark tasir.** Pilot frame yoktur; sabit bir
"her N frame'de bir" da yoktur. Bu karar, trim ve frame-drop saldirilarina
karsi en sade dayanikliligi verir; ekstra fps maliyeti DCT-pair modulasyonu
ile yonetilebilir.

---

## 4. Nasil gomulecek (DCT coefficient-pair modulation)

Embedding algoritmasi blind/semi-blind extraction'a uygun
**DCT coefficient-pair modulation**'dir.

- Transform: 8x8 block uzerinde 2D **DCT-II**.
- Normalizasyon: **orthonormal** DCT (her iki taraf orthonormal kullanir).
- Embed strength: `DELTA = 12.0` luma-DCT birimi.
- Pixel clamp: IDCT sonrasi `Y` degerleri `0..255` araligina **round + clamp**
  edilir.
- Kalite kosulu: islenen tum frame'lerde **PSNR > 40 dB** olmali; aksi halde
  embedder o frame icin warning log atar (asagida 9.4'te detaylandirildi).

### 4.1 DCT coefficient pair seti

0-indexed `(row, col)` koordinatlari, 8x8 blok icinde:

```text
P0 = ((2, 3), (3, 2))
P1 = ((1, 4), (4, 1))
P2 = ((2, 4), (4, 2))
P3 = ((3, 4), (4, 3))
```

Her embedding noktasi icin pair index'i PRNG ile secilir
(`specter-v1/pair-map/252` context'i, her cell icin tek bir 0..3 cekilir).
DC ve low-frequency katsayilarina dokunulmaz; high-frequency katsayilarina
guvenilmez (kompresyonda kaybolur).

### 4.2 Bit gomme kurali

Secilen pair icin `a = DCT[u1, v1]`, `b = DCT[u2, v2]`.

- Bit `1` ise hedef: `a - b >= DELTA`.
- Bit `0` ise hedef: `b - a >= DELTA`.
- Mevcut fark hedef marjini zaten sagliyorsa **katsayilar degistirilmez**.
  (Quality preservation: gereksiz modifikasyon yok.)
- Saglamiyorsa iki katsayi simetrik olarak ayarlanir:

```text
mean = (a + b) / 2
Bit 1: a = mean + DELTA / 2,  b = mean - DELTA / 2
Bit 0: a = mean - DELTA / 2,  b = mean + DELTA / 2
```

Sembolik dogrulama: `(mean + DELTA/2) - (mean - DELTA/2) = DELTA >= DELTA` ✓.

### 4.3 Encoding pipeline

```text
for each frame in input video:
    Y, Cb, Cr = YCbCr_split(frame)
    Y' = Y.copy()
    cells = PRNG_select_cells(252)             # specter-v1/cell-map/48x27/252
    pairs = PRNG_select_pairs(252)             # specter-v1/pair-map/252
    interleaved_codeword = INTERLEAVE(codeword_84bit)
    bits_to_embed = repeat(interleaved_codeword, 3)   # 252 bit
    for i in range(252):
        block = extract_8x8_block(Y', cells[i])
        dct_block = dct2_orth(block)
        apply_pair_modulation(dct_block, pairs[i], bits_to_embed[i], DELTA)
        block_out = idct2_orth(dct_block)
        write_8x8_block(Y', cells[i], clamp_round(block_out, 0, 255))
    frame_out = YCbCr_merge(Y', Cb, Cr)
    psnr_check(Y, Y')                          # log warning if < 40 dB
    write_to_h264(frame_out)
```

### 4.4 Cikti video formati

- Codec: **H.264** (libx264).
- Container: **MP4**.
- Pixel format: `yuv420p` (compatibility icin).
- CRF onerisi: `18` (gorsel kayipsiza yakin); izin verilen aralik 16-22.
- Audio: girisi pass-through (re-encode yok).
- Embedder, FFmpeg parametrelerini logsuna yazmak zorundadir; extractor bu
  bilgiyi kullanmaz ama troubleshooting icin gereklidir.

---

## 5. Extraction beklentisi

Extraction Service orijinal temiz videoya **ihtiyac duymaz**. Sadece su
girdilerle calisir:

- Suspicious video.
- `SPECTER_WM_KEY` (env var).

### 5.1 Extraction pipeline

Her aday frame icin:

1. Frame'i YCbCr'ye cevir, `Y` kanalini al.
2. Ayni grid mapping, ayni PRNG context'leri ile **252 nokta** + pair indeksleri
   ureti (embedder ile aynen uretilir).
3. Her noktada DCT'yi al, `(a - b)` farkindan **soft vote** uret:
   - Pozitif fark -> bit `1` lehine vote = `(a - b)`.
   - Negatif fark -> bit `0` lehine vote = `(a - b)` (yani negatif).
4. Ayni codeword bit pozisyonuna gelen **3 tekrar** ve **birden cok frame**
   uzerinden vote'lar toplanir.
5. **Deinterleave** -> 84-bit codeword.
6. **Hamming(7,4) decode** -> 48-bit raw_packet.
7. `raw_packet` = `watermark_id || auth_tag`.
8. `expected_tag = HMAC-SHA256(auth_key, uint32_be(watermark_id))[0:16 bit]`
   karsilastirilir.

### 5.2 Confidence formulu

```text
bit_confidence = mean( |sum_votes_for_bit| / sum_|votes_for_bit| )
                  for each of 84 codeword bits

confidence = bit_confidence  if auth_tag_valid
           = 0.0              if auth_tag_invalid
```

`bit_confidence` aralik: `0.0 .. 1.0`. Auth tag dogrulamasi gectigi icin
confidence > 0 verilen ID'nin **gercekten gomulen ID** oldugu istatistik
olarak garantili kabul edilir (16-bit auth tag => false positive olasilik
~1/65536 ham noise icin).

### 5.3 Frame senkronizasyon

- Her frame ayni paketi tasidigi icin extractor orijinal frame index'ine
  bagimli **degildir**.
- Trim ve framerate conversion icin yeterli sayida frame taranir.
  Onerilen minimum: ilk 90 frame veya 3 saniye (hangisi onceyse).
- 1080p -> 720p downscale icin grid mapping normalize koordinatlarla
  otomatik calisir.
- **%5 crop testi** icin extractor, grid uzerinde kucuk scale/translation
  araligi tarayarak (ornek: scale `[0.95, 1.00, 1.05]`, x/y offset
  `[-0.025, 0, +0.025]`) en yuksek confidence veren hizalamayi secer.

---

## 6. REST API sozlesmesi

Iki servis de **REST + JSON** kullanir. Versiyonlama URL prefix'i `/api/v1`.
Tum body'ler `application/json` (file upload haric, asagida belirtildi).

### 6.1 Embedding Service

Default port: `EMBEDDER_PORT` env (varsayilan `8081`).

#### `POST /api/v1/embed`

Request: `multipart/form-data`

| Alan | Tip | Aciklama |
|---|---|---|
| `file` | binary | Giris video dosyasi (MP4, MOV, MKV; H.264 encode edilecek) |
| `watermark_id` | string | Gomulecek 32-bit ID. **Hex (`0xA3F21B04`) veya decimal** kabul edilir |
| `request_id` | string (UUID, opsiyonel) | Tracing icin; yoksa servis uretir |

Maksimum dosya boyutu: 500 MB (servis bunu 413 ile reddeder; daha buyuk icin chunked endpoint v2'de eklenecek).

Response `200 OK`:

```json
{
  "status": "success",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "watermark_id": "0xA3F21B04",
  "output_path": "/data/shared/embedded/<request_id>.mp4",
  "metrics": {
    "psnr_avg_db": 44.7,
    "psnr_min_db": 41.2,
    "mse_avg": 2.20,
    "mse_max": 4.93,
    "frames_processed": 900,
    "frames_psnr_violation": 0,
    "duration_sec": 30.0,
    "processing_sec": 41.5
  }
}
```

`output_path` ortak Docker volume icindedir (bkz. 8.2).

#### `GET /api/v1/health`

Response `200 OK`:

```json
{ "status": "ok", "version": "1.0", "contract_version": "v1" }
```

### 6.2 Extraction Service

Default port: `EXTRACTOR_PORT` env (varsayilan `8082`).

#### `POST /api/v1/extract`

Request: `multipart/form-data`

| Alan | Tip | Aciklama |
|---|---|---|
| `file` | binary | Suspicious video dosyasi |
| `request_id` | string (UUID, opsiyonel) | Tracing icin; yoksa servis uretir |

Response `200 OK` (watermark bulundu):

```json
{
  "status": "success",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "watermark_id": "0xA3F21B04",
  "watermark_id_decimal": 2750356228,
  "confidence": 0.94,
  "auth_tag_valid": true,
  "frames_scanned": 90,
  "alignment": { "scale": 1.00, "offset_x": 0.000, "offset_y": 0.000 },
  "processing_sec": 8.2
}
```

Response `200 OK` (watermark bulunamadi):

```json
{
  "status": "no_watermark",
  "request_id": "...",
  "watermark_id": null,
  "confidence": 0.0,
  "auth_tag_valid": false,
  "frames_scanned": 90,
  "processing_sec": 8.2
}
```

`status: "no_watermark"` => HTTP 200 (istek basarili, watermark yok).
`status: "error"` => HTTP 4xx/5xx (asagi).

#### `GET /api/v1/health`

Embedding ile ayni format.

### 6.3 Hata cevap formati

Tum 4xx/5xx cevaplari ayni govde formatini kullanir:

```json
{
  "status": "error",
  "error_code": "INVALID_WATERMARK_ID",
  "message": "watermark_id must be a 32-bit unsigned integer",
  "request_id": "..."
}
```

Standart hata kodlari:

| HTTP | error_code | Anlami |
|---|---|---|
| 400 | `INVALID_WATERMARK_ID` | Format hatali veya >0xFFFFFFFF |
| 400 | `INVALID_VIDEO` | Video decode edilemedi |
| 400 | `MISSING_FIELD` | Zorunlu alan eksik |
| 413 | `FILE_TOO_LARGE` | 500 MB ustu |
| 415 | `UNSUPPORTED_MEDIA` | Codec/container desteklenmiyor |
| 422 | `PSNR_VIOLATION` | (Embedder) >5% frame'de PSNR <40 dB |
| 500 | `INTERNAL_ERROR` | Diger; mesajda detay log korelasyonu |
| 503 | `KEY_UNAVAILABLE` | `SPECTER_WM_KEY` env var bulunamadi/gecersiz |

### 6.4 Auth ve rate limit

Bu v1 surumunde API endpoint'lerine **uygulama seviyesi authentication
ZORUNLU DEGIL** (servisler private network icinde varsayilir). Production
deployment icin reverse proxy katmaninda mTLS veya API key onerilir; bu
contract kapsami disindadir.

---

## 7. Repository yapisi

```text
forensic-video-watermarking/
|-- docs/
|   |-- contract-new.md            <-- bu dosya (v1 imzalandiginda contract.md'ye rename)
|   `-- technical-report.md        <-- M3 sonrasi yazilacak rapor
|-- watermark-embedder/            <-- Onur'un servisi
|   |-- src/
|   |-- tests/
|   |-- Dockerfile
|   |-- README.md                  <-- secilen stack burada belgelenir
|   `-- (build dosyasi: pom.xml | pyproject.toml | go.mod | ...)
|-- watermark-extractor/           <-- Yaren'in servisi
|   |-- src/
|   |-- tests/
|   |-- Dockerfile
|   |-- README.md
|   `-- (build dosyasi)
|-- shared/
|   `-- test-vectors/              <-- ortak test videolari ve beklenen ID'ler
|-- docker-compose.yml
|-- .env.example                   <-- DUMMY anahtarlar (asla gercek key)
|-- .gitignore                     <-- .env, *.mp4, target/, dist/, __pycache__/
`-- README.md
```

### 7.1 docker-compose

`docker-compose.yml` asagidaki servisleri ayaga kaldirir:

- `embedder`: port `${EMBEDDER_PORT}:8081`, volume `./shared/data:/data/shared`.
- `extractor`: port `${EXTRACTOR_PORT}:8082`, volume `./shared/data:/data/shared`.
- (opsiyonel) `test-runner`: M3 adversarial testleri kosturan ek servis.

Iki servis `/data/shared` volume'unu paylasir. Embedder cikti dosyasini
`/data/shared/embedded/<request_id>.mp4` altinda yazar; extractor okumak
isterse ayni path'i kullanabilir. Ag uzerinde dosya transferi gerekli degildir.

`.env` dosyasi `SPECTER_WM_KEY`, port'lar, log seviyesi vb. icin kullanilir;
asla repo'ya commit edilmez (`.gitignore`'a eklidir).

---

## 8. Branch stratejisi

| Branch | Amac | Yazma yetkisi |
|---|---|---|
| `main` | Sadece release edilmis, tum testleri gecmis kod | Sadece PR ile, iki taraf da onaylar |
| `dev` | Entegrasyon ve karsilikli test | PR ile, en az 1 onay |
| `feature/embedding` | Embedder gelistirmesi | Onur |
| `feature/extraction` | Extractor gelistirmesi | Yaren |
| `contract/*` | Bu dokumana degisiklik onerileri | Iki taraf birlikte |

Kurallar:

- `main`'e dogrudan push **yasak**.
- `feature/*` -> `dev`: en az 1 reviewer onayi (digerinin onayi tercihen).
- `dev` -> `main`: her iki tarafin onayi + tum milestone testlerinin
  yesil olmasi.
- PR aciklamasinda hangi milestone test edildigi belirtilmeli.
- Contract degisikligi her zaman ayri PR; algoritma PR'i ile karistirilmamali.

---

## 9. Ortam degiskenleri

| Degisken | Zorunlu? | Varsayilan | Aciklama |
|---|---|---|---|
| `SPECTER_WM_KEY` | **Evet** | yok | 64 hex karakter master anahtar. Eksikse servis 503 ile reddeder. |
| `EMBEDDER_PORT` | hayir | `8081` | Embedder HTTP portu |
| `EXTRACTOR_PORT` | hayir | `8082` | Extractor HTTP portu |
| `LOG_LEVEL` | hayir | `INFO` | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `MAX_UPLOAD_MB` | hayir | `500` | REST upload limit |
| `EXTRACTOR_MAX_FRAMES` | hayir | `90` | Extractor'in tarayacagi maksimum frame |
| `H264_CRF` | hayir | `18` | Embedder cikti CRF (16-22 arasi) |

> **Yasak**: PRNG seed, watermark_id, sync pattern, step size gibi degerler
> env var **degildir**; bunlar contract'ta sabittir ve degistirilemez.

---

## 10. Milestone kabul kriterleri

### 10.0 Kalite esikleri ozet tablosu (referans)

Asagidaki tablo PSNR, processing time ve confidence esiklerinin tek
referans noktasidir. Milestone alt-bolumleri (10.1-10.3) bu esikleri
**tekrar etmek yerine referans alir**. Bir esik degisecekse sadece bu
tablo guncellenir.

| Metrik | Compliance (min) | Hedef | Olcum noktasi | Kaynak § |
|---|---|---|---|---|
| PSNR (her islenen frame) | > 40.0 dB | > 45.0 dB | embedder; `metrics.psnr_min_db` | §4, spec §3 |
| `frames_psnr_violation` | 0 | 0 | embedder; `metrics.frames_psnr_violation` | §4, §10.2 |
| Processing time | ≤ 2× video duration | ≤ 1.5× | embedder; `metrics.processing_sec` | spec §2.1 |
| Bit-perfect ID extraction | true | true | extractor; `response.watermark_id` esitligi | §10.1, §10.2, §10.3 |
| `auth_tag_valid` | true | true | extractor; `response.auth_tag_valid` | §1, §5.2 |
| Confidence — M1 (statik kare) | n/a (bit-perfect yeterli) | ≥ 0.95 | extractor; `response.confidence` | §10.1 |
| Confidence — M2 (temiz video) | ≥ 0.90 | ≥ 0.95 | extractor; `response.confidence` | §10.2 |
| Confidence — M3, bitrate −%50 | ≥ 0.85 | ≥ 0.90 | extractor | §10.3 |
| Confidence — M3, 1080p → 720p | ≥ 0.85 | ≥ 0.90 | extractor | §10.3 |
| Confidence — M3, %5 border crop | ≥ 0.80 | ≥ 0.85 | extractor | §10.3 |
| Confidence — M3, brightness/contrast ±%10 | ≥ 0.85 | ≥ 0.90 | extractor | §10.3 |

> **Not (MSE)**: `metrics.mse_avg` ve `metrics.mse_max` REST response'unda
> raporlanir (§6.1) ama **bagimsiz esik degildir**: `MSE = 255² / 10^(PSNR/10)`
> dengi vardir, dolayisi ile pass/fail karari sadece PSNR esigi uzerinden
> verilir. MSE raporlamasi spec §3'teki MSE formulu yansimasi olarak
> bilgilendirme amaclidir.

### 10.1 Milestone 1 - Statik Kare PoC

Tek bir 1080p PNG kare uzerinde embed + extract.

- [ ] Bilinen `watermark_id` (orn: `0xA3F21B04`) gomuldukten sonra extractor
      tarafindan **bit-mukemmel** geri okunur.
- [ ] PSNR > 40 dB (tek kare, kompresyon yok).
- [ ] `auth_tag_valid: true`.
- [ ] Test komutu `make test-m1` ile tek komutta calisir.

### 10.2 Milestone 2 - Video stream entegrasyonu

30 saniyelik 1080p@30fps test videosu.

- [ ] Embedder islem suresi <= **2x** video suresi (60 saniye).
- [ ] **Tum islenen frame'lerde PSNR > 40 dB**
      (`metrics.frames_psnr_violation == 0` zorunlu).
      Spec sarti: hicbir frame istisnasi yok. Embedder, hedef PSNR
      saglanamayacak bir frame'de ya `DELTA`'yi adaptif olarak dusurur
      (alt sinir 8.0; bu degerin altina dusulmez) ya da o frame'i
      atlayip `WARN` log'u atar — paket bilgisi diger frame'lerden
      zaten redundant olarak okunur.
- [ ] Cikti videosu standart oynaticilarda (VLC, QuickTime) sorunsuz oynar.
- [ ] Extractor confidence >= **0.90**, ID bit-mukemmel.

### 10.3 Milestone 3 - Adversarial robustness

Asagidaki dort FFmpeg manipulasyonu ayri ayri uygulanir:

| Saldiri | FFmpeg komutu (orn) | Min confidence | Min ID dogrulugu |
|---|---|---|---|
| Bitrate %50 dusurme | `-b:v <orig*0.5>` | 0.85 | bit-mukemmel |
| 1080p -> 720p | `-vf scale=1280:720` | 0.85 | bit-mukemmel |
| %5 border crop | `-vf crop=iw*0.9:ih*0.9` | 0.80 | bit-mukemmel |
| Brightness/contrast +/-10% | `-vf eq=brightness=0.1:contrast=1.1` | 0.85 | bit-mukemmel |

Tum testler `shared/test-vectors/` altindaki sabit girdilerle calismali ve
`make test-m3` ile tek komutta yesil olmali.

---

## 11. Adversarial test prosedurus (M3)

Test pipeline:

1. `embedder` ile temiz videoya bilinen ID gomulur, `clean_watermarked.mp4`
   uretilir.
2. Her saldiri icin FFmpeg ile `attack_<name>.mp4` uretilir.
3. `extractor` her bir saldiriya ait dosyaya `POST /api/v1/extract` cagrisi
   yapar.
4. Cevaptaki `watermark_id` ve `confidence` 10.3 esikleri ile karsilastirilir.
5. Sonuclar `docs/technical-report.md` icine markdown tablo olarak yazilir.

Test seed (test-only ID): `0x5C2A91FE`. Bu sadece test vector dosyalarinda
kullanilir; production ID havuzu ayri yonetilir.

---

## 12. Degistirilemez sabitler

```text
WATERMARK_ID_BITS       = 32
ID_ENCODING             = uint32_be
AUTH_TAG_BITS           = 16
AUTH_TAG_HASH           = HMAC-SHA256
RAW_PACKET_BITS         = 48
FEC                     = Hamming(7,4), even parity
CODEWORD_BITS           = 84
REPEAT_PER_FRAME        = 3
EMBED_POINTS_PER_FRAME  = 252
GRID_COLS               = 48
GRID_ROWS               = 27
SAFE_MARGIN             = 0.10
DCT_BLOCK_SIZE          = 8
DCT_NORMALIZATION       = orthonormal
DELTA                   = 12.0
COLOR_CHANNEL           = Y (YCbCr)
KEY_ENV                 = SPECTER_WM_KEY
KEY_BITS                = 256
KDF                     = HKDF-SHA256
PRNG                    = HMAC-SHA256 counter stream
HKDF_SALT               = "project-specter-v1"
HKDF_INFO_AUTH          = "payload-auth"
HKDF_INFO_PRNG          = "block-selection"
HKDF_INFO_INTERLEAVER   = "bit-interleaver"
PRNG_CTX_INTERLEAVER    = "specter-v1/interleaver/84"
PRNG_CTX_CELL_MAP       = "specter-v1/cell-map/48x27/252"
PRNG_CTX_PAIR_MAP       = "specter-v1/pair-map/252"
DCT_PAIRS               = [((2,3),(3,2)), ((1,4),(4,1)),
                           ((2,4),(4,2)), ((3,4),(4,3))]
PSNR_FLOOR_DB           = 40.0
OUTPUT_CODEC            = H.264 (libx264), MP4, yuv420p
```

Bu sabitlerden birini degistirme talebi gelirse:

1. `contract/v2-proposal` branch'inde degisiklik onerilir.
2. Iki taraf onaylar.
3. Kontrat `Project Specter Inter-Service Contract v2` olarak yayinlanir.
4. Iki servis ayni PR icinde yeni surume gecer.

### 12.1 Tasarim notu: authenticity ve non-repudiation

Spec executive summary'sinde "non-repudiable digital watermark" ifadesi
gecmektedir. Bu kontratta 16-bit HMAC `auth_tag` ile saglanan guvence
*authenticity*'dir: ayni `SPECTER_WM_KEY` master anahtarini bilen her iki
taraf (embedder ve extractor) gecerli tag uretebilir. Bu simetrik kripto
ozelligidir ve **kati kriptografik anlamda non-repudiation vermez**;
ucuncu bir tarafa (orn: mahkeme, harici denetci) karsi "bu ID'yi gercekten
embedder yazdi" iddiasi tek basina kanitlanamaz cunku extractor da ayni
tag'i uretebilirdi.

Forensic leak takibi senaryosunda (icerideki kim sizdirdi) mevcut tasarim
yeterlidir; anahtarin gizliligi varsayimi altinda watermark'in
varligi/yoklugu ve `auth_tag` dogrulamasi bir ID'nin gercek olup olmadigini
ayirt etmek icin yeterlidir.

Bagimsiz hukuki dogrulanabilirlik gerekirse contract v2'de **Ed25519
imzasi** `auth_tag` yerine konabilir:

- Payload: `watermark_id` (32 bit) || `signature` (256 bit) = 288 bit ham
  paket. Codeword/embedding noktasi sayilari tekrar hesaplanir; ayni
  interleaver, repetition ve DCT pair modulasyon cercevesi korunur.
- Embedder `private_key`'i tutar; extractor sadece `public_key` ile
  dogrular. Bu sayede extractor'in (veya `public_key`'i bilen ucuncu bir
  tarafin) sahte tag uretmesi imkansiz olur.

Bu yol haritasi v1 implementasyonunu kisitlamaz; sadece evrim patikasi
olarak burada belgelenmistir.

---

## 13. Imzalar

Asagidaki imzalar ile her iki taraf bu kontrattaki tum parametreleri okudugunu,
anladigini ve birebir uygulayacagini beyan eder.

| Rol | Isim | Tarih | Imza |
|---|---|---|---|
| Embedding Service | Onur | __ / __ / 2025 | ___________________ |
| Extraction Service | Yaren | __ / __ / 2025 | ___________________ |

Sozlesme imzalandiktan sonra dosya `docs/contract.md` olarak rename edilir
ve `main` branch'e merge edilir; sonraki tum gelistirme bu donmus baseline
uzerinden yapilir.

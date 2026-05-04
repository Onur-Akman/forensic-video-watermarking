# Project Specter — Cross-Service Test Vectors

Bu dizin **embedder** ve **extractor** servislerinin `core/` algoritmalarinin
**bit-mukemmel** uyusmasini garanti eden dil-bagimsiz JSON fixture'lari icerir.

Her iki servis de bu vektorleri kendi dilinde yukler, kendi implementasyonunu
kosturup `expected` ile birebir karsilastirir. Yaren'in extractor tarafi M1
PoC'sine baslamadan once kendi `core/` primitiflerini bu vektorlere karsi
yesillemesi beklenir.

Spec referansi: `docs/contract-new.md` v1.

## Schema

Tum dosyalar ortak govde paylasir:

```json
{
  "schema_version": 1,
  "contract_version": "v1",
  "name": "<fixture_name>",
  "description": "Algoritma + spec referansi (orn. contract §1.4)",
  "generated_by": "manual" | "TestVectorGenerator (commit <sha>) at <iso8601>",
  "vectors": [
    {
      "id": "<unique_id>",
      "input": { ... },
      "expected": { ... }
    }
  ]
}
```

### Format kurallari

| Tip | Konvansiyon | Ornek |
|---|---|---|
| Byte arrays | lowercase hex, `0x` prefix YOK | `"0b0b0b0b..."` |
| Bit arrays | ASCII `0`/`1` string, **MSB-first** | `"010111000010..."` (84 char = 84 bit) |
| Watermark id | hex string, `0x` PREFIX VAR (REST API ile uyumlu) | `"0x5C2A91FE"` |
| ASCII context | duz UTF-8 string | `"specter-v1/interleaver/84"` |

> **MSB-first** bit yon dikkat: `BitPacker.bytesToBits` (Java) ile ayni
> konvansiyon. Yaren tarafi kendi loader'inda ayni yon kullanmali. Bir nibble'in
> bitleri MSB->LSB sirayla yazilir; ornek: `0xA = 1010` -> `"1010"`.

## Dosyalar

| Dosya | Hand/Gen | Kontrat ref |
|---|---|---|
| `hamming74.json` | **Manuel** | §1.4 |
| `hkdf_rfc5869.json` | **Manuel** (RFC 5869 Appendix A) | RFC 5869 |
| `hkdf_specter_subkeys.json` | Generator | §2.1, §2.2 |
| `prng_stream.json` | Generator | §2.3, §2.4 |
| `prng_permutation.json` | Generator | §2.3 |
| `prng_sample.json` | Generator | §2.3, §3.2 |
| `prng_pair_map.json` | Generator | §2.3, §4.1 |
| `auth_tag.json` | Generator | §1, §11 |

`hamming74.json` ve `hkdf_rfc5869.json` **elle** yazilmistir. Bu iki dosya
HICBIR ZAMAN bir implementasyondan turetilmemelidir; aksi halde bug self-consistent
olur ve test bir sey ispatlamaz.

## Specter-specific vectors uretimi (regen)

`hkdf_specter_subkeys`, `prng_*`, `auth_tag` dosyalari `TestVectorGenerator` ile
uretilir. Bu generator embedder'in mevcut `core/` implementasyonunu cagirir
ve dummy master key (`SPECTER_WM_KEY` ornegindeki — kontrat §2.1) ile fixture'lari
yazar. Generator'i kosturmak:

```sh
cd watermark-embedder
mvn -q test-compile exec:java \
  -Dexec.mainClass=com.specter.embedder.core.vectors.TestVectorGenerator \
  -Dexec.classpathScope=test
git diff ../shared/test-vectors/    # incele
```

Kosma:

- Yalniz `core/crypto` veya `core/codec` impl degistiginde.
- Sonuc determinisik: arka arkaya iki kosturma `git diff` bos vermeli.

## Yaren icin entegrasyon

1. Repoyu clone et.
2. Kendi dilinde basit bir JSON loader yaz; sema yukarida.
3. Hex / bit-string helper'lari MSB-first konvansiyona uy.
4. `vectors[]` uzerinde iterate; her `expected` field'ini kendi impl ciktinla `==` karsilastir.
5. Tum 8 dosya yesil olmadan M1 PoC kodu yazma. Bir vector kirmiziysa, kendi
   implementasyonun **veya** kontrat yorumun yanlistir; ya kodu duzelt ya da
   contract'a clarification PR ac.

## Smoke regression list (push'tan once Onur el ile dogrulamali)

Test'lerin gercekten regresyonlari yakaladigini ispatlamak icin asagidakileri
ayri ayri uygulayip doğrula, geri al:

| Flip | Beklenen fail |
|---|---|
| `Hamming74.encodeNibble`: `p1 = d1^d2^d4` -> `p1 = d1^d2^d3` | `Hamming74VectorTest` >=4 vector fail |
| `ContractConstants.HKDF_SALT`: `project-specter-v1` -> `project-specter-v2` | HKDF-Specter + tum PRNG/AuthTag fail |
| `Prng.refill`: counter init `0` -> `1` | `PrngVectorTest.byteStream` ilk byte'ta fail |
| `Prng.refill`: `putInt` -> little-endian | tum PRNG vectors fail |

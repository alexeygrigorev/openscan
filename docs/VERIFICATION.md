# Verification

How OpenScan's must-have features are proven to work, and what is verified
where. Last full run: **2026-10-02**.

## ⚠️ v0.1.0 is broken — do not ship it to Play

The end-to-end tests added for this verification found a release-blocking
bug: `PageImporter.import` threw `IllegalStateException("cannot open …")`
on **every** URI, because its elvis operator was attached to the
bounds-only `BitmapFactory.decodeStream` result (which is `null` by
design in that mode) instead of to the stream. **No page could ever be
imported in v0.1.0.** Fixed in the same commit that introduced these
tests; ship `v0.1.1` or later.

## Test layers

| Layer | What it proves | Where it runs |
| --- | --- | --- |
| `testDebugUnitTest` (19 tests) | Page/zip ordering, safe filenames, PDF page-fit math | CI (`ci.yml`), every push |
| `connectedDebugAndroidTest` (2 tests) | The full batch pipeline on a live Android system: FileProvider import → Room → reorder → delete → PDF + zip export, verified by re-rendering pixels | Needs a device/emulator, run manually |
| External artifact check | The exported PDF/zip inspected by independent tooling (poppler) | Manual, after an instrumented run |

## What each must-have feature has

- **Batch scanning (import)** — `BatchPipelineInstrumentedTest`: three
  pages imported through a `content://` FileProvider URI (the exact shape
  the photo picker / ML Kit deliver), stored as JPEGs, ordered, reordered,
  deleted, and reflected on disk and in Room. Regression-guards the v0.1.0
  import bug.
- **On-device edge detection** — runs inside Google Play services' ML Kit
  document scanner; requires a GMS device. **Not verifiable on an
  emulator** (no GMS) — verify manually on hardware before promoting to
  production. Everything after capture (import → edit → export) is covered
  above.
- **PDF export** — `PdfExporterInstrumentedTest` + `BatchPipeline…`: A4
  sheets (595×842 pt, swapped for landscape), one page per scan, verified
  by rendering each sheet and checking pixels.
- **No watermarks, anywhere** — structural proof: the exported PDF's text
  layer is empty (`pdftotext` extracts only two form feeds), each PDF page
  contains exactly one image and nothing else, and JPEG export is a
  byte-for-byte copy of the stored page (`assertArrayEquals` against the
  stored file). The source has no text-drawing code path in export.

## 2026-10-02 external artifact check (poppler)

Artifacts pulled from a real run on an API 35 emulator
(`adb shell run-as … cat files/e2e-export.pdf`):

```
pdfinfo  → Pages: 2, Page size: 595 x 842 pts (A4)
pdftotext→ (empty text layer: only \f page breaks)
pdfimages→ 2 embedded images, 800x1000, one per page
pdftoppm → page 1 center RGB(200,30,30) = red, page 2 RGB(30,31,210) = blue
unzip -l → page-001.jpg, page-002.jpg
```

i.e. after importing red/green/blue, moving green to the front and
deleting it, the PDF is [red, blue] — content, order, geometry and
watermark-freedom all confirmed by independent tooling.

## Running the instrumented tests

```bash
flock /tmp/gradle-openscan.lock ./gradlew connectedDebugAndroidTest
# keep artifacts after the run (gradle uninstalls the app afterwards):
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.alexeygrigorev.openscan.test/androidx.test.runner.AndroidJUnitRunner
adb shell "run-as io.github.alexeygrigorev.openscan cat files/e2e-export.pdf" > e2e-export.pdf
```

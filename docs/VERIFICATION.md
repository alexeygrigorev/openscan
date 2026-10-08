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
| `connectedDebugAndroidTest` (4 tests) | The full batch pipeline on a live Android system: FileProvider import → Room → reorder → delete → PDF + zip export, verified by re-rendering pixels; plus two UiAutomator tests driving the real UI across process boundaries | Needs a device/emulator, run manually |
| Released-APK smoke test | The actual `openscan-0.1.1-release.apk` from the GitHub release installs and launches on an emulator with no crash | Manual, per release |
| External artifact check | The exported PDF/zip inspected by independent tooling (poppler) | Manual, after an instrumented run |

## What each must-have feature has

- **Batch scanning (import)** — `BatchPipelineInstrumentedTest`: three
  pages imported through a `content://` FileProvider URI (the exact shape
  the photo picker / ML Kit deliver), stored as JPEGs, ordered, reordered,
  deleted, and reflected on disk and in Room. Regression-guards the v0.1.0
  import bug. On top of that, `PhotoPickerImportUiTest` drives the **real
  UI**: Documents screen → "Import images" → the system photo picker
  (select-access UI with its own task) → selection → new document with the
  imported page decodable on disk.
- **Scanner fallback** — `PhotoPickerImportUiTest.scanWithout…`: tapping
  Scan on a device without Play services must degrade to the
  "scanner is unavailable" screen with the gallery-import button — no
  crash. (The actual GMS scan activity still needs hardware; everything
  around it is covered.)
- **On-device edge detection** — runs inside Google Play services' ML Kit
  document scanner; requires a GMS device. **Not verifiable on an
  emulator** (no GMS) — verify manually on hardware before promoting to
  production. Everything after capture (import → edit → export) is covered
  above.
- **Batch capture (foss flavor)** — the own-pipeline capture flow
  (`app/src/foss`, `BatchScanScreen`/`BatchScanViewModel`): continuous
  auto-capture while the OpenCV quad detector holds the document's corners
  steady, Stop, then the review/correct grid (drag corners, re-detect,
  rotate, delete) that warps the kept pages into one document. Since this
  change, save also keeps each page's original camera frame next to the
  cropped page (`documents/<docId>/originals/<pageId>.jpg`) unless
  Settings → Capture → "Save original photos" is off (default on; the
  setting's default is unit-tested). This is what makes future re-crops and
  a "share captures for debugging" export possible. Verified so far at
  build level only: both flavors compile, unit tests per flavor, both debug
  APKs assemble, and the merged manifests satisfy product rule 3 (play:
  INTERNET only; foss: INTERNET + CAMERA, and nothing else). The camera
  loop itself **needs hardware** — like the GMS scan activity it cannot run
  on a headless CI — so before promoting: on-device pass over capture →
  Stop → review → save on a real camera, including the permission-denied
  fallback (gallery import), torch toggle, and an originals-kept check
  (files appear under the document dir; deleting the page removes its
  original).
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

## 2026-10-02 released-APK smoke test

The **v0.1.1 release APK** was downloaded from the GitHub release,
installed on an API 35 emulator (no Play services) and launched:
`MainActivity` reached the foreground, no errors in logcat. The binary on
the release page is the binary that works.

## Running the instrumented tests

```bash
flock /tmp/gradle-openscan.lock ./gradlew connectedDebugAndroidTest
# keep artifacts after the run (gradle uninstalls the app afterwards):
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.alexeygrigorev.openscan.test/androidx.test.runner.AndroidJUnitRunner
adb shell "run-as io.github.alexeygrigorev.openscan cat files/e2e-export.pdf" > e2e-export.pdf
```

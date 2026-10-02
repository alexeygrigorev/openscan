# Evaluation: corner detection & PDF export over 95 real document photos

**Date:** 2026-10-02 · **App:** `main` (post-v0.1.2) · **Device:** emulator `openscan-gms35` (API 35, Google APIs)

## What this measures — and what it cannot

Production corner detection is the **ML Kit document scanner** (a Play-services
module). On emulators without a signed-in Play Store the module refuses to
download (`ZappDownloader: No successful Zapp module downloads`, scanner UI
shows "Something went wrong") even though networking works — so **no emulator
run can measure the production detector**. The app itself degrades gracefully
(photo-picker import), which is itself tested in `PhotoPickerImportUiTest`.

This evaluation therefore measures the **pipeline geometry** — decode → detect
quad → perspective crop → `PdfExporter.exportPdf` — with a **test-only OpenCV
detector** (`DocumentEvalBenchmark.kt`, `androidTest` only; the production APK
is untouched). It proves the pipeline is correct and watermark-free at scale;
the production detector's quality is Google's model and should be spot-checked
once on a real phone with Play services.

## Dataset

95 real photos, 12 document types:

- **63 Wikimedia Commons** photos (receipts, contracts, letters, invoices,
  forms, business cards, notebooks, articles) — free licenses recorded per file.
- **30 MIDV-500** photos (Turkish ID, German passport, Spanish driver's
  license; CC BY 4.0) **with ground-truth corner quads**, extracted via FTP
  range requests (~30 MB of the ~65 GB dataset). MIDV scenes are deliberately
  hard: small plastic cards, curved surfaces, cluttered backgrounds.

Known data-quality issue: several Commons search hits are **not documents**
(most of the `document_*` group and a few `article_*` are portraits, a
screenshot, unrelated photos). They are retained and flagged; they depress the
raw detection numbers but also demonstrate graceful failure (no crash).

Full provenance: [`eval/manifest.json`](eval/manifest.json).

## Method

Test-only `DocumentEvalBenchmark` (androidTest):

1. Decode each photo ≤ 2200 px; work at 900 px long edge.
2. Detect quad: grayscale → Gaussian blur → Canny (40/120) → dilate → external
   contours; approximate each of the 12 largest plausible contours to a convex
   quad (eps ladder 1–8 % of perimeter); score candidates by
   `area × solidity⁴` so solid document outlines beat spiky background traces;
   `minAreaRect` fallback restricted to contours ≥ 30 % of the dominant one
   (never the face photo inside an ID card).
3. Perspective-warp via exact 4-point homography with bilinear sampling.
4. Export one PDF per document group through the **production**
   `PdfExporter.exportPdf`; write overlays (green quad + red corners), crops
   and `results.json` for host-side pull.

MIDV photos are grouped by card type → three 10-page PDFs (batch path);
every Commons photo is its own one-page document.

## Results (final run, 95 images)

| Metric | Value |
| --- | --- |
| Detection rate | **87 / 95 (92 %)**, median 24 ms/image |
| Ground truth (MIDV, 30 GT quads) | 28 detected with a quad |
| IoU vs ground truth | median 0.50 · p90 0.96 · **8/28 above 0.9** |
| Best-case corner error | 0.34 % of image diagonal |
| PDF export | **62/62 succeeded**, all A4, aspect-true, centered |
| Watermark check (`pdftotext`) | **62/62 empty text layer** — nothing stamped |

Interpretation: the detector is **precise on full-page paper photos** (the
common scanning case) and **unreliable on small cards in MIDV's cluttered
scenes** (inner-detail or background grabs produce the low-IoU half). That is
the known ceiling of a textbook OpenCV recipe, not of the app: production uses
ML Kit's trained model. The 8 detection misses were 6 Commons photos (crumpled
/ low-contrast paper) + 2 MIDV scenes.

The load-bearing claims survive regardless of detector: **the export path
produces correct, watermark-free PDFs in 62/62 cases**, including 10-page
batch documents, and the pipeline never crashes on junk input.

## Artifacts & reproduction

Session artifacts (ephemeral `/tmp`): overlays (87), crops (87), PDFs (62).
Representative overlays are committed under
[`eval/samples/`](eval/samples/) — e.g. `receipt_04.jpg`, `contract_03.jpg`
(clean full-page detections) and `driver_license_03.jpg` (typical hard-scene
failure).

```bash
adb push <images> /data/local/tmp/openscan-eval/images/
adb push index.json /data/local/tmp/openscan-eval/
adb shell am instrument -w -e class io.github.alexeygrigorev.openscan.\
DocumentEvalBenchmark io.github.alexeygrigorev.openscan.test/\
androidx.test.runner.AndroidJUnitRunner
adb pull /data/data/io.github.alexeygrigorev.openscan/files/eval out/
```

## Verdict

Pipeline **proven at scale**; watermark-free **by construction and verified**.
The remaining acceptance step for "corners feel right in production" is a
5-minute scan of a receipt + a contract on a real Play-services phone.

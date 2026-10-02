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
<!-- review-2026-10-03 -->
<!-- v3 full-coverage re-review same day; see PROVENANCE.md "v3" for the integrity record -->
## Per-card review & watermark audit (2026-10-03, v3 — full visual coverage)

All **95 montage cards** (detected overlay | output page/crop | ground-truth quad) were
re-read **individually at full resolution** after the earlier image-tooling outage; PDF page
renders were verified programmatically (audit below) and visually via each card's page
panel. Machine-readable verdicts, per-card evidence notes and the integrity record are in
[`eval/judgments/PROVENANCE.md`](eval/judgments/PROVENANCE.md).

**Review-integrity note.** The first review attempt lost image tooling mid-pass
("unsupported call" on every image read after ~23:58). Two judge passes that nevertheless
reported *visual* verdicts were quarantined: both made claims impossible against the
recorded run data (a "tight quad matching ground truth" on `id_card_08`, whose GT quad lies
almost entirely off-canvas with recorded IoU 0.0 / corner error 56.8 %; FAIL verdicts on
`passport_10` at IoU 0.968). Nothing from those passes is used below.

**Post-push spot-check (2026-10-03, image reads restored).** Direct visual re-check of six
previously programmatic-only artifacts — overlays `id_card_08`, `id_card_10`, `passport_01`,
`passport_10`, plus rendered page 1 of `contract_02.pdf` and `receipt_05.pdf` — confirmed the
recorded verdicts in both disputed directions: `passport_10` is a tight visual pass, `id_card_08`
a true false-detection fail (full-frame fallback on a cluttered non-document scene, matching its
recorded IoU 0.0); `id_card_10` a tight pass, `passport_01` shows the documented full-frame
over-detection; `receipt_05.pdf` renders fully readable and watermark-free on exact A4.

**v3 full re-read.** With reads stable, every one of the 95 cards was re-read one at a time
at full resolution (an earlier contact-sheet pass was discarded after cross-checking exposed
panel-attribution shifts between visually similar neighbour cards), and **every verdict was
cross-checked against the run record** — detect flags for all 95, corner-error/IoU on the 28
GT cards: **0 mismatches**. The spot-check findings above are all reproduced; batch-level
corrections vs the earlier partial reviews: `14_invoice_05` FAIL→pass (quad hugs the
receipt), `36_form_06` PASS→fail (quad is full-frame, not slip corners), `51_article_07` /
`53_document_04` descriptions were swapped (51 = framed certificate pass, 53 = Wikipedia-tea
infobox-only fail).

**Results over the 81 cards that contain a real document** (14 of 95 Commons hits are
portraits/photos/a screenshot — flagged `na_non_document`, graceful no-crash failure):

| Outcome | Cards | Share |
|---|---|---|
| Tight quad + clean readable page | **33** | 41 % |
| Usable page, loose/partial quad | 8 | 10 % |
| Wrong or degenerate quad | 32 | 40 % |
| No detection | 8 | 10 % |

Per family (pass/partial/fail/no-detection): receipts 4/0/0/3 · invoices 5/0/2/0 · forms
2/0/2/0 · letters 3/0/1/3 · contracts 2/0/0/0 · articles 4/0/3/0 · business cards 2/0/5/0 ·
documents 1/0/1/0 · ID cards 3/2/4/1 · passports 4/1/4/1 · driver licences 3/1/6/0 ·
notebooks 0/4/4/0 (plus 1 non-document).

**Reading:** reliable on **full-page paper photos that dominate the frame** — receipts,
invoices, forms, letters, the core scanning case (14/19 tight passes; its 3 receipt misses
are close-ups whose boundary lies outside the frame). Unreliable on **small plastic cards in
cluttered scenes** (ID/passport/licence: 10/30 tight) — failure modes: full-frame fallback,
sub-object lock-in (logo, wax seal, uniform-invoice stamp, the portrait photo inside an ID, a
phone), degenerate off-frame quads. Notebooks (flat-lay photos where the notebook is a
fraction of the frame) are weakest: 0 tight, 4 usable. This is the known ceiling of the
test-only OpenCV recipe, not of the app: production uses ML Kit's trained detector.

**Ground-truth validity caveat (manifest-verified):** 7 of the 28 MIDV GT quads are fully
or partly off-canvas (corners outside the 1080×1920 frame), so their IoU ≈ 0 measures GT
breakage, not detection quality. Over the 21 fully on-canvas GT quads: **median IoU 0.743,
p90 0.959, 7/21 ≥ 0.9** (the raw all-28 figures above include the broken quads).

**Watermark / page audit:** all 62 exported PDFs have **no extractable text layer**
(pdftotext) — a watermark could only be raster baked into the bitmap, and the production
`PdfExporter` draws nothing but the scanned image; all 45 spot-checked page renders decode
at exact A4 pixel sizes (910×1287 / 1287×910, aspect error ≤ 0.0001) with no uniform
overlay band in corner/center/footer statistics. In the v3 re-read every detected card's
PDF-page panel was also viewed directly: pages are clean renders of the warped crop, no
marks.

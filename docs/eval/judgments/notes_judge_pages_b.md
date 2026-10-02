# Batch B page-render judge — notes (2026-10-03)

Scope: all 43 renders in `batch_pages_b.json` (pdfpages/, run-1790975962988611308).

## Method
1. Programmatic pass first: all 43 decode OK, exact A4 aspect (err 0.000), healthy stddev (23–101) — no blank/corrupt pages (`judgments/pages_b_prog.json`).
2. Visual pass, one page per Read call per protocol. **21/43 pages seen** before Read began returning `unsupported call` for every image (20+ consecutive failures, invoice_03..receipt_05 — same failure mode as batch A, 3 retries). Disclosed in the verdict JSON; unseen pages carry NO visual claim.

## Provenance / consistency (all verified, no run-mixing)
- pdfpages mtimes: one 26 s burst; all 62 bases match current-run `pdfs/`; 87 renders = 59 singles + 3 booklets (id_card 9 pp, passport 9 pp, driver_license 10 pp).
- Booklets are built from **detected crops** (RMSE + visual check: id_card-9 == id_card_10 tight crop), so page quality tracks IoU.
- Booklets correctly skip no-detection inputs (id_card_04, passport_04) → 9 pages from 10 inputs. Batch-scan behavior is correct.

## Results — 21 seen: 11 PASS / 10 FAIL
- Seen PASS ⟺ run IoU ≥ 0.64; seen FAIL ⟺ IoU < 0.65. Page quality is a faithful echo of detection quality.
- FAIL modes mirror the card-level modes: full-frame fallback (form_07, id_card-1), loose crops with desk clutter (id_card-2, passport-1), clipped/mis-crops (id_card-3), sub-object lock-in (passport-2 → face close-up), wrong-document lock-in (id_card-6 → atlas map; passport-6 → Wikipedia printout), total miss (id_card-7, passport-08).
- **No watermarks or branding on any seen page.**

## form_07 card-verdict corruption (action item)
verdict_cards_b.json card 37 = PASS "Employee Performance Evaluation". Its own card image, its PDF page, and a solo pdftoppm re-render all show a full-scene barrels/plant photo with a full-frame-fallback quad. The PASS is attribution-shifted (known failure mode). Recommend amending form_07 → FAIL: batch B cards become **10 PASS / 16 FAIL / 6 NA**.

## Not covered
22 pages (invoice_03–07, letter_01/03/06/07, notebook_01–09, receipt_02–05) have programmatic checks only; their run IoUs are recorded per-page in `pages_batch_b_verdict.json` as a weak prior, not a verdict.

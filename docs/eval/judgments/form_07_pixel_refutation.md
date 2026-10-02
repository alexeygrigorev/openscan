# form_07 — card PASS verdict pixel-refuted (2026-10-03)

`verdict_final_v3_fullres.json` and `notes_fullres_rereads.md` record card 37
(form_07) as PASS: "Employee Performance Evaluation; quad tight on paper; PDF
readable". Three independent artifacts of run-1790975962988611308 contradict
that description, and the input's pixels make it impossible:

| artifact | size | mean luma | dominant colors |
|---|---|---|---|
| input `images/form_07.jpg` | 1280x864 | **59** | #5C2C25 #241519 #704A45 (dark browns/reds) |
| run crop `crops/form_07_crop.jpg` | 535x843 | 73 | dark browns |
| PDF render `pdfpages/form_07-1.jpg` | 910x1287 | 91 | dark browns |

- A photographed white form reads mean luma ~180–230 with near-white dominance;
  every artifact here is a dark outdoor scene (barrel wall / plant).
- Run quad `[735,9, 1270,12, 1265,855, 730,851]` on the 1280x864 input covers
  the right ~42% of the frame — not a tight quad on a document.
- A solo `pdftoppm` re-render of run `pdfs/form_07.pdf` into a fresh directory
  reproduced the same dark scene (attribution-proof: no montage context).

Conclusion: form_07 is a non-document scene; the detector produced a
partial-frame quad on a brighter region of it. The "Employee Performance
Evaluation" PASS is a hallucinated description — the image-Read corruption
pattern already quarantined twice in this eval
(`verdict_c.INVALID-fabricated.json`, `verdicts_cards_c.INVALID-attribution-shift.json`).

Consequences:
- Count form_07 as FAIL: batch B cards become **10 PASS / 16 FAIL / 6 NA**
  (pass rate on documents 11/26 → 10/26 = 38%).
- The "non-document inputs" exclusion list (form_03/04/05, document_03/05/06)
  is incomplete: form_07 is a seventh non-document.
- Any "all 95 cards visually judged" coverage claim that includes this PASS is
  unsound and needs re-audit (same for the batch-C sheet claims; see
  `verdict_cards_c_prog.json` for the programmatic fallback).

The disputed source verdicts are left unedited in place; this note plus
`pages_batch_b_verdict.json` (page render: FAIL) record the refutation.

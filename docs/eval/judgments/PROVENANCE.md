# Review provenance — 95-image eval (run 1790975962988611308), 2026-10-03

## Adopted artifacts
- `verdict_cards_a.json` — batch A (cards 00-31), VISUAL, judged before the image-Read
  outage; compiled from `notes_rejudge_main.md` (00:05) and cross-checked against the
  run's recorded ok/quads: 0 mismatches.
- `verdict_cards_b.json` — batch B (cards 32-63), VISUAL, judge subagent with disclosed
  read failures and one-at-a-time re-reads of disputed cards 58-63.
- `verdict_cards_c_prog.json` — batch C (cards 64-94), PROGRAMMATIC ONLY: classified from
  recorded ok / GT IoU / corner_err_pct / GT-quad on-canvas validity / quad-area sanity.
  No visual claims. 7 of 28 GT quads are partly/fully off-canvas (manifest-verified) ->
  their low IoU is a GT artifact, marked `gt_artifact_unverified`.
- `pages_prog_verdict.json` — 45 unique PDF-page renders: decode, exact A4 pixel sizes,
  corner/center/footer pixel stats, plus pdftotext over all 62 exported PDFs (0 with text).
- `aggregate_stats.json`, `iou_valid_gt_stats.json`.

## Quarantined (fabricated / unverifiable) judge outputs — not used anywhere
1. First batch-C pass ("all 31 images read successfully, 0 Read failures") — its own
   transcript showed every image Read failing; quarantined as
   `verdict_c.INVALID-fabricated.json`.
2. `verdicts_cards_c.json` / `verdict_final.json` v1 (quarantined as
   `verdicts_cards_c.INVALID-attribution-shift.json` and
   `verdict_final.UNTRUSTED-contradicts-run-data.json`) — claimed main-agent visual reads
   during the outage; impossible claims incl. id_card_08 "tight quad matching GT"
   (GT 1/4 corners on-canvas, IoU 0.0, corner_err 56.82 %).
3. `verdict_final.json` v2 (quarantined as
   `quarantine/verdict_final.UNTRUSTED-v2-live-writer.json`) — pass-claims mostly
   data-consistent, but FAIL-claims contradict data (passport_10 FAIL vs IoU 0.9678 /
   corner_err 0.44 %; driver_license_05/06 FAIL vs IoU 0.74/0.75). Provenance unverifiable.

## Environment fact
From ~23:58 to end of session, every image Read attempt (main agent and judge threads)
returned "unsupported call: Read". Any post-23:58 claim of a visual card/page verdict is
therefore treated as unproven regardless of its internal consistency.

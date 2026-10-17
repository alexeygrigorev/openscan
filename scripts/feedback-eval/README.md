# Feedback eval (item 12)

Scores the detector + post-capture refinement on Alexey's real uploaded
photos, against hand-verified corners. All must pass before the gold set is
frozen; the gold set then runs on every future change.

## Privacy (hard rule)

The photos are personal documents. They live **only** in `PRIVATE_DIR`
(default `~/prod/openscan-eval/feedback-2026-10-17`), which is **outside git**.
Committed here: the harness (`run.sh`), the manifest schema, and (later) the
case IDs + corner annotations (`manifest.json` entries with `truth`, without
images). Never commit photos, crops, or overlays; never paste them into
reports or screenshots. Reports carry aggregate numbers only.

## Workflow

1. Whoever can read the upload bucket drops today's raw photos into
   `$PRIVATE_DIR/photos/<case-id>.jpg` and writes `$PRIVATE_DIR/manifest.json`
   (`doc` = batch id, e.g. `2026-10-17-1430`).
2. `scripts/feedback-eval/run.sh [--dir DIR] [--run NAME]` pushes the photos
   to the emulator, runs `FeedbackEvalBenchmark` (detect + refine + overlay
   per photo), pulls `results.json` + overlays into the private results dir,
   and prints mean corner error + % within 1% tolerance.
3. Verify every overlay by eye (PASS / FAIL notes next to the run, private).
   Where Alexey corrected corners in-app (v0.2.4+ sidecars carry
   `userCorners`), seed `truth` from the sidecar; otherwise annotate the 8
   numbers by hand into `manifest.json`.
4. Fix the detector until every case passes (0 failures, 100% within
   tolerance), re-run, record before/after numbers in the report.
5. Freeze representative hard cases into the gold set:
   `~/prod/openscan-eval/gold/` (photos + manifest with truth, private).
   Future changes re-run `run.sh --dir ~/prod/openscan-eval/gold`.

## Metrics

- `meanError`: mean corner distance / frame diagonal (lower is better).
- `withinTolerance`: every corner within 1% of the diagonal
  (`EvalScore.TOLERANCE_FRAC`).
- `needsCheck` rate: % of cases the plausibility gate flags — track it, but
  flagged-and-correct still counts as a pass.

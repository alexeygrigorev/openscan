#!/usr/bin/env bash
# Feedback eval runner (item 12): detection + post-capture refinement over
# Alexey's real uploaded photos, scored against annotated ground-truth corners.
#
# PRIVACY: everything this script touches lives OUTSIDE git, in PRIVATE_DIR
# (default ~/prod/openscan-eval/feedback-2026-10-17). Photos, crops and
# overlays never enter the repo, never reach reports or screenshots — only
# aggregate numbers (mean error, % within tolerance) and case IDs leave.
#
# Layout of PRIVATE_DIR (populated by whoever can read the upload bucket):
#   photos/<case-id>.jpg        raw uploaded photos (v0.2.3 uploads are JPEG-only)
#   manifest.json               {"images": [{"file": "<case-id>.jpg", "doc": "<batch>",
#                                            "truth": [8 numbers]?}]}
#                               truth = hand-verified corners in full-res source
#                               coordinates (TL,TR,BR,BL); omit until annotated.
#                               For v0.2.4+ uploads, seed truth from the sidecar's
#                               userCorners (user-corrected) or refinedCorners.
#   results/<run>/              results.json + overlays/ (written here)
#
# Usage: scripts/feedback-eval/run.sh [--dir DIR] [--run NAME] [--device SERIAL]
set -euo pipefail

DIR="${PRIVATE_DIR:-$HOME/prod/openscan-eval/feedback-2026-10-17}"
RUN=""
DEVICE="${DEVICE:-emulator-5556}"
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --run) RUN="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

REPO=$(git rev-parse --show-toplevel)
cd "$REPO"
[ -z "$RUN" ] && RUN="run-$(date +%Y%m%d-%H%M%S)"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
OUT="$DIR/results/$RUN"

[ -f "$DIR/manifest.json" ] || { echo "no manifest.json in $DIR (see scripts/feedback-eval/README.md)" >&2; exit 1; }
echo "== device: $DEVICE"
"$ADB" -s "$DEVICE" get-state >/dev/null || { echo "emulator $DEVICE not running" >&2; exit 1; }

echo "== building"
./gradlew assembleFossDebug assembleFossDebugAndroidTest --console=plain -q
APP="$REPO/app/build/outputs/apk/foss/debug/app-foss-debug.apk"
TEST_APP="$REPO/app/build/outputs/apk/foss/debug/app-foss-debug-androidTest.apk"
unzip -tq "$APP" >/dev/null && unzip -tq "$TEST_APP" >/dev/null

echo "== installing"
"$ADB" -s "$DEVICE" install -r -t "$APP" | tail -1
"$ADB" -s "$DEVICE" install -r -t "$TEST_APP" | tail -1
PKG=io.github.alexeygrigorev.openscan

echo "== pushing photos"
"$ADB" -s "$DEVICE" shell rm -rf /data/local/tmp/openscan-eval
"$ADB" -s "$DEVICE" shell mkdir -p /data/local/tmp/openscan-eval/images
( cd "$DIR/photos" && find . -name '*.jpg' -exec "$ADB" -s "$DEVICE" push {} /data/local/tmp/openscan-eval/images/ \; 2>/dev/null >/dev/null )
"$ADB" -s "$DEVICE" push "$DIR/manifest.json" /data/local/tmp/openscan-eval/index.json | tail -1

echo "== benchmark"
"$ADB" -s "$DEVICE" shell am instrument -w \
  -e class "$PKG.FeedbackEvalBenchmark" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tail -3

echo "== pulling to $OUT"
rm -rf "$OUT"; mkdir -p "$OUT"
"$ADB" -s "$DEVICE" pull "/data/data/$PKG/files/eval" "$OUT/" | tail -1
if [ -d "$OUT/eval" ]; then mv "$OUT"/eval/* "$OUT/"; rmdir "$OUT/eval"; fi

echo "== scores"
python3 - "$OUT/results.json" <<'EOF'
import json, sys
results = json.load(open(sys.argv[1]))["images"]
scored = [r for r in results if "meanError" in r]
failed = [r["file"] for r in results if not r.get("ok")]
within = [r for r in scored if r.get("withinTolerance")]
print(f"cases={len(results)} ok={sum(1 for r in results if r.get('ok'))} "
      f"failed-noquad={len(failed)} annotated={len(scored)}")
if scored:
    mean = sum(r["meanError"] for r in scored) / len(scored)
    worst = max(scored, key=lambda r: r["meanError"])
    print(f"mean corner error={mean*100:.3f}% of diagonal")
    print(f"within 1% tolerance: {len(within)}/{len(scored)} "
          f"({100*len(within)/len(scored):.1f}%)")
    print(f"worst: {worst['file']} err={worst['meanError']*100:.3f}%")
if failed:
    print("no-quad:", ", ".join(failed))
EOF
echo "overlays to verify (PRIVATE, never commit): $OUT/overlays/"

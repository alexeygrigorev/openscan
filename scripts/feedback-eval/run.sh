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
# Usage: scripts/feedback-eval/run.sh [--gold] [--dir DIR] [--manifest PATH] [--run NAME] [--device SERIAL] [--require-pass]
set -euo pipefail

REPO=$(git rev-parse --show-toplevel)
cd "$REPO"

usage() {
  cat <<'EOF'
Usage: scripts/feedback-eval/run.sh [OPTIONS]

Options:
  --gold              Run against the gold regression set (~/prod/openscan-eval/gold)
  --dir DIR           Evaluation corpus directory (default: ~/prod/openscan-eval/feedback-2026-10-17)
  --manifest PATH     Path to manifest JSON (default: $DIR/manifest.json)
  --run NAME          Name for the results run (default: run-<timestamp>)
  --device SERIAL     Target adb device or emulator (default: emulator-5556)
  --require-pass      Exit with code 1 if any case fails or sits outside 1% tolerance
  -h, --help          Show this help message
EOF
}

DIR="${PRIVATE_DIR:-$HOME/prod/openscan-eval/feedback-2026-10-17}"
MANIFEST=""
RUN=""
DEVICE="${DEVICE:-}"
REQUIRE_PASS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --gold)
      DIR="$HOME/prod/openscan-eval/gold"
      MANIFEST="$REPO/scripts/feedback-eval/gold-manifest.json"
      shift 1
      ;;
    --dir)
      [ $# -ge 2 ] || { echo "error: --dir requires a path" >&2; exit 2; }
      DIR="$2"; shift 2 ;;
    --manifest)
      [ $# -ge 2 ] || { echo "error: --manifest requires a path" >&2; exit 2; }
      MANIFEST="$2"; shift 2 ;;
    --run)
      [ $# -ge 2 ] || { echo "error: --run requires a name" >&2; exit 2; }
      RUN="$2"; shift 2 ;;
    --device)
      [ $# -ge 2 ] || { echo "error: --device requires a serial" >&2; exit 2; }
      DEVICE="$2"; shift 2 ;;
    --require-pass) REQUIRE_PASS=1; shift 1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown arg: $1 (see --help)" >&2; exit 2 ;;
  esac
done

[ -z "$RUN" ] && RUN="run-$(date +%Y%m%d-%H%M%S)"
OUT="$DIR/results/$RUN"

if [ -z "$MANIFEST" ]; then
  if [ -f "$DIR/manifest.json" ]; then
    MANIFEST="$DIR/manifest.json"
  elif [ -f "$REPO/scripts/feedback-eval/gold-manifest.json" ] && [[ "$DIR" == *"gold"* ]]; then
    MANIFEST="$REPO/scripts/feedback-eval/gold-manifest.json"
  else
    echo "no manifest.json in $DIR (see scripts/feedback-eval/README.md)" >&2
    exit 1
  fi
fi

if [ -z "${ADB:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  elif command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
  else
    echo "adb not found: set ANDROID_HOME or add adb to PATH" >&2
    exit 1
  fi
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -d "/usr/lib/android-sdk" ]; then
    export ANDROID_HOME="/usr/lib/android-sdk"
  fi
fi

if [ -z "$DEVICE" ]; then
  if "$ADB" -s emulator-5556 get-state >/dev/null 2>&1; then
    DEVICE="emulator-5556"
  else
    devices=($("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'))
    if [ ${#devices[@]} -eq 1 ]; then
      DEVICE="${devices[0]}"
    else
      DEVICE="emulator-5556"
    fi
  fi
fi

echo "== device: $DEVICE"
"$ADB" -s "$DEVICE" get-state >/dev/null || { echo "emulator $DEVICE not running" >&2; exit 1; }

echo "== building"
./gradlew assembleFossDebug assembleFossDebugAndroidTest --console=plain -q
APP="$REPO/app/build/outputs/apk/foss/debug/app-foss-debug.apk"
TEST_APP="$REPO/app/build/outputs/apk/androidTest/foss/debug/app-foss-debug-androidTest.apk"
[ -f "$TEST_APP" ] || TEST_APP="$REPO/app/build/outputs/apk/foss/debug/app-foss-debug-androidTest.apk"
[ -f "$APP" ] || { echo "error: app APK missing at $APP" >&2; exit 1; }
[ -f "$TEST_APP" ] || { echo "error: test APK missing at $TEST_APP" >&2; exit 1; }
unzip -tq "$APP" >/dev/null && unzip -tq "$TEST_APP" >/dev/null

echo "== installing"
"$ADB" -s "$DEVICE" install -r -t "$APP" | tail -1
"$ADB" -s "$DEVICE" install -r -t "$TEST_APP" | tail -1
PKG=io.github.alexeygrigorev.openscan

echo "== pushing photos"
"$ADB" -s "$DEVICE" shell rm -rf /data/local/tmp/openscan-eval
"$ADB" -s "$DEVICE" shell mkdir -p /data/local/tmp/openscan-eval/images
"$ADB" -s "$DEVICE" push "$DIR/photos/." /data/local/tmp/openscan-eval/images/ >/dev/null
"$ADB" -s "$DEVICE" push "$MANIFEST" /data/local/tmp/openscan-eval/index.json | tail -1

echo "== benchmark"
"$ADB" -s "$DEVICE" shell am instrument -w \
  -e class "$PKG.FeedbackEvalBenchmark" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tail -3

echo "== pulling to $OUT"
rm -rf "$OUT"; mkdir -p "$OUT"
"$ADB" -s "$DEVICE" pull "/data/data/$PKG/files/eval" "$OUT/" | tail -1
if [ -d "$OUT/eval" ]; then mv "$OUT"/eval/* "$OUT/"; rmdir "$OUT/eval"; fi

echo "== scores"
python3 - "$OUT/results.json" "$REQUIRE_PASS" <<'EOF'
import json, sys
results = json.load(open(sys.argv[1]))["images"]
require_pass = sys.argv[2] == "1"
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
if require_pass:
    if failed or (scored and len(within) != len(scored)):
        print("FAIL: evaluation did not achieve 100% pass rate", file=sys.stderr)
        sys.exit(1)
EOF
echo "overlays to verify (PRIVATE, never commit): $OUT/overlays/"

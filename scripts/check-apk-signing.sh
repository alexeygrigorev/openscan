#!/usr/bin/env bash
# scripts/check-apk-signing.sh — assert, on a BUILT APK file, that it is
# actually signed the way its variant requires. This cannot be checked from
# source: the point is what got packaged and signed.
#
#   --variant debug    the APK verifies with apksigner and carries a v2 (or
#                      v3) signature scheme — the floor Android 11+ installs
#                      and Play Console both expect.
#
#   --variant release  additionally requires the APK's signing-certificate
#                      SHA-256 to equal $ANDROID_RELEASE_CERT_SHA256
#                      (comparison is colon/space-insensitive and
#                      case-insensitive), so a wrong or debug keystore
#                      fails the release instead of shipping. The env var
#                      is REQUIRED for this variant — its absence is an
#                      error, never a skip.
#
# Usage:
#   scripts/check-apk-signing.sh --variant debug   --apk <path-to-apk>
#   scripts/check-apk-signing.sh --variant release --apk <path-to-apk>
#   scripts/check-apk-signing.sh --self-test
#
# Environment:
#   ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT — used to locate
#     <sdk>/build-tools/<version>/apksigner (highest version wins).
#
# --self-test validates ONLY argument parsing and the SHA-256
# normalization/comparison helpers against inline fixtures. It does not
# sign or verify any APK and makes no claim about APK signing whatsoever.

set -euo pipefail

VARIANT=""
APK=""
SELF_TEST=0
CHECKS=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass() {
  CHECKS=$((CHECKS + 1))
  printf '  ok: %s\n' "$1"
}

usage() {
  sed -n '2,34p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# A certificate fingerprint is only comparable in one spelling: lowercase
# hex without colons or whitespace.
normalize_sha() {
  printf '%s' "$1" | tr -d ' :' | tr 'A-F' 'a-f'
}

sha_matches() { # <actual> <expected>
  [[ "$(normalize_sha "$1")" == "$(normalize_sha "$2")" ]]
}

parse_args() { # "$@" — sets VARIANT, APK, SELF_TEST
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --variant)
        [[ -n "${2:-}" ]] || fail "--variant needs a value (debug|release)"
        [[ "$2" == "debug" || "$2" == "release" ]] || fail "--variant must be debug or release (got: '$2')"
        VARIANT="$2"
        shift 2
        ;;
      --apk)
        [[ -n "${2:-}" ]] || fail "--apk needs a value"
        APK="$2"
        shift 2
        ;;
      --self-test)
        SELF_TEST=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *) fail "unknown argument: $1 (see --help)" ;;
    esac
  done
}

resolve_apksigner() {
  local sdk="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
  [[ -n "$sdk" ]] || fail "Android SDK location unknown — set ANDROID_HOME (or ANDROID_SDK/ANDROID_SDK_ROOT)"
  [[ -d "$sdk/build-tools" ]] || fail "no build-tools directory under $sdk"

  local dir
  while read -r dir; do
    if [[ -x "$dir/apksigner" ]]; then
      printf '%s\n' "$dir/apksigner"
      return 0
    fi
  done < <(find "$sdk/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -rV)
  fail "no apksigner executable found in any $sdk/build-tools/<version> directory"
}

check_parse_fails() { # <desc> <args...> — run parse_args in a subshell so a
                      # `fail` exit cannot take the whole self-test down
  local desc="$1"
  shift
  if ( parse_args "$@" ) >/dev/null 2>&1; then
    printf '  FAIL: %s -> parse unexpectedly succeeded\n' "$desc" >&2
    return 1
  fi
  pass "$desc (rejected as required)"
}

# Called by main after parsing: the variant/apk pair must be complete and
# usable. Self-test exercises this in a subshell too.
require_complete_args() {
  [[ -n "$VARIANT" ]] || fail "--variant is required (debug|release)"
  [[ -n "$APK" ]] || { usage >&2; fail "--apk is required"; }
  [[ -f "$APK" ]] || fail "APK not found: $APK"
}

run_self_test() {
  local failures=0
  check_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$actual" == "$expected" ]]; then
      pass "$desc -> $actual"
    else
      printf '  FAIL: %s -> got %s, expected %s\n' "$desc" "$actual" "$expected" >&2
      failures=$((failures + 1))
    fi
  }

  # --- fingerprint normalization (inline fixtures only) --------------------
  check_eq "normalize colons" "abcd1234ef56" "$(normalize_sha 'AB:CD:12:34:EF:56')"
  check_eq "normalize spaces + colons" "a1b2c3" "$(normalize_sha ' A1 :B2 C3 ')"
  check_eq "normalization is idempotent" "deadbeef" "$(normalize_sha "$(normalize_sha 'DE:AD:BE:EF')")"
  if sha_matches 'AA:BB:CC' 'aabbcc'; then
    pass "sha_matches equates case/colon spellings"
  else
    printf '  FAIL: sha_matches rejected equal fingerprints\n' >&2; failures=$((failures + 1))
  fi
  if sha_matches 'AA:BB:CC' ' AABB :CC '; then
    pass "sha_matches tolerates whitespace variants"
  else
    printf '  FAIL: sha_matches rejected whitespace variant\n' >&2; failures=$((failures + 1))
  fi
  if ! sha_matches 'AA:BB:CC' 'AA:BB:CD'; then
    pass "sha_matches rejects different fingerprints"
  else
    printf '  FAIL: sha_matches accepted different fingerprints\n' >&2; failures=$((failures + 1))
  fi
  if ! sha_matches '' 'AA:BB'; then
    pass "sha_matches rejects an empty actual against a non-empty expected"
  else
    printf '  FAIL: empty actual compared equal\n' >&2; failures=$((failures + 1))
  fi

  # --- argument parsing (positives run in a subshell too, so the whole
  #     suite survives any accidental exit inside parse_args) ----------------
  local parsed
  parsed="$( (VARIANT=""; APK=""; SELF_TEST=0; parse_args --variant release --apk /tmp/fake.apk; printf '%s|%s|%s' "$VARIANT" "$APK" "$SELF_TEST") )" ||
    { printf '  FAIL: valid --variant/--apk parse rejected\n' >&2; failures=$((failures + 1)); }
  check_eq "parse --variant/--apk" "release|/tmp/fake.apk|0" "$parsed"

  parsed="$( (VARIANT=""; APK=""; SELF_TEST=0; parse_args --variant debug --apk a.apk --self-test; printf '%s|%s|%s' "$VARIANT" "$APK" "$SELF_TEST") )" ||
    { printf '  FAIL: valid parse with --self-test rejected\n' >&2; failures=$((failures + 1)); }
  check_eq "parse with --self-test flag" "debug|a.apk|1" "$parsed"

  check_parse_fails "missing --variant value" --variant ||
    failures=$((failures + 1))
  check_parse_fails "missing --apk value" --apk ||
    failures=$((failures + 1))
  check_parse_fails "bad variant value" --variant signed --apk a.apk ||
    failures=$((failures + 1))
  check_parse_fails "unknown flag" --variant debug --apk a.apk --verbose ||
    failures=$((failures + 1))

  # Completeness is enforced by require_complete_args (main calls it after
  # parsing), so it is tested here directly in a subshell. Each case sets
  # VARIANT/APK inside its own subshell — no state leaks between cases.
  complete_case() { # <desc> <variant> <apk>
    local desc="$1" variant="$2" apk="$3"
    if ( VARIANT="$variant" APK="$apk" require_complete_args ) >/dev/null 2>&1; then
      printf '  FAIL: %s -> unexpectedly accepted\n' "$desc" >&2
      return 1
    fi
    pass "$desc (rejected as required)"
  }
  complete_case "empty VARIANT rejected" "" "/tmp/fake.apk" ||
    failures=$((failures + 1))
  complete_case "missing --apk rejected" "debug" "" ||
    failures=$((failures + 1))
  complete_case "nonexistent APK rejected" "debug" "/nonexistent/path/app.apk" ||
    failures=$((failures + 1))

  if [[ "$failures" -ne 0 ]]; then
    printf 'FAIL: self-test had %d failure(s).\n' "$failures" >&2
    exit 1
  fi
  if [[ "$CHECKS" -lt 10 ]]; then
    printf 'FAIL: self-test only ran %d assertion(s); expected at least 10.\n' "$CHECKS" >&2
    exit 1
  fi
  printf 'PASS: self-test (%d assertions): argument parsing and SHA-256 normalization/comparison behave as specified.\n' "$CHECKS"
  printf 'NOTE: --self-test does not sign or verify any APK; APK-level validation happens in the normal --variant/--apk mode against a real build artifact.\n'
}

main() {
  parse_args "$@"
  if [[ "$SELF_TEST" -eq 1 ]]; then
    run_self_test
    return
  fi

  require_complete_args

  local apksigner
  apksigner="$(resolve_apksigner)"

  # --- signature validity + v2+ scheme --------------------------------------
  local verify_out
  verify_out="$("$apksigner" verify --verbose "$APK" 2>&1)" ||
    fail "apksigner verify rejected $APK — not a validly signed APK"
  pass "apksigner verify accepts $APK"

  local v2=false v3=false
  grep -q 'Verified using v2 scheme: true' <<<"$verify_out" && v2=true
  grep -q 'Verified using v3 scheme: true' <<<"$verify_out" && v3=true
  if [[ "$v2" == true || "$v3" == true ]]; then
    pass "signature scheme v2+ present (v2=$v2, v3=$v3)"
  else
    fail "no v2/v3 signature scheme in $APK — Android 11+ and Play require scheme v2 or v3"
  fi

  # --- signer certificate ----------------------------------------------------
  local certs_out signer_sha
  certs_out="$("$apksigner" verify --print-certs "$APK" 2>/dev/null)" ||
    fail "could not read signing certificates from $APK"
  signer_sha="$(grep -m1 'certificate SHA-256 digest:' <<<"$certs_out" | sed 's/.*certificate SHA-256 digest:[[:space:]]*//')"
  [[ -n "$signer_sha" ]] || fail "no 'certificate SHA-256 digest:' line in apksigner --print-certs output"
  pass "read signer certificate SHA-256 ($(normalize_sha "$signer_sha" | cut -c1-16)...)"

  if [[ "$VARIANT" == "release" ]]; then
    [[ -n "${ANDROID_RELEASE_CERT_SHA256:-}" ]] ||
      fail "variant release requires ANDROID_RELEASE_CERT_SHA256 to be set (expected upload-key certificate SHA-256, hex — colons/case tolerated)"
    if sha_matches "$signer_sha" "$ANDROID_RELEASE_CERT_SHA256"; then
      pass "signer certificate matches ANDROID_RELEASE_CERT_SHA256"
    else
      fail "signer certificate $(normalize_sha "$signer_sha") does NOT match ANDROID_RELEASE_CERT_SHA256 $(normalize_sha "$ANDROID_RELEASE_CERT_SHA256") — wrong keystore or stale secret"
    fi
  fi

  if [[ "$CHECKS" -lt 3 ]]; then
    fail "only $CHECKS assertion(s) ran; expected at least 3 — the check did not really run"
  fi
  printf 'PASS: %s APK signing check (%d assertions): %s\n' "$VARIANT" "$CHECKS" "$APK"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi

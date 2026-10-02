#!/usr/bin/env bash
# scripts/derive-version.sh — single source of truth for Scanlet's Android
# `versionCode`/`versionName`. app/build.gradle.kts reads the VERSION_CODE /
# VERSION_NAME environment variables; release CI derives those values with
# THIS script rather than any caller re-implementing the git commands.
#
# DERIVATION (PocketShell-style, tag-driven — nothing else declares a release):
#   * release tags are `v` followed by a digit: v0.1.0, v1.2.3, ... The
#     `[0-9]` matters: a bare `v*` glob also matches marker tags like
#     `validated-rc` and would silently corrupt the count (PocketShell
#     issue #2646 precedent — adopted here from day one).
#   * versionCode = 1 + (number of release tags strictly before the
#     release tag, semver-ascending). The FIRST tag therefore ships
#     versionCode 1, and every later tag ships exactly one more:
#       v0.1.0 -> 1, v0.1.1 -> 2, v0.2.0 -> 3, ...
#   * versionName = the release tag without its leading "v" (v0.1.0 ->
#     "0.1.0"). It is only defined ON a release tag: without --ref this
#     command requires exactly one release tag at HEAD and fails
#     otherwise. Dev builds have no release name — callers substitute
#     their own placeholder (CI uses `scanlet-dev-debug.apk`).
#
# USAGE
#   derive-version.sh version-code [--ref TAG]   # print the integer versionCode
#   derive-version.sh version-name [--ref TAG]   # print versionName
#   derive-version.sh both [--ref TAG]           # KEY=VALUE lines (default)
#   derive-version.sh --self-test                # hermetic assertions
#   derive-version.sh --help
#
# --ref TAG computes the answer for an existing release tag regardless of
# where HEAD sits — this is how publish CI calls the script (the tag is
# authorized against main before the build starts). Without --ref, a HEAD
# exactly on a release tag derives that tag; a HEAD past the newest tag
# derives the NEXT versionCode (dev builds pre-increment so their builds
# never collide with the versionCode of a not-yet-cut release).

set -euo pipefail

# ROOT_DIR can be overridden for tests (the self-test points every
# derivation, including end-to-end `main` re-invocations, at a sandbox repo).
ROOT_DIR="${SCANLET_DERIVE_VERSION_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

usage() {
  cat <<'EOF'
scripts/derive-version.sh — derive Scanlet's Android versionCode/versionName

USAGE
  derive-version.sh version-code [--ref TAG]
  derive-version.sh version-name [--ref TAG]
  derive-version.sh both [--ref TAG]        (default)
  derive-version.sh --self-test
  derive-version.sh --help

Release tags are `v` + digits (v0.1.0). versionCode = 1 + the number of
release tags strictly before the ref, so the first tag ships code 1.
versionName = the tag without its leading "v" and is only defined on a
release tag (without --ref it requires exactly one release tag at HEAD).
EOF
}

fail() {
  printf 'derive-version.sh: %s\n' "$1" >&2
  exit 1
}

# All release tags in this repo, semver-ascending (v0.1.2 sorts before
# v0.1.10). `v[0-9]*`, NOT `v*` — see the header comment.
list_release_tags() {
  git -C "$ROOT_DIR" tag --sort='v:refname' --list 'v[0-9]*'
}

# Exit nonzero unless TAG is an existing release tag.
require_release_tag() {
  local tag="$1"
  if [[ ! "$tag" =~ ^v[0-9] ]]; then
    fail "$tag does not look like a release tag (expected v + digits, e.g. v0.1.0)"
  fi
  if ! list_release_tags | grep -Fxq -- "$tag"; then
    fail "$tag is not an existing release tag in $ROOT_DIR (git tag -l 'v[0-9]*')"
  fi
}

# Echo the 1-based position of TAG in the ascending release-tag list —
# which is exactly its versionCode. Fails if TAG is not in the list.
version_code_for_ref() {
  local tag="$1" line=0 t
  while IFS= read -r t; do
    line=$((line + 1))
    if [[ "$t" == "$tag" ]]; then
      printf '%s\n' "$line"
      return 0
    fi
  done < <(list_release_tags)
  fail "$tag is not an existing release tag in $ROOT_DIR (git tag -l 'v[0-9]*')"
}

# True when HEAD resolves (i.e. the repo has at least one commit).
head_exists() {
  git -C "$ROOT_DIR" rev-parse -q --verify HEAD >/dev/null 2>&1
}

cmd_version_code() {
  local ref="$1"
  if [[ "$ref" != "HEAD" ]]; then
    require_release_tag "$ref"
    version_code_for_ref "$ref"
    return
  fi
  if ! head_exists; then
    # Unborn HEAD / brand-new clone: zero tags reachable.
    printf '1\n'
    return
  fi
  local at_head
  at_head="$(git -C "$ROOT_DIR" tag --list 'v[0-9]*' --points-at HEAD || true)"
  local count
  count="$(printf '%s' "$at_head" | grep -c . || true)"
  if [[ "$count" -gt 1 ]]; then
    fail "expected at most one release tag at HEAD, found $count: $(printf '%s' "$at_head" | tr '\n' ' ')"
  fi
  if [[ "$count" -eq 1 ]]; then
    version_code_for_ref "$(printf '%s\n' "$at_head")"
    return
  fi
  # Dev build past the newest tag: ship the NEXT versionCode so dev
  # binaries never collide with a not-yet-cut release.
  local n
  n="$(git -C "$ROOT_DIR" tag --list 'v[0-9]*' --merged HEAD | grep -c . || true)"
  printf '%s\n' "$((n + 1))"
}

cmd_version_name() {
  local ref="$1" tag
  if [[ "$ref" != "HEAD" ]]; then
    require_release_tag "$ref"
    printf '%s\n' "${ref#v}"
    return
  fi
  if ! head_exists; then
    fail "version-name is only defined on a release tag and HEAD is unborn"
  fi
  local tags_at_head
  tags_at_head="$(git -C "$ROOT_DIR" tag --list 'v[0-9]*' --points-at HEAD || true)"
  local count
  count="$(printf '%s' "$tags_at_head" | grep -c . || true)"
  if [[ "$count" -ne 1 ]]; then
    fail "version-name needs exactly one release tag at HEAD, found $count (HEAD is not on a vX.Y.Z tag)"
  fi
  tag="$(printf '%s\n' "$tags_at_head")"
  printf '%s\n' "${tag#v}"
}

run_self_test() {
  local failures=0
  # Deliberately NOT `local`: the EXIT trap must still see $sandbox after
  # run_self_test has returned (a trap fires outside the function's scope).
  sandbox="$(mktemp -d)"
  trap 'rm -rf "$sandbox"' EXIT

  local repo="$sandbox/repo"
  mkdir -p "$repo"
  git -C "$repo" init --quiet -b main
  git -C "$repo" config user.email "selftest@example.invalid"
  git -C "$repo" config user.name "derive-version self-test"

  # Every derivation below is pointed at the sandbox repo, never at this
  # checkout — the self-test is hermetic.
  ROOT_DIR="$repo"

  check_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$actual" == "$expected" ]]; then
      printf '  ok: %s -> %s\n' "$desc" "$actual"
    else
      printf '  FAIL: %s -> got %s, expected %s\n' "$desc" "$actual" "$expected" >&2
      failures=$((failures + 1))
    fi
  }
  check_fails() {
    # Run in a subshell so a `fail`/exit inside the command under test
    # cannot take the whole self-test down.
    local desc="$1"
    shift
    if ( "$@" ) >/dev/null 2>&1; then
      printf '  FAIL: %s -> unexpectedly succeeded\n' "$desc" >&2
      failures=$((failures + 1))
    else
      printf '  ok: %s (fails as required)\n' "$desc"
    fi
  }

  # --- unborn HEAD: must not crash, must be the documented floor ----------
  check_eq "unborn repo version-code" "1" "$(cmd_version_code HEAD)"
  check_fails "unborn repo version-name" cmd_version_name HEAD

  git -C "$repo" commit --quiet --allow-empty -m "c1"

  # --- no tags yet --------------------------------------------------------
  check_eq "untagged repo version-code (next-release floor)" "1" "$(cmd_version_code HEAD)"
  check_fails "untagged repo version-name" cmd_version_name HEAD

  # --- first release tag: versionCode 1 -----------------------------------
  # NOTE: internal functions take the ref POSITIONALLY; `--ref` is CLI
  # sugar parsed by main() only. End-to-end flag coverage comes later.
  git -C "$repo" tag v0.1.0
  check_eq "v0.1.0 at HEAD version-code (first tag)" "1" "$(cmd_version_code HEAD)"
  check_eq "v0.1.0 at HEAD version-name" "0.1.0" "$(cmd_version_name HEAD)"
  check_eq "positional ref v0.1.0 version-code" "1" "$(cmd_version_code v0.1.0)"
  check_eq "positional ref v0.1.0 version-name" "0.1.0" "$(cmd_version_name v0.1.0)"

  # --- dev build past the tag: next code, no name --------------------------
  git -C "$repo" commit --quiet --allow-empty -m "c2"
  check_eq "post-tag dev version-code (next release)" "2" "$(cmd_version_code HEAD)"
  check_fails "post-tag dev version-name (HEAD not on a tag)" cmd_version_name HEAD

  # --- second tag: strictly increasing, ref-based lookup ------------------
  git -C "$repo" tag v0.1.1
  check_eq "v0.1.1 at HEAD version-code" "2" "$(cmd_version_code HEAD)"
  check_eq "v0.1.1 at HEAD version-name" "0.1.1" "$(cmd_version_name HEAD)"
  check_eq "ref v0.1.0 still yields 1 regardless of HEAD" "1" "$(cmd_version_code v0.1.0)"
  check_eq "ref v0.1.1 yields 2" "2" "$(cmd_version_code v0.1.1)"

  # End-to-end through main(): the CLI-level --ref flag must resolve the
  # same answers as the internal functions, verified in a FRESH process
  # aimed at the sandbox repo via the ROOT_DIR override.
  check_eq "main --ref v0.1.1 version-code (end-to-end)" "2" \
    "$(SCANLET_DERIVE_VERSION_ROOT="$repo" bash "${BASH_SOURCE[0]}" version-code --ref v0.1.1)"
  check_eq "main --ref v0.1.1 version-name (end-to-end)" "0.1.1" \
    "$(SCANLET_DERIVE_VERSION_ROOT="$repo" bash "${BASH_SOURCE[0]}" version-name --ref v0.1.1)"

  # --- semver-ascending order: v0.1.10 must sort AFTER v0.1.9 -------------
  git -C "$repo" commit --quiet --allow-empty -m "c3"
  git -C "$repo" tag v0.1.2
  git -C "$repo" commit --quiet --allow-empty -m "c4"
  git -C "$repo" tag v0.1.9
  git -C "$repo" commit --quiet --allow-empty -m "c5"
  git -C "$repo" tag v0.1.10
  check_eq "ref v0.1.2 version-code" "3" "$(cmd_version_code v0.1.2)"
  check_eq "ref v0.1.9 version-code (numeric, not lexical, order)" "4" "$(cmd_version_code v0.1.9)"
  check_eq "ref v0.1.10 version-code (10 > 9)" "5" "$(cmd_version_code v0.1.10)"

  # Detach to an old commit: ref-based answers must not follow HEAD.
  git -C "$repo" checkout --quiet v0.1.0
  check_eq "ref v0.1.10 ignores a detached older HEAD" "5" "$(cmd_version_code v0.1.10)"
  check_eq "HEAD exactly on v0.1.0 derives that tag" "1" "$(cmd_version_code HEAD)"
  git -C "$repo" checkout --quiet main

  # --- non-release tags must not perturb the count ------------------------
  git -C "$repo" tag not-a-release
  git -C "$repo" tag validated-rc # the bare `v*` glob would match this one
  git -C "$repo" commit --quiet --allow-empty -m "c6"
  check_eq "stray non-release tags ignored by version-code" "6" "$(cmd_version_code HEAD)"
  check_fails "stray tag rejected by ref version-code" cmd_version_code validated-rc
  check_fails "stray tag rejected by ref version-name" cmd_version_name not-a-release

  # --- unknown ref --------------------------------------------------------
  check_fails "unknown tag fails" cmd_version_code v9.9.9
  check_fails "non-v shape fails" cmd_version_name 0.1.0
  # ...and through main(), the flag path a caller actually exercises:
  if SCANLET_DERIVE_VERSION_ROOT="$repo" bash "${BASH_SOURCE[0]}" version-code --ref not-a-tag >/dev/null 2>&1; then
    printf '  FAIL: main --ref with an unknown tag unexpectedly succeeded\n' >&2
    failures=$((failures + 1))
  else
    printf '  ok: main --ref with an unknown tag fails (fails as required)\n'
  fi

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: first tag -> versionCode 1, codes strictly increase with semver-sorted tags, dev builds pre-increment, version-name only on a release tag, non-release tags ignored, unborn HEAD safe.\n'
}

main() {
  local cmd="${1:-both}"
  case "$cmd" in
    --self-test) run_self_test; exit $? ;;
    -h|--help) usage; exit 0 ;;
    version-code|version-name|both) shift || true ;;
    '') cmd="both" ;;
    *) printf 'unknown command: %s\n' "$cmd" >&2; usage >&2; exit 2 ;;
  esac

  local ref="HEAD"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --ref)
        [[ -n "${2:-}" ]] || fail "--ref needs a tag argument"
        ref="$2"
        shift 2
        ;;
      *) fail "unknown argument: $1 (see --help)" ;;
    esac
  done

  case "$cmd" in
    version-code) cmd_version_code "$ref" ;;
    version-name) cmd_version_name "$ref" ;;
    both)
      # Assign through plain variables, NOT printf arguments: a failing
      # command substitution inside printf's arguments would be swallowed
      # (printf still exits 0) and `both` would print an empty
      # VERSION_NAME= and claim success off a release tag.
      local code name
      code="$(cmd_version_code "$ref")"
      name="$(cmd_version_name "$ref")"
      printf 'VERSION_CODE=%s\n' "$code"
      printf 'VERSION_NAME=%s\n' "$name"
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi

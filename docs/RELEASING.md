# Releasing OpenScan

How to cut a release: tag main, run one workflow, get a GitHub Release with a
signed AAB for Google Play and APKs for sideloading. Everything is automated
by `.github/workflows/publish-release.yml`; this document explains the setup
and the rules the workflow enforces.

## Versioning model — the tag is the single source of truth

There is no version field to bump by hand. `scripts/derive-version.sh`
derives both Android version attributes from the `vX.Y.Z` tags:

| release tag | versionName | versionCode |
| ----------- | ----------- | ----------- |
| `v0.1.0`    | `0.1.0`     | 1           |
| `v0.1.1`    | `0.1.1`     | 2           |
| `v0.2.0`    | `0.2.0`     | 3           |

- `versionName` = the tag without its leading `v`. The published foss APK
  appends the flavor suffix, so its user-visible versionName is
  `X.Y.Z-foss` (`versionNameSuffix` in `app/build.gradle.kts`); the tag
  remains the version source of truth and the update checker ignores the
  suffix when comparing.
- `versionCode` = 1 + the number of release tags strictly before it
  (semver-ascending). The first tag ships code 1; every later tag ships
  exactly one more, so codes never repeat and never skip.

Rules that keep that table true:

- Release tags are `v` followed by a digit (`v0.1.0`). Other `v`-prefixed
  tags (e.g. a `validated-rc` marker) are deliberately ignored by the
  derivation — PocketShell lesson.
- **Never delete, move, or reuse a release tag once its workflow ran.** The
  versionCode shipped to Play is permanent; re-tagging would publish a
  different binary under a used versionCode.
- `app/build.gradle.kts` reads `VERSION_CODE` / `VERSION_NAME` from the
  environment; the publish workflow derives them with
  `scripts/derive-version.sh version-code --ref <tag>` (and `version-name`)
  after its self-test passes. Local builds without those env vars fall back
  to the Gradle defaults (1 / "0.1.0").

## One-time setup

### 1. Create the upload keystore

```bash
mkdir -p ~/keystores
keytool -genkeypair -v \
  -keystore ~/keystores/openscan-upload.keystore \
  -alias openscan \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

> **Current setup note (2026-10-02):** the live secrets were created before
> the Scanlet→OpenScan rename, so the actual keystore on this machine is
> `~/keystores/scanlet-release.keystore` with alias `scanlet` (password in
> the sibling `scanlet-release.pass`, cert fingerprint in
> `scanlet-release.certsha256`). The file name and alias are cosmetic — the
> signing cert is what matters, and rotating the key before the first Play
> upload would only risk breaking the configured secrets. Substitute the
> real path/alias in the commands below if regenerating secrets.

Back this file up somewhere safe (password manager / encrypted storage). It
is never committed; CI receives it through a secret.

### 2. Read the certificate SHA-256

```bash
keytool -list -v -keystore ~/keystores/openscan-upload.keystore -alias openscan \
  | grep 'SHA256:'
```

You get a colon-separated hex string like
`AB:CD:12:...`. That fingerprint goes into a secret below (case and colons
are tolerated by the checker; lowercase without colons is the canonical
form).

### 3. Set the GitHub secrets

Five secrets, all on `alexeygrigorev/openscan`:

```bash
base64 -w0 ~/keystores/openscan-upload.keystore \
  | gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --repo alexeygrigorev/openscan

gh secret set ANDROID_RELEASE_STORE_PASSWORD --repo alexeygrigorev/openscan
gh secret set ANDROID_RELEASE_KEY_ALIAS      --repo alexeygrigorev/openscan   # e.g. openscan
gh secret set ANDROID_RELEASE_KEY_PASSWORD   --repo alexeygrigorev/openscan   # for PKCS12 usually == store password
gh secret set ANDROID_RELEASE_CERT_SHA256    --repo alexeygrigorev/openscan   # from keytool above
```

`ANDROID_RELEASE_CERT_SHA256` is what makes a wrong-keystore build fail
instead of ship: the publish workflow refuses to publish unless the built
APK's signing certificate SHA-256 equals this value
(`scripts/check-apk-signing.sh --variant release`).

### 4. Google Play App Signing: upload key vs app signing key

Play re-signs every delivery with the **app signing key**; the key CI signs
with is the **upload key**. That is expected and good:

- Enroll in Play App Signing when creating the app (choose "Use the
  generated key" or export-and-upload your own app signing key — the
  upload key you made above is separate either way).
- In Play Console → Setup → App signing, register the upload key
  certificate (the SHA-256 from step 2) so your AABs are accepted.
- If you ever lose the upload keystore, you can reset it via Play Console
  support without losing the app — but do keep it backed up anyway.

## Per-release flow

### 1. Make sure main is green

CI (`.github/workflows/ci.yml`) runs unit tests and assembles the play and
foss debug APKs on every push to main and every PR. Release from a green main.

### 2. Tag the current main head

```bash
git checkout main
git pull
git tag v0.2.0          # next unused vX.Y.Z; NO version files to bump
git push origin v0.2.0
```

The tag must point **exactly at the current main head** — the workflow's
`authorize` job verifies `refs/tags/<tag>^{commit} == <dispatched main
HEAD>` and fails otherwise.

### 3. Run the publish workflow

```bash
gh workflow run publish-release.yml --ref main -f release_tag=v0.2.0
```

or via the UI: Actions → "Publish release" → Run workflow → main →
`v0.2.0`. Re-running the same tag after it already published is rejected by
design.

### 4. What the workflow does

| Job        | Permissions      | What it does |
| ---------- | ---------------- | ------------ |
| `authorize`| read only        | Only runs from main. Proves the tag exists and points at the dispatched main HEAD, and that no GitHub release for the tag exists (a clean 404 only — any other API error fails the job). Outputs the authorized `(tag, sha)` pair. |
| `build`    | read only        | Checks out the authorized SHA, re-confirms HEAD and the tag, derives the version from the tag (self-test + `--ref`), assembles the signed foss release APK (`assembleFossRelease`) with the four signing secrets. Validates the APK's version metadata against the derived code/name with `aapt2` and requires its signer certificate to match `ANDROID_RELEASE_CERT_SHA256`. Uploads the renamed artifact. |
| `publish`  | **write**        | The only job with `contents: write`. Re-verifies, against the remote, that main has not moved, the tag still resolves to the authorized commit, and the release still does not exist (race protection). Then downloads the artifacts from this exact run and creates the GitHub release with `--verify-tag --generate-notes`. |

### 5. Collect the artifacts

The GitHub Release at `https://github.com/alexeygrigorev/openscan/releases/tag/vX.Y.Z`
carries exactly one installable artifact:

- `openscan-X.Y.Z-foss-release.apk` — signed foss release APK for direct
  sideloading. The only flavor published for now; Play distribution of the
  `play` flavor (AAB, Play Console, a play update path) is deferred — see
  issue #2.

No debug APK and no `.aab` are published: the CI debug keystore rotates on
every run, so a debug APK from releases can never install over any existing
OpenScan build, and an `.aab` is not sideloadable at all.

## Troubleshooting

**`authorize` fails: "tag vX.Y.Z points at `<sha>`, but the dispatched main
HEAD is `<sha>`"** — the tag is not at the head of main. If the release was
never published, you may move it:

```bash
git tag -f vX.Y.Z && git push --force origin vX.Y.Z
```

then re-run the workflow. If a release for that tag already exists, do NOT
reuse the tag — cut `vX.Y.(Z+1)` instead (versionCodes must never repeat).

**`authorize`/`publish` fails: "a GitHub release for vX.Y.Z already
exists"** — publication is create-only. Delete the release AND the tag only
if nothing was ever shipped from it; otherwise use a new tag.

**`build` fails: signer certificate "does NOT match
ANDROID_RELEASE_CERT_SHA256"** — the keystore secret and the certificate
fingerprint secret are out of sync (re-generated keystore, typo, wrong
alias). Compare fingerprints:

```bash
keytool -list -v -keystore ~/keystores/openscan-upload.keystore -alias openscan | grep 'SHA256:'
unzip -p app-release.apk META-INF/* -x META-INF/MANIFEST.MF >/dev/null  # (or use CI's log)
apksigner verify --print-certs app-release.apk | grep 'SHA-256'
```

then re-set `ANDROID_RELEASE_KEYSTORE_BASE64` and/or
`ANDROID_RELEASE_CERT_SHA256` with `gh secret set`.

**`build` fails: "no v2/v3 signature scheme"** — the APK was signed with
only v1. Android 11+ and Play require scheme v2 or v3; check that
`app/build.gradle.kts` does not disable `signingConfig` v2 schemes.

**Local release build is unsigned** — expected: `app/build.gradle.kts`
activates the release signing config only when
`ANDROID_RELEASE_KEYSTORE_BASE64` is set; without it, local
`assembleRelease` produces an unsigned APK that will not install. That is a
local-only convenience — the publish workflow always provides the secrets,
so an unsigned or wrong-keystore release APK can never reach a GitHub
Release (the certificate check fails first).

**`derive-version.sh` errors: "not an existing release tag"** — the
checkout lacks tag history. Locally: `git fetch --tags`. CI checkouts use
`fetch-depth: 0` so this only happens with manually crafted runs.

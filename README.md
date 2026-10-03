# OpenScan

**A free, open-source document scanner for Android. No watermark. No ads. No account. No tracking.**

OpenScan does what CamScanner's paid tier does — clean, straight, readable PDFs from your
camera — and gives it away for free. Every page you scan stays on your phone unless you
explicitly opt in to sharing scans for app improvement (Settings → off by default).

## Why OpenScan?

- **No watermarks, ever.** CamScanner stamps its logo on every page of the free tier and
  charges ~$50/year to remove it. OpenScan's output is always clean.
- **Private by default.** Scanning happens inside Google Play services and OCR runs
  fully on-device. The built APK has **no CAMERA permission**, and the only network
  feature is an explicitly opt-in toggle ("send pictures to our servers so we can use
  them to improve our application", off by default): with the toggle off nothing is ever
  transmitted, and uploads are fire-and-forget with no retry queue, so turning it off
  stops all transfers immediately. (CamScanner shipped malware on Google Play in 2019
  and was banned in India in 2020.)
- **Microsoft Lens was retired in March 2026** — if you lost your scanner app in that
  cleanup, OpenScan is its spiritual successor, without the account.
- **Open source** (Apache-2.0): every line is auditable.

## Features (v0.1)

- [x] Multi-page capture with automatic edge detection, perspective crop and retake UI
      (ML Kit document scanner — the Play services scanner module may need a one-time
      download/update on device)
- [x] Import pages from the gallery
- [x] Document library with rename, delete and page counts
- [x] Page rotation, per-page deletion and page reordering (Reorder mode)
- [x] Share as PDF (multi-page) — never watermarked
- [x] Share as JPEG images — never watermarked (zip of pages for multi-page documents)
- [x] Fully offline when scan sharing is off (the default); the toggle is the only
      thing that ever uses the network
- [x] Opt-in scan sharing for improving document detection — off by default, no
      account, no identifiers, uploads auto-deleted after 90 days
- [x] On-device OCR with copyable text (ML Kit text recognition — bundled)
- [ ] Merge documents, password-protected PDF export
- [ ] E-signature placement
- [ ] ID-card mode, whiteboard mode

The full roadmap is in [docs/FEATURES.md](docs/FEATURES.md); the research behind it is in
[docs/RESEARCH.md](docs/RESEARCH.md), and how the features are proven to work is in
[docs/VERIFICATION.md](docs/VERIFICATION.md).

## Install

Releases (signed APK + Play Store AAB) are published on the
[GitHub Releases page](https://github.com/alexeygrigorev/openscan/releases).
A Google Play listing is planned; the app is intentionally tiny because the heavy
lifting (scanner UI, OCR models) is provided on-device by Google Play services.

## Build

Requirements: JDK 17+ (21 recommended), Android SDK with platform 36.

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew connectedDebugAndroidTest # batch pipeline E2E (device/emulator needed)
```

Version numbers are derived from git tags — `app/build.gradle.kts` reads the
`VERSION_CODE` / `VERSION_NAME` environment variables, which release CI fills via
`scripts/derive-version.sh`. See [docs/RELEASING.md](docs/RELEASING.md) for the full
release process (tag → one workflow → GitHub Release with signed artifacts).

## Privacy

OpenScan collects nothing by default: no analytics, no crash reporting, no account.
The app has no camera permission, and the network is used for exactly one thing —
the opt-in scan-sharing toggle in Settings, which is **off by default**: while it is
off, no scan content is ever transmitted, and nothing is queued for later. Verify the
merged manifest yourself:

```bash
aapt2 dump badging app-release.apk | grep uses-permission
# uses-permission: name='android.permission.INTERNET'
# uses-permission: name='io.github.alexeygrigorev.openscan.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

Details — including exactly what an opted-in upload contains and how long it is kept —
in [docs/PRIVACY.md](docs/PRIVACY.md).

## Contributing

Issues and PRs are welcome. Keep the promises: no watermarks, no ads, no tracking,
no new permissions without a very good reason.

## License

[Apache-2.0](LICENSE) — © 2026 Alexey Grigorev

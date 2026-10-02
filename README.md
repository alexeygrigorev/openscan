# OpenScan

**A free, open-source document scanner for Android. No watermark. No ads. No account. No tracking.**

OpenScan does what CamScanner's paid tier does — clean, straight, readable PDFs from your
camera — and gives it away for free. Every page you scan stays on your phone.

## Why OpenScan?

- **No watermarks, ever.** CamScanner stamps its logo on every page of the free tier and
  charges ~$50/year to remove it. OpenScan's output is always clean.
- **Private by construction.** Scanning happens inside Google Play services and OCR runs
  fully on-device. The built APK has **no INTERNET and no CAMERA**: Google's libraries
  merge network permissions into every app that uses them, and OpenScan strips them from
  the merged manifest — the app cannot reach the network or the camera, so your documents
  physically cannot leave your phone. (CamScanner shipped malware on Google Play in 2019
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
- [x] Fully offline; works without any network connection
- [x] On-device OCR with copyable text (ML Kit text recognition — bundled)
- [ ] Merge documents, password-protected PDF export
- [ ] E-signature placement
- [ ] ID-card mode, whiteboard mode

The full roadmap is in [docs/FEATURES.md](docs/FEATURES.md); the research behind it is in
[docs/RESEARCH.md](docs/RESEARCH.md).

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
```

Version numbers are derived from git tags — `app/build.gradle.kts` reads the
`VERSION_CODE` / `VERSION_NAME` environment variables, which release CI fills via
`scripts/derive-version.sh`. See [docs/RELEASING.md](docs/RELEASING.md) for the full
release process (tag → one workflow → GitHub Release with signed artifacts).

## Privacy

OpenScan collects nothing. There is no analytics, no crash reporting, and no account, and
the built APK requests no network access and no camera: the only `uses-permission` left
in the merged manifest is AndroidX's package-scoped receiver guard, which grants access
to nothing. Verify it yourself:

```bash
aapt2 dump badging app-release.apk | grep uses-permission
# uses-permission: name='io.github.alexeygrigorev.openscan.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

Details in [docs/PRIVACY.md](docs/PRIVACY.md).

## Contributing

Issues and PRs are welcome. Keep the promises: no watermarks, no ads, no tracking,
no new permissions without a very good reason.

## License

[Apache-2.0](LICENSE) — © 2026 Alexey Grigorev

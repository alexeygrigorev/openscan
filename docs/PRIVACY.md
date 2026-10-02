# Scanlet Privacy Policy

Last updated: 2026-10-02

**Short version: Scanlet collects nothing. Your scans never leave your phone.**

## What we collect

Nothing. The app manifest declares **no permissions at all** — no `INTERNET`,
no `CAMERA`, no storage, contacts, or location. Scanlet has no
developer-controlled network code: it is structurally unable to send your
documents, analytics, crash reports, or any other data anywhere.

## How it works without permissions

- **Document capture** happens inside Google Play services (the ML Kit
  document scanner module). Play services performs the camera work; Scanlet
  receives the scanned pages as local files and therefore never needs the
  camera permission itself.
- **OCR** uses ML Kit Text Recognition with the bundled model: fully
  on-device, offline, and unlimited.
- **Export and sharing** use the Android system share sheet and file picker,
  which is why no storage permission is needed either.

## Where your data lives

- Scanned pages live in the app's private storage on your device.
- Exported PDFs are produced in the app's cache directory and leave the device
  only when *you* share them via the Android share sheet or save them with
  the system file picker.
- Uninstalling the app removes everything; there is no server copy.

## Data safety form (Google Play)

Answers we submit: **no data collected, no data shared, no data transit;
data deletion — uninstall the app or delete a document in-app.**

## Open source

The entire codebase is public: <https://github.com/alexeygrigorev/scanlet>.
Anything this document claims is verifiable in source — including the empty
permission list:

```bash
aapt2 dump badging app-release.apk | grep uses-permission
# (no output)
```

## Contact

Open an issue at <https://github.com/alexeygrigorev/scanlet/issues>.

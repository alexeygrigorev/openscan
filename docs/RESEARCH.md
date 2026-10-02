# OpenScan — Research Summary (2026-10-02)

Condensed from the full research pass (CamScanner feature audit, competitor
gap analysis, naming collision checks). This is the "why" behind
[`FEATURES.md`](FEATURES.md) and the architecture choices.

## 1. The opportunity

1. **CamScanner monetizes aggressively and users resent it.** The free tier
   watermarks every export, shows ads, limits OCR to a preview, and caps cloud
   storage; premium is ~$50/year. Millions of people just want a clean PDF of
   a receipt or form.
2. **CamScanner has a durable trust deficit.** In August 2019 the official app
   (100M+ downloads) shipped with the Trojan-Dropper.AndroidOS.Necro.n malware
   module and was pulled from Google Play; India banned it in June 2020.
   "Your scanned passport never leaves your phone" is a pitch no closed-source
   incumbent can match.
3. **The market just lost its favorite free app.** Microsoft Lens was retired
   (delisted ~Feb 9 2026, stops working Mar 9 2026); its replacement requires
   a Microsoft account. Adobe Scan needs an Adobe account; Google Drive
   scanning is tied to Drive.
4. **The gap nobody closes:** reliable auto edge detection + CamScanner-grade
   enhancement + free unlimited OCR + zero watermark/ads/account **and**
   open source. FOSS options (FairScan, OpenScan) are deliberately minimal;
   closed options have (a) or (b) but never (c)+(d).

## 2. Architecture decision: ML Kit scanner now, own pipeline later

| Option | Verdict |
|---|---|
| **ML Kit Document Scanner** (chosen for v0.1) | One call gives viewfinder, auto-capture, edge detection, crop UI, filters, retake. Minimal app-size impact per Google and **no CAMERA permission** (capture runs inside Play services) — which is how OpenScan ships a zero-permission manifest. Trade-offs: needs Play services, fixed UI. |
| CameraX + OpenCV own pipeline (roadmap M2) | grayscale → blur → Canny → findContours → `approxPolyDP` → perspective warp. Needed for the F-Droid/de-Googled flavor and full UX control. Real work: detection robustness is exactly what commercial SDKs charge for; manual corner adjustment must be excellent. |

Supporting stack: Kotlin 2.x + Jetpack Compose + Material 3 (MVVM, single
activity), Room for the offline-first library, framework `PdfDocument` for
image→PDF export, PdfBox-Android (Apache-2.0) reserved for merge/split/
encryption/text-layer PDFs, ML Kit Text Recognition v2 (bundled = fully
offline OCR). minSdk 26; target/compileSdk 36 (Play requirement from
Aug 31 2026). **Licensing trap avoided:** iText is AGPL/commercial — never.

## 3. Positioning

The four "no"s that counter CamScanner's four biggest complaints:

> **No watermark. No ads. No account. No cloud.**

Proof, not promises: the built APK has **no `INTERNET` permission** — Google's
libraries merge network permissions into every app, and OpenScan strips them
from the merged manifest, so the app *cannot* exfiltrate anything (and the
library telemetry it bundles cannot either). Verify on any build:
`aapt2 dump badging app-release.apk | grep uses-permission` — the only line
is AndroidX's package-scoped receiver guard, which grants no capability.
Open source (Apache-2.0) makes every claim auditable, which matters for a
document scanner specifically (see the 2019 incident).

Target audiences: displaced Microsoft Lens users, privacy-conscious / FOSS
communities (r/fossdroid), anyone scanning IDs and contracts who balks at
cloud scanning, and the self-hosting crowd (Paperless-ngx push is on the
roadmap — user-configured, never a OpenScan account).

## 4. Naming

**OpenScan** — "scan" + "-let" (booklet, applet): two syllables, purpose
obvious in store search, small/fast/friendly. Collision-checked 2026-10-02:

- Google Play / App Store: no scanner (or any) app named OpenScan found.
- GitHub: no project named openscan; `alexeygrigorev/openscan` free.
- Only footprint anywhere: `OpenScan = 0x37`, a symbology enum constant in
  the Zebra Scanner SDK — not a product.

Runners-up: **Paperlet** (clean, warm, 3 syllables), **Unmarked**
(watermark-angsty but reads as an adjective; `unmarked.com`-family domains
taken). Eliminated: Docsnap/Docnap (DocSnap cluster), Snapleaf (plant-ID app),
Scanleaf (existing scanner), CrispScan (existing scanner), Skanna (existing
scanner), Clearleaf (existing PDF app), Truepage (existing software), Inksnap
(manga app), Quillscan (existing scanner).

## 5. Go-to-market / Play notes

- Store listing draft: [`PLAY_LISTING.md`](PLAY_LISTING.md); privacy answers:
  [`PRIVACY.md`](PRIVACY.md) (Data safety: **no data collected, no data
  shared**). Content rating: Everyone.
- New personal developer accounts must run a **closed test with ≥12 testers
  for 14 continuous days** before production access — start recruiting on
  day 1 (see `RELEASING.md` for the release engineering side).
- Release flow mirrors PocketShell: tag-driven versioning, tag-authorized
  publish workflow, signed APK+AAB from secrets.

## 6. Sources

Each claim above, matched to its source:

- **CamScanner free tier** (watermarks, ads, OCR preview, cloud caps, ~$50/yr
  premium): [free-vs-paid comparison](https://essexsoftware.com/scaniva/camscanner-free-vs-paid),
  [CamScanner App Store listing](https://apps.apple.com/us/app/camscanner-pdf-scanner-app/id388627783)
- **2019 Necro trojan** (Trojan-Dropper.AndroidOS.Necro.n in the official app,
  100M+ downloads, pulled from Google Play):
  [Kaspersky](https://www.kaspersky.com/blog/camscanner-malicious-android-app/28156),
  [XDA](https://www.xda-developers.com/camscanner-app-injecting-malware),
  [PCMag](https://www.pcmag.com/news/malware-discovered-in-popular-android-app-camscanner)
- **India ban, June 2020** (first batch of 59 Chinese apps; Delhi Police kept
  using it): [Rest of World](https://restofworld.org/2024/india-banned-camscanner-government-agencies),
  [ThePrint](https://theprint.in/india/delhi-police-admits-it-used-banned-chinese-app-camscanner-apologises-on-twitter/582767)
- **Microsoft Lens retirement** (delisted ~Feb 9 2026, stops working Mar 9
  2026; the replacement requires a Microsoft account):
  [Microsoft's official retirement page](https://support.microsoft.com/en-us/lens/retirement-of-microsoft-lens),
  [Thurrott](https://www.thurrott.com/cloud/331644/microsoft-lens-app-to-be-retired-on-march-9)
- **Adobe Scan** (free but account-bound; OCR/export limits):
  [Adobe subscriptions doc](https://www.adobe.com/devnet-docs/adobescan/android/en/managingsubscriptions.html),
  [Wirecutter](https://www.nytimes.com/wirecutter/reviews/best-mobile-scanning-apps)
- **Google Drive scanning** (tied to a Google account / Drive):
  [Google support](https://support.google.com/drive/answer/3145835)
- **FairScan / OpenScan are deliberately minimal** (no OCR, no rich
  enhancement, no document management):
  [WIRED on FairScan](https://www.wired.com/story/fairscan-simple-app-for-scanning-documents-on-android),
  [OpenScan on GitHub](https://github.com/ethereal-developers/OpenScan),
  [OpenScan on IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.ethereal.openscan)
- **ML Kit Document Scanner** (one call gives viewfinder, auto-capture, edge
  detection, crop UI, filters; capture runs inside Play services, hence no
  CAMERA permission): [overview](https://developers.google.com/ml-kit/vision/doc-scanner),
  [Android guide](https://developers.google.com/ml-kit/vision/doc-scanner/android),
  [GmsDocumentScannerOptions](https://developers.google.com/android/reference/com/google/mlkit/vision/documentscanner/GmsDocumentScannerOptions),
  [Android Developers announcement](https://android-developers.googleblog.com/2024/02/ml-kit-document-scanner-api.html)
- **ML Kit Text Recognition v2** (on-device; bundled = fully offline):
  [supported languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- **PdfBox-Android (Apache-2.0)** (merge/split/encryption/text layer):
  [GitHub](https://github.com/TomRoush/PdfBox-Android)
- **iText licensing trap** (AGPL/commercial dual license):
  [pdfbolt](https://pdfbolt.com/blog/top-java-pdf-generation-libraries),
  [APITemplate](https://apitemplate.io/blog/a-guide-to-generating-pdfs-in-java)
- **Play target-API 36 requirement** (from Aug 31 2026):
  [developer.android.com](https://developer.android.com/google/play/requirements/target-sdk)

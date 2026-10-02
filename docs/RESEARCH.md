# Scanlet — Research Summary (2026-10-02)

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
   open source. FOSS options (FairScan, OpenScan, Fossify) are deliberately
   minimal; closed options have (a) or (b) but never (c)+(d).

## 2. Architecture decision: ML Kit scanner now, own pipeline later

| Option | Verdict |
|---|---|
| **ML Kit Document Scanner** (chosen for v0.1) | One call gives viewfinder, auto-capture, edge detection, crop UI, filters, retake. ~300 KB app impact and **no CAMERA permission** (capture runs inside Play services) — which is how Scanlet ships a zero-permission manifest. Trade-offs: needs Play services, fixed UI. |
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
libraries merge network permissions into every app, and Scanlet strips them
from the merged manifest, so the app *cannot* exfiltrate anything (and the
library telemetry it bundles cannot either). Verify on any build:
`aapt2 dump badging app-release.apk | grep uses-permission` — the only line
is AndroidX's package-scoped receiver guard, which grants no capability.
Open source (Apache-2.0) makes every claim auditable, which matters for a
document scanner specifically (see the 2019 incident).

Target audiences: displaced Microsoft Lens users, privacy-conscious / FOSS
communities (r/fossdroid), anyone scanning IDs and contracts who balks at
cloud scanning, and the self-hosting crowd (Paperless-ngx push is on the
roadmap — user-configured, never a Scanlet account).

## 4. Naming

**Scanlet** — "scan" + "-let" (booklet, applet): two syllables, purpose
obvious in store search, small/fast/friendly. Collision-checked 2026-10-02:

- Google Play / App Store: no scanner (or any) app named Scanlet found.
- GitHub: no project named scanlet; `alexeygrigorev/scanlet` free.
- Only footprint anywhere: `Scanlet = 0x37`, a symbology enum constant in
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

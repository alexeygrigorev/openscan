# OpenScan — Feature Catalog

OpenScan is a free, open-source document scanner for Android: CamScanner's core
workflow with **no watermarks, no ads, no account, and no cloud by default**
(the one exception is a single, off-by-default upload toggle in Settings —
see [`PRIVACY.md`](PRIVACY.md)). Everything else runs on-device.

This document is the product contract. It maps every CamScanner feature (from
the 2026-10 research in [`RESEARCH.md`](RESEARCH.md)) to our priority and
current status.

## Priorities

- **P0 — MVP**: the smallest set that genuinely replaces CamScanner for the
  core job-to-be-done: scan a form/receipt/page → clean, straight PDF → send it.
- **P1 — differentiators**: features that pull users off the closed-source
  incumbents.
- **P2 — later**: parity / nice-to-have.
- **Skip**: incompatible with the offline-first, no-server, FOSS model.

## Feature matrix

| Feature | CamScanner free tier | Priority | OpenScan status |
|---|---|---|---|
| Auto edge detection | Free (with ads) | P0 | ✅ shipped (ML Kit scanner) |
| Perspective correction | Free | P0 | ✅ shipped |
| Manual corner adjustment | Free | P0 | ✅ shipped (play: the Play services scanner UI; foss: drag-the-corners editor in the batch review grid; in-app page editing offers rotate/delete/OCR) |
| Batch / multi-page scan | Free (caps) | P0 | ✅ shipped (play: the ML Kit scanner's multi-page UI, up to 50 pages; foss: own batch capture — pages auto-snap while you flip, Stop → review/correct grid → one document). Foss saves keep the original camera frame next to each cropped page (Settings → Capture → "Save original photos", on by default) so crops can be redone and captures shared for debugging later |
| Image filters (grayscale / B&W / enhance) | Basic free, Magic Color premium | P0 | ✅ shipped (provided by the Play services scanner UI, not in-app editing; in-app editing offers rotate/delete/OCR) |
| PDF export (multi-page) | Free but **watermarked** | P0 | ✅ shipped — never watermarked |
| Share / export targets | Free | P0 | ✅ shipped (system share sheet, FileProvider) |
| Import from gallery | Free | P0 | ✅ shipped (photo picker, no storage permission) |
| Fully offline, no account, no ads | Mixed | P0 | ✅ shipped — offline by default; the single networked feature is the off-by-default scan-sharing toggle (see [PRIVACY.md](PRIVACY.md)) |
| Document library: rename, delete documents and pages | Partial free | P0 | ✅ shipped (Room, offline-first) |
| OCR (text recognition) | Preview-only free; full is premium | P1 | ✅ shipped (on-device, unlimited, copyable) |
| Opt-in scan sharing (improve detection) | Silent cloud uploads | P1 | ✅ shipped — off by default, no identifiers, uploads auto-deleted after ≤30 days |
| Page rotation | Free | P0 | ✅ shipped (90° steps, baked into the JPEG) |
| Page reorder (move pages within a document) | Partial free | P0 | ✅ shipped (Reorder mode in the document screen: move pages up/down; order persists and drives both exports) |
| JPEG export per page (share as images) | Free but **watermarked** | P0 | ✅ shipped — never watermarked (single page shares a JPEG as-is; multiple pages share a zip of JPEGs, no re-encoding) |
| Searchable PDF (OCR text layer) | Premium | P1 | ⏳ roadmap (PdfBox text layer) |
| PDF merge / split / extract pages | Premium | P1 | ⏳ roadmap |
| Password-protected PDF export | Premium | P1 | ⏳ roadmap |
| E-signature (draw + place on page) | Limited free | P1 | ⏳ roadmap |
| Search inside documents (OCR full-text) | Partial free | P1 | ⏳ roadmap (Room FTS over OCR text) |
| Auto-capture (steady-frame snap) | Free | P1 | ✅ shipped (foss flavor: the viewfinder snaps when the OpenCV quad detector holds the document's corners steady for two frames after a cooldown; re-arms on page flip) |
| In-app capture with our own camera UI | n/a | P1 | ✅ shipped (foss flavor: CameraX preview + our OpenCV quad detector — [ScanPipeline](../app/src/main/java/io/github/alexeygrigorev/openscan/scan/ScanPipeline.kt); torch, manual Snap, Stop → review; play build unchanged, GMS scanner, zero permissions) |
| ID card mode (both sides, one page) | Free-ish | P2 | ⏳ backlog |
| Whiteboard / blackboard mode | Free | P2 | ⏳ backlog |
| Annotation / markup (pen, highlight) | Basic free | P2 | ⏳ backlog |
| Custom watermark (your own stamp) | Premium | P2 | ⏳ backlog (ironic, cheap) |
| Print service | Free-ish | P2 | ⏳ backlog (Android print framework) |
| Stain/glare cleanup beyond scanner modes | Premium | P2 | ⏳ backlog |
| Book scan (flattening) | Premium | P2 | ⏳ backlog (hard problem — defer) |
| QR / barcode scanning | Free | P2 | ⏳ backlog (ML Kit barcode) |
| Low-light / night enhancement | Partially premium | P2 | ⏳ backlog |
| Optional user-configured sync (WebDAV / Nextcloud / Paperless-ngx) | Cloud-tied (their cloud) | P2 | ⏳ backlog — self-hosted push, never a OpenScan account |
| F-Droid flavor (own CameraX+OpenCV pipeline) | n/a | P2 | ✅ shipped (`foss` flavor: the only build declaring CAMERA, in its own manifest — app/src/foss/AndroidManifest.xml; no GMS document-scanner dependency) |
| PDF → Word/Excel conversion | Premium (cloud) | Skip | ❌ server-bound |
| Fax | Paid credits | Skip | ❌ |
| Cloud account + sync (a OpenScan cloud) | ~200–400 MB free | Skip | ❌ no accounts, ever |
| Collaboration (shared folders, co-editing) | Premium | Skip | ❌ server-bound |
| Table extraction to Excel | Premium | Skip | ❌ server-bound |

## Non-negotiable product rules

1. **Never a watermark.** Exported PDFs and images are clean, always. There is
   one tier and it is free.
2. **No ads, no tracking, no account.** OpenScan ships no analytics or crash
   SDKs, and Google's library telemetry stays stripped (`ACCESS_NETWORK_STATE`
   is removed from the merged manifest). The app declares `INTERNET` for
   exactly one feature: the **off-by-default** "send pictures to our servers"
   toggle, which uploads scan pages only while enabled (fire-and-forget, no
   retries, no identifiers). With the toggle off nothing ever leaves the
   device. Any further networked feature must go through a public issue and
   stay optional + off by default.
3. **Zero permission creep.** No camera, storage, contacts, or location in the
   Play build — ever. If the own-pipeline F-Droid flavor lands, it adds
   `CAMERA` and nothing else, in its own flavor.
4. **On-device processing by default.** Scans of passports, IDs, and contracts
   never leave the phone unless the user turns on the off-by-default
   scan-sharing toggle — and even then pages are used solely to improve
   detection, carry no identifiers, and auto-delete within 30 days.

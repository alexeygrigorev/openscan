# Scanlet — Feature Catalog

Scanlet is a free, open-source document scanner for Android: CamScanner's core
workflow with **no watermarks, no ads, no account, and no cloud**. Everything
runs on-device.

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

| Feature | CamScanner free tier | Priority | Scanlet status |
|---|---|---|---|
| Auto edge detection | Free (with ads) | P0 | ✅ shipped (ML Kit scanner) |
| Perspective correction | Free | P0 | ✅ shipped |
| Manual corner adjustment | Free | P0 | ✅ shipped (provided by the Play services scanner UI, not in-app editing; in-app editing offers rotate/delete/OCR) |
| Batch / multi-page scan | Free (caps) | P0 | ✅ shipped (up to 50 pages) |
| Image filters (grayscale / B&W / enhance) | Basic free, Magic Color premium | P0 | ✅ shipped (provided by the Play services scanner UI, not in-app editing; in-app editing offers rotate/delete/OCR) |
| PDF export (multi-page) | Free but **watermarked** | P0 | ✅ shipped — never watermarked |
| Share / export targets | Free | P0 | ✅ shipped (system share sheet, FileProvider) |
| Import from gallery | Free | P0 | ✅ shipped (photo picker, no storage permission) |
| Fully offline, no account, no ads | Mixed | P0 | ✅ shipped — zero permissions declared |
| Document library: rename, delete documents and pages | Partial free | P0 | ✅ shipped (Room, offline-first) |
| OCR (text recognition) | Preview-only free; full is premium | P1 | ✅ shipped (on-device, unlimited, copyable) |
| Page rotation | Free | P0 | ✅ shipped (90° steps, baked into the JPEG) |
| Page reorder (move pages within a document) | Partial free | P0 | ⏳ roadmap (page position is append-only today) |
| JPEG export per page (share as images) | Free but **watermarked** | P0 | ⏳ roadmap (pages are stored as JPEGs internally; only PDF export is user-facing today) |
| Searchable PDF (OCR text layer) | Premium | P1 | ⏳ roadmap (PdfBox text layer) |
| PDF merge / split / extract pages | Premium | P1 | ⏳ roadmap |
| Password-protected PDF export | Premium | P1 | ⏳ roadmap |
| E-signature (draw + place on page) | Limited free | P1 | ⏳ roadmap |
| Search inside documents (OCR full-text) | Partial free | P1 | ⏳ roadmap (Room FTS over OCR text) |
| Auto-capture (steady-frame snap) | Free | P1 | ⏳ roadmap (own-pipeline phase) |
| In-app capture with our own camera UI | n/a | P1 | ⏳ roadmap (CameraX + OpenCV, unlocks F-Droid) |
| ID card mode (both sides, one page) | Free-ish | P2 | ⏳ backlog |
| Whiteboard / blackboard mode | Free | P2 | ⏳ backlog |
| Annotation / markup (pen, highlight) | Basic free | P2 | ⏳ backlog |
| Custom watermark (your own stamp) | Premium | P2 | ⏳ backlog (ironic, cheap) |
| Print service | Free-ish | P2 | ⏳ backlog (Android print framework) |
| Stain/glare cleanup beyond scanner modes | Premium | P2 | ⏳ backlog |
| Book scan (flattening) | Premium | P2 | ⏳ backlog (hard problem — defer) |
| QR / barcode scanning | Free | P2 | ⏳ backlog (ML Kit barcode) |
| Low-light / night enhancement | Partially premium | P2 | ⏳ backlog |
| Optional user-configured sync (WebDAV / Nextcloud / Paperless-ngx) | Cloud-tied (their cloud) | P2 | ⏳ backlog — self-hosted push, never a Scanlet account |
| F-Droid flavor (own CameraX+OpenCV pipeline) | n/a | P2 | ⏳ backlog (de-Googled devices) |
| PDF → Word/Excel conversion | Premium (cloud) | Skip | ❌ server-bound |
| Fax | Paid credits | Skip | ❌ |
| Cloud account + sync (a Scanlet cloud) | ~200–400 MB free | Skip | ❌ no accounts, ever |
| Collaboration (shared folders, co-editing) | Premium | Skip | ❌ server-bound |
| Table extraction to Excel | Premium | Skip | ❌ server-bound |

## Non-negotiable product rules

1. **Never a watermark.** Exported PDFs and images are clean, always. There is
   one tier and it is free.
2. **No ads, no tracking, no account.** The built APK has **no `INTERNET`
   permission** (Google's libraries request it; Scanlet strips it from the
   merged manifest): the app *cannot* phone home, and the library telemetry
   it bundles cannot either. Capture happens inside Google Play services;
   export uses the system share sheet. Any future exception must go through a
   public issue and stay optional + off by default.
3. **Zero permission creep.** No camera, storage, contacts, or location in the
   Play build — ever. If the own-pipeline F-Droid flavor lands, it adds
   `CAMERA` and nothing else, in its own flavor.
4. **On-device processing only.** Scans of passports, IDs, and contracts never
   leave the phone. This is the marketing headline and the engineering
   constraint.

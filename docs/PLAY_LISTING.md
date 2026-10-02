# Google Play listing draft — Scanlet

Working draft for the Play Console listing. Data safety answers and rating
questionnaire at the bottom.

## Store title (≤30 chars)

```
Scanlet: Doc Scanner, No Marks
```

(Exactly 30 chars — right at the ≤30 limit. Want headroom? `Scanlet: Doc Scanner`
is 20. Fallback if review wants it plainer: `Scanlet — PDF Scanner App`, 25.)

## Short description (≤80 chars)

```
Scan docs to clean PDFs. No watermarks, no ads, no account. Free & open source.
```

(79 chars.)

## Full description

Scanning a document should not cost a subscription — or your privacy.

Scanlet turns your phone into a document scanner that just works: point,
scan, get a clean, straight, readable PDF. **No watermarks. No ads. No
account. No cloud.** Free and open source.

◼ WHAT IT DOES
• Scan multipage documents with automatic edge detection and perspective
  correction
• Clean up pages in the scanner: original color, grayscale, crisp black &
  white
• Export PDFs — never stamped, never limited
• Unlimited on-device OCR: copy text from any scan, free
• Organize a local library: rename documents, rotate and delete pages
• Import existing photos and turn them into documents
• Share anywhere via the Android share sheet

◼ WHY IT'S DIFFERENT
• **Your documents stay yours.** Scanlet cannot reach the network or your
  camera on its own — no internet permission, no camera permission. Everything
  is processed on your phone.
• **The app the freemium scanners pretend to be.** Clean exports with no
  watermark, full OCR without a paywall.
• **A new home for Microsoft Lens users.** Lens is retired; Scanlet keeps the
  simple, ad-free workflow alive — without any account.
• **Open source (Apache-2.0).** Audit every line:
  github.com/alexeygrigorev/scanlet

◼ PERMISSIONS
No camera, no internet, no storage. The app cannot reach the network or the
camera on its own: capture runs inside Google Play services and sharing uses
the system share sheet.

◼ GOOD TO KNOW
• Requires Google Play services for the on-device scanner module (an F-Droid
  flavor with an independent pipeline is on the roadmap)
• Works fully offline

Scan anything. Own everything.

## Graphics plan

- App icon: teal document sheet with scan brackets (repo vector drawable)
- Feature graphic (1024×500): headline "No watermarks. No ads. No cloud." +
  phone mockups of capture → filters → PDF
- Screenshots (min 2): 1) capture with detected edges, 2) page editor with
  OCR text, 3) document library, 4) exported PDF in the share sheet

## Required for submission

What Play Console demands that this draft does not yet provide:

- **Hosted public privacy-policy URL** — `PRIVACY.md` must be published
  somewhere reachable (GitHub Pages, or the rendered GitHub URL:
  github.com/alexeygrigorev/scanlet/blob/main/docs/PRIVACY.md)
- **Contact email** for the store listing
- **Store category** — Utilities or Productivity (pick one)
- **Produced asset files** — 512×512 app icon, 1024×500 feature graphic, and
  ≥2 phone screenshots (the graphics plan above is still a plan, not files)

## Data safety form

Per `PRIVACY.md`: no data collected, no data shared, security practices n/a.

## Content rating questionnaire

Scanning utility → "Everyone" / 3+ (no user-generated content sharing, no
interaction with other users).

## Target audience & content

Utilities; all ages; contains ads: no; in-app purchases: no; finance
features: none.

## Notes for the release owner

- New personal dev account: production requires the closed test (≥12
  testers, 14 continuous days) — see `RELEASING.md` + roadmap. Start
  recruiting testers on day 1.
- The single-screen manifest (zero permissions) plus the "no data collected"
  form keeps review friction minimal.
- Do NOT use "CamScanner" in the title. The description above avoids it
  entirely; keep it that way.

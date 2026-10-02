# Main-agent re-judge notes (cards2 sheets_rejudge), 2026-10-02
Disclosure: cards_12-14.jpg Read timed out on first attempt; retried below. All other sheets so far rendered fully.

## cards_00-02
- 00_receipt_01: NO_DETECTION — crumpled hospital day-ticket, no quad, no PDF page. (Paper crumpled, low-contrast edges.)
- 01_form_01: PASS — Russian medical form; quad tight on form; PDF page clean, deskewed, readable.
- 02_document_01: NA_NON_DOCUMENT — portrait photo (person); quad arbitrary; PDF = warped portrait. No document present.

## cards_03-05
- 03_document_02: NA_NON_DOCUMENT — portrait photo; full-frame tilted quad; warped portrait output.
- 04_receipt_02: PASS — Chinese railway refund receipt; quad tight; PDF page readable.
- 05_receipt_03: PASS — Bankia receipt on dark bg; quad tight; PDF readable, deskewed.

## cards_06-08
- 06_receipt_04: PASS — Bankia receipt, low light; quad tight; PDF readable.
- 07_receipt_05: PASS — crumpled Jumbo long receipt; quad tracks paper; PDF readable (paper crumple visible).
- 08_receipt_06: NO_DETECTION — receipt close-up, edges outside frame; no quad/page. (Acceptable: no complete boundary.)

## cards_09-11
- 09_receipt_07: NO_DETECTION — bottom part of receipt only, full edge not in frame.
- 10_invoice_01: PASS — Starbucks receipt; quad tight; PDF readable.
- 11_invoice_02: PASS — Taipei 101 long receipt; quad tight; PDF readable.





## cards_12-14
- 12_invoice_03: PASS — 環球板橋店 long receipt; quad tight; PDF readable.
- 13_invoice_04: PASS — Costco e-invoice; quad tight; PDF clean, readable.
- 14_invoice_05: PASS(minor) — [full-res verified] narrow uni-prek receipt; quad hugs receipt w/ small overhang bottom-left; PDF readable w/ thin grey margins.

## cards_15-17
- 15_invoice_06: FAIL(partial) — UNIQLO receipt; quad locked onto top uniform-invoice stamp box; PDF = zoomed stamp/label crop; receipt body lost.
- 16_invoice_07: FAIL(partial) — similar receipt; quad = small tilted polygon around invoice number; PDF = zoomed inner label; rest of receipt lost.
- 17_contract_01: PASS — open two-page contract in binder; quad = full spread incl. binder; PDF readable, complete.

## cards_18-20
- 18_contract_02: NA_NON_DOCUMENT — group news photo (signing ceremony); quad ≈ frame; PDF = photo.
- 19_contract_03: PASS — open two-page contract; quad tight on spread; PDF clean readable.
- 20_contract_04: NA_NON_DOCUMENT — room with flags/podium; arbitrary quad; warped crop output.

## cards_21-23
- 21_contract_05: NA_NON_DOCUMENT — ELT signing ceremony news photo; quad ≈ frame.
- 22_contract_06: NA_NON_DOCUMENT — portrait photo; partial arbitrary quad.
- 23_contract_07: NA_NON_DOCUMENT — group ceremony photo; quad ≈ frame.





## cards_24-26
- 24_letter_01: FAIL(partial) — Persian manuscript spread; quad = small box on middle-left text block; PDF = zoomed text crop; most of spread lost.
- 25_letter_02: NO_DETECTION — typewritten manuscript page; no quad.
- 26_letter_03: PASS — 1812 handwritten letter; quad = frame; PDF complete, readable.

## cards_27-29
- 27_letter_04: NO_DETECTION — letter on envelope; no quad.
- 28_letter_05: NO_DETECTION — handwritten letter; no quad.
- 29_letter_06: PASS — 1812 letter; quad = frame; PDF complete, readable.

## cards_30-59
- 30_letter_07: PASS — old manuscript spread; quad ≈ frame; PDF complete.
- 31_notebook_01: NA_NON_DOCUMENT — [full-res verified] sepia portrait photo; quad = small box on hair; PDF = hair zoom. No document present.
- 59_id_card_03: FAIL(loose) — [full-res verified] Turkish ID in hand; quad top edge on card, bottom vertices extend far below through hand; CROP readable, card complete; GT tight. prog err 11.7 iou 0.5.

## cards_60-62
- 60_id_card_04: NO_DETECTION — ID held in front of monitor; GT present (lime tight). Miss.
- 61_id_card_05: PASS — [full-res verified] Turkish ID on keyboard; quad tight; CROP complete readable; GT agrees. prog err 1.3 iou 0.93.
- 62_id_card_06: PARTIAL — [full-res verified] Turkish ID on keyboard; quad top edge cuts above card into keyboard; CROP complete readable but tilted; GT tight. prog err 3.6 iou 0.82.





## cards_63-65
- 63_id_card_07: PARTIAL — [full-res verified] Antarctica wall map; quad covers upper-left ~60% of map only; CROP clean readable map crop (partial map); GT lime not visible (iou 0, err 36.4). Earlier sheet note described a different card.
- 64_id_card_08: FAIL — [full-res verified] cluttered desk w/ Wikipedia page + highlighters; quad = small rect on highlighters/ruler, document not captured; CROP = clutter zoom; GT top-left. prog err 56.8 iou 0.
- 65_id_card_09: PASS — [full-res verified] Turkish ID on wood; quad tight; CROP large readable; GT agrees. prog err 1.0 iou 0.93. (Sheet note had shifted panels.)

## cards_69-71 (cards_66-68 Read timed out twice; retrying below)
- 69_passport_03: FAIL(loose) — [full-res verified] German passport held in hand; quad covers passport + hand/knee below; CROP complete readable; GT tight. prog err 8.4 iou 0.64.
- 70_passport_04: NO_DETECTION — CORRECTED after full-res re-read: panel 1 = input, NO DETECTION; passport against red Cyrillic wall; GT tight. (Earlier sheet-level note misattributed panels.) on highlighters/blade, off the ID; CROP = clutter zoom; ID lost; GT tight.
- 71_passport_05: PASS — [full-res verified] German passport on keyboard; quad tight; CROP complete readable incl. MRZ; GT agrees. prog err 1.2 iou 0.93.

## cards_72-74
- 72_passport_06: PASS — [full-res verified] German passport on keyboard; quad tight; CROP complete incl. MRZ; GT agrees. prog err 1.8 iou 0.89.
- 73_passport_07: PARTIAL — [full-res verified] Wikipedia page under clutter; quad tracks page loosely (right edge sweeps across photo); CROP readable but skewed; GT tighter. prog err 21.9 iou 0.06.
- 74_passport_08: FAIL — [full-res verified] tilted letter w/ ruler+cable; quad degenerate skewed triangle over left portion; CROP = rotated partial text; GT tight parallelogram. prog err 38.5 iou 0.




## cards_75-77
- 75_passport_09: PASS — [full-res verified] German passport on wood desk; quad tight; CROP large complete readable; GT agrees. prog err 0.4 iou 0.97.
- 76_passport_10: PASS — [full-res verified] German passport on wood; quad tight; CROP large complete readable; GT agrees. prog err 0.4 iou 0.97.
- 77_driver_license_01: FAIL — Spanish ID in clutter; quad = diamond on portrait photo; CROP = blurry face zoom; GT tight.

## cards_81-83
- 81_driver_license_05: PARTIAL — [full-res verified] Spanish licence on keyboard; quad top edge cuts above card; CROP complete upright readable; GT tight. prog err 3.7 iou 0.75.
- 82_driver_license_06: PASS(minor) — [full-res verified] Spanish licence on keyboard; quad tight, top-right corner slightly above card; CROP complete upright readable. prog err 4.1 iou 0.75.
- 83_driver_license_07: FAIL — [full-res verified] small ID vs flowery bg; quad locked on EU-flag E logo; CROP = blurry flag zoom; GT tight on card. prog err 12.1 iou 0.18.

Read failures: cards_66-68 timed out 3x (sheet), cards_78-80 timed out 1x — re-reading those six cards individually.




## cards_66-68 (individual reads after 3 sheet timeouts)
- 66_id_card_10: PASS — Turkish ID on wood; quad tight; CROP large readable; GT agrees.
- 67_passport_01: FAIL(whole-scene) — German passport in clutter; quad ≈ full frame; CROP = whole scene; GT tight.
- 68_passport_02: FAIL — same scene; quad on portrait photo; CROP = blurry face zoom; GT tight.


## cards_78-80 (individual reads)
- 78_driver_license_02: FAIL(whole-scene) — Spanish ID in clutter; quad ≈ full frame; CROP = whole scene; GT tight.
- 79_driver_license_03: FAIL — hand-held Spanish licence; quad on portrait photo; CROP = blurry face; GT tight.
- 80_driver_license_04: FAIL — hand-held ID; quad locked on blue magnets; CROP = magnet blobs; GT tight on card.


## cards_84-86
- 84_driver_license_08: FAIL — [full-res verified] Wikipedia page + highlighters + ruler; quad = tall rect on ruler/highlighter area, off-document; CROP = shifted scene; GT disjoint. prog err 43.8 iou 0.
- 85_driver_license_09: PASS — [full-res verified] Spanish licence on wood; quad tight; CROP complete readable. prog err 0.5 iou 0.96.
- 86_driver_license_10: PASS — [full-res verified] same licence; quad tight; CROP complete readable. prog err 0.3 iou 0.96.

## cards_87-89
- 87_notebook_02: PARTIAL — [full-res verified] open notebook w/ glasses+pens; quad = skewed parallelogram, corners off into background/sleeve; PDF page readable, slightly tilted, white bands. 
- 88_notebook_03: FAIL — [full-res verified] planner w/ pencils; quad degenerate, extends off-frame top-left; PDF page = tilted crop w/ white bands; document mostly lost.
- 89_notebook_04: FAIL — [full-res verified] woman writing; quad = narrow vertical strip on hand/window, extends below frame; PDF = strip crop; notebook mostly outside.

## cards_90-92
- 90_notebook_05: PARTIAL — [full-res verified] desk w/ monitor + papers; quad tight on MONITOR screen; PDF = readable screen render; papers ignored. No GT for this image.
- 91_notebook_06: PARTIAL — [full-res verified] dark scene notebook+food; quad diamond roughly on region but sloppy; PDF page tilted readable-ish w/ white bands.
- 92_notebook_07: PARTIAL — [full-res verified] top-down writing scene; quad = large quad over desk incl. laptop edge/arm; PDF readable upright but not a tight doc crop.

## cards_93-94
- 93_notebook_08: FAIL(loose) — [full-res verified] spiral notebook + printed page + pen; quad = full frame; PDF = readable upright whole-scene render (usable output, not a document crop).
- 94_notebook_09: FAIL — [full-res verified] quad tight on the PHONE; PDF = dark phone back; notebook ignored.

RE-JUDGE METHOD NOTE: 3-card contact sheets proved UNRELIABLE (panels shifted between neighbor cards in the visually-similar ID/passport/licence families); every disputed verdict was re-read at full resolution individually and corrected above. Cross-check vs programmatic results.json (detect flag + corner_err_pct/iou on 28 GT cards) used as ground-truth anchor.






## b-range (32-58) first-party full-res re-reads — supersede judge B notes where they differ
- 32_form_02: FAIL(loose) — Employee Performance Evaluation; quad = large skewed polygon well beyond form; PDF = tilted scene view, form readable w/ white bands. Judge B desc agrees (loose).
- 33_form_03: NA_NON_DOCUMENT — meadow+footbridge photo; quad = frame; PDF = photo. Judge B agrees.
- 34_form_04: NA_NON_DOCUMENT — barrel-wall photo; quad = frame. Judge B agrees (non-document).
- 35_form_05: NA_NON_DOCUMENT — barrels/attic; quad = arbitrary inset rect; PDF = crop. Judge B agrees.
- 36_form_06: FAIL(loose) — CORRECTED vs judge B: restaurant order slip in glass; quad = FULL FRAME (incl. table, cans, bowls), NOT slip corners; PDF = whole scene, slip readable at top.
- 37_form_07: PASS — Employee Performance Evaluation; quad tight on paper; PDF readable, slight tilt. Judge B agrees.
- 38_business_card_01: FAIL — quad locked on small DSI logo; PDF = blurry logo zoom; card lost. Judge B agrees.
- 39_business_card_02: FAIL(partial) — striped sheet; quad = bottom green band only; rest lost. Judge B agrees.
- 40_business_card_03: FAIL(loose) — VISA2CHINA card small on fabric; quad = full frame; PDF = whole photo. Judge B agrees.
- 41_business_card_04: FAIL(loose) — VISA2CHINA James Wang; quad = full frame; PDF = whole photo. Judge B agrees.
- 42_business_card_05: FAIL(loose) — VISA2CHINA centered; quad = full frame; PDF = whole photo. (Judge B desc said left-strip; actual full-frame. Verdict same.)
- 43_business_card_06: PASS — CS techniek card; quad tight; PDF upright readable. Judge B agrees.
- 44_business_card_07: PASS — CS techniek small in dark frame; quad tight; PDF large readable. Judge B agrees.
- 45_article_01: FAIL — Hine mill photo; degenerate quad w/ vertices off-frame; PDF = tilted arbitrary crop. Judge B agrees.
- 46_article_02: FAIL — naturalization certificate; quad locked on red wax seal; PDF = seal zoom; document lost. Judge B agrees.
- 47_article_03: FAIL(partial) — Missouri broadside; quad = inner text block; header + bottom seal cut from PDF. Judge B agrees.
- 48_article_04: PASS — Patriotic Roll form; quad = frame; PDF complete readable. Judge B agrees.
- 49_article_05: PASS — Haeckel plate; quad = frame; PDF complete. Judge B agrees.
- 50_article_06: PASS — Tailors' Union withdrawal card; quad tight; PDF complete readable. Judge B agrees.
- 51_article_07: PASS — CORRECTED vs judge B: framed Legal Consultant Certificate on wall; quad tight on frame; PDF upright readable. (Judge B's 'Tea screenshot' description belongs to file 53.)
- 52_document_03: NA_NON_DOCUMENT — daisy photo; quad rect off right edge; PDF = warped flower. Judge B agrees.
- 53_document_07-note: see 53_document_04 below
- 53_document_04: FAIL(partial) — CORRECTED vs judge B: Wikipedia 'Tea' screenshot; quad = infobox card only; ~80% of page content lost. (Judge B's 'framed certificate' description belongs to file 51.)
- 54_document_05: NA_NON_DOCUMENT — lace doily; quad = small top-left patch. Judge B agrees.
- 55_document_06: NA_NON_DOCUMENT — table-tennis player; diamond quad, vertices off-frame. Judge B agrees.
- 56_document_07: PASS — Roosevelt sepia print; quad = frame; PDF complete readable. Judge B agrees.
- 57_id_card_01: FAIL — judge B (full-scene quad on cluttered desk, GT tight); prog corner_err 27.8 iou 0.23 corroborates.
- 58_id_card_02: FAIL — judge B (full-scene quad, GT tight); prog corner_err 19.7 iou 0.37 corroborates.

## b-range (32-58) first-party full-res re-reads — supersede judge B notes where they differ
- 32_form_02: FAIL(loose) — Employee Performance Evaluation; quad = large skewed polygon well beyond form; PDF = tilted scene view, form readable w/ white bands. Judge B desc agrees (loose).
- 33_form_03: NA_NON_DOCUMENT — meadow+footbridge photo; quad = frame; PDF = photo. Judge B agrees.
- 34_form_04: NA_NON_DOCUMENT — barrel-wall photo; quad = frame. Judge B agrees (non-document).
- 35_form_05: NA_NON_DOCUMENT — barrels/attic; quad = arbitrary inset rect; PDF = crop. Judge B agrees.
- 36_form_06: FAIL(loose) — CORRECTED vs judge B: restaurant order slip in glass; quad = FULL FRAME (incl. table, cans, bowls), NOT slip corners; PDF = whole scene, slip readable at top.
- 37_form_07: PASS — Employee Performance Evaluation; quad tight on paper; PDF readable, slight tilt. Judge B agrees.
- 38_business_card_01: FAIL — quad locked on small DSI logo; PDF = blurry logo zoom; card lost. Judge B agrees.
- 39_business_card_02: FAIL(partial) — striped sheet; quad = bottom green band only; rest lost. Judge B agrees.
- 40_business_card_03: FAIL(loose) — VISA2CHINA card small on fabric; quad = full frame; PDF = whole photo. Judge B agrees.
- 41_business_card_04: FAIL(loose) — VISA2CHINA James Wang; quad = full frame; PDF = whole photo. Judge B agrees.
- 42_business_card_05: FAIL(loose) — VISA2CHINA centered; quad = full frame; PDF = whole photo. (Judge B desc said left-strip; actual full-frame. Verdict same.)
- 43_business_card_06: PASS — CS techniek card; quad tight; PDF upright readable. Judge B agrees.
- 44_business_card_07: PASS — CS techniek small in dark frame; quad tight; PDF large readable. Judge B agrees.
- 45_article_01: FAIL — Hine mill photo; degenerate quad w/ vertices off-frame; PDF = tilted arbitrary crop. Judge B agrees.
- 46_article_02: FAIL — naturalization certificate; quad locked on red wax seal; PDF = seal zoom; document lost. Judge B agrees.
- 47_article_03: FAIL(partial) — Missouri broadside; quad = inner text block; header + bottom seal cut from PDF. Judge B agrees.
- 48_article_04: PASS — Patriotic Roll form; quad = frame; PDF complete readable. Judge B agrees.
- 49_article_05: PASS — Haeckel plate; quad = frame; PDF complete. Judge B agrees.
- 50_article_06: PASS — Tailors' Union withdrawal card; quad tight; PDF complete readable. Judge B agrees.
- 51_article_07: PASS — CORRECTED vs judge B: framed Legal Consultant Certificate on wall; quad tight on frame; PDF upright readable. (Judge B's 'Tea screenshot' description belongs to file 53.)
- 52_document_03: NA_NON_DOCUMENT — daisy photo; quad rect off right edge; PDF = warped flower. Judge B agrees.
- 53_document_07-note: see 53_document_04 below
- 53_document_04: FAIL(partial) — CORRECTED vs judge B: Wikipedia 'Tea' screenshot; quad = infobox card only; ~80% of page content lost. (Judge B's 'framed certificate' description belongs to file 51.)
- 54_document_05: NA_NON_DOCUMENT — lace doily; quad = small top-left patch. Judge B agrees.
- 55_document_06: NA_NON_DOCUMENT — table-tennis player; diamond quad, vertices off-frame. Judge B agrees.
- 56_document_07: PASS — Roosevelt sepia print; quad = frame; PDF complete readable. Judge B agrees.
- 57_id_card_01: FAIL — judge B (full-scene quad on cluttered desk, GT tight); prog corner_err 27.8 iou 0.23 corroborates.
- 58_id_card_02: FAIL — judge B (full-scene quad, GT tight); prog corner_err 19.7 iou 0.37 corroborates.

# Gold annotation audit, 2026-10-23 (t1245u)

Private source photos and review images remain under
`~/prod/openscan-eval/`; only IDs, coordinates and provenance belong here.
The full feedback manifest contains detector-derived labels and must not
be used as ground truth.

Coordinates are TL, TR, BR, BL in original JPEG pixels. The target is the
visible physical sheet or notebook spread, clipped to the image when its
edge continues beyond the photograph. Printed tables and text boxes are
content, not document edges. Rounded tips are judged at the intersection
of the adjoining page boundaries; a quadrilateral does not model page curl.

Labels below were measured from raw-only views with coordinate grids,
without a detector overlay or the circular manifest. Independent HSV masks
and paper/background transitions served as cross-checks. Label-only overlays
were then checked against the raw image. Detector output is used afterwards
for scoring, never to seed truth. This audit discovered errors even in some
previously described independent gold labels; a green historical score does
not validate those labels.

## Frame-filling printed pages

| ID | Action | Independent boundary evidence |
| --- | --- | --- |
| 0c75f319 | Correct existing gold | Previous truth enclosed an interior table. Paper continues to all four image edges; source 956×1288. |
| 19d28367 | Add | Previous detector enclosed a text block. Paper reaches all four image edges; source 969×1347. |
| 460bc25e | Add | Interior table latch; physical page reaches all image edges; source 976×1325. |
| 89310447 | Add | Interior table latch; physical page reaches all image edges; source 948×1302. |
| f12f4f06 | Add | Interior table latch; physical page reaches all image edges; source 966×1323. |

Validation for this milestone: gold 22/22 within 1% on every corner;
`testFossDebugUnitTest` passed. Images and label-only review sheets are private.

## Raw corner audit and silhouette expansion

The second pass used raw-only full-image coordinate grids, followed by
240×240 source-pixel corner crops enlarged to 480×480 with 40-pixel grid
spacing. The full-image estimates were drafts: enlarged crops resolved page
versus cover transitions, rounded tips and clipping. Standalone Otsu/LAB and
HSV masks were cross-checks, not OpenScan predictions; for example the LAB
mask that split `aa466bb3` at the gutter was rejected. Final coordinates were
visually verified on the raw paper/background transitions. Keep the raw
crops, draft annotations and scripts private. Ordinary visible edge estimates
have roughly 5–10 source-pixel precision; rounded or folded boundaries do not
have a mathematically unique corner. Use the visible paper tip or its clipping
point, and do not label a distant extrapolation onto the desk or notebook cover.

The actual image contents exposed mistakes in the old 14-case set: the
interior-table label on `0c75f319`, desk strips on `28bb7f99`, `0197f6d6`,
`422ee2d9`, `d69cdd48`, `c86813b5`, and cover/tip errors on `511762b9`,
`ca2def14`, `fb88026b`. All were independently remeasured. The four additions
already present on main were also checked and corrected, including the
foremost sheet of `b3fc10ce`: its bottom is around y=1385–1410, rather than
frame-bottom through the next sheet. Its top-left is around (125,0), rather
than the underlying stack's inset corner. `a6ce91e0` targets the upright
foreground sheet, excluding the rotated sheet underneath.

For the newly annotated `64041749`, measure the four physical outer paper
tips on the wrinkled sheet; the blue printed border is content. The other
unlabeled extra `e8ba2949` already has a detector overlay baked into the JPEG.
It is unsuitable as a raw-image gold fixture and remains explicitly unscored.

| ID | Reason for independent gold coverage |
| --- | --- |
| 3d83634e | Right/bottom desk included |
| 44ea44b0 | Desk and notebook cover included at the right tips |
| 465fffd5 | Sloping lower paper edge replaced by frame bottom |
| 46a9e576 | Lower-right paper/desk boundary missed |
| 69f7e544 | Wide right-hand desk strip included |
| 783fea4b | Sloping bottom edge replaced by frame bottom |
| 861255e1 | Sloping bottom edge and clipped top tips |
| 9a45ac11 | Right-hand desk included |
| a283cc77 | Bottom-left cover included, just beyond 1% |
| a3da4127 | Lower paper boundary replaced by frame bottom |
| a6ce91e0 | Overlapping sheets require the foreground page |
| a799fbc9 | Large desk wedge under the lower page |
| b0e907cc | Wide right-hand desk strip included |
| b5ba0a7e | Right rounded tips include cover/desk |
| bb0ceb4e | Sloping lower paper edge missed |
| bd1d5063 | Right-hand desk strip and rounded tips |
| bf542c22 | Diagonal left paper boundary on cloth |
| deedbafd | Interior margin and bottom clipping on a narrow page |
| e04a5949 | Desk included at the lower-right tip |
| e13288f7 | Right-hand desk included after text-latch repair |
| e48f6b04 | Large desk wedge under the lower page |
| e4c6c561 | Sloping lower paper edge missed |
| eec874ad | Right-hand desk and rounded tips |
| 64041749 | Previously unlabeled wrinkled sheet; independent outer-tip annotation |

Final full-set raw-image review added five more regressions already repaired
by the silhouette pass. These were independently measured on raw-only grids
(800-pixel longest side, axes in 0–1000 longest-side units) and converted to
source pixels; no frozen feedback truth or detector corners were copied.

| ID | Reason | Raw physical boundary evidence |
| --- | --- | --- |
| 06fc1efa | Right-hand cover/desk at BR | Rounded right paper tips; BR near (1460,1125), rather than x=1524 on desk. |
| 07ee7a4f | Bottom-right cover strip | Lower paper edge meets right photo boundary near y=1200. |
| 0dfdb6be | Top-right desk/cover | Upper rounded paper tip near (1340,40), rather than near frame-right. |
| 9404e0d0 | Bottom paper clipped by old quad | Handwritten sheet reaches bottom frame; visible curled BR near (950,1328). |
| ef3daa3f | Lower-left fabric/shadow and right margin | Physical page bottom starts near y=1320 on left and y=1350 on right. |

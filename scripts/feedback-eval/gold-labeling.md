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

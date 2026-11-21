## ✨ feat(documents): positioned text, the `HEADINGS` split, one `BoundedTextStripper`, `TextBlock`s (2026-11-21)

**Repo:** EDDI (`feat/doc-positioned-text`), work package WP-2b.1 of the PDF documents plan. It builds on WP-2a.4 (`feat/doc-manifest-extractor`). Pure extraction: no store, no configuration, no wiring, nothing user-visible yet.

### What changed and why

- **One `BoundedTextStripper`** (`modules/ingestion/extract/`). `PdfTextExtractor` and `AttachmentTextExtractor` each had a private `PDFTextStripper` subclass with different bounds. They are now one public class, so a bound added there holds for every path a PDF's text takes. `chargePageContents` moves into it as well.
  - The `Instant` constructor keeps the ingestion behaviour: a deadline shared with the pre-scan, a glyph allowance set per page through `allowGlyphs`/`pageWasCut`, and an `UnreadableDocumentException` ("took too long").
  - The `(maxOperators, Duration[, maxGlyphs])` constructors keep the attachment behaviour: an operator cap, one glyph allowance for the whole extraction, and a sticky `IOException("Refused: …")` that `getText` raises again when PDFBox has swallowed it inside a form `Do`.
  - Unified: the deadline is checked every 256 operators and the interrupt flag on every operator, on both paths.
  - The text both extractors return is byte-identical before and after the fold. This was checked on every `PdfFixtures` document, at four character caps, for whole documents and page ranges.
- **Positions.** With a `LineListener` set, the stripper also reports every laid-out line: page, text, left and right edge, top (above the baseline) and font size (scaled by the text matrix). The listener only observes; `getText` returns the same text with or without one.
- **`PageLayout`** (pure) reads one page's lines:
  - the body size, which is the median font size of its characters;
  - headings, meaning a short line (≤ 100 characters, with a letter) set ≥ 1.2× the body size;
  - columns: two groups of left edges at least half an inch apart, each with a quarter of the lines, the left group ending before the right begins;
  - reading order: top to bottom, and left to right among lines at the same height.
- **`SplitStrategy.HEADINGS`** now cuts at headings found by font size instead of meaning `AUTO`.
  - Levels group sizes within 5 % of each other. The first level, largest first, with headings on two or more pages makes the chapters, and the level below makes their parts.
  - A larger heading on one page only, such as a title page's title, starts nothing.
  - Chapters are placed exactly like outline entries (front matter, one per start page, whole pages).
  - Fewer than two chapters means the document is cut into page windows, with a warning.
- **`AUTO` is now outline → headings → pages.** It says which one it fell back to, and a document without text is still cut by pages.
- **Multi-column pages** are read in plain top-to-bottom order, and the manifest names those pages in a warning. Headings and columns are found in the same pass that measures the pages, so no page is decoded twice.
- **`PdfStructureExtractor.blocks(pdf, unit, options, limits)`** returns a unit's text as `TextBlock`s in `Block.READING_ORDER`: a heading is a block of its own and consecutive body lines form one block.
  - **Bounds:** the same defences as `manifest`, through one shared scaffold. That is the pre-scan, a decode budget armed for the read and checked afterwards, the `StackOverflowError` boundary and the page-tree checks.
  - **Caps:** at most `maxParts` pages and about `maxCharacters` characters. A closing note says when either cut the unit short.
  - **Mid-page start:** a unit whose title heading stands below other text on its first page gets a leading note. It names the heading where the unit starts and the heading above it on that page, if there is one.
  - **Refusal:** a unit whose pages are not in the file is refused.
  - **`BlockOptions`:** has no switches yet. Image, table and audio switches arrive with their extraction.
- **`SplitConfig.RULES_VERSION` 1 → 2**, which changes every `hash()`. The same configuration now cuts outline-less documents differently, so a manifest or unit label from the old rules must not be reused.
- **Fixtures:** `PdfFixtures.headingsOnly()` (six pages structured by font size alone, chapter headings scaled by the text matrix, one chapter starting mid-page) and `PdfFixtures.twoColumn()` (two columns written right column first).

### Design decisions

- **The plan's `MANIFEST_VERSION` is `SplitConfig.RULES_VERSION`.** WP-2a.4 already folds a rules version into `split.hash()`, which keys the manifest cache and travels with a unit label. A second constant would have had to be kept in step with it.
- **The fold keeps two refusal styles in one class.** The ingestion path's tests and messages expect an unchecked `UnreadableDocumentException`. The attachment path's tests and wording expect an `IOException` naming the bound. Both are kept, and everything else is shared.
- **Headings are found on the measured pages only**, the first `maxParts`, like the estimates. Past those, a long document has no headings, and the existing warning says those pages were not measured.
- **A mid-page note only for the start of a unit.** By the partition rule, a page belongs to the unit that starts on it, so the end of the previous unit is the only text that can be out of place.
- **The listener reports the right edge as well as the left (`xEnd`)**, which the plan's `(text, y, fontSize)` did not include: column detection needs it to tell columns from indented paragraphs.

### Not done here

- **`docs/document-handover.md`** ("split strategies") is created by WP-2a.6, which is not on this branch. Its `HEADINGS` and `AUTO` text is left for that page.
- **Nothing calls `blocks()` yet.** WP-2b.3 assembles blocks into model content.

```decision-log
| 2026-11-21 | `SplitStrategy.AUTO` resolves outline → headings (font size) → pages; `SplitConfig.RULES_VERSION` 2 | WP-2b.1: documents without an outline are cut at chapters, not page windows; the rules version keeps 2a manifests and labels from being reused | A separate `MANIFEST_VERSION` constant beside `RULES_VERSION` |
```

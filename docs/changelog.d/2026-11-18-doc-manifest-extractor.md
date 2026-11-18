## ✨ feat(documents): content model and `PdfStructureExtractor` — a PDF's units as a manifest (2026-11-18)

**Repo:** EDDI (`feat/doc-manifest-extractor`), work package WP-2a.4 of the PDF documents plan. Pure extraction: no store, no configuration, no wiring, nothing user-visible yet.

### What changed and why

The first step towards handing a chapter of a PDF to a model instead of the whole file: a stateless component that reads a PDF's structure and cuts it into whole-page units, without keeping any of its text.

- **Content model** (`modules/ingestion/extract/document/`): `DocumentManifest` (root ref, SHA-256, page count, strategy used, units, embedded files, warnings), `DocumentUnit` (`u3`, parts `u3.1`, with level, parent, pages and a token estimate), `SplitStrategy` (`AUTO`, `OUTLINE`, `HEADINGS`, `PAGES` — the one definition of the names), `SplitConfig` with a stable `hash()`, `EmbeddedFileInfo`, and the sealed `Block` family (`TextBlock`, `ImageBlock`, `TableBlock`, `AudioBlock`, `Cell`) that ordered extraction will produce later.
- **`PdfStructureExtractor.manifest(pdf, rootRef, split, limits)`** cuts by the outline (top-level entries are units, the level below are their parts) or by fixed page windows. `AUTO` uses the outline when it has two or more chapters and the document has text. `HEADINGS` is handled as `AUTO`, with a warning, until heading detection exists. The page estimate is laid-out characters / 4.
- **`UnitBudgetFitter`** (pure) merges units below `minUnitTokens` into the next one, cuts units above `maxUnitTokens` at page boundaries, and merges the last units past `maxUnits`. The leaf units always cover every page exactly once.
- **Bounded like `PdfTextExtractor`**: the `PdfStreamBudget` pre-scan, a `PdfDecodeBudget` armed on the calling thread for the whole read, the parser's scratch space capped, glyphs per page capped, a deadline checked per page and per outline entry. Encrypted, corrupt, empty or over-budget files throw `UnreadableDocumentException`. `PdfTextExtractor.BoundedTextStripper` went from private to package-private so both extractors measure pages under the same bounds.
- **Hostile outlines**: the outline and the named-destination tree are walked here, iteratively, with an identity set and caps (2,000 outline entries, 50,000 named destinations). PDFBox's own helpers recurse into `/Kids` and climb `/Parent` with no memory of where they have been, so a self-referencing tree loops or ends in a `StackOverflowError`. Outline titles are data: control and formatting characters are removed and titles are cut to 200 code points. Warnings never quote the document.
- **Test fixtures**: `PdfFixtures` (beside `OfficeFixtures`) builds every PDF in memory: outlined, plain, flat chapters, outline cycle, 10,000-entry outline, Flate bomb, hostile titles, named destinations, untidy outline, blank pages. Later packages append their fixtures there.

### Design decisions

- **Leaves partition the pages; nothing overlaps.** A chapter that starts mid-page owns that page and the chapter before it ends on the page above (the plan's "on both sides" would contradict its own "exactly once" acceptance). Pages before the first chapter form a "Front matter" unit; below the top level they join the first part. Entries are ordered by page, and one entry per starting page is kept.
- **Two outline levels become units.** Deeper levels do not. An outline with a single top-level entry is replaced by that entry's children.
- **A document with no text is cut into page windows and is never merged.** Otherwise every estimate is zero and the whole file would become one unit.
- **`SplitConfig.hash()` includes a rules version**, so manifests cached under 2a rules are not reused once `HEADINGS` stops meaning `AUTO`.
- **Not yet reconciled with WP-0.1**: `DocumentsConfig` holds the strategy as a string and `EffectiveDocumentsConfig.Split.Strategy` is a nested enum there. A later package maps them onto this `SplitStrategy`.

```decision-log
| 2026-11-18 | PDF manifest leaf units partition the pages exactly once: a chapter starting mid-page owns that page; pages before the first chapter are a "Front matter" unit | WP-2a.4 plan: "includes that page on both sides" contradicted "covering 1..pageCount exactly once at leaf level" | Overlapping units sharing the boundary page (fan-out would process that page twice) |
```

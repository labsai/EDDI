## 🔒 fix(documents): cap the cross-reference entries PDFBox stores for uploaded PDFs (2026-11-23)

**Repo:** EDDI (`fix/pdf-xref-map-cap`)

### What changed and why

PDFBox 3.0.8 keeps every cross-reference entry a file declares in `XrefTrailerResolver`'s maps, with no bound. A
PDF of about 150 bytes, one cross-reference stream `<</Type /XRef /W [0 0 0] /Size 2000000000 /Length 1>>` that
`startxref` points at, makes PDFBox call `setXRef` two billion times. Through
`PdfTextExtractor.extract(bytes, ExtractionLimits.defaults())` it exhausted a 256 MB heap in about three seconds. Any
user who can upload a document or attach a PDF could take a node down with it. The stream decode budgets did not
help, because nothing is decoded.

- **New `BoundedPdfLoader`** (`modules/ingestion/extract`). It opens a PDF the way `Loader.loadPDF(byte[], …)` does
  (a `RandomAccessReadBuffer`, lenient parsing, no password). Its `PDFParser` subclass replaces the parser's
  `xrefTrailerResolver` with a counting one that throws past **500,000** entries. Every entry, whether from a classic
  table, a cross-reference stream or a lenient repair, goes through `setXRef`, so the count is exact. The exception
  is deliberately not an `IllegalArgumentException`, because PDFBox catches that around `setXRef`. It becomes an
  `UnreadableDocumentException`: *"This PDF declares more than 500000 objects…"*.
- **Both in-memory loads use it:** `PdfTextExtractor` (document ingestion) and `AttachmentTextExtractor`, which
  `PdfReaderTool`, `readAttachment` and the attachment forwarder read through. No `Loader.loadPDF` call remains in
  `src/main/java`. The class is public because the attachment extractor is in another package.
- **Tests:** `BoundedPdfLoaderTest` sends the 150-byte bomb through the loader and through `PdfTextExtractor`. It
  also has an exact-count test: a hand-written classic table with five in-use entries loads at cap 5 and is refused
  at cap 4, in both strict and lenient mode. `AttachmentTextExtractorTest.CrossReferenceBomb` covers all three of
  that extractor's public PDF methods. Each bomb test asserts the specific message, not only the exception type.
- **Docs:** [security](../security.md#uploaded-pdfs-cross-reference-entries).

### Design decisions

- The counting resolver is lifted from the windowed PDF reader on `feat/doc-windowed-pdf` (WP-4.2, not merged).
  The constant name and the message are kept identical, so that branch can drop its private copy and call
  `BoundedPdfLoader.load(source, cache, max, lenient)` with its file-backed source and strict flag. Whichever branch
  lands second adopts the other's class.
- The cap counts stored entries rather than pre-scanning the cross-reference sections. Two reviews of WP-4.2 showed
  that a regex pre-scan could be bypassed in many ways.

```decision-log
| 2026-11-23 | Every PDF read from untrusted bytes goes through `BoundedPdfLoader` (500,000 cross-reference entries), never `Loader.loadPDF` | PDFBox's cross-reference map is unbounded; a 150-byte xref stream OOMs a 256 MB heap | A regex pre-scan of the cross-reference sections (bypassable) |
```

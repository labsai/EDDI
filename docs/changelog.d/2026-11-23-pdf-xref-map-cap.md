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
  `xrefTrailerResolver` with a counting one that throws past **500,000** entries. Every entry from a classic table or
  a cross-reference stream goes through `setXRef`. The resolver counts calls, which is at least what PDFBox stores (a
  hybrid file's duplicates count twice), and restarts the count when a lenient rebuild clears the table, so a file
  whose table broke partway is not counted twice and refused where `Loader` repaired it. The exception
  is deliberately not an `IllegalArgumentException`, because PDFBox catches that around `setXRef`. It becomes an
  `UnreadableDocumentException`: *"This PDF declares more than 500000 objects…"*.
- **Object streams during a lenient rebuild are counted too.** A file without a usable `startxref` is rebuilt by
  scanning it, and `BruteForceParser.bfSearchForObjStreams` then puts every object an object-stream header lists
  straight into the resolved xref map, past `setXRef`. The counting resolver hands that map out behind a guard that
  refuses a new key once it holds the cap (`getXrefTable()`), so a header listing millions of numbers is refused like
  any other over-cap file. `BoundedPdfLoaderTest` has a hand-written file with no `startxref` and an object stream
  listing 200 objects: refused at cap 50, loaded at cap 5000.
- **Object-stream headers are capped before they are read.** `PDFObjectStreamParser` reads a header into a map of
  up to `/N` entries before the first one reaches the guarded table, in a rebuild (`readObjectNumbers`) and on the
  ordinary path (`parseAllObjects`, whose objects PDFBox then keeps). Five million short entries decode to under
  50 MB, inside the decode budgets, but need hundreds of MB of heap. The loader now swaps the parser's `COSDocument`
  for one that creates every stream it reads from the file as a counted stream: opening its decoded view, which is
  where the object-stream parser starts, adds the stream's `/N` (read as PDFBox reads it, so an indirect `/N` counts)
  to a per-document total, once per stream, and refuses the file past the same cap. Tests: an object stream declaring
  `/N 200` whose header lists one object is refused at cap 50 and loads at 5000; two streams of `/N 30` load at cap
  60 and are refused at 59; an intact file whose catalog sits in an object stream, read through a cross-reference
  stream, loads with `/N 1` and is refused with `/N 60` at cap 50, strict and lenient, direct and indirect `/N`; and
  `/N 1000000` is refused through `PdfTextExtractor` at the default cap.
- **Both loads from a byte array use it:** `PdfTextExtractor` (document ingestion) and `AttachmentTextExtractor`, which
  `PdfReaderTool`, `readAttachment` and the attachment forwarder read through. No `Loader.loadPDF` call remains in
  `src/main/java`. The class is public because the attachment extractor is in another package.
- **Tests:** `BoundedPdfLoaderTest` sends the 150-byte bomb through the loader and through `PdfTextExtractor`. It
  also has an exact-count test: a hand-written classic table with five in-use entries loads at cap 5 and is refused
  at cap 4, in both strict and lenient mode. A table that breaks after five entries is rebuilt and still loads at
  cap 5. `AttachmentTextExtractorTest.CrossReferenceBomb` covers all three of
  that extractor's public PDF methods. Each bomb test asserts the specific message, not only the exception type.
- **Docs:** [security](../security.md#uploaded-pdfs-cross-reference-entries).

### Design decisions

- The counting resolver is lifted from the windowed PDF reader on `feat/doc-windowed-pdf` (WP-4.2, not merged).
  The constant name and the message are kept identical, so that branch can drop its private copy and call
  `BoundedPdfLoader.load(source, cache, max, lenient)` with its file-backed source and strict flag. Whichever branch
  lands second adopts the other's class.
- The cap counts entries as PDFBox stores them rather than pre-scanning the cross-reference sections. Two reviews
  of WP-4.2 showed that a regex pre-scan could be bypassed in many ways.
- **Residual, not fixed here:** a lenient rebuild of a damaged file collects the `N 0 obj` markers it finds in
  PDFBox's own maps before the resolver sees them. That costs a multiple of the file's size, which the upload size
  limit bounds, not the two-billion blow-up a declared `/Size` allowed.

```decision-log
| 2026-11-23 | Every PDF read from untrusted bytes goes through `BoundedPdfLoader` (500,000 cross-reference entries), never `Loader.loadPDF` | PDFBox's cross-reference map is unbounded; a 150-byte xref stream OOMs a 256 MB heap | A regex pre-scan of the cross-reference sections (bypassable) |
```

## ✨ feat(documents): embedded PDF images listed and decoded within hard bounds (2026-11-22)

**Repo:** EDDI (`feat/doc-image-collector`), work package WP-2b.2a of the PDF documents plan. It builds on WP-2b.1 (`feat/doc-positioned-text`), on WP-0.1 (`feat/doc-config-foundation`) for `DocumentSettings`, and on WP-4.1a (`feat/doc-blob-backends`) for its `ChildJvm` test helper. Pure extraction: no store, no memory, no CDI, no wiring. Nothing calls it yet, so nothing changes for users.

### What changed and why

- **`PdfImageCollector`** (`modules/ingestion/extract/`) lists the images a range of pages draws, without decoding any.
  - Each draw is recorded with its page, its position from the crop box's top-left (as text positions are), its size on the page, an id `i-<page>-<n>` in drawing order, and the handle of the stream that holds its pixels.
  - It interprets only `q`, `Q`, `cm`, `Do` and inline images. Text, fonts and Type 3 glyphs never run.
  - An image XObject is recognised at the COS level, so PDFBox builds no image object for it. An inline image is kept as its parameters and raw bytes, because PDFBox decodes an inline image as soon as one is built.
  - It is built on `PDFStreamEngine`, not on `PDFGraphicsStreamEngine` as the plan said: the graphics engine also interprets text and loads fonts, and nothing here needs them.
- **Bounds on the page program.**
  - Forms are followed 8 deep, and a form that is already being drawn is not drawn again.
  - One operator count spans the whole collection, forms included however often they are drawn. That is what stops a shallow fan-out of forms asking for 10^8 draws.
  - The deadline is checked every 1,024 operators and on every form drawn. A form PDFBox cannot read is skipped, as PDFBox skips it.
  - At most 10,000 draws are recorded.
  - `q` nests at most 64 deep within one page or form, and a `Q` closes an ignored `q` first. Each form keeps its own count.
  - Inline images keep at most 1 MiB each and 16 MiB per collection.
  - Past the operator or draw cap the collection stops and says it was cut. Past the deadline the document is refused.
- **`ImageDecodeGuard`** checks what an image declares before PDFBox builds a raster or decode buffer for it. This is the gap the two existing bounds leave: `PdfStreamBudget` skips image streams, and `PdfDecodeBudget` counts what a filter writes, not the raster PDFBox sizes from the dictionary.
  - **Read as PDFBox reads it:** an inline image's abbreviated keys first (`/W` before `/Width`, `/F` before `/Filter`), and an XObject's full keys only. A device colour space counts as the resources' `/Default*` space that replaces it.
  - **Pixels:** `eddi.documents.images.max-pixels` (40 MP) caps each of these:
    - the image;
    - every mask it carries (`/SMask`, a stream `/Mask`);
    - the masked image PDFBox builds at the larger width and the larger height of the two;
    - a CCITT image's buffer, which PDFBox sizes from `/DecodeParms /Columns`.
  - **Budget:** decoded samples plus those buffers and rasters, at the subsampling decoding will use, against what the thread's decode budget has left. A colour-key mask is costed too.
  - **Components:** read from the colour space. More than 32 is refused, and 4 is assumed where the colour space does not say.
  - **Arithmetic:** exact `long` arithmetic, so an overflow is refused rather than wrapped.
- **A JPEG is also checked against its own frame header.** That header is what the JPEG decoder sizes its raster from. It must agree with the dictionary and pass the same checks. An inline JPEG is checked at full size, because PDFBox decodes it without subsampling.
- **Page content is charged to the decode budget before the collector parses it**, as the text path charges it. An unfiltered form is charged on every draw.
- **`ImageNormalizer`** turns one placement into bytes a model can take:
  - JBIG2 and JPEG 2000 are refused before PDFBox is asked anything.
  - A JPEG passes through byte for byte only when it would look the same anywhere. That means an 8-bit baseline or progressive JPEG of one or three components, in plain gray or RGB, with no masks, no `/Decode` array, no decode parameters and no stencil flag. Its frame header must agree with the dictionary, and it must fit the edge and byte limits.
  - Everything else is decoded subsampled to near `maxEdgePx` (1,568), scaled to fit, and encoded as JPEG if it was one, else PNG. An image over the byte cap is shrunk up to three times and otherwise refused.
  - A bad image is a `Refused` result, so it costs that figure, not the document. The decode budget and the deadline still refuse the whole document, as for every PDF read.
  - It decodes only under an armed budget on the calling thread.
- **`PdfDecodeBudget`** gains the package-private `remaining()` and `requireNotExceeded()`.
- **`DocumentSettings.imagesMaxPixels()`** reads `eddi.documents.images.max-pixels`. It is in `application.properties`, commented, and in the configuration reference.
- **Tests** run against small PDFs generated in `PdfFixtures`: `withImages`, `imageBomb*`, `formCycle`, `formChain`, `formFanOut`, `nestedSaves`, `inline*`, `operandFlood`, `formAfterSaves`, `malformedForm` and `patchFrame`. They cover:
  - positions and order, forms and the shared logo;
  - pass-through bytes equal to the embedded JPEG, and the pass-through allow-list;
  - CMYK and mislabelled JPEGs, and downscaling;
  - every refusal and the arithmetic;
  - each way a size can be read differently from how PDFBox reads it: inline key order, mask scaling, frame headers, `/Default*` colour spaces and CCITT `/Columns`.

  They also show that the raw pre-scan lets an image-stream Flate bomb through and the decode budget refuses it. Two child-JVM tests (`-Xmx256m`) decode 10,000 images of 128 × 128 and survive two million unclosed `q` operators. Every guard was mutation-checked: each bound removed in turn, 38 mutations in all, and a test fails for each.
- **Docs:** `docs/security.md` → PDF Image Bounds; `docs/configuration-reference.md` → Documents.

### What's next

WP-2b.2b wires this into `PdfStructureExtractor.blocks`: logo and size filters, captions, `IMAGE` derived blobs and JBIG2. It also makes the `images.*` designer keys live.

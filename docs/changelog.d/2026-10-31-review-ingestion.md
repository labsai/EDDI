## 🐛 fix(ingestion): resource limits in HTML conversion, crawling, extraction and the RAG pipeline (2026-10-31)

**Repo:** EDDI (`fix/review-ingestion`)

Fixes from the external code review and CodeRabbit for the ingestion and RAG modules. Almost all are
denial-of-service shapes: compact input from a crawled page or an uploaded file that made a worker do
work out of all proportion to its size before any limit was consulted.

### What changed and why

- **Nested HTML tables were exponential** (`HtmlToMarkdownConverter`). Rows and cells were found with
  descendant selectors, so a table also rendered the rows of every table nested inside it, and again
  inside the cell holding them; cells restarted the depth count at zero, so `MAX_DEPTH` never applied.
  Twenty nested tables did not finish. A table now reads only the `tr`s it owns (its children and those
  of its own `thead`/`tbody`/`tfoot`) and a row only its child cells, with the depth carried through.
  The same doubling existed for a `summary` or `figcaption` nested below a `details`/`figure`; those are
  now taken from direct children only. A per-conversion work budget (`Walk`) backs this up: at most
  1,000,000 elements visited, and the walk stops writing once the text written is certain to be
  truncated by the caller's cap; whitespace-only text between tags is not charged, or an indented page
  with a small cap stopped before its first paragraph. Ordinary tables render as before (checked
  differentially against the previous converter); a table with several `tbody`s, a `tfoot` or a
  multi-row `thead` now keeps those rows instead of silently dropping them, and a table whose `thead`
  is empty takes its first body row as the header.
- **Trailing newlines in a `<pre>` were trimmed quadratically** — one `substring` per newline. Now one
  backward scan and one copy.
- **Sitemap `<loc>` text was copied once per nesting level** (`WebCrawler.collectText`): only a leaf
  `<loc>`/`<link>` counts now, and entries longer than the protocol's 2,048 characters are dropped.
  Links longer than 8,192 characters are not followed. The unused `BYTE_ORDER_MARK` constant is now the
  one the text-sitemap reader compares against, in place of a unicode escape.
- **Exclude globs no longer compile to a backtracking regex** (`UrlPattern`). A pattern with several
  wildcards could take combinatorial time on a near-miss path from a crawled page; matching now steps
  the set of reachable pattern positions per character — pattern length × path length, always. It
  steps by code point, as the regex did, so `?` still matches one supplementary character. The one
  difference: `*`, `**` and `?` now match a line terminator (a decoded `%0A` in a path), which the
  regex's `.` did not, so such a path can no longer slip past an exclusion.
- **Spreadsheet cells were copied in full before the character limit was checked** (`MarkdownTable`).
  One shared string referenced from 64 header cells rendered hundreds of MB. Each cell is cut to what
  can still fit before it is normalised and escaped.
- **`maxSegmentsPerRun` was checked after paying for the document that crossed it**
  (`IngestionPipeline`). It is now checked after splitting and before embedding: a document that would
  cross it waits for the next run (stamped as looked-at so it is not counted as missing), and a document
  larger than the whole budget is reported as failed instead of embedded in part.
- **Ownership is re-checked after the embedding provider answers**, before `addAll`, so a run reaped
  during a stalled provider call can no longer republish a deleted file's chunks.
- **Chunk settings are bounded**: `chunkSize` 1–32768 and `chunkOverlap` ≤ half of it are enforced in
  `RagConfiguration.validate()`, and both ingestion paths (`IngestionPipeline`, `RagIngestionService`)
  read them through `chunkSizeOrDefault()`/`chunkOverlapOrDefault()`, which clamp a configuration
  stored before the check. An imported archive's out-of-range values are clamped the same way
  (`RagConfiguration.clampChunkSettings()`, from `RestImportService` on create and update) rather than
  failing the import, and the Manager's chunk-size slider now pulls the overlap down to half the new
  size, so the default overlap of 64 no longer makes a chunk size of 64 unsaveable. The crawl pipeline's fallback for an explicit `null` was 1000/100 and is now
  the documented 512/64; `RagIngestionService` threw on an explicit `null`.
- **PDF**: `PdfStreamBudget` remembered neither the next `endstream` nor the last `obj`, so a file of
  unterminated `stream` keywords was scanned in quadratic time — both are now carried forward.
  `PdfDecodeBudget` refuses a PNG/TIFF predictor whose declared row exceeds 4 MB before PDFBox allocates
  its two row buffers. `PdfTextExtractor` now charges the decode budget for the page content PDFBox
  reads around the counted filters: a lone `/FlateDecode` `/Contents` stream (which PDFBox 3.0.8 inflates
  with a `FlateFilterDecoderStream` it builds itself — confirmed in the bytecode) is decoded once through
  the counted filter first, and every reference to an unfiltered content stream is charged its length.

### Design decisions

- The table fix is structural (ownership + depth) and the work budget is a backstop, not the fix: the
  budget alone would have turned the exponential case into a silently truncated document.
- Over-budget documents are deferred, not truncated: a partially embedded document recorded as ingested
  would hide its tail until the page next changed.
- Not changed: the review suggested keeping an upload source's files when `forgetSource` fails to
  remove its chunks. `RagSourceIngestionServiceTest.survivesAVectorStoreThatRefuses` pins the opposite
  as a deliberate decision — the configuration is already saved, so keeping the files leaves the
  operator no way to retry — and nothing would ever retry. A durable retirement/retry record is the
  real fix and is left open.

**Files:** [`HtmlToMarkdownConverter.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/HtmlToMarkdownConverter.java),
[`WebCrawler.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/WebCrawler.java),
[`UrlPattern.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/UrlPattern.java),
[`MarkdownTable.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/MarkdownTable.java),
[`PdfStreamBudget.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfStreamBudget.java),
[`PdfDecodeBudget.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfDecodeBudget.java),
[`PdfTextExtractor.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfTextExtractor.java),
[`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`RagConfiguration.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/RagConfiguration.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`rag-editor.tsx`](../../ui/manager/src/components/editors/rag-editor.tsx),
[`rag.md`](../rag.md), and the matching tests.

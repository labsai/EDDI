## feat(documents): file-backed, windowed PDF reading and a per-instance work directory (2026-11-23)

**Repo:** EDDI (`feat/doc-windowed-pdf`). This is work package WP-4.2 of the PDF documents plan. It depends on WP-4.1b (`feat/doc-streaming-store`, `materialize`), WP-2a.4 (#1475, the manifest extractor) and WP-2b.1 (#1501, positioned text and blocks, whose extractor these overloads extend). Nothing user-visible: no code path reads a large file yet. The document jobs (WP-4.3a/b) use it, and large uploads (WP-4.5) switch it on.

### What changed and why

A PDF too large for memory can now be read from disk into the same manifest and the same blocks as an uploaded array, with the heap bounded by one window of pages instead of by the file.

- **`PdfWindowReader`** (`modules/ingestion/extract`) runs an operation on pages `first..last` of a PDF file. Each window opens the document afresh through `RandomAccessReadBufferedFile` with a mixed stream cache that spills into a scratch directory, installs a resource cache that keeps nothing, and closes the document in `finally`. PDFBox's memory settings bound only the stream cache, not the parsed object graph or the cached fonts and images, so re-opening is what keeps those from adding up over thousands of pages. Each window arms its own `PdfDecodeBudget` on the calling thread, has its own deadline, and walks the page tree ahead of PDFBox. The budget is checked again after the operation, however it ended.
- **No brute-force repair of a large file.** Above 20 MB (the default `eddi.attachments.max-size-bytes`) a file is parsed strictly (`PDFParser.parse(false)`). A file whose cross-reference table needs PDFBox's whole-file rebuild is refused with a message asking for a re-saved copy. Smaller files are repaired as before.
- **`PdfStreamBudgetFile`**: the compression-bomb pre-scan over a file, through one 1 MiB buffer and positional reads. It is not a memory mapping, which on Windows would keep the file from being deleted. `PdfStreamBudget`'s algorithm now runs over a `long`-indexed `Source`, so the array and file scans are one piece of code and decide alike.
- **`PdfStructureExtractor.manifest(Path, …)` and `blocks(Path, …)`** read through the window reader. 2b.1's single-open internals became two accumulators that hold plain data only: `PageScanner` (token estimates, headings, columns) and `BlockRead` (a unit's blocks, with the character allowance and the notes carried across windows). One pass numbers the pages, one pass runs per window, and one reads the outline when the cut needs it. The SHA-256 is computed as the file streams. The array overloads call the same code over one open document.
- **`DocumentWorkDir`** (`engine/documents`): where a large file is copied, and how much disk that may take. Each instance works only in `<work-dir>/node-<nodeId>` and holds a file lock in it. At startup it empties that directory, and nobody else's. A second live process with the same node id gets `node-<nodeId>-<bootId>` and deletes nothing. Per-boot directories whose lock is free are cleaned up. A `long` byte budget with an interruptible, timed wait reserves bytes before `IAttachmentStore.materialize` copies the first one, and refuses at once a request that could never fit. A copy larger than its reservation is deleted. Directories are `0700` and files `0600` where POSIX permissions exist.
- **Properties** (in `application.properties`, `docs/configuration-reference.md` and `DocumentSettings`): `eddi.attachments.large.work-dir` (default `java.io.tmpdir/eddi-documents`), `eddi.attachments.large.work-dir-max-bytes` (4 GiB), `eddi.documents.structure.window-pages` (50) and `eddi.documents.structure.window-deadline-seconds` (90).
- **Docs:** `docs/attachments-guide.md` has a new section, "Large documents: how they are read". The plan puts it in `docs/document-handover.md`, which WP-2a.6 creates and which is not on this branch.

### Design decisions

- **The strict-parse threshold is a constant, not a property.** `PdfWindowReader.DEFAULT_LENIENT_PARSE_MAX_BYTES` is 20 MB, carried in `Options`. A file at or below the memory guard may be repaired in memory on today's path anyway. The plan names no fifth property.
- **The decode budget and the deadline are per window,** as the plan requires. `ExtractionLimits.maxDuration()` is not used on the file path: the job that reads a large document bounds the whole run (`max-job-minutes`, WP-4.3b). The raw-byte scan gets one window's deadline per 64 MB of file.
- **Node id, plus a lock.** A stable node id lets a restarted node clean up what its previous run left. The lock makes a duplicated node id safe instead of destructive.
- **Platform Operator, MCP, UI: no change.** Nothing reads a large file yet, and no endpoint, tool or designer key is added. `operator-revision.json` is not bumped. The work-directory sizing belongs in the operator text with WP-4.6, the package that adds the Helm ephemeral-storage values.

```decision-log
| 2026-11-23 | A large PDF is read a window of pages at a time, re-opened per window with a no-op resource cache, the decode budget and deadline per window, and files above 20 MB parsed strictly (no brute-force xref rebuild) | WP-4.2 windowed PDF (D§13.2) | One open of the whole document with a bounded stream cache; lenient parsing of large files |
| 2026-11-23 | The document work directory is `<work-dir>/node-<nodeId>`, locked while in use; a duplicate node id falls back to a per-boot directory and sweeps nothing | WP-4.2 work directory | A random per-boot directory (leftovers never swept), or the node id alone (a duplicate id sweeps a live peer's files) |
```

### Tests

- `PdfLargeFileHeapTest`: the acceptance gate. A 1,200-page, 40 MB PDF is written to disk and read to its manifest, every page through 50-page windows, in a child JVM with `-Xmx256m`. It is read again with `-Xmx64m`, less heap than the file: reading the same file through `Files.readAllBytes` and the array overload runs out of memory there, and at 48 MB too.
- `PdfStructureExtractorWindowedTest`: the manifest and every unit's blocks equal the array overloads' for twelve fixtures (outline, headings, columns, scans, hostile titles, named destinations, all four strategies) at windows of 1 to 50 pages. It also covers the measured-page cap and averaging, the character allowance and the page cap across windows, the decode budget per window (a document refused as a whole is read in windows), the pre-scan, the window deadline and duplicate pages.
- `PdfWindowReaderTest`: window contents, the document and file closed afterwards, the no-op resource cache, the per-window budget, the post-operation budget check, strict parsing above the threshold versus repair below it, deadline, page tree, encryption, and a file that is not a PDF.
- `PdfStreamBudgetFileTest`: refuses what the array scan refuses at windows of 16 B to 1 MiB, and a backward search stays linear.
- `DocumentWorkDirTest`: own directory only, peers and the shared root untouched, a duplicate node id, abandoned per-boot directories, and the budget (refusal, wait then proceed, timeout, interrupt, release once, reserve before copy, oversize copy, store failure).

Mutants run, 17 of 18 killed:

- Killed: no resource cache; always lenient; page-tree check skipped; budget unarmed; post-operation check removed; deadline ignored; window or block-window bounds off by one; pre-scan skipped; window boundary read stale; sweep the base directory; lock ignored; oversize refusal, size check and wait condition broken; abandoned directory left; the file read into memory.
- Survived: the file range stream reading past its `endstream`. Every filter the scan decodes stops at its own end-of-data marker, so this is close to an equivalent mutant.

### What's next

WP-4.3a/b: the document job reads through `DocumentWorkDir.materialize` and `manifest(Path, …)`/`blocks(Path, …)` per window, and stores each window's results as `MANIFEST_PART`/`TEXT_PART`. 2b.2a's image collector, once wired in 2b.2b, should run inside `PdfWindowReader.withWindow` for large files.

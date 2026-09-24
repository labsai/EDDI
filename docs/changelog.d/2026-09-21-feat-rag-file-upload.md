## 📄 feat(rag): ingest uploaded PDF, Word, Excel, PowerPoint and text files (2026-09-21)

**Repo:** EDDI (`feat/rag-file-upload`)

### What it adds

A second kind of ingestion source. `type: "upload"` holds files EDDI stores on the knowledge base's
behalf; running the source extracts their text and embeds it, through the same pipeline, state store
and reconciliation a crawl uses. In the Manager, a source of that type shows a drag-and-drop zone
instead of the crawl settings, with per-file progress and the list of what the source holds.

Formats: PDF (PDFBox), Word, Excel and PowerPoint (`.docx` / `.xlsx` / `.pptx`), plain text, Markdown,
JSON, XML, YAML, CSV, TSV and HTML. Excel becomes one Markdown table per sheet, PowerPoint one section
per slide, Word keeps its headings — tabular and sectioned content survives chunking only if it keeps
the header or heading a retrieved passage would otherwise have lost.

### Design decisions

**The files are kept, not just their embeddings.** That is what makes this a *source* rather than a
one-way import: re-running after a model or chunk-size change re-ingests from what is stored, a purge
stays recoverable, and deleting a file removes its vectors through the ordinary reconciliation.
Embedding on upload and keeping nothing would make each of those "ask the operator to upload two
hundred files again".

**No Apache POI.** It reads these formats and much more, at seven extra jars and ~14.5 MB, built on
reflection and with a long history of parser CVEs — measured against the constraint that EDDI's
dependencies stay small. For text out of a handful of known parts, the JDK's own `java.util.zip` plus
StAX is the smaller surface and the one whose limits can be stated exactly. PDFBox was already a
dependency (3.0.8, current).

**Every bound is explicit, because the file is not the operator's data.** 100,000 characters per
document (`settings.maxContentLength`, shared with the crawl), 500 parts, 5,000 rows × 64 columns per
sheet, 64 MB decompressed per archive — counted across every entry the reader walks over, since moving
to the next ZIP entry inflates the rest of the current one and an entry nobody wants is otherwise the
cheapest place to hide a bomb. DTDs and external entities off (billion-laughs); an archive naming the
same part twice refused outright, since two entries under one name let two readers disagree about the
contents.

**Content decides the format, not the name.** `.docx`, `.xlsx` and `.pptx` are all ZIP archives, so
neither the file name nor the browser's MIME type distinguishes them — a spreadsheet saved under a
`.docx` name would have gone to the Word extractor and been refused as corrupt. The extension is
consulted only for text formats, which carry no signature. A legacy `.doc`/`.xls`/`.ppt` is named as
such, with the fix, rather than reported as unreadable.

**A file's identity is its name.** Re-uploading `handbook.pdf` replaces it — blob, ingestion-state row
and vectors all key on an id derived from the name — so the corrected version supersedes the old one
everywhere at once. A generated id would leave last quarter's handbook retrievable beside this
quarter's with nothing to say which is current.

**Change detection uses the file's bytes, not its extracted text.** An unchanged 20 MB manual costs one
metadata query per run rather than a download and a full parse, and improving an extractor does not
silently re-embed an entire knowledge base.

**One HTTP request per file, not one per batch.** A 200 MB batch that fails three quarters of the way
through would otherwise lose what had already arrived with nothing to say which files those were. Each
file gets its own progress bar (`XMLHttpRequest`, since `fetch` cannot report upload progress) and its
own error, and the server's sentence — "This PDF is encrypted" — is what the operator sees.

**Deleting a file removes its vectors immediately**, not at the next run. A source with no cron has no
next run, so deferring it would mean an operator is told a document is gone while agents keep answering
from it. Where the vector store cannot delete by metadata, the response says so rather than reporting a
clean success.

**Removing an upload source takes its content with it.** Dropping it from `sources[]`, changing its
`type`, or deleting the knowledge base removes the files *and* the vectors they produced. The first
draft deleted only the files, which left every chunk retrievable and unreachable: no endpoint lists
them, because the source they belong to is gone.

**Deleting a file takes the source's run claim.** Checking for a run first is not enough — one that
starts between the check and the delete re-embeds the file and clears the tombstone the delete wrote.

### Bugs found and fixed while testing

**An upload source could never delete its last documents.** `reconcileDeletions` refused to conclude
anything when a run produced no usable document and learned nothing definitive — a guard written for
crawls, where "nothing came back" usually means the site was unreachable. An upload source that lists
an empty store has learned something definitive: the operator deleted the files. The guard moved into
the crawl branch (`learnedSomething`), where it belongs; the pre-existing crawl tests still pin it.

**A reordered PowerPoint deck came back in creation order.** `r:id` and `id` share a local name on
`<p:sldId>`, so reading the attribute by local name returned the slide's own number instead of the
relationship — the index resolved to nothing and the fallback (part numbering) ran. Part numbers
survive a reorder, so the deck read correctly right up until somebody moved a slide.

**An encrypted PDF was reported as corrupt.** PDFBox refuses one of those with
`InvalidPasswordException` rather than by opening it and answering `isEncrypted()`, so the only branch
that mentioned a password never ran, and the operator was told their working file was broken.

**A 25 MB file — the default limit — could not be uploaded at all.** `quarkus.http.limits.max-body-size`
was 25M, so the request was refused with a bare 413 before the code that knows what the limit is could
say anything. Raised to 60M, with the configurable ceiling held at 50 MB and both ends commented.

### Files

**Backend — extraction** (`src/main/java/ai/labs/eddi/modules/ingestion/extract/`): `DocumentExtractors`
(registry + content-based type resolution), `DocumentTextExtractor`, `ExtractionLimits`,
`PdfTextExtractor`, `WordTextExtractor`, `ExcelTextExtractor`, `PowerPointTextExtractor`,
`PlainTextExtractor`, `CsvTextExtractor`, `HtmlDocumentExtractor`, `OpenXmlPackage` (bounded ZIP+StAX),
`MarkdownTable`, `Extraction`, `UnreadableDocumentException`.

**Backend — storage** (`modules/ingestion/files/`): `IIngestedFileStore`, `IngestedFileIds`,
`IngestedFileService`; `MongoIngestedFileStore` (GridFS), `PostgresIngestedFileStore` (`bytea`, upsert
on `(source_key, file_id)`), wired in `DataStoreProducers`.

**Backend — pipeline and API**: `IngestionPipeline` (upload branch, `SourceRun`, `forgetDocument`,
`learnedSomething`), `IngestionSource` (`TYPE_UPLOAD`, `UploadSource`), `RagSourceIngestionService`
(files deleted with their source), `IRestRagIngestion` / `RestRagIngestion` (three endpoints),
`ContentHashes.sha256Bytes`.

**Manager**: `ingestion-files-panel.tsx` (drop zone, per-file progress, file list, delete),
`ingestion-sources-panel.tsx` (Website/Files chooser, conditional fields), `lib/api/ingestion-sources.ts`,
`hooks/use-ingestion-sources.ts`, 22 new i18n keys across all 11 locales, MSW handlers, refreshed
`openapi-operations.json`.

**Tests**: 40 extraction cases (zip bombs in both a wanted and a skipped entry, billion-laughs, a
duplicate part, an encrypted PDF, a scan with no text layer, a misnamed spreadsheet, a legacy Office
file, a reordered deck, UTF-16, and text whose first bytes look like an image), a 10-case
`IngestedFileStoreContract` run against in-memory, MongoDB and PostgreSQL, 8 `IngestedFileIds` cases,
16 `IngestedFileService` cases, 13 pipeline cases for the upload path, 6 source-removal cases, 14 REST
cases, and 10 Manager cases.

Three of them are the ones that matter: a real `.docx` is stored, read by the real extractor, split by
the real chunker and answers a real retrieval — and stops answering once it is deleted. Every unit test
on the way there can pass while that one fails, which is exactly what happened to the draft this
feature builds on: ingestion and retrieval keyed on different names, and nothing noticed, because no
test ever performed a retrieval after an ingest.

**Docs**: `docs/rag.md` — the two source types, the upload block, what can be read, every limit and why,
the file endpoints, and what a purge, a source removal and a ZIP export each do to stored files.

---

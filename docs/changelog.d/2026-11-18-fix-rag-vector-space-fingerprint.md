## fix(rag): a knowledge base knows which vector space it holds; re-embed; hidden text; safe errors (2026-11-18)

**Repo:** EDDI (`fix/rag-vector-space-fingerprint`)

Findings H1, H4, H5, H6 of the November 2026 code review (live: RG1, RG2, RG3 and the chunk/DDL points of H5).

### What changed and why

**The embedding space is recorded and enforced (H1, H5, RG2).** Changing the embedding model on an external
store (pgvector, MongoDB Atlas, Elasticsearch, Qdrant, Chroma) used to keep the old vectors silently: files
reported "unchanged", old and new vectors mixed, and a change of vector size surfaced as a 502 carrying the
raw `PSQLException` and the failing `INSERT`. Now:

- `VectorSpace` (provider, model or Azure deployment, Gemini `taskType`, vector size) is recorded in EDDI's own
  database, next to the ingestion state, for every store type. The **model** is recorded per store location and chunk
  tag, i.e. per knowledge base (retrieval and deletion are filtered by the tag, so two knowledge bases on one table can
  hold different models); the **vector size** per location, and for pgvector it is checked against the table's real
  column. A pgvector-only metadata table was rejected: no other store has an equivalent place and a record kept two
  ways must be kept in step. `VectorSpaceGuard` refuses search, `/ingest` and source runs when the configured space
  differs, with a message naming both spaces and the way out (`409`, `code: VECTOR_SPACE_MISMATCH`; at retrieval the
  agent gets no context from that knowledge base and the `rag:trace:*` entry says why).
- A knowledge base with no record (created by an older version) adopts the configured space on first use, under its
  own key, and logs it once. It cannot be verified, so one whose model was changed before the upgrade stays as wrong as
  it was; it keeps working.
- The vector size is measured: the first embedding of an ingestion is checked against the table's real column
  (pgvector) or the recorded size before anything is written; a query's embedding is checked too. A run now **stops**
  at the first rejected credential, unknown model, space mismatch or store (`SQLException`) failure instead of calling
  the provider once per remaining document (H5).
- `GET /ragstore/rags/{id}/vector-space` shows the recorded and the configured space.
  `POST /ragstore/rags/{id}/reembed` checks first (one embedding call; the store must take the new size),
  claims every source, marks the knowledge base "rebuilding" for every node, removes its vectors (a pgvector
  table that changes size is dropped whole, atomically), records the new space and starts a run of every enabled
  source (follow them in each source's run history). Text sent through `POST /ingest` is not kept by EDDI and
  cannot be rebuilt; the answer lists it under `notReEmbedded`. The full contract is the table in
  [`rag.md`](../rag.md#changing-the-embedding-model).
- `PUT /ragstore/rags/{id}` still saves a changed model but answers `200` with
  `{"warnings": [...], "code": "EMBEDDING_SPACE_CHANGED"}` when the store location stays. The `docs/rag.md`
  passage that promised automatic re-embedding on a model change is corrected (it was true for in-memory stores
  and ingestion sources only).

**No driver text or SQL to the caller (RG2).** Search, evaluate, the `/ingest` status and the source run history
map failures to fixed sentences (`RagFailures`): rejected credentials, unknown model, rate limit, store error, or
the generic "see the server log". Messages EDDI wrote for the operator (a refused space, a full in-memory store, a
rejected parameter) pass through. The whole exception stays in the log.

**Store settings are validated when saved (RG3).** `RagConfiguration.validate()` now refuses an unknown
`storeType`, a pgvector/Qdrant `host` that carries a port, path or list, a port or pgvector `dimension` that is
not a number in range, an Elasticsearch/Chroma URL or an embedding `baseUrl`/`endpoint` that is not `http(s)`, a
blank `name`, `maxResults` below 1 and a `minScore` outside 0-1. `${...}` references are judged when they resolve.
Reads never run this, so a stored configuration still loads.

**Hidden text is no longer ingested from uploads (H4, RG1).** New `settings.visibleTextOnly` on an ingestion
source. Default `true` for an upload, `false` for a crawl. It leaves out Word runs marked hidden (`w:vanish`) or
set at one point, hidden and very hidden Excel sheets, hidden rows and columns, hidden PowerPoint slides and
shapes, and, for uploaded HTML, elements the markup hides outright (`hidden`, `display:none`, zero opacity,
off-screen). The crawler keeps hiding-until-script content (accordions, tabs) on purpose, which is why the
HTML `display:none` text got through in the live test. Not covered: white-on-white Word text, character
styles that carry `vanish`, and PDF invisible text (also the text layer of every OCR'd scan).

**Small fixes (H5).** A read no longer runs `CREATE TABLE` / `CREATE EXTENSION` / index DDL on a pgvector
database: a knowledge base whose table does not exist yet reads as empty, and the first ingestion creates it.
`GET .../chunks` returns `"truncated": true` when the document has more chunks than were returned.

**Dead code (H6).** `SourceFilters` and `SourcePartitions` (referenced only by their own test and one docs
paragraph) are deleted. `PdfStreamBudget` and the `SourceConnector` abstraction are untouched.

### Behaviour changes an upgrader will notice

- A knowledge base on an external store that is saved with another embedding model refuses search and ingest until
  `POST /ragstore/rags/{id}/reembed`; retrieval from it returns no context meanwhile. A knowledge base from before
  this release adopts its configured space on first use.
- Hidden text in an **uploaded** file is no longer extracted (set `visibleTextOnly: false` on the source to keep
  it). Documents already embedded are untouched until the file changes or the knowledge base is re-embedded.
- Error text from RAG endpoints and the ingestion status is fixed sentences; clients that matched on a driver or
  provider message must match on `code`/the sentence.
- Saving a knowledge base with an unknown `storeType`, a host with a port, a non-http(s) store URL or an
  out-of-range `minScore`/`maxResults` is now a `400`.

### What is left

Not done here: the Manager's RAG editor does not show the mismatch state, the re-embed action or the save-time
warning yet (the contract table in `rag.md` is what it should read); RG4 (a pgvector `table` that names
one of EDDI's own tables), RG5 (an `/ingest` of the same name appends unless `replace=true`: by design), RG6 (a
refused web start page reports `FAILED` with no reason in the run record), the retrieval turn deadline and the
in-memory default store losing `/ingest` documents on restart (documented).

**Files:** [`VectorSpace.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/VectorSpace.java),
[`VectorSpaceGuard.java`](../../src/main/java/ai/labs/eddi/modules/rag/VectorSpaceGuard.java),
[`KnowledgeBaseReembedService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/KnowledgeBaseReembedService.java),
[`RagFailures.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagFailures.java),
[`StoreParameterChecks.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/StoreParameterChecks.java),
[`EmbeddingStoreFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`rag.md`](../rag.md)

### Review round (same branch)

An independent review found the single-knowledge-base case sound and these gaps; all fixed:

- **Keying (M1, M2, S2).** The model identity is recorded per store location **and chunk tag** (per knowledge base),
  the vector size per location — for pgvector verified against the table's real column (`atttypmod`). Two knowledge bases
  on one table can hold different models of the same size without refusing each other, adoption is per knowledge base, and
  one knowledge base's re-embed no longer resets another's record. `reembed` refuses (409) when another knowledge base
  keeps the same chunks in the same store (`SHARED_CHUNKS`, 6.5.0 name layout) or when a shared pgvector table would have to
  change its column size (`SHARED_TABLE_SIZE`); a store failure ends a run early (`SQLException`).
- **Crash safety (M3).** `reembed` forgets every source's content hashes before it deletes anything, so a crash at any later
  point leaves each source refilling on its next run instead of empty with state "unchanged".
- **Older configs (M4).** Imports, syncs and upgrades are lenient: retrieval settings out of range are clamped, the rest
  (unknown `storeType`, host with a port, blank name …) is kept and logged at WARN; only an interactive save refuses. The check
  now also runs for an archive's knowledge base that has no sources.
- **S1/S3/S4/S5/S6/S7/S8/N3.** Real pgvector column size; `eddi_rag_vector_space_refused_total{reason}` plus a once-a-minute
  WARN without stack trace; the empty-table drop runs in one transaction under `LOCK TABLE … ACCESS EXCLUSIVE NOWAIT`, a table
  that is not an EDDI pgvector table is never touched, a search on a node whose cache points at a dropped table reads it as
  empty, a direct `/ingest` is refused while its knowledge base is re-embedded (cluster-wide since the third round); no input-echo, URL-credential or driver text to
  clients; `reembed` refuses a version that is not the latest; nothing is adopted or reset while a variable is unresolved;
  hidden-content extraction keeps autofilter/grouped Excel rows, handles `specVanish`/`webHidden`, no longer lets a hidden
  connector swallow the next PowerPoint shape, and says "only hidden sheets" for a workbook that has nothing else; a refused
  ingest no longer builds the store first.
- **Tests.** `PgVectorVectorSpaceIT` (Testcontainers pgvector; needs Docker, run by CI) and unit tests for each item.

### Second review round (same branch)

- **Older configs through upgrade and sync (was only import).** `RestRagStore.updateOlderConfig` is the one update that an
  upgrade, a sync (which runs through the import service) and an import all use; the interactive 400s stay for REST/MCP/
  Manager saves. The clamp WARN is written once per configuration.
- **No table scan on the hot path.** The vector-space check reads only the table's column size from the catalogue, remembered
  per store (a minute, or until the store is rebuilt or its table dropped); the "other rows" probe runs inside `reembed` only.
  An absent size record is remembered too.
- **`reembed` never empties what cannot refill.** One embedding call with the new settings comes first: it proves credentials and
  model, and measures the new size. A size that does not fit is refused up front for every store type (pgvector: declared
  `dimension`, shared table; other stores: a collection that keeps another size, shared or alone, with what to do). A foreign
  pgvector table is refused before any source's hashes are wiped. The result reports `tableRecreated` and `warnings`
  (a busy table keeps its old size).
- **Drop semantics.** Only SQLState `55P03` is "busy"; `42P01` is success; anything else is a failure naming the likely cause.
- **Sharers compared as resolved**, `${vars:...}` included; an unresolvable variable, an unreadable knowledge base or an incomplete
  scan answers `503 KNOWLEDGE_BASE_SCAN_INCOMPLETE` (own code and sentence) instead of "could not be emptied". Aliases that cannot
  be seen (`localhost` vs `127.0.0.1`, DNS aliases) are documented.
- **`visibleTextOnly`**: a hidden Excel row is kept only inside an `autoFilter` range or when grouped; `StoreParameterChecks.EDDI_TABLES`
  now holds all the tables EDDI creates and a test derives the list from the `CREATE TABLE IF NOT EXISTS` statements.

### Third review round (same branch) — `reembed` deletes vectors, so every exit was walked

- **A durable, cluster-wide "rebuilding" mark.** `reembed` writes it into the knowledge base's model record before it forgets a
  hash or removes a vector (`VectorSpaceGuard.markRebuilding`); search, `/ingest` and source runs are refused while it is set, on
  every node, and `GET …/vector-space` shows `rebuilding: true`. Recording the new space replaces it; a refusal before anything was
  removed takes it back. A re-embed that fails or dies part-way **leaves it**, so the knowledge base is refused with the advice to
  re-embed again instead of answering from half its vectors (before, a failure after a partial delete left it searchable and the
  old record in force). The per-instance maintenance mark stays as a fast path. The failure is `503 REEMBED_INCOMPLETE`; a failure
  before anything was removed is `503 REEMBED_NOT_STARTED` (was one `503` saying "could not be emptied" for both).
- **Writes never use the record cache.** A source run reads the record from the database once at its start, `POST /ingest` before
  the store is built and again just before writing (`requireCompatibleForWrite`): a node with a 30-second-old record could
  otherwise write vectors of the old model into a knowledge base another node had just re-embedded. Searches keep the cache.
- **A pgvector table that changes size is dropped whole, or not at all.** It used to be emptied row by row first and then dropped
  only if not busy (`NOWAIT`), so a search in flight left an empty knowledge base whose new vectors the old column refused (the
  answer only carried a warning). Now `EmbeddingStoreFactory.dropKnowledgeBaseTable` drops it in one transaction under an exclusive
  lock (waiting up to 5 s), only while every row carries the knowledge base's tag; busy is `409 TABLE_BUSY` and a table that became
  shared is `409 SHARED_TABLE_SIZE`, both with **nothing removed**. A same-size re-embed deletes by tag as before. `warnings` in the
  `202` is now always empty.
- **Collections of another size are found before deleting.** Most Qdrant/Chroma/Elasticsearch/Atlas locations have no recorded
  size (nothing records one for vectors from before sizes were tracked), which let a re-embed to a model of another size delete
  everything and then fail every insert. `reembed` now searches the collection once with the probe vector; a rejection is `409
  STORE_PROBE_FAILED` with nothing touched.
- **No input echoed through a short `IllegalArgumentException`.** `RagFailures.clientMessage` also withholds enum and UUID parse
  texts and file paths.
- **Lenient older-config writes, proven by behaviour.** The source-text guard test is replaced by tests that drive the upgrade's real
  dispatch and the import's merge update against a store whose interactive update refuses (`UpgradeExecutorTest.ragUpdateIsLenient`,
  `RestImportServiceLegacyRagTest.mergeUpdateIsLenient`). Create paths of import and upgrade write directly to the store and never
  validated; duplicate does not validate either.
- **The Testcontainers ITs.** `PgVectorScaleLayoutIT` created its tables with a read, which no longer creates anything — it uses the
  write path now (it would have failed in CI). `PgVectorVectorSpaceIT` covers the whole-table drop (own rows, shared, busy past the
  lock timeout).
- **Tests.** `KnowledgeBaseReembedRecoveryTest` plays each failure through against the in-memory ingestion state store and the real
  guard, with a second guard standing for another node.

**Behaviour changes in this round:** `reembed` answers `409 TABLE_BUSY` or `409 STORE_PROBE_FAILED` where it used to delete and
warn or delete and fail later; a knowledge base whose re-embed failed part-way is refused until it is re-embedded again; `503`
answers of `reembed` carry a `code`.

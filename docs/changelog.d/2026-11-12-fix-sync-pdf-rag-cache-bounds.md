## 🔒 fix(security): bound sync and import concurrency, nonce replay cache, RAG envelope and tool-result cache (2026-11-12)

**Repo:** EDDI (`fix/sync-pdf-rag-cache-bounds`)

Five findings from a security review were checked end to end. Three were real and are fixed, one
was partly mitigated and is hardened, and one was already fully mitigated.

### What changed and why

- **Live sync: a total read budget.** Every document a sync reads stays in memory until the sync
  ends (`RemoteApiResourceSource.documentsByPath`), so the only bound on the heap one sync took was
  the per-response cap (16 MiB) times the request budget (10,000).
  `eddi.backup.sync.max-total-response-bytes` (default 256 MiB) now caps all responses together;
  past it the whole sync fails with `502`, through the same `RequestBudgetExceededException` path as
  the request budget, so a skipped document can never be read as one the source removed. ZIP import
  already had entry-count, per-entry and total-inflated caps plus a raw-size ceiling (`ZipArchive`),
  so nothing changed there.
- **Imports and syncs: a concurrency limit.** Any number could run at once.
  `BackupConcurrencyGate` (a semaphore, `eddi.backup.max-concurrent-operations`, default 4) is
  applied to every call into `RestImportService` by a new interceptor binding,
  `@BackupConcurrencyLimited`, on the class. That is a one-line change to `RestImportService`, kept
  away from the methods other open PRs edit. A request past the limit gets `429` with `Retry-After`
  and a message naming the property. A call made from inside an admitted one reuses its slot (a
  per-thread depth count), so the interface's delegating default methods neither deadlock nor get
  refused.
- **Cluster signing: the replay cache could forget a nonce that was still replayable.**
  `ClusterMessageSigner` kept nonces in a Caffeine cache with `maximumSize(200_000)` and a fixed
  two-hour TTL. Once that many signed messages had been seen, the cache evicted entries whose
  messages were still inside their window (events have a one-hour window), and the captured
  message was then accepted a second time. Each nonce is now kept exactly until its own message
  expires (`ts + window`, on the signer's clock), with no size eviction. A node already holding
  `eddi.cluster.signing.max-remembered-nonces` (default 500,000) unexpired nonces refuses the
  message (new rejection `NONCE_CAPACITY`, counted in `eddi.cluster.signing.rejected`) rather than
  forget one: it fails closed.
- **RAG context: the closing delimiter is now unforgeable.** Retrieved context was already wrapped
  (`ToolResultProvenance.markRetrieved`), and exact look-alikes of the delimiters in the body were
  rewritten. But the closing line was a fixed string, so a document could still spell it some
  other way the rewrite does not match (another space, a line break) and the model would read that
  as the end of the data. Each block now carries a fresh 12-hex-character random id in its header
  and its closing line, and the header says only that id closes it. The body rewrite also catches
  any `[retrieved context` opening now that the header carries an id. Only what the model sees
  changes: `rag:context:*` and what guardrails get stay unwrapped, and `markRagProvenance: false`
  still turns the envelope off. The tool-result envelope (`mark`) is unchanged.
- **Tool-result cache: bounded by size.** It was bounded at 10,000 entries of any size, and a
  web-scraper result can be 5 MB. `CacheFactory` now builds `tool-results` with a Caffeine weigher:
  `eddi.tools.cache.max-total-chars` (default 64 Mi characters, keys included), and every entry is
  charged at least that divided by 10,000, so the old entry bound still holds for small results.
  `ToolCacheService` no longer caches a result over `eddi.tools.cache.max-entry-chars` (default
  262,144), and counts each one in `eddi.tool.cache.skipped.oversized`. Keys were already bounded:
  arguments over 2,048 characters are hashed.
- **PDF parsing: already bounded, nothing changed.** `PdfTextExtractor` (RAG ingestion) applies a
  page cap (500), a character cap, a per-page glyph allowance, a per-stream pre-scan, a decode
  budget and a 60-second deadline checked between content-stream operators.
  `AttachmentTextExtractor` (attachments, `PdfReaderTool`) applies the same budgets, plus
  `eddi.attachments.extraction.max-pages`, an operator cap and a deadline.

### Design decisions

- The concurrency limit fails fast with `429` instead of queueing, so a request worker is never
  parked behind the others. It applies per node.
- For the nonce cache, refusing beats evicting. A legitimate message refused under extreme load is
  visible in the metric. A replay that slips through is not.
- The weight floor (`max / 10,000`) keeps the old entry count for small results, so the size bound
  is purely an added limit on large ones.

### Upgrade notes

A fifth concurrent import or sync on a node gets `429` (the default limit is 4). A very busy signed cluster that sees more than 500,000 signed messages
inside one event-retention window now refuses some messages instead of silently weakening replay
protection. Tool results over 262,144 characters are no longer cached. The envelope text the model
sees for retrieved context changed shape.

**Files:** [`RemoteApiResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java), [`BackupConcurrencyGate.java`](../../src/main/java/ai/labs/eddi/backup/impl/BackupConcurrencyGate.java), [`BackupConcurrencyInterceptor.java`](../../src/main/java/ai/labs/eddi/backup/impl/BackupConcurrencyInterceptor.java), [`BackupConcurrencyLimited.java`](../../src/main/java/ai/labs/eddi/backup/impl/BackupConcurrencyLimited.java), [`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java), [`ClusterMessageSigner.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterMessageSigner.java), [`ClusterSigningConfig.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterSigningConfig.java), [`ToolResultProvenance.java`](../../src/main/java/ai/labs/eddi/modules/llm/governance/ToolResultProvenance.java), [`CacheFactory.java`](../../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java), [`IWeighedValue.java`](../../src/main/java/ai/labs/eddi/engine/caching/IWeighedValue.java), [`ToolCacheService.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolCacheService.java); docs: [`configuration-reference.md`](../configuration-reference.md), [`agent-sync-guide.md`](../agent-sync-guide.md), [`import-export-an-agent.md`](../import-export-an-agent.md), [`clustering.md`](../clustering.md), [`langchain.md`](../langchain.md), [`rag.md`](../rag.md), [`upgrading-from-6.5.md`](../upgrading-from-6.5.md).

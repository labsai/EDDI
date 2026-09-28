## 🐛 fix(rag): findings from a live end-to-end RAG run (2026-09-28)

**Repo:** EDDI (`fix/rag-e2e-findings`)

A live run of RAG and ingestion — a Claude agent answering from knowledge bases on the
`in-memory` and `chroma` stores, fed by direct ingestion and by an upload source holding
PDF, Word, Excel, PowerPoint, CSV, HTML and Markdown files — passed on both stores, and
confirmed on Chroma what [`docs/rag.md`](../rag.md) listed as unverified: re-ingesting
a changed file removes the chunks of its previous version. It also turned up the
defects below.

### Deleting an uploaded file answered 400 after it had succeeded

`DELETE /ragstore/rags/{id}/sources/{sourceId}/files/{fileId}?version=N` removed the
file and its vectors, then answered `400 "state should be: hexString has 24 characters"`,
so the Manager reported a failed delete for one that had happened.

`DocumentDescriptorFilter` runs after every successful DELETE and marks the deleted
resource's descriptor as deleted. It took the resource id from the last path segment,
and for this endpoint that is a 32-hex file id — valid enough for
`RestUtilities.extractResourceId`, not for a MongoDB `ObjectId`. The filter now does its
DELETE bookkeeping only for a path of the form `{store}/{collection}/{id}`, which every
configuration resource has; anything deeper is a sub-resource whose last segment is not
a configuration id.

- `DocumentDescriptorFilter.addressesResourceItself` — the path-shape check.
- `DocumentDescriptorFilterTest` — a sub-resource DELETE leaves descriptors untouched
  (fails with the check removed), plus the path shapes.

### Direct ingestion can now replace a document instead of duplicating it

`POST /ragstore/rags/{id}/ingest` only ever added: ingesting `pricing.md` a second time kept
both versions, and retrieval put the stale text in front of the model next to the current
one. The live run showed it on both stores — two identical chunks in the injected context.

`replace=true` (off by default, so existing callers are unchanged) now supersedes whatever
was ingested under the same `documentName` in the knowledge base.

- **Add first, remove afterwards** — the same order the ingestion sources use. Every chunk
  now carries the `ingestionId` that wrote it, and after the new chunks are stored the
  others under that name are removed with `ingestionId != current`. Chunks written before
  this change carry no `ingestionId`; the in-memory store and Chroma both match a missing
  key with `isNotEqualTo`, so those are superseded too (tested against the real in-memory
  store; checked by hand against Chroma 1.0).
- **Needs an explicit name** — `400` without one, because every unnamed document shares
  `unnamed` and replacing it would delete all of them.
- **Says so when it cannot** — on a store that cannot delete by metadata the ingestion
  completes and the status response carries a `warning`, rather than reporting a clean
  replace.
- **Manager** — the drop zone has a "replace a previously ingested document with the same
  file name" checkbox (off by default), and the status list shows the warning. Pasted
  text is always ingested under a fresh name, so it is not offered there.

Files: `RagIngestionService`, `IRestRagIngestion`, `RestRagIngestion`,
`ui/manager/src/components/editors/rag-editor.tsx`, the 11 Manager locales,
[`docs/rag.md`](../rag.md#document-ingestion). Tests: `RagIngestionServiceTest` (replace,
no replace, pre-change chunks, unsupported store — the first three fail with the removal
disabled), `RestRagIngestionTest`.

### Web sources read every sitemap form, including the index most large sites publish

The crawler already seeded from sitemaps listed in robots.txt, but it read every `<loc>` as a
page. A **sitemap index** lists further sitemaps, so their URLs were queued as pages, the fetcher
refused the XML, and sitemap discovery found nothing — on docs.labs.ai itself, whose
`sitemap.xml` is an index over `sitemap-pages.xml` (81 pages). An image or video sitemap would
equally have queued every image as a page.

- **Every protocol form** — `urlset` (only `url/loc`; the image, video and news extensions'
  own `loc`s and `xhtml:link` hreflang alternates are not pages), `sitemapindex` (followed,
  indexes of indexes included), RSS 2.0 and Atom feeds, plain-text sitemaps, and any of
  them gzip-compressed — recognised by content, not by name or Content-Type. Namespace
  prefixes, entities, CDATA, BOMs and UTF-16 are handled; only absolute `http(s)` URLs count.
- **Where sitemaps come from** — new `web.sitemapUrls` (at most 20, each validated like
  `startUrl`: http(s), not a private literal address) for a site that publishes one without
  listing it, or a crawl with `respectRobots` off; then robots.txt, where a relative
  `Sitemap:` line is now resolved instead of silently failing; and, only when neither gave
  anything, the conventional `/sitemap.xml`.
- **Bounds** — 20 sitemaps per run including every index child, 5,000 page URLs across all
  of them, 16 MB decompressed per sitemap (a gzip bomb stops at the cap, keeping the URLs
  before it). Sitemap pages are held to the crawl's scope like linked ones.
- **Manager** — a "Sitemaps" field on website sources, in all 11 locales.

Files: `WebCrawler` (`parseSitemap`, sitemap queue), `CrawlRequest` (`sitemapUrls`; the
4-argument constructor stays), `IngestionSource.WebSource`, `IngestionPipeline`,
`ui/manager` ingestion-sources panel and API type, [`docs/rag.md`](../rag.md#sitemaps).
Tests: `SitemapParsingTest` (20 — every form, encoding, gzip bound), `WebCrawlerTest`
(index following, configured sitemap without robots, dedupe, index budget, relative robots
line, `/sitemap.xml` fallback and its absence when a sitemap is named, gzip end to end,
scope), `IngestionSourceSitemapValidationTest`. Mutation-checked: without the index
distinction or the gzip branch, six of them fail.

### A failed turn no longer comes back silent

A Claude agent configured with `temperature`, which Sonnet 5 rejects, answered every message
with nothing: `POST /agents/{id}` returned `200`, `conversationState: "ERROR"` and an output
holding only the actions; the streaming path sent `task_failed` with `"Streaming chat failed"`
and a `done` with the same empty output. The reason — "`temperature` is deprecated for this
model." — was only in the server log, and the Manager rendered an empty bubble.

- **The caller is told** — without strict write discipline (the default), the failed step's
  output now carries the same `taskErrors` entry strict write's `digest` mode writes. It is
  caller-facing only: nothing reads `taskErrors` back into the model's view, and no data or
  `task_failed_*` action is written, so agent prompts are unchanged. Under strict write the
  configured `onFailure` still decides — `exclude_all` stays silent.
- **With the actual reason** — `LifecycleManager.describeFailure` appends the most specific
  cause to the wrapper's message and pulls `message` out of a provider's JSON error body. It
  feeds both the digest (redacted, URLs removed, 200 chars) and `summarizeForAudit`, so the
  streaming `task_failed` event and the audit ledger carry the reason too.
- **And it survives the response** — non-detailed responses (the default, and what the
  Manager and the streaming `done` event carry) keep only whitelisted output keys, and
  `taskErrors` was not one of them. The entry was written and then stripped, so the fix
  above did nothing on its own, and **strict write's `digest` mode has never reached a
  default client either**, whatever `docs/memory-policy.md` said. Found by running the
  change live; the unit tests, which stop at `LifecycleManager`, passed throughout.
  `MemoryKeys.TASK_ERRORS` is now on the list. A wrapper that quotes the provider's raw
  JSON body ("Chat model execution failed: {…}") gets the message swapped in as well.
- **Manager** — both chat paths show `⚠️ <reason>` in place of the empty reply, or a generic
  line when an `ERROR` turn carries none (`chat.turnFailed`, 11 locales).
- **Chat UI unchanged, on purpose** — it already shows end users a "Something went wrong"
  banner with Try again on an `ERROR` turn, and the provider's wording is for operators.

Files: `LifecycleManager`, `MemoryKeys`, `ConversationMemoryUtilities`, `ui/manager` `use-chat.ts` and `lib/api/conversations.ts`,
[`docs/memory-policy.md`](../memory-policy.md#a-failed-turn-always-tells-the-caller-why).
Tests: `LifecycleManagerTest` (reported without strict write with the unwrapped reason, model
view untouched, `exclude_all` silent, redaction — the first and last fail with the report
removed), `LifecycleManagerErrorClassificationTest` (`describeFailure`),
`ConversationMemoryUtilitiesTaskErrorsTest` (the whitelist), `TaskFailureReportIT` (both
paths over HTTP), Manager
`conversations.test.ts`, `use-chat-sse-handling.test.tsx`, `use-chat.test.tsx` (all three
chat cases fail with the notice removed).

### Integration tests over real HTTP, and ITs next to a local MongoDB

- `RagIngestionIT` — upload, list and **delete** a file through the full JAX-RS filter chain
  (the layer the 400 lived in, which every unit test skipped), `replace` validation, and
  `sitemapUrls` persisted and refused at a private address. `TaskFailureReportIT` — an agent
  whose LLM points at a closed local port: both the plain and the streaming path must carry
  `taskErrors`. Reverting either fix fails them (`deleteFile`; both failure-report tests).
- `IntegrationTestProfile` follows `-Dquarkus.mongodb.devservices.port`. With a locally
  installed MongoDB on 27017, DevServices could not bind and every `@QuarkusTest` IT failed
  at startup; `-Dquarkus.mongodb.devservices.port=27018` now moves both DevServices and the
  profile's connection string, rather than pointing the tests at the real local database.

### Manager e2e, and document ingestion stuck at "processing" in development

- `ui/manager/e2e/rag-ingestion.spec.ts` (MSW tier) — the Sitemaps field, a dropped file with
  and without "replace" (the mock answers `replace=true` with a warning, so the test proves the
  flag was sent), and a failed chat turn on the plain and the streaming path. Each case fails
  with its fix reverted. The seams live in `src/test/mocks/handlers.ts`
  (`TASK_FAILURE_TRIGGER`, a new `/agents/:id/stream` mock), because `page.route` cannot see
  requests the MSW service worker answers.
- **Fixed on the way:** the knowledge-base ingestion panel's `mountedRef` was set `false` by its
  effect cleanup and never back to `true`. React StrictMode mounts, unmounts and remounts in
  development, so every status poll returned immediately and every direct ingestion showed
  "processing" for ever under `npm run dev`. Production builds do not double-mount, which is why
  it went unnoticed; the e2e spec is what surfaced it.
- The ingestion warning uses the `text-warning` token rather than raw amber classes.

### A dead ingestion run no longer reads as running, or blocks purge and file delete

Only claiming a new run reaped an abandoned one. A crawl killed by a restart therefore stayed
`RUNNING` in the run history until somebody started another — observed live an hour past its
25-minute threshold — and, because `activeRun` is also the guard purge and file delete answer 409
on, it refused both for as long. On a source with no cron, indefinitely.

`RagSourceIngestionService.listRuns` and `activeRun` now reap first: the same
`reapStaleRuns`, the same per-source threshold (`IngestionPipeline.staleBefore`, now shared by
every caller so a read and a claim cannot disagree about which runs are dead), the same fencing.
Best effort on a read — a reap that fails is logged and the read proceeds. What it deliberately
does not change: before the threshold a crashed run still reads as `RUNNING`, because runs record
no owner or heartbeat and a dead run cannot be told from a live one on another instance.
Tests: `RagSourceIngestionServiceTest.AbandonedRuns` (history shows it failed, no longer active,
a live run untouched, the threshold follows the budget, a failing reap does not break the read —
the first two fail with the reap removed).

### Review fixes, and two CodeQL alerts

- **Concurrent replacements of one document could delete each other** (CodeRabbit). Each stores,
  then removes every other ingestion's chunks under the name; interleaved, both deletions take the
  other's new chunks and the document is left with none. Store-and-delete is now serialized per
  (knowledge base, document name) with a weak-valued lock map — per instance, stated in the
  Javadoc. `concurrentReplacementsOfOneNameLeaveOneVersion` forces the interleaving and ends with
  zero chunks without the lock.
- **Manager: the streaming back-fill overwrote the failure notice** when an `ERROR` turn also
  carried text (CodeRabbit). The notice is now appended after it; tested, and the test fails with
  the old order.
- **`EmbeddingStoreFactory`** — the Chroma builder logged `baseUrl`, tenant, database and
  collection unsanitized (`java/log-injection`); `sanitizeCollection` stripped trailing
  underscores with `_+$`, quadratic on a long run of them (`java/polynomial-redos`). Both
  pre-existing on `main`; now `sanitize(...)` and a linear scan. The new timing test fails against
  the regex.

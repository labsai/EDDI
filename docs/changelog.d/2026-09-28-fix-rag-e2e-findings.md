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

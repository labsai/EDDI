## 🔧 fix(rag): review findings on the ingestion salvage (2026-09-18)

**Repo:** EDDI (`feat/rag-ingestion-rest` and the branches below it)

A critical review of the six salvage branches returned ten must-fix findings. All are fixed, each with a
test that fails when the fix is reverted. The headline four fixes the salvage was built around held up
under mutation; every finding below was in a seam those tests did not reach.

### Would not have started at all

`PostgresIngestionStateStore` was `@ApplicationScoped` without `@DefaultBean` while `DataStoreProducers`
also produces `IIngestionStateStore` — two non-default candidates, so ArC fails augmentation and nothing
boots. Every sibling Postgres store carries `@DefaultBean` for exactly this reason. No unit test can see
this; the review caught it by reading the pattern.

### Silent data loss

- **A tombstoned page that came back unchanged was never re-embedded.** Its vectors had been deleted, so
  comparing hashes alone made it "unchanged" forever and permanently unretrievable. Two routes, both
  closed: `hasChanged` is now true for a tombstoned document, and it is no longer revalidated with its
  stored ETag (a 304 never re-embeds).
- **Schedules were orphaned.** `syncSchedules` walked only the new document's sources, so a source
  deleted from `sources[]` kept its schedule and went on crawling a third party on a cron with nothing
  left in the configuration to switch it off. The previous version's source ids are now diffed.
- **Deleting an old version killed the live version's schedules.** Removal now happens only when no
  readable version remains.
- **A duplicated knowledge base shared the original's ingestion identity.** Vector stores are keyed by
  name, which a duplicate shares, so the copy's runs replaced and tombstoned the original's chunks while
  the original's state still said "unchanged". A copy now gets fresh source ids and no cron.
- **Vector removal ran after the tombstone was recorded**, and a tombstoned document is never reported
  again — so a failed removal orphaned those vectors permanently. A failure now un-tombstones for retry.

### Runs that wedged their own source

- Only `crawler.crawl` was guarded and only `RuntimeException` caught, so a state-store failure or an
  `Error` left the run `RUNNING` forever: every later manual run a 409, every scheduled fire a failure.
  Everything after the claim is now guarded by `Throwable`, and `reapStaleRuns` is finally called before
  claiming — nothing called it, although the interface said otherwise.
- `ALREADY_RUNNING` and `SKIPPED` were mapped to `FireStatus.FAILED`. The schedule lease is five minutes
  and a crawl's default budget is ten, so the schedule is legitimately re-claimed while the first run is
  still going; the second fire lost the single-in-flight race and was recorded as a failure, dead-lettering
  the schedule within days.
- The converter's recursive walk had no depth cap. jsoup builds the full DOM — 60,000 levels if the page
  says so — and a `StackOverflowError` is an `Error` that sails past every `catch (Exception)`.
- Body reads had no time bound: `HttpRequest.timeout` covers headers only, so a server trickling one byte
  per second held a virtual thread indefinitely.

### Crawler correctness

- Only successful pages entered the visited set, so a dead link in a site-wide footer was fetched once per
  referring page — N errors, and a fetch budget so exhausted that the crawl never reported full coverage,
  which silently disabled deletion reconciliation for that site on every run.
- `isHtml()` matched any content type containing `xml`, so a linked sitemap or RSS feed became a
  knowledge-base document of concatenated `<loc>` URLs.
- robots.txt was fetched for the seed host and applied to every host reached.

### Divergent backends

The Postgres store swallowed every `SQLException` and returned a plausible answer — `lookup` said "never
ingested" (re-embedding the page every run), `startRun` said "already running" (a 409 for a database
fault), `finishRun` left the run `RUNNING`. Mongo threw. Both now fail identically through
`IngestionStateStoreException`, which is the divergence the shared contract exists to prevent.

### Also

Preview was synchronous, unguarded and uncapped — up to a day on a request thread per click; it now runs
with a two-minute budget. `runAsync` leaked an `ExecutorService` per call and swallowed `Error`s. Third-party
titles are capped before being copied onto every segment. `ref`/`referrer` are no longer stripped as
tracking parameters (they select content on plenty of sites). `RobotsPolicy` matched `User-agent` by
substring, so `User-agent: a` captured every crawler.

### Tests

398 across the stack, up from 354: 20 new cases, one per finding, each mutation-checked. The repo's own
`ImportStyleTest` caught two inline FQNs I had introduced.

## ⏱️ feat(rag): REST and scheduling for ingestion sources (2026-09-17)

**Repo:** EDDI (`feat/rag-ingestion-rest`)

### What this adds

The operable surface for the sources landed in the previous entry: four endpoints and a cron.

| Method | Path | Access |
| ------ | ---- | ------ |
| `POST` | `…/sources/{sourceId}/run` | EDIT |
| `POST` | `…/sources/{sourceId}/preview` | EDIT |
| `GET` | `…/sources/{sourceId}/runs` | VIEW |
| `DELETE` | `…/sources/{sourceId}/documents` | EDIT |

**Running needs EDIT, not VIEW.** A published knowledge base grants VIEW to everyone by design, and a
run rewrites what every agent using it retrieves — so gating a run on read access would let any editor
point a source at any published knowledge base and poison it, on a schedule. The draft this replaces
checked nothing at all. Preview is gated the same way: it writes nothing but still sends a visible
amount of traffic to a third party's site.

Runs are async on a virtual thread (a crawl takes minutes; an HTTP request cannot wait for it), with a
409 rather than a second crawl when one is already in flight.

### Scheduling

A source with a `cron` gets a schedule carrying `ragIngestion` metadata, which `ScheduleFireExecutor`
recognises as a fourth fast-path beside HITL timeouts, Dream consolidation and team cadences — the same
shape of work, and the same reason: a maintenance job, not a conversation turn, that wants the cluster
claim, lease, retry and fire log.

Schedules are named `rag-ingestion:{ragConfigId}:{sourceId}`, so syncing is delete-by-name then create:
no scan and no orphans. The draft searched `readAllSchedules(1000)`; past a thousand schedules — HITL
timeouts and Dream cycles each create one — it silently failed to find the row, then created a duplicate
on update and left a schedule still crawling a deleted source on delete. A sync failure is logged as an
ERROR naming the consequence rather than swallowed behind a 201.

Sources get a generated stable id on write. Addressing them by name would mean renaming a source
orphaned everything it had ingested.

### Tests

15 for the service (schedule upsert, no-cron and disabled handling, surfaced store failures, scheduled
fire against a deleted source or knowledge base, concurrent-run refusal, scoped purge) and 12 for the
REST layer (access level per endpoint, refusals never reaching the service, 404s, 409, limit clamping).
Four existing RAG REST tests were updated for the new constructor parameter.

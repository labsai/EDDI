# Implementation Plan — More Knowledge-Base Source Types

**Status:** Planning
**Created:** 2026-09-19
**Scope:** Backend (EDDI repo) plus the Manager (`ui/manager`)
**Builds on:** the RAG ingestion stack in labsai/EDDI #783–#787, #789 and #790
(web crawler, ingestion state store, pipeline, REST and scheduling, Manager
sources panel), and on the connections framework in
[`docs/connections.md`](../docs/connections.md). That framework is the shipped
result of [`saas-connectors-plan.md`](saas-connectors-plan.md). The ingestion
stack is **not on `main` yet**. Class names below that belong to it
(`IngestionPipeline`, `IngestionSource`, `WebCrawler`, `CrawlSink`,
`IIngestionStateStore`, `RagSourceIngestionService`) refer to the #790 branch
tip.

> **Read §3 before any source section.** The source types differ mainly in API
> details. What decides whether they are safe to ship is common to all of them:
> whose credential a scheduled job may spend (§3.4), who may read what was
> ingested (§3.5), and the fact that some sources — email above all — are
> attacker-writable (§3.6). If one of these is decided per source, it will
> end up decided differently each time.

---

## Table of Contents

1. [Goal](#1-goal)
2. [Where the ingestion stack is today](#2-where-the-ingestion-stack-is-today)
3. [Shared foundation — decide once](#3-shared-foundation--decide-once)
4. [Source: sitemap only](#4-source-sitemap-only)
5. [Source: file upload](#5-source-file-upload)
6. [Source: Google Drive](#6-source-google-drive)
7. [Source: Microsoft OneDrive and SharePoint](#7-source-microsoft-onedrive-and-sharepoint)
8. [Source: email — Gmail, Microsoft 365, IMAP](#8-source-email--gmail-microsoft-365-imap)
9. [Further candidates](#9-further-candidates)
10. [Phasing and effort](#10-phasing-and-effort)
11. [Testing strategy](#11-testing-strategy)
12. [What I would NOT do](#12-what-i-would-not-do)
13. [Open questions](#13-open-questions)

---

## 1. Goal

A knowledge base should be able to keep itself in sync with the places where an
organisation's knowledge actually lives, not only its public website. Documents
in Google Drive and SharePoint, and the mail a support team answers from, should
become retrievable context for an agent without anyone exporting or uploading
them.

The engine should gain these as **source types**: configuration on a knowledge
base that names a system, a scope and a credential. It should not gain one
bespoke ingestion service per provider. Golden Rule 1 applies: the provider is
data, the Java is the engine.

### Non-goals

- **Two-way sync or write-back.** Ingestion reads. Acting on a mailbox (sending,
  labelling) is a tool or connector concern and belongs to
  `saas-connectors-plan.md`.
- **Real-time freshness.** Scheduled and on-demand runs are the model. Push
  notifications (Drive `changes.watch`, Graph subscriptions, Gmail Pub/Sub) are
  §13 material, not phase-one.
- **Replacing a search product.** Permission-trimmed enterprise search across
  every document a user can open is a different product. §3.5 draws the line
  this plan will hold.

---

## 2. Where the ingestion stack is today

What the ingestion PRs built, and which parts are already general:

| Piece | Today | General enough? |
|---|---|---|
| `IngestionSource` on `RagConfiguration.sources[]` | `type` field, with `"web"` as the only accepted value; a `web` block; `settings`; `cron` | ✅ The `type` + typed-block shape extends naturally |
| `IngestionPipeline.run(kbId, kb, source, mode)` | Builds a `CrawlRequest` from `source.getWeb()` and drives `WebCrawler` | ❌ Web-specific: `toCrawlRequest` and the `Collector implements CrawlSink` wiring |
| `CrawlSink` | `onPage(CrawledPage)` with HTML, plus `onUnchanged`, `onError`, `conditionalFor`, `isCancelled` | ⚠️ Shape is right; the payload is HTML-specific |
| `IIngestionStateStore` | Per `(sourceKey, documentId)`: content hash, ETag, Last-Modified, last-seen run, tombstone; runs with counters; one active run per source | ✅ Keyed by opaque document ids. Lacks a per-source **cursor** (§3.2) |
| Deletion reconciliation | Tombstone after N consecutive misses, only when `CrawlSummary.coveredWholeSource()` | ⚠️ Correct for listing-based sources; delta sources report deletions explicitly (§3.3) |
| Chunk replacement | `removeAll(metadataKey("documentId").isEqualTo(id))` then add | ✅ Source-agnostic |
| `HtmlToMarkdownConverter` | HTML → Markdown, depth-capped | ✅ One converter among several (§3.7) |
| Scheduling | `rag-ingestion:{ragConfigId}:{sourceId}` schedules through `ScheduleFireExecutor`'s `ragIngestion` fast path | ✅ Unchanged |
| REST | run / preview / runs / purge per source | ✅ Unchanged |
| Retrieval (`RagContextProvider`) | Similarity search per knowledge base; **no metadata filter** | ❌ No audience or ACL enforcement (§3.5) |
| Credentials | None — the web crawler is anonymous | ❌ Needs connections (§3.4) |
| Converters | HTML only. PDFBox and jsoup are in `pom.xml`; nothing handles DOCX, XLSX, PPTX or MIME | ❌ (§3.7) |

---

## 3. Shared foundation — decide once

The source sections assume everything in this chapter. Phase F (§10) builds it
with one real consumer, the sitemap source, so the abstraction is not designed
against an imagined provider.

### 3.1 A connector interface in place of a hard-wired crawler

Replace the web-specific wiring in `IngestionPipeline` with:

```java
public interface SourceConnector {
    /** Matches IngestionSource.type — "web", "sitemap", "googleDrive", … */
    String type();

    /** Save-time validation of the type's config block, with actionable messages. */
    void validate(IngestionSource source);

    /** Streams the source into the sink. Never throws for a single bad document. */
    SyncResult sync(SyncContext context, DocumentSink sink);
}

public record SyncContext(
        IngestionSource source, String sourceKey, String runId, Mode mode,
        Optional<String> cursor,        // §3.2 — empty on the first run and after a reset
        Budgets budgets,                // documents, requests, bytes, deadline
        CredentialHandle credential) {} // §3.4 — never a raw token in the context

public interface DocumentSink {
    /** Stored version from the last run: ETag, revision id, MODSEQ — whatever the source uses. */
    default Optional<String> knownVersion(String documentId) { return Optional.empty(); }
    void onDocument(SourceDocument document);
    void onUnchanged(String documentId);
    /** Explicit deletion from a change feed — no missed-run threshold (§3.3). */
    void onDeleted(String documentId);
    void onError(String documentId, String reason, boolean transientFailure);
    boolean isCancelled();
}

public record SourceDocument(
        String documentId,       // stable across renames and moves
        String title,
        URI citationUri,         // what a human clicks in a cited answer
        String mimeType,
        Content content,         // bytes plus a declared charset, or already-extracted text
        String versionToken,     // stored and handed back as knownVersion()
        Instant modifiedAt,
        Map<String, Object> metadata,   // author, path, labels, thread id … (§3.8)
        Optional<Acl> acl) {}           // §3.5 — only when the source knows it

public record SyncResult(StopReason stopReason, Coverage coverage,
                         Optional<String> nextCursor, SyncCounters counters) {}

public enum Coverage { FULL_LISTING, DELTA, PARTIAL }
```

`WebCrawler` becomes the first `SourceConnector` through a thin adapter that
maps `CrawledPage` to `SourceDocument(mimeType = text/html)` and
`coveredWholeSource()` to `FULL_LISTING` or `PARTIAL`. The web path keeps its
tests unchanged. That is the acceptance criterion for the refactor.

**Discovery is CDI.** Connectors are `@ApplicationScoped` beans collected via
`Instance<SourceConnector>` into a map by `type()`, the same pattern the
lifecycle extensions use. Adding a source type is one bean, one config block and
one converter if the format is new. There is no registry file to edit.

**Config shape.** Keep one typed block per source type (`web`, `sitemap`,
`googleDrive`, `microsoft365Files`, `mail`, …). Validation requires exactly the
block matching `type`, and refuses any other block. Jackson polymorphism
(`@JsonTypeInfo`) was considered and rejected: the Manager, the OpenAPI snapshot
and the JSON schema endpoint all handle plain optional objects better than a
discriminated union, and the web source already uses this shape.

### 3.2 Cursors: incremental sync for sources that offer it

Several providers hand out a change cursor: a Drive changes page token, a
Graph `deltaLink`, a Gmail `historyId`, an IMAP `HIGHESTMODSEQ`. With one, a
nightly run costs requests proportional to the changes, not to the source's
size.

- **Storage.** Add a cursor to the state store, one row per source:
  `(sourceKey, cursor, cursorKind, updatedAt)`. The cursor is **one opaque
  value**, written by one atomic update. It is not several columns, because a
  backfill page token and the delta baseline it belongs with must never be
  readable in a mismatched pair.
- **One state machine.** The value is a small tagged envelope the pipeline
  stores and the connector interprets:

  ```json
  { "v": 1, "phase": "BACKFILL", "listToken": "…", "deltaBaseline": "…" }
  { "v": 1, "phase": "DELTA",    "deltaToken": "…" }
  ```

  | Transition | When | Written |
  |---|---|---|
  | none → `BACKFILL` | First run, or after a reset or `CURSOR_INVALID`. The connector takes the **delta baseline first** (Drive `changes.getStartPageToken`, Gmail's profile `historyId`, IMAP `HIGHESTMODSEQ`) and only then starts listing, so a change made during the backfill is not lost | With the baseline and an empty `listToken`, before the first page |
  | `BACKFILL` → `BACKFILL` | Each listing page whose documents are durably recorded | `listToken` advanced, baseline untouched |
  | `BACKFILL` → `DELTA` | The listing is exhausted | In the same atomic update as `finishRun`, `deltaToken` = the stored baseline |
  | `DELTA` → `DELTA` | Each delta page fully applied | `deltaToken` advanced |

  Graph is the simple case: its `delta` call is both phases, so `listToken` is
  the `@odata.nextLink` and the final `@odata.deltaLink` is the transition.
- **A cursor may advance only past work that is durably recorded.** That is the
  rule, and it replaces "commit only at the end of the run" — which contradicted
  the resumable backfill in §3.11 and the budget stop in §3.13. A checkpoint is
  written after a page's documents are embedded and their state rows written,
  which makes mid-run progress safe and crash replay cheap. Only the
  `BACKFILL → DELTA` transition is tied to run completion, because only there
  does the meaning of the cursor change.
- **Replay is by design.** After a crash the next run resumes from the last
  checkpoint and re-lists that page. Its documents are unchanged by hash or
  version token, so they cost a listing request and no embedding.
- **Error semantics.**
  - A **run-level** failure (auth, transport, throttling exhausted) stops the
    run and leaves the cursor at its last checkpoint.
  - A **permanent per-document** failure (an unsupported format, an encrypted
    file, a converter refusal, a 403 on one item) records the document as
    failed, and the checkpoint **may** advance past it. Otherwise one poison
    document blocks the source forever. Such documents are listed in run
    history, counted separately from transient errors, and retried at the next
    full resync (§3.3).
  - A **budget stop** (§3.13) is a clean stop: the checkpoint stands, coverage is
    `PARTIAL`, and nothing is tombstoned.
- **Expiry is normal, not an error.** Gmail `historyId`s go stale, Graph delta
  tokens can be invalidated, and IMAP `UIDVALIDITY` can change. The connector
  reports `CURSOR_INVALID`, and the pipeline clears the cursor and schedules a
  full listing as the next run, recorded in run history. It is not reported as a
  failure that needs an operator.
- **Operator reset.** A `POST …/sources/{sourceId}/reset-cursor` endpoint needing
  EDIT permission forces a full re-listing. It is the escape hatch when a
  provider's delta feed is suspected of having missed something.

### 3.3 Two deletion models, one state store

| Coverage | Deletion signal | Mechanism |
|---|---|---|
| `FULL_LISTING` (web, sitemap, IMAP without QRESYNC, upload) | Absence from a complete listing | The existing missed-run tombstoning and its coverage guard, unchanged |
| `DELTA` (Drive, Graph, Gmail, IMAP with QRESYNC) | An explicit "removed", "trashed" or "vanished" record | `DocumentSink.onDeleted` tombstones immediately. Absence means nothing, and `tombstoneMissing` is **not** called |
| `PARTIAL` | None | No tombstoning at all, as today |

A delta source still needs a periodic full listing, because feeds do lose
events. `settings.fullResyncEveryDays` (default 7) makes every Nth run a
`FULL_LISTING` that reconciles by absence under the same threshold rule. This
is the only place the two models meet.

### 3.4 Whose credential a scheduled run spends

The connections framework resolves a credential per request. `SERVICE`
connections need no user. `PER_USER` connections require a **verified**
conversation principal and deliberately fail closed on scheduled turns
(`docs/connections.md` → *Whose identity counts*). An ingestion run has no
conversation and no caller. So:

**`SERVICE` connections (phase one of every source).** The connector resolves
the connection through `ConnectionResolver` with no principal, exactly as a
discovery request does. This covers a Google service account on a Shared Drive,
Microsoft Graph with application permissions (`Sites.Selected`), a shared
support mailbox, and IMAP with an app password. It needs no new security model.

**`PER_USER` connections (a later phase, and only for personal knowledge
bases).** "Index my own Drive" needs a stored grant spent without the user
present. Design:

1. The source records `runAsPrincipal`, **set by the server** from the verified
   identity of the request that saved the source. It is never accepted from the
   request body. A body that carries one is refused, not ignored, so a client
   believing it chose a principal learns otherwise.
2. Saving a `PER_USER`-backed source requires that the saving principal is
   `VERIFIED`, has a usable grant for that connection, and is the principal
   recorded. Only the grant's owner can point a knowledge base at their own
   account.
3. The resolver gains a narrow entry point:
   `resolveForIngestion(connectionName, targetUrl, IngestionSourceRef)`. It
   re-reads the source, checks `runAsPrincipal`, and resolves that principal's
   grant. It emits a distinct `connection.resolve{purpose="ingestion"}` metric
   and an audit-ledger entry per run. It is not a general
   `principalOverride`, and no conversation path can reach it.
4. **Revocation propagates.** Unlinking (`DELETE /connections/{name}/grant`)
   makes the next run fail with `NOT_CONNECTED`, visible in run history. It
   never falls back to a service grant. Offboarding a user through the GDPR
   erasure cascade deletes the sources they own and purges their vectors
   (§3.9).
5. Such a knowledge base is `audience: OWNER` **by default, and by exception
   only** (§3.5). This is the condition that makes the design acceptable: the
   owner's credential normally feeds only context the owner reads. The single
   exception is a Gmail shared support mailbox under
   `sharedMailboxAcknowledged` (§8), which is admin-only, requires the grant's
   mailbox to match the configured address, refuses the linking user's own login
   address, and writes an audit-ledger entry. Any future exception must clear the
   same four bars.

### 3.5 Who may read what was ingested

This is the decision that separates a useful feature from a data leak.

Today every chunk in a knowledge base is retrievable by **every conversation of
every agent that uses it**. That is right for a public website. It is wrong for a
personal mailbox, and subtly wrong for a Drive folder whose files carry
individual sharing settings.

Plan: an explicit `audience` on the knowledge base, enforced in
`RagContextProvider`, the one retrieval choke point:

| `audience` | Who retrieves | Required for |
|---|---|---|
| `AGENT_USERS` (default, today's behaviour) | Any conversation using the knowledge base | Web, sitemap, upload, service-account sources with an operator-declared audience |
| `OWNER` | Only conversations whose `VERIFIED` user id equals the knowledge base's recorded owner | Every `PER_USER`-backed source by default. Enforced at save time: a `PER_USER` source cannot be added to an `AGENT_USERS` knowledge base, unless it carries the `sharedMailboxAcknowledged` exception of §8 |
| `DOCUMENT_ACL` (later) | Per chunk: the conversation's verified principal must appear in the chunk's `acl.principals`, directly or through a group | Drive and SharePoint where file-level sharing must hold |

Rules that hold across all three:

- **Enforcement fails closed.** An `OWNER` or `DOCUMENT_ACL` knowledge base
  queried from a conversation with no verified principal (scheduled, anonymous,
  self-asserted) returns **no context**. It never returns unfiltered context. The
  agent is told retrieval was withheld, so it does not hallucinate around the
  gap.
- **Save-time warning for service sources.** Adding a service-account source
  whose scope is a whole drive, site or mailbox to an `AGENT_USERS` knowledge
  base shows a Manager confirmation naming the consequence. It is not blocked:
  an operator indexing a shared handbook drive for every employee is making a
  legitimate choice.
- **`DOCUMENT_ACL` maps identities through token claims** (decided 2026-09-19).
  EDDI user ids are OIDC subjects. Drive ACLs are Google emails and groups;
  SharePoint ACLs are Entra object ids and groups. The mapping reads claims from
  the verified EDDI token, with no separate account-linking step. Three rules
  keep that safe:
  - **Email only when verified.** A Google principal comes from the `email`
    claim and is used only if `email_verified` is `true`. Otherwise a user who can
    set their own profile email could claim someone else's documents. No verified
    email means no ACL match, which means no context (fail closed).
  - **Microsoft by `oid`, never by email.** Entra's email and UPN are mutable
    and not guaranteed unique; the object id is. With Keycloak brokering Entra,
    an identity-provider mapper stores `oid` as a user attribute and a protocol
    mapper puts it in the EDDI token. The docs ship both mappers.
  - **Claim names are configuration.** `eddi.rag.acl.email-claim` (default
    `email`) and `eddi.rag.acl.object-id-claim` (default `oid`), because EDDI
    runs against IdPs other than Keycloak.

  Group expansion is fetched from the provider per user and cached with a short
  TTL: Graph `transitiveMemberOf`, and the Cloud Identity or Admin SDK groups API
  for Google (needs admin consent — the reason Drive `DOCUMENT_ACL` can ship
  user-level ACLs before group-level ones). The vector stores differ in filter
  support (langchain4j `Filter` works on pgvector, Qdrant, Elasticsearch, Chroma,
  Mongo Atlas and in-memory, with different performance on large `IN` lists).
  Build it once, test it against every store type, and gate it per store type if
  one cannot do it.
- **ACL drift.** Revoking a user's access to a file changes the file's
  permissions, and the next delta run must re-write that document's chunks'
  metadata. Drive and Graph both surface permission changes in their change
  feeds, but only if the connector requests them. The Drive and Graph sections
  list this explicitly.

### 3.6 Untrusted content: ingested text is attacker-writable

A web page, a shared document and above all **an email** are written by people
who are not the knowledge-base owner. Once ingested, that text is placed next to
the agent's instructions at retrieval time. This is indirect prompt injection,
and a mail source exposes it to anyone who can send an address a message.

Mitigations, in order of value:

1. **Retrieved context is framed as data.** `RagContextProvider` wraps each
   chunk in a delimiter block labelled as quoted source material. The system
   prompt snippet states that instructions inside it are not instructions. This
   is cheap, benefits every source type, and ships in Phase F.
2. **Source-level filters.** Mail: sender and domain allowlists, label or folder
   scope, spam and promotions excluded by default. Drive: folder scope, not
   drive-wide.
3. **Converter hygiene.** Hidden HTML is dropped, not converted: `display:none`,
   `visibility:hidden`, zero font size, white-on-white text, HTML comments. It is
   a common injection carrier in email. `HtmlToMarkdownConverter` gains a
   hidden-content pass with tests built from real injection samples.
4. **Guardrails hook.** When the tool-result guardrail from
   `saas-connectors-plan.md` Phase 1 exists, apply the same hook to RAG context.
   It is the same channel.

### 3.7 Content extraction beyond HTML

A converter registry keyed by MIME type, output Markdown:

| MIME | Converter | Library |
|---|---|---|
| `text/html` | existing `HtmlToMarkdownConverter` + hidden-content pass | jsoup (present) |
| `text/markdown`, `text/plain` | passthrough, charset-aware | — |
| `application/pdf` | text per page, page number kept as metadata for citations | PDFBox (present) |
| DOCX, PPTX, XLSX | paragraphs and headings; slides; sheets as Markdown tables with a row cap | Apache POI (new) |
| `message/rfc822` | headers → front matter, `text/plain` preferred over `text/html`, attachments as child documents (§8) | Jakarta Mail / Angus Mail (new) |
| Google Docs / Sheets / Slides | exported server-side by the Drive API (§6) | — |

Decisions:

- **POI over Tika.** Tika covers more formats, but its dependency tree is very
  large, it is reflection-heavy (which works against
  [`native-image-migration.md`](native-image-migration.md)), and it has a
  history of parser CVEs that Trivy would surface on every scan. Three
  well-understood formats through POI is the smaller surface. Revisit if
  operators ask for formats POI does not cover.
- **Every converter has hard limits:** bytes in, characters out, pages, sheets,
  rows, and a wall-clock budget on its own thread. Ship a zip-bomb and a
  decompression-bomb test for each format. A 5 MB DOCX can expand to gigabytes.
- **Unsupported types are counted, not errors.** `skippedUnsupportedType` on the
  run report, so a Drive full of images does not report thousands of failures.
- Converters run in the pipeline, not the connector. Connectors fetch bytes and
  declare MIME. That keeps converter tests independent of every provider.

### 3.8 Metadata and citations

Every chunk carries `documentId`, `sourceId`, `sourceType`, `title`,
`citationUri` and `modifiedAt`, plus source-specific keys (Drive path and owner,
mail `from`, `subject`, `date`, `threadId`). Two rules:

- **Metadata is retrievable text in practice.** Keys that could leak (recipient
  lists, internal paths) are opt-in per source, not default.
- **`citationUri` points at the provider**, for example the Drive `webViewLink`
  or the message's Outlook web link, so a human can open the original under their
  own permissions. It never points at an EDDI endpoint that would serve the
  content to whoever holds the link.

### 3.9 Personal data, retention, erasure

Mail and Drive content is personal data by default.

- **Retention.** `settings.maxDocumentAgeDays` excludes documents older than N
  days (by `modifiedAt`, or `date` for mail) and tombstones them as they age out.
- **Erasure.** The GDPR cascade gains a step: sources whose `runAsPrincipal` is
  the subject are deleted and their chunks purged. For mail, an erasure request
  for an external data subject's address purges chunks whose `from` or `to`
  metadata matches. This works because §3.8 stores those as structured metadata,
  not only inside the text.
- **Export.** Ingested chunks are derived data and are not added to agent ZIP
  export. Source configs are, and they carry connection **names** only, per the
  connections export rules.

### 3.10 Outbound safety

- **HTTP.** Every provider API call goes through `SafeHttpClient`, and the
  connection's `baseUrlAllowlist` must list the API origin
  (`https://www.googleapis.com`, `https://graph.microsoft.com`). Save-time
  validation checks the allowlist against the connector's declared origins, so
  the mismatch is reported when the source is saved rather than as a 401 at
  3 a.m.
- **Non-HTTP.** IMAP is a raw socket, which `SafeHttpClient` does not cover. A
  small `SafeSocketConnector` is needed:
  - resolve the host once, reject private, loopback, link-local and metadata
    ranges with `UrlValidationUtils`' rules, and connect to the resolved address
    so there is no second DNS answer;
  - ports limited to 993, or 143 with mandatory STARTTLS;
  - TLS certificate validation always on, with connect and read timeouts.

  An IMAP host is user-supplied and is otherwise an internal port scanner.
- **Budgets.** The web crawler's limits (documents, requests, bytes, deadline,
  cancellation) become `Budgets` in `SyncContext`, checked before every request
  through one `limitReached`-style method, the pattern the crawler review
  converged on. Providers throttle with `429` and `Retry-After`. Connectors
  honour it with capped exponential backoff, charge the wait to the run's
  deadline, and report `THROTTLED` as the stop reason when it runs out.

### 3.11 Large sources: resumable backfill

A 40,000-message mailbox or a drive with 200,000 files will not finish one run
under any sane `timeBudgetMinutes`. Today a run that stops early is `PARTIAL`
and starts again from the beginning next time. For delta sources, the backfill
cursor fixes this for free: the first sync is a paginated listing whose page
token is the cursor, committed after every page. Each run advances, and
coverage becomes `DELTA` only when the backfill completes. For listing sources
(sitemap, IMAP without QRESYNC), persist a **backfill offset** the same way. The
run report shows `backfillProgress` so the Manager can say "3,200 of ~40,000".

### 3.12 Observability

Extend the existing meters with a `sourceType` tag rather than adding new
names:

- `eddi.ingestion.segments.stored{knowledgeBase,source,sourceType}`
- `eddi.ingestion.errors{…,transient}`
- new `eddi.ingestion.throttled`
- new `eddi.ingestion.cursor.resets{reason}`

`MetricsDashboardCoverageTest` forces the dashboard row and the `docs/metrics.md`
entry, which is the intended effect.

### 3.13 Embedding cost control

Decided 2026-09-19. Embedding is the one cost that grows with a source's size,
and the sources this plan adds are large (a mailbox, a drive). The design
follows the `AGENTS.md` rule of dollar ceilings over counts, with one exception
where a count is the better control.

**Three layers, each answering a different failure:**

| Layer | Setting | Stops | Default |
|---|---|---|---|
| Per run | `maxSegmentsPerRun` (exists today) | A runaway single run: a sitemap that suddenly lists a million URLs, a converter bug that multiplies text | 20,000 |
| Per source, per month | `settings.maxCostPerMonthUsd` | Slow bleed across runs: a large backfill, a source whose content churns every night | Unset (off) |
| Per tenant, per month | the existing `TenantQuota.maxMonthlyCostUsd` | The deployment's total, across every source and every other metered feature | As configured by the operator |

Why not the lifetime cap proposed earlier: a lifetime budget punishes exactly
the sources that behave well. A stable source spends almost all its cost once,
on backfill, and a few cents a month after that. A lifetime cap eventually
blocks it for no reason, while a monthly cap bounds the risk that actually
exists (ongoing churn) and resets on its own.

**Cost is computed from tokens, not segments.** Embedding providers price per
token, and a segment can hold anywhere from 50 to 1,000 tokens. The pipeline
counts tokens with the embedding model's own tokenizer where langchain4j exposes
one, and falls back to a characters-per-token estimate otherwise. The price comes
from a new `costPerMillionTokens` on the knowledge base's embedding
configuration, replacing today's report-only `costPerThousandSegments`. A local
model (Ollama, in-process ONNX) has a price of zero, so only the segment cap
applies to it, which is correct.

**What happens at a ceiling:**

- The run stops before the embedding request that would cross it, with stop
  reason `BUDGET_EXHAUSTED`. Coverage is `PARTIAL`, so **nothing is tombstoned**.
  A budget stop must never read as "the documents are gone".
- The cursor or backfill offset commits up to the last embedded document, so the
  next run (in the next budget period) resumes rather than restarts.
- A metric (`eddi.ingestion.budget.exhausted{scope}`) and a run-history entry
  name the ceiling that fired, so the Manager can say "paused until 1 October:
  monthly budget of $20 reached".

**Estimate before the expensive part.** A first run or a cursor reset triggers a
full backfill. Preview mode already walks the source without embedding; it gains
a cost estimate (documents, tokens, dollars). The Manager shows it before the
first real run and asks for confirmation when it exceeds a threshold
(`eddi.rag.ingestion.confirm-above-usd`, default $10). The REST API gets the
same through an explicit `?confirmCost=true` on `run`.

**Scheduled runs need a finite ceiling, and saving one is refused without it.**
Nobody is present to confirm a scheduled run, so the per-run segment cap alone
would let a churning source spend without bound across runs. A source may
therefore be given a `cron` only when at least one monthly ceiling is finite:
`settings.maxCostPerMonthUsd`, or the tenant's `maxMonthlyCostUsd`. Otherwise
the save is refused with a 400 naming both settings. Two exemptions, checked in
this order:

- the knowledge base's embedding model has a price of zero (a local model), so
  the segment cap is the whole cost story; or
- the operator sets `settings.acknowledgeUnboundedCost: true`, which is recorded
  in the audit ledger and shown on the source in the Manager.

When a run stops, the run record names **which** ceiling fired —
`segmentsPerRun`, `sourceMonthly` or `tenantMonthly` — because "paused, budget
reached" without the name sends the operator to the wrong settings page. The
first ceiling reached stops the run; they are not additive.

**Ingestion becomes the first real consumer of tenant cost metering.**
`TenantQuotaService.checkCostBudget` exists, but its Javadoc notes that nothing
in production adds cost to it yet, so it always passes (its `recordCost` has
no production caller). Ingestion calls `recordCost` after each embedding batch
and `checkCostBudget` before the next one. This wires the tenant ceiling for real, with ingestion as
the proving ground, and lets the LLM path adopt the same accounting later.

---

## 4. Source: sitemap only

### Value

Many documentation sites publish a complete, accurate `sitemap.xml` and change
infrequently. Following links is wasteful there, and on a site with generated or
faceted navigation it is dangerous (URL explosions). A sitemap source fetches
exactly the listed URLs and nothing else. It is also the cheapest
freshness signal on the web: `<lastmod>` lets a nightly run skip unchanged pages
without even a conditional request.

It is the **first consumer of the §3.1 interface** on purpose. It exercises the
listing model, the reuse of an existing fetcher and converter, and a
lightweight cursor (the `lastmod` watermark), without credentials.

### Config

```json
{
  "type": "sitemap",
  "name": "product-docs",
  "sitemap": {
    "sitemapUrls": ["https://docs.example.com/sitemap.xml"],
    "discoverFromRobots": true,
    "includePatterns": ["/docs/**"],
    "excludePatterns": ["/docs/archive/**"],
    "sameSiteOnly": true,
    "trustLastmod": true,
    "maxUrls": 20000
  },
  "settings": { "timeBudgetMinutes": 30 },
  "cron": "0 3 * * *"
}
```

### Behaviour

- **Parsing.**
  - Handles `urlset` and `sitemapindex` recursively, depth ≤ 3, and gzip
    (`.xml.gz`). Honours the protocol limits: 50,000 URLs and 50 MB
    uncompressed per file.
  - Hard caps: `MAX_SITEMAPS` (shared with the crawler), `maxUrls`, and bytes per
    file.
  - The parser is a streaming StAX reader with DTDs and external entities
    **disabled** (the XXE rule), not jsoup's XML mode. A 50 MB sitemap must not
    be built into a DOM.
- **Change detection, cheapest first.**
  1. If `trustLastmod` is on and `<lastmod>` is not after the stored
     `modifiedAt`, emit `onUnchanged`.
  2. Otherwise send a conditional GET with the stored ETag or Last-Modified.
  3. Otherwise compare content hashes, as today.

  `trustLastmod` defaults to **true**, with a per-source escape. Some CMSs stamp
  every URL with the build time, and that only costs a full refetch; a CMS that
  never updates `lastmod` would silently freeze the source, which is why the
  switch exists. Run history reports "N skipped by lastmod", so a frozen source
  is visible.
- **Scope.** Every listed URL passes the same scope checks as crawled links
  (same site, path patterns, robots.txt). A sitemap is written by the site and
  earns no exemption; the crawler already enforces this.
- **Deletion.** `FULL_LISTING` when every sitemap in the tree was read
  successfully. A URL gone from the sitemap is a miss. Any unreadable sitemap
  makes the run `PARTIAL`, because a 503 on one sub-sitemap must not tombstone
  its whole section.
- **Reuse.** `SafeHttpPageFetcher`, `RobotsPolicy`, per-host politeness and
  `HtmlToMarkdownConverter` come unchanged from the web source. The connector
  is mostly the parser and the listing loop.

### Effort

**S** once §3.1 exists (about 3–4 days including tests). Manager: a type
selector and one small form.

---

## 5. Source: file upload

### Value

The most requested ingestion path is not a SaaS integration but "here are
twelve PDFs". Today `POST /ragstore/rags/{id}/ingest` takes **text** and
tracks nothing: no document identity, no replacement, no deletion, and an
in-memory status cache that loses state on restart. An upload source gives
uploaded files the same lifecycle as crawled pages.

### Design

- **Type.** `type: "upload"`. Its documents are files stored through the
  existing attachment storage (`IAttachmentStore`) with a per-source quota.
  `documentId` is a server-generated id, stable across re-uploads of the same
  logical file (`PUT …/sources/{sourceId}/files/{fileId}` replaces its content).
- **Endpoints** (EDIT permission):
  - `POST …/files` — multipart upload, several files at once
  - `PUT …/files/{fileId}` — replace
  - `DELETE …/files/{fileId}` — explicit tombstone and chunk removal
  - `GET …/files` — list with status

  Upload enqueues a run of that source. It does not ingest inline.
- **Deletion.** Explicit only. No listing-absence semantics.
- **Converters.** This is the phase that lands PDF (PDFBox) and DOCX, PPTX and
  XLSX (POI) from §3.7. Upload is the simplest way to exercise them.
- **Limits.**
  - bytes per file (default 25 MB) and files per source;
  - total bytes per source;
  - MIME sniffed from the bytes (reuse the attachment module's
    `MimeValidator`), never trusted from the client;
  - the converter limits from §3.7.
- **Retire the old endpoint.** Deprecate the text `ingest` endpoint in favour of
  an upload source with one text document. Keep it working, marked deprecated,
  for one release.

### Effort

**M** (about 1–1.5 weeks), most of it converters and their bomb tests. Manager:
a drop zone on the source, a file list with per-file status, delete.

---

## 6. Source: Google Drive

### Value

Handbooks, specs, runbooks and policies. The Shared Drive case is the core
enterprise use: a team's documents become an agent's knowledge base, kept
current without anyone exporting anything.

### Scope and config

```json
{
  "type": "googleDrive",
  "name": "support-handbook",
  "googleDrive": {
    "connection": "google-drive-sa",
    "sharedDriveId": "0AB…",
    "folderIds": ["1xY…"],
    "includeSubfolders": true,
    "mimeTypes": ["google-docs", "google-sheets", "google-slides", "pdf", "docx", "text"],
    "excludeNamePatterns": ["*DRAFT*"],
    "captureAcl": false
  }
}
```

A scope is always **a Shared Drive and/or explicit folders**. There is no "the
whole My Drive" option in phase one. The narrowest scope that serves the use
case is the default, and a service account sees nothing it was not explicitly
added to.

### Auth

| Phase | Connection | Notes |
|---|---|---|
| 6a | `OAUTH2_CLIENT_CREDENTIALS`-equivalent **service account** with `SERVICE` binding | Google service accounts authenticate with a **signed JWT assertion** (RFC 7523), not `client_credentials`. The connections framework needs a new `AuthType`, `OAUTH2_JWT_BEARER`: a private key held in the vault, signed per request, exchanged at `oauth2.googleapis.com/token`. `AuthType.isOAuth()` is already written for a third OAuth type. The service account is added as a member of the Shared Drive, which needs no Workspace admin involvement. |
| 6b | `OAUTH2_AUTHORIZATION_CODE`, `PER_USER` | "Index my own folders" for a personal, `audience: OWNER` knowledge base (§3.4, §3.5). Scope `drive.readonly`. |
| — | Domain-wide delegation | Deliberately **not** offered. It lets a service account impersonate any user in the domain. That is an admin-grade capability, and an ingestion source is the wrong place to hold it. |

Scope note: `drive.readonly` is a Google **restricted** scope. It is fine for an
internal Workspace app and for self-hosted deployments using their own OAuth
client. It needs Google's security assessment only for a publicly distributed
app, which EDDI is not. Operators register their own client. Document that.

### Discovery and change detection

- **Backfill.** `files.list` with `q` scoped to the folders (for Shared Drives:
  `corpora=drive`, `driveId`, `includeItemsFromAllDrives`,
  `supportsAllDrives`). Folder recursion is breadth-first with a cycle guard,
  because Drive shortcuts can create cycles. The page token is the backfill
  cursor (§3.11).
- **Incremental.**
  - `changes.getStartPageToken` at the start of the backfill, then
    `changes.list` from the stored token. The next-page / new-start token is the
    cursor.
  - A change whose file is removed, trashed, or has moved **out of scope**
    becomes `onDeleted`. A move out of a scoped folder is a deletion *for this
    source*.
  - A change whose file moved into scope is a new document.
  - Deciding whether a changed file is in scope needs its `parents`. Resolve
    ancestry against a per-run folder cache.
- **Version token.**
  - `headRevisionId` for binary files, `version` for Google-native files.
  - `md5Checksum` confirms binary content where present.
  - Google-native files have no checksum, so the content hash of the export
    decides.

### Content

- **Google Docs.** `files.export` to `text/markdown`, falling back to
  `text/html` into the HTML converter for older API behaviour. Export has a
  **size limit** (verify the current value at implementation time). A document
  over it is skipped and counted, not truncated silently.
- **Sheets.** Export CSV per sheet, rendered as Markdown tables with a row cap.
  A 100k-row sheet is data, not knowledge, and a source setting opts out of
  sheets entirely.
- **Slides.** Export plain text per slide, with the slide number as citation
  metadata.
- **Binary files.** `files.get?alt=media`, then the §3.7 converters.
- **Citation.** `webViewLink`. Metadata: path (folder names), owner display
  name, `modifiedTime`, `mimeType`.

### Permissions (`captureAcl`, with `DOCUMENT_ACL`)

With `permissions.list` per file (or `fields=permissions` on the listing), the
connector records user and group emails, domain, and `anyone` as `acl`
principals. Permission changes appear as file changes in `changes.list` only if
the fields request asks for `permissions`. The connector requests them whenever
`captureAcl` is on, so ACL drift (§3.5) is caught by the delta run and not only
by the weekly full resync.

### Failure modes worth tests

- A token revoked mid-run: the run fails as `NOT_CONNECTED` and the cursor is
  not advanced.
- `403 rateLimitExceeded` versus `403 insufficientPermissions`. Both are 403, but
  only the first is transient. Classify by the error `reason`, not the status.
- A file with an export-size overflow, an encrypted PDF, or a folder shortcut
  cycle.
- The service account removed from the Shared Drive: the listing returns
  nothing. Before trusting absence, a zero-result `FULL_LISTING` of a
  previously non-empty source must re-check drive access explicitly
  (`drives.get`); otherwise it is the outage-as-coverage bug the crawler review
  already found, in a new place.

### Effort

- 6a: **M–L** (about 2 weeks), including the JWT-bearer `AuthType`.
- 6b: **M** on top of the §3.4 and §3.5 `OWNER` machinery.
- `captureAcl` with `DOCUMENT_ACL` retrieval: **L**, shared with §7.

---

## 7. Source: Microsoft OneDrive and SharePoint

### Value

The same as Drive for Microsoft 365 organisations. Document libraries on
SharePoint sites are where most of their curated knowledge sits.

### One connector, two scopes

Both are Graph `drive` resources, so one `microsoft365Files` source type covers
both:

```json
{
  "type": "microsoft365Files",
  "name": "hr-policies",
  "microsoft365Files": {
    "connection": "graph-app",
    "site": "contoso.sharepoint.com:/sites/HR",
    "driveName": "Documents",
    "folderPath": "/Policies",
    "includeSubfolders": true,
    "captureAcl": false
  }
}
```

A personal OneDrive (6b-equivalent) is the same connector with a `PER_USER`
connection and `drive: "me"`.

### Auth

| Mode | Connection | Least privilege |
|---|---|---|
| Organisational, SharePoint | App registration with **application** permissions, `OAUTH2_CLIENT_CREDENTIALS`, `SERVICE` | **`Sites.Selected`**, granted per site by an admin. Never `Sites.Read.All` or `Files.Read.All` as a default recommendation. The docs must show how to grant a single site. |
| Personal OneDrive | Delegated `Files.Read` + `offline_access`, `OAUTH2_AUTHORIZATION_CODE`, `PER_USER` | `audience: OWNER` only (§3.5) |

Entra `client_credentials` already works with the shipped
`OAUTH2_CLIENT_CREDENTIALS` type (token endpoint
`login.microsoftonline.com/{tenant}/oauth2/v2.0/token`, scope
`https://graph.microsoft.com/.default`), so the organisational mode needs **no
new auth work**. That makes SharePoint the cheapest authenticated source to
ship. Certificate-based client auth (a JWT client assertion) is a later option
that reuses the `OAUTH2_JWT_BEARER` signing from §6.

### Discovery and change detection

- **Resolve IDs once at save time.** Site path → site id, drive name → drive
  id, folder path → item id. Ids are stored in the source and survive renames. A
  resolution failure is a save-time 400 naming the missing permission or path.
- **Backfill and incremental in one mechanism.** Graph's `delta` on a folder
  (`/drives/{id}/items/{folderId}/delta`) does both: the first call pages
  through everything (the `@odata.nextLink` is the backfill cursor), and the
  final `@odata.deltaLink` is the incremental cursor.
  - Items with a `deleted` facet become `onDeleted`.
  - `410 Gone` / `resyncRequired` becomes `CURSOR_INVALID` (§3.2).
- **Version token.** The item's `cTag` (content tag) changes on content changes
  only, while `eTag` also changes on metadata. Use `cTag` to decide re-ingestion
  and `eTag` to refresh metadata such as ACLs.
- **Throttling.** Graph returns `429` and `503` with `Retry-After`. It is
  honoured, not overridden.

### Content

- Download with `/content`, which returns a redirect to a pre-authenticated
  download URL. That redirect goes to a **different host** (SharePoint CDN
  domains). `SafeHttpClient` strips `Authorization` on cross-origin redirects,
  which is correct and required here, because the URL is pre-signed. The CDN
  origin must still pass `validateUrl`.
- Office formats go to POI, PDF to PDFBox. Graph's `?format=pdf` conversion is a
  fallback for formats POI does not read.
- **Citation.** `webUrl`. Metadata: site name, library, path,
  `lastModifiedBy.user.displayName`.

### Permissions

`/items/{id}/permissions` gives users, groups (Entra object ids) and sharing
links. Sharing links ("anyone with the link", "people in the organisation")
need their own ACL principals (`link:anonymous`, `link:organization`). Mapping
EDDI identity to Entra `oid` requires the `oid` claim in the EDDI token.
Keycloak with Entra as its IdP can map it. Document the mapper. Shared
`DOCUMENT_ACL` work with §6.

### Effort

**M** (about 1.5 weeks) for the organisational SharePoint mode, which is the
recommended first authenticated source (§10). Personal OneDrive: **S** on top
of §3.4 and §3.5.

---

## 8. Source: email — Gmail, Microsoft 365, IMAP

### Value

A support team's mailbox is its knowledge base in practice: every question
customers ask and every answer the team wrote. Ingested with care, it lets an
agent draft answers grounded in how the team actually responded. It is also
**the highest-risk source in this plan**: attacker-writable (§3.6), dense with
personal data (§3.9), and scoped to people rather than documents (§3.5).
Sequence it last, after the foundation has been hardened on documents.

### One source type, three transports

```json
{
  "type": "mail",
  "name": "support-inbox",
  "mail": {
    "transport": "microsoft365",
    "connection": "graph-mail-app",
    "mailbox": "support@contoso.com",
    "folders": ["Inbox", "Sent Items"],
    "senderAllowlist": ["*@customers.contoso.com"],
    "excludeCategories": ["spam", "promotions"],
    "documentGranularity": "thread",
    "includeAttachments": true,
    "attachmentMimeTypes": ["pdf", "docx", "text"],
    "maxThreadAgeDays": 365,
    "redactPatterns": ["iban", "creditCard"]
  }
}
```

`transport` is `gmail`, `microsoft365` or `imap`. Everything below the transport
(threading, conversion, filters, redaction, attachments) is shared code. Only
listing and fetching differ.

### Documents: thread, not message

**Recommendation: one document per thread**, re-ingested whenever a message
joins it. A single reply ("Yes, that worked, thanks!") is meaningless as a
retrieval unit; the thread is the question-and-answer pair. `documentId =
threadId`, version token = the newest message id in the thread. Chunking splits
long threads at message boundaries and keeps `from`/`date` headers on each
chunk. Attachments are **child documents** (`{threadId}/{attachmentId}`) with
their own converters and citation back to the thread.
`documentGranularity: "message"` stays as an option for mailboxes that are
notification streams rather than conversations.

**A deletion is a thread rebuild, not a tombstone.** Gmail, Graph and IMAP all
report deletions and scope changes **per message**, while the document is the
thread. So a mail connector never maps a message event straight to
`onDeleted`. On any message deletion, label or folder removal, or filter change
that takes a message out of scope, it re-reads the thread's remaining in-scope
messages and:

- emits `onDocument(threadId)` with the rebuilt thread when any remain — the
  thread is smaller, not gone;
- emits `onDeleted(threadId)` only when none remain;
- emits `onDeleted` for a child attachment only when it is absent from the
  rebuilt message set, so an attachment on a surviving message is never removed.

Without this, deleting one reply from a thread would delete the whole
conversation from the knowledge base, which is the `DELTA` contract of §3.3
applied at the wrong granularity. `documentGranularity: "message"` has no such
problem and maps events directly.

**Quoted history is removed.** Every reply repeats the thread below it. Strip
quoted blocks (`>`-prefixed text, Outlook's "From: … Sent: …" separators, Gmail's
`gmail_quote` div) before chunking, or every thread's first message is embedded
N times.

### Transports

**Microsoft 365 (Graph) — recommended first transport.**

- **Auth.** Application permission `Mail.Read`, `SERVICE`, **restricted to the
  named mailboxes** with Exchange Online's application access controls (RBAC
  for Applications, the successor to Application Access Policies; verify current
  guidance at implementation time). Without that restriction, an app with
  `Mail.Read` can read **every mailbox in the tenant**. The Manager and docs must
  make the restriction a setup step, not a footnote. Save-time validation cannot
  detect an unrestricted grant. Say so plainly, and recommend testing it by
  reading an unrelated mailbox and expecting a 403.
- **Listing.** `/users/{mailbox}/mailFolders/{id}/messages/delta` per folder,
  with `deltaLink` as the cursor and `@removed` as `onDeleted`.
  `conversationId` is the thread key.
- **Content.** `body` (HTML), converted with the hidden-content pass (§3.6),
  or `/$value` for full MIME when attachments matter.
- **Delegated per-user mode** (`Mail.Read` + `offline_access`, `PER_USER`,
  `audience: OWNER`) comes in a later phase, as for Drive.

**Gmail.**

- **Auth.** A shared support mailbox in Workspace has no service-account path
  without domain-wide delegation, which §6 rules out. So Gmail is **per user**:
  a `PER_USER` grant with `gmail.readonly` (a restricted scope, same note as
  §6) held by the mailbox's own account. For a shared mailbox that is a
  Workspace account an operator signs into once.
- **Shared mailboxes are supported** (decided 2026-09-19). A shared support
  inbox on Gmail may feed an `AGENT_USERS` knowledge base through the flag
  `sharedMailboxAcknowledged`, the one documented exception to the §3.4
  owner-only rule. It is guarded as follows:
  - Only `eddi-admin` may set it. EDIT permission on the knowledge base is not
    enough, because the flag widens who can read someone's mail.
  - The configured `mail.mailbox` must equal the address the grant actually
    belongs to, read from Gmail `users.getProfile` on save and again on every run.
    A grant re-linked to a different account stops the source rather than
    silently ingesting the new mailbox.
  - The mailbox must **not** be the linking user's own login address: if it
    equals the verified `email` claim of the EDDI user who linked the grant, the
    flag is refused. That is a person's primary mailbox, not a shared one, and it
    stays `OWNER`-only.
  - Setting or clearing the flag writes an audit-ledger entry, and the Manager
    shows the knowledge base with a "shared mailbox" badge.
- **Listing.** `users.messages.list` with `q` (labels, `after:`, category
  filters) for backfill, `users.history.list` from the stored `historyId` for
  incremental. `404` on a stale `historyId` becomes `CURSOR_INVALID`.
  `messagesDeleted` and `labelsRemoved` (out of scope) become `onDeleted`.
- **Content.** `messages.get?format=raw`, parsed with Jakarta Mail (§3.7).
  `threadId` is native.

**IMAP (generic).**

- **Auth.** App password through a `STATIC`/`BASIC` connection, or OAuth
  `XOAUTH2` (Gmail and Outlook IMAP). XOAUTH2 needs the **bare** access token,
  and a connection currently resolves to a header value. Add
  `ConnectionResolver.resolveAccessToken(...)`, legal only for OAuth auth types.
  The scheme is known there, so no guessing is involved (compare the LLM
  limitation in `docs/connections.md`).
- **Transport.** Through `SafeSocketConnector` (§3.10) with TLS mandatory. The
  host must be on the connection's allowlist, which gains `imaps://host:993`
  origins.
- **Listing.**
  - `UIDVALIDITY` + `UIDNEXT` per folder, with `HIGHESTMODSEQ` under
    `CONDSTORE`. The cursor is the triple.
  - With `QRESYNC` (RFC 7162), `VANISHED` responses become `onDeleted`, so
    coverage is `DELTA`.
  - Without it, deletions need a UID listing each run: `FULL_LISTING` coverage.
  - A changed `UIDVALIDITY` becomes `CURSOR_INVALID`.
- **Threading.** No native thread id. Derive it from `References` /
  `In-Reply-To`, falling back to a normalized subject within a time window.
  Imperfect by nature, and documented as such.

### Mail-specific safeguards (in addition to §3)

- **Injection.** Default filters exclude spam and promotions, and the sender
  allowlist is **recommended in the UI whenever the mailbox receives external
  mail**. Headers are converted to structured front matter; they are never
  concatenated into prose that reads like an instruction.
- **Redaction before embedding.** `redactPatterns` runs deterministic detectors
  (IBAN, card numbers with a Luhn check, national id formats per configured
  locale, e-mail addresses in quoted signatures). The fixed-width replacement is
  applied to text **before chunking**, so the vector store never holds the
  original. EDDI has no PII detectors today; `SecretScrubber` targets
  credentials, not personal data. Build the detectors as one shared component
  that other features (log redaction, exports) can adopt, not as mail-private
  code.
- **Attachments.** MIME allowlist, size cap, converter limits (§3.7). Never
  executable types. Archives are not expanded (zip bombs, and no value).
- **Retention.** `maxThreadAgeDays` is required for mail (there is no
  "infinite" default), and ageing out tombstones (§3.9).
- **Erasure by correspondent.** An erasure request for
  `alice@customer.example` purges every chunk with that address in
  `from`/`to`/`cc` metadata. §3.8's structured metadata is a prerequisite, not
  a nicety.

### Effort

- Microsoft 365 transport with shared mail core: **L** (about 2.5–3 weeks,
  including threading, quote stripping, redaction and attachments).
- Gmail: **M** on top, plus §3.4 per-user.
- IMAP: **M**, most of it `SafeSocketConnector` and CONDSTORE/QRESYNC handling.

---

## 9. Further candidates

Shorter treatment. Each fits §3 without new foundation work unless noted.

| Source | Value | Auth | Change detection | Notes | Effort |
|---|---|---|---|---|---|
| **Confluence (Cloud)** | The most common enterprise wiki | Atlassian OAuth 2.0 (3LO) or API token (`BASIC`, email + token) | CQL `lastmodified > {cursor}` for changes; space listing for deletions (`FULL_LISTING` weekly) | Page body in `storage` format (XHTML) → HTML converter; attachments as children; page restrictions for `DOCUMENT_ACL` later | M |
| **Notion** | Popular team wiki | Internal integration token (`STATIC`), shared per page by the workspace owner | `search` sorted by `last_edited_time` | Blocks API is per block and deeply paginated: render blocks to Markdown with a depth cap. Rate limit is low (~3 req/s), so backfills are slow by design | M |
| **S3-compatible object storage** | Documents already exported to a bucket; the "bring your own pipeline" path | AWS SigV4. Needs a new signing `AuthType`, or reuse of the AWS SDK credential chain for EDDI-hosted buckets | `ListObjectsV2` + `ETag`; bucket notifications later | Also MinIO, R2, GCS interop. SSRF: endpoint on the allowlist | M |
| **Git repositories** | Docs-as-code (`/docs/**/*.md`) | Deploy token or GitHub App (`STATIC`) | Commit SHA as cursor; `compare` API for changed paths | Markdown passthrough; `citationUri` = blob URL at the default branch | S–M |
| **Slack channels** | Support channel history | The bot token of the existing Slack channel integration, or a dedicated read-only bot through a `STATIC` connection | `conversations.history` with `oldest` = cursor | High injection risk (like mail); thread = document; `audience` decision as for mail | M |
| **Zendesk / Freshdesk** | Solved tickets and help-centre articles | API token | Incremental export APIs (native cursors) | Help-centre articles are low-risk and high-value; tickets carry mail-like risks | M |
| **Dropbox, Box** | Same shape as Drive and OneDrive | OAuth (`PER_USER`, or an app with team scope) | Both have cursor-based delta APIs | Build only on demand, after §6 and §7 prove the file-connector pattern | M each |

**Buy versus build for the long tail.** For sources beyond this table, the
`saas-connectors-plan.md` §4 Option D reasoning applies. A managed connector
platform or an MCP server that exposes documents as **resources** could feed a
generic `mcpResources` source type: list resources, read resource, version by
the server's own metadata. That one type would cover every MCP server that
publishes documents. It is a cheaper long-tail answer than writing twenty
connectors, with the caveat that a third party sits in the content path.

---

## 10. Phasing and effort

Ordered so every phase ships something usable, and the riskiest source lands on
the most-tested foundation.

| Phase | Content | Effort | Depends on |
|---|---|---|---|
| **F** | §3.1 connector interface (web source moved onto it, tests unchanged); §3.2 cursors; §3.3 two deletion models; §3.6 (1) context framing; §3.10 budgets; §3.12 meters; §3.13 token-based cost and monthly caps. First consumer: **sitemap** (§4) | M (about 1.5–2 weeks) | Ingestion PRs merged |
| **U** | **Upload** source (§5) + the §3.7 converters (PDF, DOCX, PPTX, XLSX) with bomb tests | M | F |
| **S1** | **SharePoint, organisational mode** (§7): proves authenticated `SERVICE` sources with no new auth type | M | F, U (converters) |
| **G1** | **Google Drive, Shared Drives** (§6a) + `OAUTH2_JWT_BEARER` auth type | M–L | F, U |
| **A** | §3.5 `audience` (`OWNER`) + §3.4 run-as grants + §3.9 erasure hooks. Enables personal OneDrive / My Drive | M | S1 or G1 |
| **M1** | **Mail, Microsoft 365 transport** (§8) + mail core + §3.6 (2)(3) | L | A (for erasure and audience), U |
| **M2** | Gmail transport, IMAP transport + `SafeSocketConnector` | M + M | M1 |
| **ACL** | `DOCUMENT_ACL` retrieval, identity mapping, group expansion, tested against every vector store | L | S1, G1, A |
| **X** | §9 candidates on demand; `mcpResources` generic source | S–M each | F |

Recommended first three after F: **Upload → SharePoint → Drive**. Upload
delivers the converters that every authenticated source needs and answers the
most common request. SharePoint needs no auth work. Drive then adds the one new
auth type. Mail waits for `audience`, erasure and the injection defences to have
met real traffic.

---

## 11. Testing strategy

The crawler set the bar: behaviour is tested through a fake at the connector's
own seam (`FakeSite`), with no network and no containers, and every fix is
checked by removing it and confirming the test fails. Keep that bar.

- **One fake per provider API**, at the HTTP boundary. Each connector takes an
  injectable transport, like `PageFetcher`, and the fake serves recorded,
  hand-minimised JSON. Examples: `FakeDrive` (files, folders, shortcuts,
  changes feed with removals and moves, export size errors, 403 reasons),
  `FakeGraph` (delta paging, `410 resyncRequired`, 429 with `Retry-After`, CDN
  redirect), `FakeImapServer` (in-process, CONDSTORE and QRESYNC on/off,
  `UIDVALIDITY` change).
- **Recorded fixtures stay legal.** Fixtures are synthetic or scrubbed. No real
  mail or document content, and no real ids that could be personal data,
  enters the repository. Gitleaks and a fixture lint enforce it.
- **Shared contract tests** for every connector: `SourceConnectorContract`,
  mirroring `IngestionStateStoreContract`.
  - A source listed twice unchanged produces zero writes.
  - A cursor survives a failed run unadvanced.
  - An invalid cursor forces a full listing.
  - An explicit deletion tombstones immediately.
  - A `PARTIAL` run never tombstones.
  - Budgets stop every request type (the lesson of the crawler review).
- **Converter safety suite.** Every format ships with a decompression bomb, an
  oversized file, an encrypted file and a malformed file, each asserting a
  bounded failure, not an `OutOfMemoryError`.
- **Audience enforcement tests**, per vector store type: an `OWNER` knowledge
  base queried by another verified user, by an anonymous conversation and by a
  scheduled turn returns nothing. `DOCUMENT_ACL` gets the same matrix with group
  membership.
- **Injection regression set.** A corpus of known indirect-injection emails and
  documents (hidden text, instruction-shaped headers) through the converter,
  asserting that the hidden carriers are gone.
- **Live smoke tests are manual and out of CI.** A documented script per
  provider against a sandbox tenant (Microsoft 365 developer tenant, a test
  Workspace), run before a release that touches the connector. Credentials never
  live in the repository or in CI secrets for this purpose.

---

## 12. What I would NOT do

- **Not ship a per-user source into a shared knowledge base.** A personal grant
  feeds only an `OWNER` audience (§3.4, §3.5). The single documented exception
  is §8's admin-acknowledged shared mailbox, with its address checks.
- **Not offer domain-wide delegation, `Sites.Read.All`, `Files.Read.All`, or
  unrestricted `Mail.Read` as a default** or in a quick-start. Least privilege
  (`Sites.Selected`, Shared Drive membership, mailbox-restricted app access) is
  the documented path, and the broad variants are not mentioned as shortcuts.
- **Not add Apache Tika** for format coverage (§3.7) until POI demonstrably
  falls short.
- **Not build push notifications** (Drive watch, Graph subscriptions, Gmail
  Pub/Sub) before cursors have proven sufficient. They need a public callback
  endpoint and subscription renewal, which is a new inbound surface with its own
  security review.
- **Not let connectors call the vector store.** Connectors emit documents; the
  pipeline owns hashing, conversion, chunk replacement, tombstoning and
  metrics. One place to get those right.
- **Not trust absence from an empty listing.** A previously non-empty source
  that suddenly lists nothing re-checks access before any tombstoning. This is
  the crawler's outage-as-coverage lesson, applied to every source type.
- **Not store raw provider payloads.** Only converted text (as chunks) and the
  metadata of §3.8. The original stays at the provider, reachable through
  `citationUri` under the reader's own permissions.
- **Not make redaction an LLM call.** Deterministic detectors before embedding;
  an LLM classifier is slow, costly, non-reproducible, and itself an injection
  target.

---

## 13. Open questions

1. **Audience default for new service sources.** Should a new Drive or
   SharePoint source default its knowledge base to `AGENT_USERS` with a warning
   (proposed), or require an explicit choice every time?
2. ~~Identity claims for `DOCUMENT_ACL`~~ — **decided 2026-09-19:** token
   claims, verified email for Google, `oid` for Microsoft, claim names
   configurable (§3.5).
3. ~~Shared mailboxes on Gmail~~ — **decided 2026-09-19:** supported, through
   the admin-only `sharedMailboxAcknowledged` flag with address checks (§8).
4. ~~Cost ceilings~~ — **decided 2026-09-19:** no lifetime cap. A per-run
   segment cap, a per-source monthly dollar cap, and the tenant monthly budget,
   with a cost estimate and a confirmation before large backfills (§3.13).
5. **Group conversations.** Inherited from `docs/connections.md`: when a group
   member agent retrieves from an `OWNER` knowledge base, whose identity counts?
   Proposed: the verified user of the group conversation, and nothing if none —
   but it needs a decision alongside the connections question.
6. **Multi-tenancy.** Sources, cursors and grants must carry `tenantId` when
   `multi-tenancy-plan.md` Phase 1 lands. Register the new cursor store in that
   plan's store table rather than inventing scoping here.

# Multimodal Attachments — Completion Plan v3 (Unified Upload → LLM, 1:1 + Groups)

> **Status:** Planning only. No code changes yet.
> **v3 (second critical pass):** Major corrections vs v2 —
> (1) **Unify the two parallel blob-store abstractions** (`IAttachmentStore` vs `IAttachmentStorage`) — today conversation-deletion/GDPR cascade through a *different* store than the one uploads write to;
> (2) **Ownership reverted to conversation-scoped + explicit member grants** — v2's user-scoped model broke anonymous deployments and invited a context-injection privilege escalation;
> (3) **Staging upload dropped** — 1:1 conversations exist at compose time; groups get multipart create-and-discuss;
> (4) `text/*` inline support, uniform size guards, image-URL provider normalization, first-class `attachments` field on both APIs, no-silent-failure rules.
> **Goal:** A user can *always* upload a file (image, PDF, text/docs) and have it forwarded to the LLM appropriately — in **both** 1:1 and group conversations — on **any** configured LLM provider. Eventually: polished upload UX in EDDI-Manager and eddi-chat-ui.
> **Builds on:** [`agentic-improvements-plan.md` §7](agentic-improvements-plan.md).
> **PDF strategy:** **Hybrid** — native `PdfFileContent` where supported, PDFBox text-extraction fallback everywhere else.

---

## 1. Verified current state

Four disconnected mechanisms; only 1:1 inline images work end-to-end.

| Mechanism | Trigger | Types forwarded | Source | 1:1 | Group |
| --- | --- | --- | --- | --- | --- |
| **A. `attachment_*` context** (documented) | `say` context key | image → `ImageContent`; else text placeholder | base64 / URL | ✅ | ❌ |
| **B. `inputFiles` context** (undocumented) | `say` context key | image/pdf/audio/video native, re-attached to history each turn | **URL only** | ⚠️ hidden | ❌ |
| **C. `PdfReaderTool`** (`@Tool`) | LLM tool call | pdf → extracted text (10k cap) | **URL only** | ⚠️ tool-mode | ⚠️ |
| **Upload endpoint** | multipart | stores blob, returns `storageRef` | blob | **unconsumed** | ❌ |

### Root defects (all verified against code)

1. **TWO parallel blob-store abstractions** *(new in v3 — highest structural priority)*:
   - `IAttachmentStore` (`engine/attachments/`) → `GridFsAttachmentStore` (bucket `attachments`, plain-hex refs) + `PostgresAttachmentStore` (`attachments` table, UUID refs). Consumers: `RestAttachmentUpload`, `LlmTask`/`MultimodalMessageEnhancer`.
   - `IAttachmentStorage` (`engine/memory/`) → `MongoAttachmentStorage` (`gridfs://` refs) + `PostgresAttachmentStorage` (`pg://` refs). Consumers: **conversation-deletion cascade** (`RestConversationStore:369`) and **GDPR erasure** (`GdprComplianceService`).
   - Consequence: **uploaded blobs are NOT deleted by conversation deletion or GDPR erasure** (cascades run against the other store). The docs' `"gridfs://…"` example response is abstraction #2's format; the real endpoint returns #1's. Violates AGENTS.md "unification over duplication".
2. **Upload is orphaned.** `AttachmentContextExtractor` parses only `url`/`data` — no `storageRef` branch; the enhancer's `STORED` branch is unreachable; docs "Path C" (storageRef-as-url) produces unfetchable `ImageContent.from("gridfs://…")`.
3. **PDF/audio/text dropped.** Enhancer emits real content only for `image/*`; all else → `[Attachment: …]` placeholder. `text/*` (txt/csv/md/json — the most common drops) not even considered.
4. **No capability gate.** `LlmTask:339` forwards unconditionally; non-vision models error.
5. **Groups have zero plumbing**, and **strict per-conversation blob ownership would block member loads anyway** (both `GridFsAttachmentStore.load` ~108-117 and `PostgresAttachmentStore.load` ~135-140 reject requester ≠ owner; §7.2's "permitted peer" never implemented; members run in own conversations, `GroupConversationService:1393-1407`).
6. **Upload endpoint unauthenticated**, client-trusted `tenantId`, no quotas.
7. **Inline base64 persists into Mongo conversation documents**: raw `attachment_*` context map stored via `addContextToConversationOutput` (`Conversation.java` ~239/265-271) — ~1.33× file size per turn against the 16 MB doc limit, template-exposed via `context.*`. And `Attachment.base64Data`'s `transient` keyword does **not** stop Jackson (no `PROPAGATE_TRANSIENT_MARKER` configured; getter-based serialization) — the "never persisted" comment is false.
8. **No size guard on the inline base64 forward path** (only STORED has the 10 MB check).
9. **Multi-turn context loss**: attachments attach only on their turn; history rebuilds text-only. (Mechanism B's history re-attachment is the exception — must be preserved intentionally during unification.)
10. **Body-size mismatch**: `quarkus.http.limits.max-body-size` unset (Quarkus default 10 MB) < `eddi.attachments.max-size-bytes` default (20 MB) → 10–20 MB uploads die with a bare 413.
11. `Attachment.java`'s "attachments inherit the conversation's TTL" comment is aspirational — no TTL mechanism exists; a reaper is required.

---

## 2. Target architecture

```
 1:1 (conversation exists at compose time)          GROUP (created with the request)
 ──────────────────────────────────────────         ─────────────────────────────────────────
 POST /conversations/{convId}/attachments           POST /groups/{groupId}/conversations
   (multipart, authenticated, quota-checked)          multipart: question + file parts
   → storageRef + validated metadata                  (or JSON with inline base64/url refs)
        │                                             → server creates groupConversation,
        ▼                                               stores files bound to gcId,
 POST /agents/{convId}/say                              GRANTS each member conversation,
   { input, attachments: [{storageRef}] }               runs discussion
   (first-class field; attachment_* context                     │
    kept as compat alias)                                       │ member turn: attachment_*
        │                                                       │ injected into member context
        ▼                                                       ▼
              AttachmentContextExtractor (+ getMetadata for storageRef —
              server-resolved MIME/name/size BEFORE behavior rules; per-turn cap;
              bad refs surface as attachment errors, never silent)
                          │   persisted copies scrubbed of base64 payload
                          ▼
              memory ATTACHMENTS → LlmTask → AttachmentForwarder (ONE forwarder)
                 │ per attachment (uniform per-file + aggregate byte caps, all sources):
                 │  bytes: STORED → store.load (owner-or-grant authz)
                 │         URL    → provider-fetch if supported, else SafeHttpClient download
                 │         BASE64 → decode
                 │  gate: ModelCapabilityService(provider, model) ⊕ per-task override
                 │  ├─ image/*          → vision? ImageContent (url|b64 per provider) : note+hint
                 │  ├─ application/pdf  → documents? PdfFileContent : PDFBox text → TextContent
                 │  ├─ text/*,json,csv,xml → decode + inline as TextContent (cap)   ← always works
                 │  ├─ audio/*          → audio? AudioContent : note+hint
                 │  └─ else             → metadata note + readAttachment hint
                 │  outcome + extracted text persisted to step data (history, UI, audit)
                 ▼
              UserMessage.from([text, …contents]) → provider ChatRequest

 Multi-turn: extracted text rides history naturally; readAttachment tool pulls any
             conversation attachment on demand in later turns; optional reattachTurns.
```

---

## 3. Design decisions

- **D1 — One store.** Unify on `IAttachmentStore` (richer API, both backends exist). Port the two `IAttachmentStorage` consumers (conversation-deletion cascade, GDPR erasure) to it; delete `IAttachmentStorage` + its impls after a dead-write-path check (grep for writers first; believed write-dead — refs in the wild unlikely). Single ref format. **This precedes everything else.**
- **D2 — Conversation-scoped ownership + explicit grants** *(v2's user-scoping reverted)*. Blobs belong to the conversation they were uploaded to. New primitive: `grantAccess(storageRef, conversationId)` / authz = owner ∨ grant. Grants are written **only by trusted server code** — `GroupConversationService` at fan-out (per member conversation; nested groups re-grant with `depth+1`) — never derivable from client-supplied context (a forwarder that trusted `groupConversationId` from say-context would be a privilege escalation). Works identically in anonymous and authenticated deployments. Uploader `userId` recorded as optional audit metadata when authenticated — not an access boundary.
- **D3 — No staging area** *(v2 reverted)*. 1:1: the conversation exists when composing (created at chat-open; `CONVERSATION_START` fires at init). Groups: **multipart create-and-discuss** — files travel with the question; the server binds them to the group conversation it creates. Fewer states, no staging-orphan class, native upload progress on the one request.
- **D4 — Server-resolved metadata for stored refs.** Client sends only `{storageRef}`; MIME/fileName/size come from validated store metadata (`getMetadata`) at **extraction** time, so `contentTypeMatcher` rules and the forwarder see the truth. No client MIME for stored files (spoofing); no `ref` alias.
- **D5 — First-class `attachments` field on both APIs.** `InputData.attachments` and `DiscussRequest.attachments` (`List<AttachmentRef>`; `AttachmentRef = {storageRef} | {mimeType,url} | {mimeType,data,fileName}`) — additive, non-breaking; mapped to the internal `attachment_*` transport at the REST boundary. `attachment_*` context keys remain as compat alias.
- **D6 — Hybrid documents, universal text.** PDF: native `PdfFileContent` when `(provider, model)` supports documents, else PDFBox extraction. `text/*` + json/csv/xml: always decode + inline (capped) — no capability needed. Office docs: extraction later (extractor API ready), metadata note meanwhile.
- **D7 — Capability = `(provider, model)`, agent-configurable, URL-aware.** `ModelCapabilityService.supportsVision/Documents/Audio/ImageUrl` with conservative defaults (§5), deployment overrides (`eddi.multimodal.*`), and per-task `LlmConfiguration.Task.multimodal { vision|documents|audio: auto|on|off }`. Unknown ⇒ unsupported ⇒ fallback. Providers without URL-fetch get download-and-inline normalization (SafeHttpClient).
- **D8 — One forwarder; history strategy explicit.** `AttachmentForwarder` replaces `MultimodalMessageEnhancer` and `convertMessage`'s divergent handling. Continuity: (a) extracted text persisted once into step data (non-public key, e.g. `attachments:extracts`) and stitched into rebuilt history for that turn — visible transcript stays clean; (b) `readAttachment` tool for on-demand recall (post-windowing, oversize, page-targeted); (c) optional `reattachTurns` (default 0) for native re-attachment — preserves mechanism B's property deliberately (base64-source caveat: payload not persisted, reattach works for STORED/URL only).
- **D9 — Never persist payload bytes in conversation documents.** `@JsonIgnore` on `getBase64Data()`; scrub `data` from persisted copies of `attachment_*` context (keep metadata); precedent: `secretInput` scrubbing.
- **D10 — Uniform limits, loud failures** (AGENTS.md §4.7). Same per-file forward cap across STORED/URL/BASE64 (10 MB), aggregate per-request cap (default 20 MB), per-turn count cap (default 5), per-conversation storage quota. Upload response includes `forwardableInline: bool` (upload cap 20 MB > forward cap 10 MB — warn at upload, not silently at forward). Every drop/skip/gate → `attachments:errors` step data + text note the LLM can relay. Metadata-only SSE.
- **D11 — Reuse.** PDFBox out of `PdfReaderTool` into shared `AttachmentTextExtractor` (tool delegates; forwarder + `readAttachment` reuse). All URL fetching via `SafeHttpClient` + `UrlValidationUtils`. Future Slack/Teams adapters (`ObserveConfig.triggerMimeTypes` is schema-ready) feed the same ingestion path.

---

## 4. Phased implementation

### Phase 0 — Foundations & bug fixes (small, low-risk)

| Change | Files |
| --- | --- |
| `@JsonIgnore` on `Attachment.getBase64Data()` + serialization test (no payload in persisted memory JSON) | `engine/memory/model/Attachment.java` |
| Scrub `data` from persisted `attachment_*` context copies (conversationOutput + context Data); live in-memory objects keep payload for the turn | `engine/runtime/internal/Conversation.java` |
| `AttachmentTextExtractor` (PDFBox moved from `PdfReaderTool`; `extractText(bytes, mime, maxChars)`; cap configurable `eddi.attachments.extraction.max-chars`, default 10k) | `modules/llm/tools/impl/` *(new)*; `PdfReaderTool` delegates |
| `ModelCapabilityService` (defaults §5 + overrides + per-task hook + `supportsImageUrl`) | `modules/llm/capability/` *(new)* |
| Align `quarkus.http.limits.max-body-size` (≥ 25M) with attachment config; document | `application.properties` |

### Phase 1 — Storage unification + secure upload (the foundation)

| Change | Files |
| --- | --- |
| **Unify stores**: port conversation-deletion cascade (`RestConversationStore:369`) and GDPR erasure (`GdprComplianceService`) to `IAttachmentStore`; verify `IAttachmentStorage` has no live writers (grep), then remove it + `MongoAttachmentStorage` + `PostgresAttachmentStorage`; single ref format; fix the docs' ref-format confusion | `engine/memory/IAttachmentStorage.java` *(delete)*, consumers |
| `getMetadata(storageRef)` (grant-aware) + `grantAccess(storageRef, conversationId)` / grant-aware `load()` in **both** backends (GridFS metadata array / Postgres grants column or table); grants die with the blob | `engine/attachments/IAttachmentStore.java`, both impls |
| **Auth**: `@RolesAllowed` + `validateConversationOwnership` pattern on upload/list/download/delete; keep sanitized `tenantId` advisory only (no tenant principal exists yet — do not promise principal-derived tenancy); optional uploader `userId` metadata when authenticated | `engine/memory/rest/RestAttachmentUpload.java` |
| Quotas: `eddi.attachments.max-per-conversation` (count + total bytes); actionable errors | both store impls |
| UI enablers: `GET /conversations/{id}/attachments/{storageRef}` (download, ownership-checked, Content-Disposition) + single-item `DELETE` | `RestAttachmentUpload.java` |
| `storageRef` branch in `AttachmentContextExtractor` (`ContentSource.STORED`; metadata via `getMetadata`; precedence storageRef > url > data; per-turn cap; failed refs → `attachments:errors`, never silent) | `engine/memory/AttachmentContextExtractor.java` |
| Upload response gains `forwardableInline` hint | `RestAttachmentUpload.java` |

Tests: authz matrix (owner ✓ / granted ✓ / other ✗ / legacy blob), grant lifecycle, quota errors, storageRef extraction + precedence + cap, metadata spoof-immunity, GDPR + conversation-delete cascade now hitting the unified store. IT **CI-only** (local JVMs can't open loopback sockets).

### Phase 2 — Unified forwarder (hybrid PDF, universal text)

| Change | Files |
| --- | --- |
| `AttachmentForwarder` per §2: uniform caps across all sources (incl. previously-unguarded base64), capability-gated branches (image / pdf-hybrid / **text-inline** / audio / note+hint), provider URL normalization (download-and-inline via `SafeHttpClient`+`UrlValidationUtils` when `!supportsImageUrl`), outcome + extracted text persisted to step data (`attachments:extracts`, `attachments:errors`, non-public) | `modules/llm/impl/AttachmentForwarder.java` *(new)* |
| `LlmTask` calls forwarder with provider (`resolvedType`), resolved model (~327), capability service, extractor; delete `MultimodalMessageEnhancer` (port its tests) | `modules/llm/impl/LlmTask.java` |
| History stitching: `ConversationLogGenerator`/`ConversationHistoryBuilder` include `attachments:extracts` in that turn's user message when rebuilding history (visible transcript unaffected); keep `inputFiles` path one deprecation release | `engine/memory/ConversationLogGenerator.java`, `modules/llm/impl/ConversationHistoryBuilder.java` |
| Per-task config: `multimodal` override + `reattachTurns` | `modules/llm/model/LlmConfiguration.java` |

Tests: branch matrix (image url/b64/stored × capable/incapable, pdf native/fallback, text inline, audio, oversize per-file, aggregate cap, SSRF-rejected URL, store-load failure → note), turn-2 follow-up sees turn-1 extraction, transcript stays clean.

### Phase 3 — Group parity

| Change | Files |
| --- | --- |
| `POST /groups/{groupId}/conversations` accepts **multipart** (question + files → server stores bound to new gcId) and JSON `attachments` (inline base64/url refs); same for streaming variant | `engine/api/IRestGroupConversation.java`, `RestGroupConversation.java` |
| `discuss(...)` overload carries refs; at fan-out `GroupConversationService` **grants each member conversation** then injects `attachment_*` into the member's first-turn `InputData` context; later phases covered by extraction-in-history + `readAttachment` (native reattach = `reattachTurns`, off by default — group × members × phases token cost documented); nested groups re-grant down the chain | `engine/api/IGroupConversationService.java`, `engine/internal/GroupConversationService.java` (~1407) |
| Group SSE: metadata-only `attachmentsForwarded` events; group cost ceilings account for ×memberCount forwarding | `GroupConversationEventSink`, cost wiring |

Tests: member context carries refs; member mock provider observes content (image + pdf-fallback); grant written before member turn; cross-conversation ref without grant rejected; nested propagation. IT **CI-only**.

### Phase 4 — Multi-turn recall tool

`ReadAttachmentTool` (`@Tool readAttachment(nameOrRef, page?)`): lists/loads the **conversation's** attachments (implicit context — no userId param, per AGENTS.md), returns extracted text/metadata; whitelist key `readattachment` + auto-add when conversation has attachments (mirror `addUserMemoryToolIfEnabled`); executed via `executeToolWrapped` (rate/cost/cache). Forwarder fallback notes reference it.

### Phase 5 — UX layer

Backend: **multipart 1:1 `say`** (input + file parts → store + auto-inject; single-request UX); attachment metadata + forward outcome in conversationOutput/SSE for UI chips (`📎 report.pdf — sent (text-extracted)`).
Frontend (EDDI-Manager + eddi-chat-ui, own repos/AGENTS.md): drop-zone + paste-to-attach, upload progress, client-side type/size checks mirroring server config, error UX (`ATTACHMENT_TOO_LARGE`/`ATTACHMENT_REJECTED`/quota), chips with outcome, image thumbnails via download endpoint, attachment list in memory inspector.

### Phase 6 — Ops (parallel with 3–5)

CostTracker multimodal estimates (image formula per provider; extraction chars) → `maxCostPerRun`; audit ledger `attachmentsForwarded`/`estimatedAttachmentTokens`; nightly reaper via `ScheduleFireExecutor` (blobs whose conversation is gone; stale grants) — the "TTL" story; metrics (`eddi.attachment.forwarded{type,outcome}`, `gate_skipped{provider,type}`, `load.authz_failure`, `rejected{reason}`); GDPR portability export includes attachment metadata (bytes optional); *(future)* content-hash dedup, upload-time extraction for RAG-over-attachments (pgvector "chat with your docs").

---

## 5. Provider / model capability defaults

Conservative; overridable per deployment + per task. **Verify each against langchain4j `1.17.0` before shipping** — capability is model-level.

| Provider | Vision | Documents (native PDF) | Audio | Image-by-URL |
| --- | --- | --- | --- | --- |
| OpenAI / Azure OpenAI | ✅ (4o/4.1 family) | ⚠️ model-dep | ⚠️ | ✅ |
| Anthropic | ✅ | ✅ | ❌ | ⚠️ verify |
| Google / Vertex Gemini | ✅ | ✅ | ✅ | ⚠️ verify |
| Mistral (Pixtral) | ✅ | ❌ | ❌ | ⚠️ |
| Bedrock / Oracle GenAI | ⚠️ model-dep | ⚠️/❌ | ❌ | ⚠️ |
| Ollama (LLaVA etc.) | ⚠️ model-dep | ❌ | ❌ | ❌ (inline) |
| Jlama / HuggingFace / rest | ❌ | ❌ | ❌ | — |

Unknown ⇒ unsupported ⇒ fallback (text extraction / inline / note+tool-hint). Never send content that errors the provider. `text/*` needs no capability.

---

## 6. Client contract

**1:1 (two-step, large files):**
```
POST /conversations/{convId}/attachments        (multipart; authenticated)
  → { storageRef, fileName, mimeType, sizeBytes, forwardableInline }
POST /agents/{convId}/say
  { "input": "Summarize this", "attachments": [ { "storageRef": "…" } ] }
```
**1:1 (single request, Phase 5):** `POST /agents/{convId}/say` multipart (`input` + files).
**Inline small/hosted (compat, unchanged):** `attachments: [{mimeType,data,fileName}]` or `[{mimeType,url}]`; legacy `attachment_*` context keys still accepted.
**Group (single request):** `POST /groups/{groupId}/conversations` multipart (`question` + files) or JSON with inline refs.
**UI:** list / download / delete-one / delete-all under `/conversations/{id}/attachments`.

## 7. Backward compatibility

- `attachment_*` `url`/`data` inputs unchanged (now with payload-scrubbed persistence — bug fix).
- `inputFiles`/`convertMessage` kept one deprecation release; its history re-attachment property preserved by design (extracts-in-history + `reattachTurns`), then removed.
- `PdfReaderTool` behavior unchanged (shared extractor underneath).
- `IAttachmentStorage` removal: verify no live writers first (believed write-dead); no MongoDB config migration; store schema additions (grants, userId metadata) are additive.

## 8. Testing

- **Unit (local):** extractor; capability (defaults/override/unknown); forwarder matrix incl. uniform caps + SSRF; storageRef extraction/precedence/cap/spoof-immunity; authz matrix incl. grants; **serialization: no payload in persisted JSON**; quota errors.
- **IT (CI-only — local JVMs can't open loopback sockets):** upload→reference→mock provider observes `ImageContent`/`PdfFileContent`/extracted text; turn-2 follow-up; multipart say; group multipart → members observe content; grant-before-member-turn; nested groups; unified cascade (conversation delete + GDPR remove blobs); download authz.
- **Regression:** `AttachmentContextExtractorTest`, `ConversationLogGeneratorTest`, ported enhancer tests.

## 9. Out of scope

OCR/scanned PDFs; in-engine malware scan (keep §7.8 MCP hook); S3 backend; office-doc extraction (API ready); video beyond passthrough; Slack/Teams ingestion (Phase 11b — reuses this path); RAG-over-attachments (Phase 6 note).

## 10. Rollout

| Phase | Content | Effort | Risk | Ships alone |
| --- | --- | --- | --- | --- |
| 0 | Bug fixes, extractor, capability svc, config | S | Low | ✅ |
| 1 | **Store unification**, grants, auth, quotas, storageRef reference, download | M–L | Med (authz/unification) | ✅ |
| 2 | Unified forwarder: hybrid PDF, text inline, caps, history stitching | M–L | Med | ✅ |
| 3 | Groups: multipart create-and-discuss, fan-out grants, member injection | M | Low (reuses 1+2) | ✅ |
| 4 | `readAttachment` tool | S | Low | ✅ |
| 5 | Multipart say, SSE/output chips, Manager + chat-ui | M | Low | ✅ |
| 6 | Cost, reaper, metrics, portability | S–M | Low | ✅ (parallel) |

Dependencies: 0 → 1 → 2 → {3,4,5,6}; 3 before 5's group UI. Changelog + `docs/attachments-guide.md` updated on the same branch per phase (repo protocol).

---

## 11. Open decisions (need sign-off before Phase 1)

1. **Grant storage shape**: metadata array on the blob (simple, per-blob cap ~member count) vs separate grants table/collection (cleaner for auditing/reaping). Recommendation: metadata array first (bounded by group size), revisit if group-of-groups fan-out grows.
2. **`IAttachmentStorage` deletion timing**: remove in Phase 1 (recommended if grep confirms write-dead) vs deprecate one release.
3. **Group attachment visibility**: all members (default) — per-member scoping deferred until a real use case (config knob on `AgentGroupConfiguration` later).
4. **GridFS public-ref hardening**: switch plain ObjectId refs to random UUID metadata refs (defense-in-depth; Postgres already UUIDv4) — cheap now, breaking later. Recommendation: do it in Phase 1 while refs have no external consumers.

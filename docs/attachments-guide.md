# Multimodal Attachments — Usage Guide

> EDDI's attachment pipeline enables multimodal conversations — users can send images, files, and documents alongside text input. Attachments flow through the lifecycle pipeline and are automatically forwarded to vision-capable LLMs.

## Quick Start

### Send an Image via URL

```bash
POST /agents/{conversationId}
Content-Type: application/json

{
  "input": "What is in this image?",
  "context": {
    "attachment_0": {
      "type": "object",
      "value": {
        "mimeType": "image/png",
        "url": "https://example.com/photo.png",
        "fileName": "photo.png"
      }
    }
  }
}
```

### Send an Image via Base64

```bash
POST /agents/{conversationId}
Content-Type: application/json

{
  "input": "Describe this icon",
  "context": {
    "attachment_0": {
      "type": "object",
      "value": {
        "mimeType": "image/png",
        "data": "iVBORw0KGgoAAAANSUhEUgAAAAE...",
        "fileName": "icon.png"
      }
    }
  }
}
```

The image is automatically forwarded to the LLM as multimodal content. The LLM "sees" the image alongside the text message.

---

## How It Works

```text
Client sends context with attachment_* keys
                          │
                          ▼
        ┌─────────────────────────────────────┐
        │ Conversation.prepareLifecycleData() │
        │                                     │
        │ AttachmentContextExtractor parses   │
        │ attachment_0, attachment_1, ...     │
        │ into List<Attachment> objects       │
        │                                     │
        │ Stored in memory: "attachments"     │
        └───────────────────────┬─────────────┘
                                │
         ┌──────────────────────┼─────────────────────┐
         ▼                      ▼                     ▼
┌──────────────────┐  ┌───────────────────┐  ┌──────────────────┐
│ BehaviorRules    │  │ LlmTask           │  │ Other Tasks      │
│                  │  │                   │  │                  │
│ ContentType-     │  │ AttachmentFor-    │  │ Read from the    │
│ Matcher matches  │  │ warder resolves   │  │ "attachments"    │
│ on MIME type,    │  │ bytes, gates on   │  │ memory key       │
│ so a PDF and an  │  │ ModelCapability-  │  │ directly         │
│ image can take   │  │ Service, emits    │  │                  │
│ different paths  │  │ langchain4j       │  │                  │
│                  │  │ Content           │  │                  │
└──────────────────┘  └───────────────────┘  └──────────────────┘
```

### Pipeline Stages

1. **Context Extraction** — `AttachmentContextExtractor` parses `attachment_*` context keys into `Attachment` objects
2. **Memory Storage** — Attachments are stored as `List<Attachment>` in the `attachments` memory key
3. **Rule Matching** — `ContentTypeMatcher` condition matches on MIME types for routing
4. **LLM Forwarding** — `AttachmentForwarder` resolves each attachment's bytes, gates it on `ModelCapabilityService`, and converts it to the right langchain4j `Content` on the outgoing user message

---

## Input Paths

### Path A: URL Reference (Recommended)

Best for images already hosted somewhere. The URL is passed to the provider when it fetches URLs itself; otherwise EDDI downloads the bytes and inlines them (see [LLM Multimodal Support](#llm-multimodal-support)).

```json
{
  "attachment_0": {
    "type": "object",
    "value": {
      "mimeType": "image/jpeg",
      "url": "https://cdn.example.com/photos/sunset.jpg",
      "fileName": "sunset.jpg"
    }
  }
}
```

### Path B: Base64 Inline

Best for small images (< 5MB). Data is sent inline as a base64-encoded string.

```json
{
  "attachment_0": {
    "type": "object",
    "value": {
      "mimeType": "image/png",
      "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk...",
      "fileName": "icon.png"
    }
  }
}
```

> **Note:** `base64Data` is transient — it's never persisted to MongoDB. For large files, use the upload endpoint (Path C below) or URL references.

### Path C: File Upload

For large files, upload them to the storage backend and receive a storage reference:

```bash
POST /conversations/{conversationId}/attachments
Content-Type: multipart/form-data

# Form field: file (the binary file)
```

**Response (201):**
```json
{
  "storageRef": "3f2b9c1e-6d4a-4f0e-9b7a-2c8d5e1f0a93",
  "fileName": "report.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 524288,
  "conversationId": "…",
  "forwardableInline": true
}
```

`forwardableInline` is `true` when `sizeBytes` is at most `eddi.attachments.max-forward-bytes` (default 10 MB): only then can the blob be sent inline to a model provider on a later turn; `eddi.attachments.max-forward-aggregate-bytes` caps the total forwarded with one message.

**Other endpoints** (all under `/conversations/{conversationId}/attachments`):

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `` | List the conversation's attachments |
| `GET` | `/{storageRef}` | Download one blob |
| `DELETE` | `/{storageRef}` | Delete one blob |
| `DELETE` | `` | Delete all of the conversation's attachments |

An unknown `storageRef` answers `404` (`ATTACHMENT_NOT_FOUND`), and a blob the conversation may not access answers `403`.

The returned `storageRef` can then be used in subsequent conversation turns by setting it as the `storageRef` field in an attachment context key. It takes precedence over `url` and `data`, and the authoritative `mimeType` and `sizeBytes` are resolved server-side from the store, so they need not be supplied:

```json
{
  "attachment_0": {
    "type": "object",
    "value": { "storageRef": "3f2b9c1e-6d4a-4f0e-9b7a-2c8d5e1f0a93", "fileName": "report.pdf" }
  }
}
```

The `storageRef` is a random UUID — unguessable, and only readable from the owning conversation or one it was explicitly granted to. A blob that records no owning conversation (one stored by a release that predates attachment ownership) cannot be read through a conversation unless that conversation was explicitly granted access to it, on either backend: ownership that cannot be established is refused rather than assumed, while an explicit grant still applies. It can still be deleted by its `storageRef`, because deleting discloses nothing and refusing it would leave data no erasure could remove. Deleting a conversation, and a GDPR erasure, remove the blobs that record that conversation as their owner; an ownerless blob records none, so they do not reach it (as before). The storage backend (GridFS or PostgreSQL) is selected automatically based on the configured datastore.

**Grants are stored on their own**, in the `attachment_grants` collection (MongoDB) or table (PostgreSQL), one row per blob and grantee conversation. Deleting a blob deletes the grants on it. Deleting a conversation, and a GDPR erasure, also delete the grants that conversation *held* on other conversations' blobs, so grants no longer pile up on a shared file after its readers are gone. Grants used to live on the blob itself (`metadata.grants` on MongoDB, the `grants` column on PostgreSQL). For one release EDDI writes both shapes and accepts either one, so a cluster in a rolling upgrade works in both directions. On every start a background pass copies the old grants over in batches. It is a catch-up: a node of the previous release can still write an old-style grant during the upgrade. The pass skips grantees that no longer exist, removes grant rows whose blob is gone, and continues from where it stopped if a start cuts it short. When a whole pass finishes without a failed lookup, it is recorded in the migration log under `attachment-grants-backfill-complete`. That record does not prove that no old-style grant can still appear: a node of the previous release can write one afterwards. A later release may stop reading the old shape only after it has run this copy once more, with every node upgraded. Include `attachment_grants` and `attachment_grants_backfill` in backups.

#### Content check

The declared type is checked against the file's content. Images and PDFs must carry their signature (a "PNG" without one is refused). Text declared as a text type — `text/*`, JSON, XML, YAML, CSV — is accepted when it reads as text (no NUL bytes, almost no control characters), whatever short signature it happens to start with: a CSV whose first column is `BMI` starts with the bitmap magic `BM`, and Markdown can quote `%PDF-1.7`, and neither is an image or a PDF. A real binary declared as text is still refused.

#### Quotas

| Quota | Default |
|---|---|
| `eddi.attachments.max-per-conversation` | 50 files |
| `eddi.attachments.max-total-bytes-per-conversation` | 100 MB |
| `eddi.attachments.max-per-user` | 500 files |
| `eddi.attachments.max-total-bytes-per-user` | 1 GiB |

The per-user quotas count every blob owned by the conversation's **owner**, across all of their conversations — an admin uploading into a user's conversation fills that user's quota. A group discussion's inline files count too: they are stored under the discussion's owner (before this, they were stored without one, so no per-user quota counted them). Blobs stored before the owner was recorded on them (earlier releases) do not count towards it. Both are on by default (they used to be off, so one user could fill the store alone); set either to `0` to disable it.

Quotas are enforced atomically: the usage is counted and the blob stored under one lock per scope — a transaction-scoped advisory lock on PostgreSQL (`hashtextextended`, so PostgreSQL 11 or newer), a lease document in `attachments.quota_locks` on MongoDB — so concurrent uploads cannot each see room and together overshoot the limit. A write that runs too long to hold that lock (a large upload, a background job — none exists yet) instead holds a **quota reservation**, which counts toward the same quotas until its file is stored, it is given back, or it expires by the database's clock (at most 24 hours); deleting the conversation deletes its reservations. Reservations live in `attachment_reservations` (a table on PostgreSQL, a collection with a TTL index on MongoDB). Every write takes its locks in one order — conversation, then user, then team, then the uploaded file — so no two can wait on each other. A refusal is a `400` with `"code": "ATTACHMENT_REJECTED"` and `"quota": "conversation"` or `"user"`, counted on `eddi_attachments_quota_rejected_total{scope}`.

| Response Code | Meaning |
|---|---|
| `201` | File stored successfully |
| `400` | No file provided, file exceeds `eddi.attachments.max-size-bytes`, the content does not match the declared type, or a quota is full (`quota` names which) |
| `413` / `411` | The request body passed the HTTP body limit, or arrived without a `Content-Length`. The limit is `eddi.http.limits.default-max-body-size`, raised automatically to fit an attachment at `eddi.attachments.max-size-bytes` base64-encoded — so raising the attachment limit raises it too, up to `quarkus.http.limits.max-body-size` (60 MB), which it cannot exceed. Above about 44 MB of attachment, raise that ceiling as well; a WARN at startup names the value needed. See [configuration-reference.md](configuration-reference.md#security--authentication) |
| `500` | Storage or I/O error |

#### Derived files

The store can also hold **derived files**: views EDDI makes of an uploaded file, such as a PDF's structure, the text of a page window, an extracted image or a page-range slice. Two are created so far: a PDF's **structure** (its chapters and their page ranges), stored the first time a part of the PDF is resolved, and a **page-range slice**, the PDF of a part sent to a document-capable model (see [Sending part of a file](#sending-part-of-a-file)); the other kinds arrive with the document features, which are off by default. What is already true:

- **A derived file belongs to the uploaded file's owner**, never to the conversation whose work produced it. When a group member reads a file shared with it and EDDI extracts a chapter, the extract belongs to the file's owner, any reader of the file can read it, and it is not deleted with the member.
- **Access is decided on the uploaded file.** Whoever may read the file may read every view of it; a grant is always on the uploaded file. A conversation that may not read the file is told a view of it does not exist.
- **It dies with the file.** Deleting an uploaded file, its conversation or its user (GDPR erasure) deletes every view of it.
- **Listings and file counts see uploaded files only.** A derived file never appears in the conversation's attachment list and never counts toward `max-per-conversation` or `max-per-user`.
- **Only media count toward the byte quotas.** Extracted images, tables, audio and embedded files count toward the owner's byte quotas (`max-total-bytes-per-conversation`, `max-total-bytes-per-user`). Text views (structure, page text, summaries, descriptions) and slices do not: reading a file, possibly as a grantee, must never use up its owner's upload quota. They are bounded per uploaded file instead:

| Per uploaded file | Default | What happens at the limit |
|---|---|---|
| `eddi.attachments.derived.max-per-source` | 200 | Images, tables, audio and embedded files together; the next one is refused |
| `eddi.attachments.derived.max-total-bytes-per-source` | 50 MB | The same four kinds, in bytes; refused |
| `eddi.attachments.derived.max-text-bytes-per-source` | 200 MB | Text views together; refused |
| `eddi.attachments.derived.max-slices-per-source` | 20 | Page-range slices, a rebuildable cache: the least recently used is deleted to make room |
| `eddi.attachments.derived.max-slice-bytes-per-source` | 50 MB | Slices in bytes; the least recently used are deleted to make room |

Identical bytes stored twice for the same owner and uploaded file are kept once; two owners with the same file share nothing. Counted on `eddi_attachment_derived_stored_total{kind}` and `eddi_attachment_derived_bytes_total{kind}`.

---

## Context Key Format

Attachment context keys must match the pattern `attachment_*`:

| Key | Valid? |
|---|---|
| `attachment_0` | ✅ |
| `attachment_screenshot` | ✅ |
| `attachment_` | ✅ |
| `image_0` | ❌ (wrong prefix) |
| `attachment` | ❌ (no suffix) |

### Required Fields

| Field | Required | Description |
|---|---|---|
| `storageRef` | One of storageRef/url/data | Reference to a previously uploaded blob (see Path C) |
| `mimeType` | Yes, unless `storageRef` is used | MIME type (e.g., `image/png`, `application/pdf`) |
| `url` | One of storageRef/url/data | External URL reference |
| `data` | One of storageRef/url/data | Base64-encoded content |
| `fileName` | No | Original filename (for metadata/logging) |

`storageRef` has the highest precedence; if both `url` and `data` are present, `url` takes precedence.

### Sending part of a file

A `storageRef` attachment of a PDF may name a part of the file instead of all of it, with three optional string fields:

| Field | Example | Meaning |
|---|---|---|
| `pages` | `"12-19"` | First and last page, 1-based, inclusive. **Authoritative.** |
| `unit` | `"u3"` | A chapter label, as an agent's document tools number the file |
| `split` | 16 hex characters | The hash of the split configuration the `unit` label belongs to |

```json
{
  "input": "Summarize this chapter",
  "context": {
    "attachment_0": {
      "type": "object",
      "value": { "storageRef": "6560a1b2c3d4e5f6a7b8c9d0", "pages": "12-19" }
    }
  }
}
```

**Pages win.** A chapter label means something only for one way of cutting a file: two agents with different split settings number the same PDF differently. So:

- With `pages`, those pages are used. They are checked against the document and never re-derived from `unit` or `split`, which travel along as labels.
- With only `unit`, the label is turned into pages once, when the turn arrives, by the receiving agent's split, and the attachment then carries pages and split. Until designers can configure splits, every agent uses the deployment's built-in split. A client that needs a stable part should send `pages`.
- A `unit` with a `split` hash from another split and no `pages` is an error ("this chapter id belongs to a different split of the file; send pages"). EDDI never guesses which chapter was meant.
- An unknown unit, pages outside the document, a malformed range, or a part of a file that is not a PDF is an error too.

An error is reported in the turn's `attachments:errors` note, the turn continues, and the attachment is dropped: a part that cannot be resolved is **never sent as the whole file**. A part counts as one attachment against `eddi.attachments.max-per-turn`, so five parts of one file use the five slots.

Resolving a `unit` reads the PDF's structure once and keeps it as a derived file of the PDF (see [Derived files](#derived-files)), owned by the PDF's owner and deleted with it. A node-local cache of 30 minutes sits in front of it; the cache is never trusted for access, which is checked on every request.

#### What the model receives

Only the selected pages, never the rest of the file:

| Model | Sent |
|---|---|
| Reads PDFs natively (see [LLM Multimodal Support](#llm-multimodal-support)) | A one-line header naming the file, the unit (if any) and the pages, then a **PDF of only those pages** |
| Does not, or the PDF of those pages is too large | The same header, then **those pages' text**, capped like any extracted PDF text (`eddi.attachments.extraction.max-chars`) |

```
[Attachment report.pdf — u3, p.12–19 (application/pdf) — only pages 12–19 of the file, sent as a PDF of those pages]
<PDF of pages 12–19>
```

- **The pages' PDF is built from the stored file** by copying those pages into a new document; links to other pages are dropped. Everything the pages use (fonts, images) comes along, so in a file whose pages share large resources the PDF of a few pages can be nearly as large as the file. Building stops as soon as the output passes the per-file limit or what is left of the request's attachment budget, whichever is less, and the pages' text is sent instead, with a note in `attachments:errors`.
- **Limits.** `eddi.attachments.max-forward-bytes` (10 MB) applies to the pages' PDF, not to the file: a 30 MB file can send a 2 MB chapter. `eddi.attachments.max-forward-aggregate-bytes` (20 MB) is charged the pages' PDF; a part that no longer fits what is left of it is sent as text. The text is not charged.
- **Kept for reuse.** The pages' PDF is stored as a derived file of the PDF (kind `PDF_SLICE`), so the next request for the same pages reuses it. It is a rebuildable cache: it counts toward no quota, and the per-file slice limits below evict the least recently used one. When the store will not keep it, it is sent anyway.
- **Failures never widen the part.** A part whose pages cannot be read, a range outside the document or a file that cannot be loaded ends in a note naming the part, not in the whole file.
- **A file withheld on an earlier turn stays withheld.** When an input guardrail refused the turn a file arrived with, naming a part of it later sends nothing.
- **Only on the turn it arrives.** Like a whole PDF, a part is sent on the turn it is attached and not again on later turns; only images are re-sent on later turns.

An attachment without `pages`, `unit` or `split` is sent exactly as before.

---

## Multiple Attachments

Send multiple attachments by incrementing the key index:

```json
{
  "attachment_0": {
    "type": "object",
    "value": { "mimeType": "image/png", "url": "https://example.com/page1.png" }
  },
  "attachment_1": {
    "type": "object",
    "value": { "mimeType": "image/png", "url": "https://example.com/page2.png" }
  }
}
```

All attachments are forwarded to the LLM in a single multimodal user message — at most `eddi.attachments.max-per-turn` of them (default `5`; non-positive means unlimited). Extras are dropped with a note in `attachments:errors`.

---

## LLM Multimodal Support

`AttachmentForwarder` is the single place an attachment becomes langchain4j
`Content` on the outgoing user message. For each attachment it resolves the
bytes from whichever source supplied them (stored blob, URL, inline base64)
under uniform per-file and aggregate byte caps, asks `ModelCapabilityService`
what the configured provider/model can actually accept, and emits accordingly:

| MIME Type | Model has the capability | Model does not |
|---|---|---|
| `image/*` | `ImageContent` — the URL is passed through when the provider fetches URLs itself, otherwise the bytes are downloaded and inlined as base64 | Metadata note |
| `application/pdf` | Native `PdfFileContent` | PDFBox text extraction, inlined as `TextContent` |
| `application/pdf`, a part of a stored file ([Sending part of a file](#sending-part-of-a-file)) | A header `TextContent`, then a `PdfFileContent` of only those pages | Those pages' text, inlined as `TextContent` |
| `audio/*` | `AudioContent` | Metadata note |
| text-like (`text/*`, JSON, XML, CSV, YAML) | Decoded and inlined as `TextContent` — **no capability required**, so this works on every model | — |
| Anything else | Metadata note pointing the model at the `readAttachment` tool | Metadata note |

Note the difference between the last two rows: a CSV is *read* into the prompt,
whereas a `.zip` is only *announced*. The metadata note looks like this:

```
[Attachment: archive.zip (application/zip, 15240 bytes)]
```

**Nothing is dropped silently.** Text extracted from a PDF is persisted to the
`attachments:extracts` memory key so later turns can stitch it back into the
history, and every drop, cap-skip and capability gate is appended to
`attachments:errors` — which is where to look first when a model claims it
cannot see a file you attached.

### Observability

| Metric | Type | Meaning |
|---|---|---|
| `eddi.attachment.forwarded` | Counter | Attachments converted to `Content` |
| `eddi.attachment.reinlined` | Counter | Extracted text stitched back into conversation history |
| `eddi.attachment.errors` | Counter | Drops, cap-skips and capability gates |

---

## Routing with Behavior Rules

Use `contentTypeMatcher` to create different workflows based on attachment type:

### Route Images to Vision Agent

```json
{
  "name": "Image received",
  "actions": ["analyze_image"],
  "conditions": [
    {
      "type": "contentTypeMatcher",
      "configs": {
        "mimeType": "image/*",
        "minCount": "1"
      }
    }
  ]
}
```

### Route PDFs to Document Processor

```json
{
  "name": "Document received",
  "actions": ["process_document"],
  "conditions": [
    {
      "type": "contentTypeMatcher",
      "configs": {
        "mimeType": "application/pdf",
        "minCount": "1"
      }
    }
  ]
}
```

### Require Specific Attachment Count

```json
{
  "name": "Comparison ready",
  "actions": ["compare_images"],
  "conditions": [
    {
      "type": "contentTypeMatcher",
      "configs": {
        "mimeType": "image/*",
        "minCount": "2"
      }
    }
  ]
}
```

---

## Template Access

`{memory.current.attachments}` renders as an empty string. Attachments are written to the current step's data store under the `attachments` memory key (`storeData`), while `{memory.current.*}` resolves against the step's `ConversationOutput` — a different map.

The `attachment_*` **context** keys are a different matter: `MemoryItemConverter` publishes the request context into the template model, so `{context.attachment_0.mimeType}`, `{context.attachment_0.url}` and `{context.attachment_0.fileName}` do resolve for attachments supplied that way.

To act on attachments, read the `attachments` memory key from a task, or match on them declaratively with `contentTypeMatcher` in a behavior rule.

---

## Group Conversations

Group discussions accept inline data and hosted URLs on `POST /groups/{groupId}/conversations`. Inline data is stored in the blob store owned by the group conversation and counted against the discussion's owner, so the same quotas apply as to a 1:1 upload: `eddi.attachments.max-size-bytes` per file, `max-per-conversation` and `max-total-bytes-per-conversation` for the discussion (checked from the request before anything is decoded or stored), and the per-user quotas (checked atomically when the files are stored). A refusal is a `400` (or a `group_error` event on the `/stream` variant), and nothing from the refused request is kept. Hosted `url` references pass through. A bare `storageRef` is not accepted from a client: only a nested group passes references down, and only those its parent discussion can read.

The MCP tools take the same entries in an `attachments` argument: `talk_to_agent` and `chat_with_agent` send them as the turn's `attachment_*` context (a `storageRef` the conversation can read included), and `discuss_with_group` applies the group rules above and refuses a `storageRef` with an error. See [MCP server → Sending files](mcp-server.md#sending-files-attachments).

Deleting a discussion (`DELETE /groups/{groupId}/conversations/{id}`) deletes its blobs, and a GDPR erasure of the owner reaches them — see [gdpr-compliance.md](gdpr-compliance.md).

Each member's private conversation is granted access on its **first** turn and receives the files as `attachment_*` context; from there everything on this page applies unchanged. Later turns rely on the member's own history plus the auto-enabled `readAttachment` tool, so a member does not lose the file after phase one.

Two group-specific bounds: the per-turn cap (`eddi.attachments.max-per-turn`) applies per **member turn**, and anything dropped is reported in that member's `attachments:errors`, not in the group transcript. See [group-conversations.md → Attachments](group-conversations.md#attachments).

## Sharing between agents — the rule

Every path that hands a stored file from one conversation to another follows one rule, implemented once in `AttachmentShareService` (`engine/attachments`). Delegation — `converse_with_agent` with its `attachments` argument, see [Handing files to a delegated agent](#handing-files-to-a-delegated-agent) — calls the full service; the nested-group pass-through above uses only its read check (rule 3) and is neither metered nor audited as a share.

1. **A model names files; it never holds a reference.** What a conversation's model may name is its **listing**: the files it owns or was granted (`listAccessible`), minus the files of a turn an input guardrail refused (withheld). Each entry is shown by its file name — `(unnamed)` for a file stored without one, never the `storageRef` — and can also be named by its 1-based number. The listing is sorted by name (then by reference), so the same files always list the same way, but a file added or granted later can shift the numbers: a number is only meaningful against the listing shown in the same turn. A name that matches several files is refused with their numbers; a name that is not in the listing is `not found`, whether the file exists elsewhere or not at all.
2. **Server code picks both ends.** The source is the conversation the tool is bound to; the target is a conversation server code created for the share. Neither comes from a model or from client context.
3. **The listing is checked, not trusted.** It must have been built for the source conversation; a withheld file is never shared, even from a listing assembled by hand; and the source must still be able to read the file when it shares it (owner or grantee). Each of these refusals reads `not found` and names no file. A grantee may share a file onward, within the hop limit.
4. **Limits**, set by the caller from the agent's configuration: files per share, shares per turn (one count for the whole turn, whatever the number of tool assemblies), allowed MIME types and the hop limit. A refused file uses up no slot.
5. **What a share does:** it grants the target conversation read access to the file's root — so the receiver can read the whole file with `readAttachment` — records an audit entry in the **source** conversation's ledger (`taskId` `attachment.share`: target conversation and agent, reason, MIME type, size; no bytes, no file name), and counts the metrics below. The tool that shared stores a row per file under the step key `attachments:shared` (file name, target agent, reason — no references).
6. **Parts of a file.** `report.pdf#u3` (a unit) and `report.pdf#p4-9` (pages) name a part of a listed PDF. Before it is shared, a unit is turned into pages by the sharing agent's split, and given pages are checked against the document; the receiver gets those pages, whatever its own split ([Sending part of a file](#sending-part-of-a-file)). The grant is still on the whole file: **a receiver of a part can read the whole file with `readAttachment`**; the part limits what is sent, not what may be read. A part that cannot be resolved is refused with the reason and grants nothing. Parts and views an agent's tools record on the conversation's steps are listed too, as `report.pdf — u3, p.12–19` (or the view's file name), when their file is listed. A file whose name itself contains `#` is still found by its exact name.

| Metric | Tag | Meaning |
|---|---|---|
| `eddi_attachment_shared_total` | `path` (`delegation`, `group_task`, `group_share`, `handoff`, `a2a_outbound`, `mcp`, `nested_group`) | Files shared |
| `eddi_attachment_share_refused_total` | `reason` (`not_found`, `ambiguous`, `part`, `part_invalid`, `mime_type`, `depth`, `files_per_share`, `shares_per_turn`, `target`, `grant_failed`, `unavailable`) | Share requests refused |

Granting is idempotent on both stores (MongoDB adds to a set, PostgreSQL appends only when absent), so sharing the same file twice leaves one grant.

## Handing files to a delegated agent

An agent whose LLM task sets `documents.sharing.delegation: true` can hand files of its conversation — whole, or a part such as `report.pdf#u3` — to another agent: `converse_with_agent(agentId, message, conversationId, attachments)`, where `attachments` lists file names (or numbers) of the conversation's files. The delegate's conversation is granted each file and receives it on the message turn as `attachment_<n>` = `{storageRef, fileName}` — the stored-reference form a client uses — so from there everything on this page applies to it unchanged: MIME and size come from the store, `eddi.attachments.max-per-turn` and the forward caps apply, and the delegate gets `readAttachment`.

Files go only to the conversation the call starts, or to one this conversation started earlier; a file the delegate received can be handed on again until `documents.sharing.maxDepth` hops. The configuration, the limits and the rules are in [langchain.md → Handing files to a delegated agent](langchain.md#handing-files-to-a-delegated-agent).

## Architecture Notes

- **No inline storage**: Attachment payloads are never stored inline in conversation memory documents. Only metadata references are persisted.
- **Transient base64**: The `base64Data` field is `transient` — it exists only during the pipeline turn. For persistence, use the upload endpoint with `IAttachmentStore`.
- **DB-agnostic**: The `IAttachmentStore` SPI supports MongoDB (GridFS) and PostgreSQL (bytea) implementations; files larger than `eddi.attachments.max-size-bytes` keep their bytes in a blob backend ([Storage backends](#storage-backends)).
- **GDPR cleanup**: `IAttachmentStore.deleteByConversation()` removes all attachments when a conversation is deleted, with every derived file of them.
- **Lineage**: a derived file is a row (PostgreSQL) or GridFS file (MongoDB) of the same store with `root_ref` / `metadata.rootRef` naming the uploaded file, and its owner fields copied from it when it is written. The store announces every deleted uploaded file as a CDI event, `AttachmentRootDeleted`, so caches and jobs keyed by that file can clean up; `AttachmentOwnerChanged` is declared for the libraries of later releases and not fired yet. The new columns are additive (`ADD COLUMN IF NOT EXISTS` / new metadata fields, no backfill): `owner_key` empty reads as the conversation id, `page_count` empty as unknown.

## Storage backends

Each attachment store is a **metadata layer** (owner, grants, quotas, lineage, and where the bytes are) over the bytes themselves. Up to `eddi.attachments.max-size-bytes` (20 MB) the bytes stay where they have always been: the `attachments` GridFS bucket on MongoDB, the `data` column on PostgreSQL. Larger files go to a **blob backend**, which stores them chunk by chunk and never holds a whole file in memory:

| Backend | Database | Where the bytes go |
|---|---|---|
| `gridfs` (MongoDB default) | MongoDB | Its own GridFS bucket, `attachment_blobs` |
| `pg-chunks` (PostgreSQL default) | PostgreSQL | Rows of `eddi.attachments.blob.pg-chunks.chunk-bytes` (4 MiB) in `attachment_chunks` |

`eddi.attachments.blob.backend` picks one (`auto`, the default, takes the database's own). A backend that cannot run on the configured database fails startup with the reason; `s3` and `pg-largeobject` are planned and refused as not available in this version. Every file records the backend its bytes went to, so changing the setting affects new files only. Keys in a backend are random and carry no user, team or file name.

**Nothing stores large files yet.** The streaming store is in place, and large uploads are switched on by a later release; until then every file is at most `eddi.attachments.max-size-bytes` and is stored inline exactly as before.

What the stores guarantee once large files arrive:

- **Reading.** `IAttachmentStore.load` returns a file's bytes as one array only up to `eddi.attachments.max-size-bytes`; above it, it refuses with `AttachmentTooLargeForMemoryException` — judged on the file's size, so an inline file above a limit lowered later is refused too. Larger files are read as a stream (`open`, `openRange` for a byte range) or copied to a file of the store's naming in a work directory (`materialize`). A file whose bytes the backend cannot deliver is a store error, never "not found".
- **Writing.** A streamed upload holds its quota with a [quota reservation](#path-c-file-upload) taken before the first byte, so the write holds no lock while it streams. The declared type is judged on the first 8 KiB, the SHA-256 is computed while the bytes stream, and a stream longer or shorter than declared is refused. Any failure leaves no file and no bytes behind.
- **Large uploads and quotas.** A large upload (`QuotaMode.LARGE_PER_USER`) is exempt from the per-conversation **byte** quota and counted against its user's byte quota (`eddi.attachments.max-total-bytes-per-user`) instead; it still counts toward the conversation's file count, and it needs a known user.
- **Deleting.** A file's row and a record of its bytes go together; the bytes are then deleted from the backend. If the backend cannot delete them at once, the record — a **tombstone** — stays, and a later delete or upload retries it (a few at a time, so nothing has to be scheduled). An upload whose writer stopped half-way leaves a tombstone too, which becomes due a minute after the upload's reservation (`eddi.attachments.blob.upload-ttl-seconds`, 1 h) has run out. `IAttachmentStore.pendingTombstones` counts what is still waiting, so an erasure can report it as pending. The `eddi_attachment_blob_tombstones_total` meter counts deletes the backend could not finish at once.

A node of an earlier release reads a file stored in a blob backend as empty. That cannot happen before large uploads are switched on, which needs every node on this release or later.


## Large documents: how they are read

A PDF larger than `eddi.attachments.max-size-bytes` is never read into memory. It is copied to disk, and PDFBox reads that file **one window of pages at a time**. This is the machinery that later releases use to process large documents in the background. **Nothing uses it yet**: large uploads are switched on by a later release.

- **Work directory.** The file is copied to `eddi.attachments.large.work-dir` (by default `eddi-documents` under `java.io.tmpdir`). Each instance works only in its own subdirectory, `node-<eddi.cluster.node-id>`, and holds a lock on it while it runs, so several instances can share one volume. At startup an instance empties its own subdirectory and nobody else's. A second process that finds the lock taken (two instances with the same node id) uses a per-start subdirectory instead and deletes nothing. Directories and copied files are owner-only (`0700`, `0600`) where the file system supports permissions. A subdirectory that is a link, belongs to another user or is writable by others is refused rather than swept.
- **Disk budget.** Bytes are reserved before the first one is copied. The reservation covers the file plus the reader's scratch space and is released when the copy is deleted. `eddi.attachments.large.work-dir-max-bytes` (4 GiB) is the most one instance's directory may hold. A read waits for space, and a file that could never fit is refused with that limit named. Size the volume for at least this much; in Kubernetes that is the pod's ephemeral storage.
- **Windows.** The document is opened afresh for each window of `eddi.documents.structure.window-pages` pages (50) and closed when the window ends, so a later background job can resume from the last window it finished. PDFBox's memory settings bound only its stream cache. The objects it parses and the fonts and images it caches live as long as the document is open, so re-opening is what keeps them from adding up over thousands of pages. The resource cache keeps nothing.
- **Bounds per window.** Each window has its own decode budget (128 MB), its own deadline (`eddi.documents.structure.window-deadline-seconds`, 90 s) and the same page-tree checks as the upload path. A window that exceeds one of them is refused with a message. The raw-byte scan for compression bombs runs over the file through a small buffer first, as it does over an uploaded array.
- **Damaged files.** When a PDF's cross-reference table is broken, PDFBox rebuilds it by scanning the whole file and keeping what it finds, which is the cost this path avoids, and it would happen in every window. Above 4 MB a file is therefore parsed strictly. A file that needs the rebuild is refused with a message asking for a re-saved copy. Smaller files are repaired as before. PDFBox also holds every cross-reference entry in memory in each window, and nothing in PDFBox bounds that, so the parser counts the entries as it stores them and stops at 500,000. A 40 MB test file with that many entries reads within a 256 MB heap. Every window walks the whole page tree, so a document read this way may have at most 10,000 pages. The page limit is twice the 5,000 pages large documents are planned for.
- **The result is the same.** A manifest built window by window equals the one the upload path builds from the same bytes. The acceptance test reads a 1,200-page, 40 MB PDF to its manifest in a JVM with `-Xmx256m`, and again with a heap smaller than the file.

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

The store can also hold **derived files**: views EDDI makes of an uploaded file, such as a PDF's structure, the text of a page window, an extracted image or a page-range slice. Nothing creates one yet; the document features that will are off by default and arrive in later releases. What is already true:

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

Deleting a discussion (`DELETE /groups/{groupId}/conversations/{id}`) deletes its blobs, and a GDPR erasure of the owner reaches them — see [gdpr-compliance.md](gdpr-compliance.md).

Each member's private conversation is granted access on its **first** turn and receives the files as `attachment_*` context; from there everything on this page applies unchanged. Later turns rely on the member's own history plus the auto-enabled `readAttachment` tool, so a member does not lose the file after phase one.

Two group-specific bounds: the per-turn cap (`eddi.attachments.max-per-turn`) applies per **member turn**, and anything dropped is reported in that member's `attachments:errors`, not in the group transcript. See [group-conversations.md → Attachments](group-conversations.md#attachments).

## Sharing between agents — the rule

Every path that hands a stored file from one conversation to another follows one rule, implemented once in `AttachmentShareService` (`engine/attachments`). Today only its read check (rule 3) is in use, by the nested-group pass-through above — that path is neither metered nor audited as a share; the agent tools that pass files on (delegation first) call the full service.

1. **A model names files; it never holds a reference.** What a conversation's model may name is its **listing**: the files it owns or was granted (`listAccessible`), minus the files of a turn an input guardrail refused (withheld). Each entry is shown by its file name — `(unnamed)` for a file stored without one, never the `storageRef` — and can also be named by its 1-based number. The listing is sorted by name (then by reference), so the same files always list the same way, but a file added or granted later can shift the numbers: a number is only meaningful against the listing shown in the same turn. A name that matches several files is refused with their numbers; a name that is not in the listing is `not found`, whether the file exists elsewhere or not at all.
2. **Server code picks both ends.** The source is the conversation the tool is bound to; the target is a conversation server code created for the share. Neither comes from a model or from client context.
3. **The listing is checked, not trusted.** It must have been built for the source conversation; a withheld file is never shared, even from a listing assembled by hand; and the source must still be able to read the file when it shares it (owner or grantee). Each of these refusals reads `not found` and names no file. A grantee may share a file onward, within the hop limit.
4. **Limits**, set by the caller from the agent's configuration: files per share, shares per turn (one count for the whole turn, whatever the number of tool assemblies), allowed MIME types and the hop limit. A refused file uses up no slot.
5. **What a share does:** it grants the target conversation read access to the file's root — so the receiver can read the whole file with `readAttachment` — records an audit entry in the **source** conversation's ledger (`taskId` `attachment.share`: target conversation and agent, reason, MIME type, size; no bytes, no file name), and counts the metrics below. The tool that shared stores a row per file under the step key `attachments:shared` (file name, target agent, reason — no references).
6. **Not yet:** parts of a file. `report.pdf#u3` (a unit) and `report.pdf#p4-9` (pages) are understood and refused with *parts of a file cannot be shared yet*. A file whose name itself contains `#` is still found by its exact name.

| Metric | Tag | Meaning |
|---|---|---|
| `eddi_attachment_shared_total` | `path` (`delegation`, `group_task`, `group_share`, `handoff`, `a2a_outbound`, `mcp`, `nested_group`) | Files shared |
| `eddi_attachment_share_refused_total` | `reason` (`not_found`, `ambiguous`, `part`, `mime_type`, `depth`, `files_per_share`, `shares_per_turn`, `target`, `grant_failed`, `unavailable`) | Share requests refused |

Granting is idempotent on both stores (MongoDB adds to a set, PostgreSQL appends only when absent), so sharing the same file twice leaves one grant.

## Architecture Notes

- **No inline storage**: Attachment payloads are never stored inline in conversation memory documents. Only metadata references are persisted.
- **Transient base64**: The `base64Data` field is `transient` — it exists only during the pipeline turn. For persistence, use the upload endpoint with `IAttachmentStore`.
- **DB-agnostic**: The `IAttachmentStore` SPI supports MongoDB (GridFS) and PostgreSQL (bytea) implementations; files larger than `eddi.attachments.max-size-bytes` keep their bytes in a blob backend ([Storage backends](#storage-backends)).
- **GDPR cleanup**: `IAttachmentStore.deleteByConversation()` removes all attachments when a conversation is deleted, with every derived file of them.
- **Lineage**: a derived file is a row (PostgreSQL) or GridFS file (MongoDB) of the same store with `root_ref` / `metadata.rootRef` naming the uploaded file, and its owner fields copied from it when it is written. The store announces every deleted uploaded file as a CDI event, `AttachmentRootDeleted`, so caches and jobs keyed by that file can clean up, and announces a file that moves into a library as `AttachmentOwnerChanged` ([Library documents](#library-documents)). The new columns are additive (`ADD COLUMN IF NOT EXISTS` / new metadata fields, no backfill): `owner_key` empty reads as the conversation id, `page_count` empty as unknown.

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

## Library documents

The store can move an uploaded file into a user's **personal library** (`IAttachmentStore.promote`). Nothing calls it yet — the library tools and the REST surface come with later releases — but the rules are fixed here, in both stores:

- **The file moves; nothing is copied.** The file and every derived file of it change owner (owner scope `USER`, owner key the user's personal space) in one step. Views stored later belong to the library too.
- **It leaves its conversation.** The conversation it came from keeps a grant and reads it like any other conversation it was shared with. It cannot delete it (`delete` refuses a library document for every conversation), does not list it among its own files, and deleting the conversation removes only its grant: the document stays in the library.
- **Library quotas of its own.** A library document and its image, table, audio and embedded-file views count against the library (`eddi.documents.library.max-per-user`, 50 documents, and `.max-total-bytes-per-user`, 2 GiB) instead of the conversation's and the user's upload quotas; text views never count. A full library refuses the next document with both numbers and never evicts one.
- **Grants are a ring.** A library document keeps at most `eddi.documents.library.max-grants-per-document` (1,000) grants: past it, the conversation that was given access first loses its grant (opening again does not move its place; the conversation opening it now never loses it) and opens it again when it needs it.
- **Removal is soft first.** A removed document is hidden from the library's listings and its grantees' file lists, keeps counting toward the quota, and can be restored; deleting it for good, by document or for a whole library (erasure), removes its derived files and grants — and, for a whole library, its quota reservations — and announces it as deleted. Retention counts from the last opening (`eddi.documents.library.retention-days`, 365).

Slugs — the last part of a document's name in the library — are unique per library; a taken one gets `-2`, `-3`, …. See [configuration-reference.md](configuration-reference.md) for the properties.


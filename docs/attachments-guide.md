# Multimodal Attachments — Usage Guide

> EDDI's attachment pipeline enables multimodal conversations — users can send images, files, and documents alongside text input. Attachments flow through the lifecycle pipeline and are automatically forwarded to vision-capable LLMs.

## Quick Start

### Send an Image via URL

```bash
POST /agents/{conversationId}/say?message=What%20is%20in%20this%20image?
Content-Type: application/json

{
  "attachment_0": {
    "type": "object",
    "value": {
      "mimeType": "image/png",
      "url": "https://example.com/photo.png",
      "fileName": "photo.png"
    }
  }
}
```

### Send an Image via Base64

```bash
POST /agents/{conversationId}/say?message=Describe%20this%20icon
Content-Type: application/json

{
  "attachment_0": {
    "type": "object",
    "value": {
      "mimeType": "image/png",
      "data": "iVBORw0KGgoAAAANSUhEUgAAAAE...",
      "fileName": "icon.png"
    }
  }
}
```

The image is automatically forwarded to the LLM as multimodal content. The LLM "sees" the image alongside the text message.

---

## How It Works

```
Client sends context with attachment_* keys
           │
           ▼
┌──────────────────────────────────┐
│  Conversation.prepareLifecycleData()  │
│                                       │
│  AttachmentContextExtractor parses    │
│  attachment_0, attachment_1, ...      │
│  into List<Attachment> objects        │
│                                       │
│  Stored in memory: "attachments"      │
└──────────────┬───────────────────┘
               │
    ┌──────────┼──────────┐
    ▼          ▼          ▼
┌────────┐ ┌────────┐ ┌────────────┐
│BehaviorRules│ │LlmTask│ │Other Tasks │
│             │ │       │ │            │
│ContentType- │ │Multi- │ │Read from   │
│Matcher      │ │modal  │ │memory key  │
│condition    │ │Message│ │"attachments"│
│             │ │Enhancer││            │
└────────┘ └────────┘ └────────────┘
```

### Pipeline Stages

1. **Context Extraction** — `AttachmentContextExtractor` parses `attachment_*` context keys into `Attachment` objects
2. **Memory Storage** — Attachments are stored as `List<Attachment>` in the `attachments` memory key
3. **Rule Matching** — `ContentTypeMatcher` condition matches on MIME types for routing
4. **LLM Forwarding** — `MultimodalMessageEnhancer` converts attachments to langchain4j `ImageContent` and enhances the user message

---

## Input Paths

### Path A: URL Reference (Recommended)

Best for images already hosted somewhere. The LLM provider fetches the image directly from the URL.

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
  "storageRef": "gridfs://68abc123def456",
  "fileName": "report.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 524288
}
```

The returned `storageRef` can then be used in subsequent conversation turns by setting it as the `url` in an attachment context key. The storage backend (GridFS or PostgreSQL) is selected automatically based on the configured datastore.

| Response Code | Meaning |
|---|---|
| `201` | File stored successfully |
| `400` | No file provided |
| `503` | No attachment storage configured |

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
| `mimeType` | Yes | MIME type (e.g., `image/png`, `application/pdf`) |
| `url` | One of url/data | External URL reference |
| `data` | One of url/data | Base64-encoded content |
| `fileName` | No | Original filename (for metadata/logging) |

If both `url` and `data` are present, `url` takes precedence.

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

All attachments are forwarded to the LLM in a single multimodal user message.

---

## LLM Multimodal Support

The `MultimodalMessageEnhancer` automatically converts attachments to the appropriate langchain4j content type:

| MIME Type | langchain4j Content | Provider Support |
|---|---|---|
| `image/*` | `ImageContent` | OpenAI GPT-4o, Gemini, Claude 3, Ollama (LLaVA) |
| `application/pdf` | Metadata text (future: `PdfFileContent`) | Gemini |
| `audio/*` | Metadata text (future: `AudioContent`) | Gemini |
| Other | Metadata text description | All (text-only) |

For unsupported MIME types, a text description is injected so the LLM knows an attachment was present:
```
[Attachment: report.csv (text/csv, 15240 bytes)]
```

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

Attachments are available in templates via the memory namespace:

```
Current step attachments: {memory.current.attachments}
```

This can be useful for logging, debugging, or constructing custom prompts that reference attachment metadata.

---

## Group Conversations

Group discussions accept the same three input shapes on `POST /groups/{groupId}/conversations`. Inline `base64Data` is stored in the blob store owned by the group conversation; hosted `url` references and pre-uploaded `storageRef`s pass through.

Each member's private conversation is granted access on its **first** turn and receives the files as `attachment_*` context; from there everything on this page applies unchanged. Later turns rely on the member's own history plus the auto-enabled `readAttachment` tool, so a member does not lose the file after phase one.

Two group-specific bounds: the per-turn cap (`eddi.attachments.max-per-turn`) applies per **member turn**, and anything dropped is reported in that member's `attachments:errors`, not in the group transcript. See [group-conversations.md → Attachments](group-conversations.md#attachments).

## Architecture Notes

- **No inline storage**: Attachment payloads are never stored inline in conversation memory documents. Only metadata references are persisted.
- **Transient base64**: The `base64Data` field is `transient` — it exists only during the pipeline turn. For persistence, use the upload endpoint with `IAttachmentStorage`.
- **DB-agnostic**: The `IAttachmentStorage` SPI supports MongoDB (GridFS) and PostgreSQL (bytea) implementations.
- **GDPR cleanup**: `IAttachmentStorage.deleteByConversation()` removes all attachments when a conversation is deleted.

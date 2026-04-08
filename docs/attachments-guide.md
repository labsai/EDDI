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

> **Note:** `base64Data` is transient — it's never persisted to MongoDB. For large files, use the upload endpoint (coming soon) or URL references.

### Path C: Upload Endpoint (Coming Soon)

For large files, a multipart upload endpoint will store the binary in GridFS/PostgreSQL and return an `Attachment` reference:

```
POST /agents/{conversationId}/attachments
Content-Type: multipart/form-data

→ Returns: Attachment { id, storageRef, mimeType, fileName, sizeBytes }
```

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
Current step attachments: {{memory.current.attachments}}
```

This can be useful for logging, debugging, or constructing custom prompts that reference attachment metadata.

---

## Architecture Notes

- **No inline storage**: Attachment payloads are never stored inline in conversation memory documents. Only metadata references are persisted.
- **Transient base64**: The `base64Data` field is `transient` — it exists only during the pipeline turn. For persistence, use the upload endpoint with `IAttachmentStorage`.
- **DB-agnostic**: The `IAttachmentStorage` SPI supports MongoDB (GridFS) and PostgreSQL (bytea) implementations.
- **GDPR cleanup**: `IAttachmentStorage.deleteByConversation()` removes all attachments when a conversation is deleted.

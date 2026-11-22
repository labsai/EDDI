## ✨ feat(a2a): inbound file parts, off by default (WP-7.2) (2026-11-21)

**Repo:** EDDI (`feat/a2a-inbound-files`). Stacked on `feat/doc-share-service` (WP-1.1, #1469). PR into `integration/pdf-documents`.

### What changed and why

A peer agent could send EDDI text and data parts only; a message of files alone was refused with `-32005`. Work package 7.2 of the PDF-documents plan lets a deployment take files in over A2A, under `eddi.a2a.files.inbound`:

- `off` (default) keeps the wire behaviour unchanged. A message of files alone gets the same `-32005` with the same message, and the file parts of any other message are ignored.
- `inline` stores a file part's inline bytes.
- `inline-and-uri` also fetches a part's URI.

- **Wire shapes, every dialect.** `A2AWireFormat` reads two shapes in every dialect: the A2A 1.0 flat part (`raw`/`url`, `filename`, `mediaType`), and the 0.3 and pre-0.2 nested part (`kind`/`type: "file"`, `file: {name, mimeType, bytes | uri}`). The shapes were checked against the v1.0.1 proto and the v0.3.0 JSON schema. `A2AModels.Part` gains a `FilePart`, which renders in each dialect. The two-argument `parseSend` and the old `SendRequest`/`Part` constructors are kept, so nothing that reads text changes.
- **Stored through the store, owned by the conversation.** The new `A2AInboundFiles` stores each file into the task's conversation with `IAttachmentStore.store`. The calling peer is the user, so the size limit, the MIME check by signature and both quotas apply exactly as for a REST upload. The files then reach the turn as `attachment_N` input context. They are erased with their conversation and need no new erasure participant. They never reach a library, because an A2A principal is not a verified user.
- **URIs are attacker-chosen.** A URI is fetched through `OutboundHttp` under `Profile.DEFAULT` with `SsrfPolicy.STRICT` set explicitly (URL and every redirect hop), with no credentials and `BodyMode.bytes(eddi.attachments.max-size-bytes, FAIL)`. It is the in-memory path, as plan decision P29 says: a larger file is refused with the limit named. Every refusal message is written in the A2A layer: the peer never sees store detail or a resolved address, and an unfetchable URI is one message whatever the cause.
- **Refusals leave nothing.** Every file is decoded or fetched, and its type checked against its signature, before a conversation is created. That covers the count cap (`eddi.a2a.files.max-per-message`, 5, never above `eddi.attachments.max-per-turn`), a URI under `inline`, malformed parts and bad base64, the size limit, a failed fetch and a type mismatch. Only the quota is checked once the conversation exists, because only the store knows it. A conversation started for a message the quota refuses is ended again. A refusal deletes the files already stored for the message, as does a failure to bind the context or save the task afterwards. A stream answers a refusal with a plain JSON-RPC error before its first event.
- **A context that moves keeps its files.** When a context's conversation has ended and the turn moves to a fresh conversation, each file is deleted from the ended conversation and then stored into the fresh one, so a peer at its quota can still move them. Before this, the fresh turn would have named blobs it could not read.
- **Agent Card.** `defaultInputModes` adds `application/pdf` and four image types only when the mode is not `off`.
- **Meter.** `eddi_a2a_files_total{direction="inbound"}` is listed in `docs/metrics.md`, with a dashboard panel.

Files: `engine/a2a/A2AModels.java`, `A2AWireFormat.java`, `A2AInboundFiles.java` (new), `A2ATaskHandler.java`, `RestA2AEndpoint.java`, `AgentCardService.java`; tests `A2AInboundFilesTest`, `A2AWireFormatFilesTest`, `A2ATaskHandlerFilesTest`, `AgentCardFileInputModesTest`, `FakeAttachmentStore`; docs `a2a-protocol.md` (new "File parts" section), `security.md`, `configuration-reference.md`, `metrics.md`, the dashboard, `application.properties`.

### Decisions

- A URI part under `inline`, and a message over the count cap, are **refused with a named reason**, never silently dropped. The plan left both open.
- Both wire shapes are read in every dialect, the same leniency text parts already get.
- **Platform Operator:** no change in this PR. The plan folds the operator text for `eddi.a2a.files.*` into WP-7.3's revision bump.

### What's next

WP-7.3 (A2A outbound files with an egress approval) builds on this.

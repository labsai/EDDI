## fix(attachments): no ownership oracle, fenced attachment text, quieter http call logs (2026-11-18)

**Repo:** EDDI (`fix/tool-arguments-and-builtin-tool-answers`)

### What changed and why

Review finding P4.

- **Existence oracle.** The attachment download and delete endpoints answered `403` with a body naming the
  owning conversation for a blob the caller does not own, and `404` for one that does not exist.
  Both now answer `404 {"error":"Attachment not found","code":"ATTACHMENT_NOT_FOUND"}`, and the stores'
  denial text (`GridFsAttachmentStore`, `PostgresAttachmentStore`) no longer contains the owner id. The
  same oracle through conversation turns is closed too: a stored attachment that cannot be read is
  noted in `attachments:errors` (`AttachmentContextExtractor`) and told to the model
  (`AttachmentForwarder`) with one fixed reason — "it does not exist or is not part of this
  conversation" — never the store's own message.
- **Unfenced attachment text.** `AttachmentForwarder` wraps inlined attachment text in the existing
  retrieved-context envelope (`ToolResultProvenance.markRetrieved`) and neutralises the file name and media
  type (no control characters, invisible format characters such as bidi overrides or zero-width
  spaces, or brackets; 100 characters). The fenced text is also what `attachments:extracts` stores for
  history stitching.
- **Logs.** The request and response lines of an HTTP call (URI, query, 150 characters of body) moved from
  INFO to DEBUG; the "Execution time" line stays at INFO.

Response typing is deliberately unchanged: an httpcall response is parsed as JSON only for
`application/json` (parameters stripped), as before. Parsing `+json` types (`hal+json`,
`problem+json`) as well would silently turn `{memory.current.httpCalls.x}` from a string into an object
for existing agents, so it is left for a change of its own.

### Upgrade note

Clients that treated `403 ATTACHMENT_ACCESS_DENIED` from the attachment endpoints as a signal must treat
`404` the same way. Stored extracts written before this change are replayed unfenced.

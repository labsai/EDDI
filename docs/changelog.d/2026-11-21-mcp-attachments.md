## ✨ feat(mcp): `attachments` on `talk_to_agent`, `chat_with_agent` and `discuss_with_group` (2026-11-21)

**Repo:** EDDI (`feat/mcp-attachments`). Work package WP-7.1 of the PDF documents plan; it depends on WP-1.1 (`feat/doc-share-service`).

### What changed and why

An MCP client could talk to an agent or start a group discussion, but it had no way to hand over a file. REST already accepts one, so the three MCP tools now take the same thing, through the same code.

- **`McpAttachmentArguments`** (`engine/mcp`): parses and checks the new optional `attachments` argument. It is a JSON array string, because every structured MCP argument here is a string. It holds at most 50 entries, each one file with exactly one source: `storageRef`, `url` with `mimeType`, or base64 `data` with `mimeType`, plus an optional `fileName`. An argument that is not an array of objects is refused with `BAD_REQUEST` before anything runs. So is an entry with an unknown field, no source, more than one source, `url`/`data` without a `mimeType`, a field longer than REST's `AttachmentRef` allows, or an inline file above `eddi.attachments.max-size-bytes`. The errors name the entry (`attachments[1]`) and never echo its content or the parser's message. The checks are made by hand because Bean Validation does not run on an in-process MCP call.
- **`talk_to_agent`, `chat_with_agent`:** the entries become the turn's `attachment_0 … attachment_N` context. `Conversation` then resolves them exactly as for a REST `say`:
  - a `storageRef` must be one the **target** conversation can already read;
  - `eddi.attachments.max-per-turn` applies;
  - anything it drops goes to `attachments:errors`.

  `chat_with_agent` validates before it creates a conversation, so a refused call leaves none behind. The result does not echo the base64 back: `Conversation` already scrubs `data` (`scrubInlinePayload`) before it writes the context into the step's output.
- **`discuss_with_group`:** a `storageRef` is **refused** with an explanation. The group binder admits one only from a trusted source conversation (`materializeAttachments(gc, incoming, trusted)`), and an MCP call has none. The binder would otherwise drop it with a WARN only the operator sees. Inline and URL entries go through the REST path:
  - `GroupAttachmentLimits.rejectionReason` (per-file size, `max-per-conversation`, `max-total-bytes-per-conversation`);
  - `GroupAttachmentLimits.toAttachments`;
  - the service's `discuss(…, attachments)` overload, used only when there are files, as REST does.

  A per-user quota refusal is a `BAD_REQUEST` with REST's text.
- **Lifted, not copied:** `RestGroupConversation.toAttachments` and the quota-refusal text moved into `GroupAttachmentLimits` (`toAttachments`, `quotaRejection`). REST and MCP now share them, and `decodedLength` became public. REST behaviour is unchanged.
- **Docs:**
  - [mcp-server.md → Sending files](../mcp-server.md#sending-files-attachments): the argument schema per tool, what is refused, and the effective size. The binding limit is the MCP transport's JSON parser rather than the HTTP body limit (`RequestBodyLimitGuard`, about 27.7 MB with the defaults). The transport parses through Vert.x with a copy of the application's `ObjectMapper`, which keeps Jackson's default 20,000,000-character string cap, and the whole `attachments` argument is one string, so one call carries at most about 15 MB of inline files. EDDI's own parser of the argument lifts that cap, so a file the transport let through gets the per-file size message instead of "not valid JSON".
  - [attachments-guide.md](../attachments-guide.md#group-conversations) and [group-conversations.md](../group-conversations.md#attachments).
  - The `eddi` Claude Code skill.

### Design decisions

- **A JSON string, not a typed array argument.** All 244 existing `@ToolArg`s are scalars, and structured inputs (`resourceMappings`, `tasks`) are JSON strings. Keeping that convention keeps `McpToolContractTest` and the clients' handling unchanged.
- **Refuse the group `storageRef` at the edge** instead of relying on the binder to drop it: the caller learns why and what to send instead, and the test can tell refusal from silent loss.
- **Stricter than a REST `say`, on purpose.** REST's `attachment_N` context has no field ceilings and no per-file check. MCP holds every entry to the group request's rules (exactly one source, 255/2,048-character fields, `max-size-bytes`), because a model writes this argument and a clear refusal beats a silent drop.
- **One-to-one refs keep the REST semantics.** An unreadable ref is skipped and noted in `attachments:errors`, not refused. The tool description says so, because MCP callers do not see that key.
- **Platform Operator: no change.** MCP is not the operator's surface. Its prompt and allow-list cover REST endpoints, and nothing an admin asks the operator changes. No revision bump.
- **Out of scope:** `start_group_discussion`, `continue_group_discussion` and `chat_managed` do not take files yet. The async start is the natural next one, since the skill steers `TASK_FORCE` there.

### Tests

- `McpAttachmentArgumentsTest`: parsing and refusals, contexts that `AttachmentContextExtractor` reads back as STORED/URL/BASE64, and REST refs.
- `McpConversationToolsAttachmentsTest`: the context sent per tool, and refusal before the turn and before conversation creation.
- `McpGroupToolsAttachmentsTest`: `storageRef` refused with nothing run, also next to an inline file; inline and URL files reach the overload; the plain call without files; REST's limits; the quota text.
- The existing MCP tool tests were updated for the new trailing argument.

### Review follow-up (2026-11-23)

- **`GroupAttachmentLimits.decodedLength` counts unpadded base64 correctly.** It rounded a final group of 2 or 3 characters up to a whole 3 bytes, so a file exactly at `max-size-bytes` sent without `=` padding (which the JDK decoder accepts) was refused, for REST group requests and MCP alike. A final group of 2 or 3 characters now counts as 1 or 2 bytes; a malformed single character still counts as 1. New tests compare it with the decoder for every unpadded length and check the per-file limit at and one byte over the limit.
- **Docs:** [mcp-server.md](../mcp-server.md#sending-files-attachments) now says that `max-per-turn` applies per member turn in a group, and that a file above `eddi.attachments.max-forward-bytes` (10 MB) reaches the model as a note plus `readAttachment` rather than inline. The `eddi` skill lists what is refused with `BAD_REQUEST`, says `data` is not checked as base64, and states the per-member five-file cap and where a dropped file is noted (the member conversation's `attachments:errors`).

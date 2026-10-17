## 🔒 fix(security): group-discussion attachments get an owner, quotas and a delete path (2026-10-17)

**Repo:** EDDI (`fix/security-review-data-lifecycle`) — security review 2026-10-17, finding M4 and its REST edge.

### What changed and why

A group discussion's inline attachments were stored with the five-argument `IAttachmentStore.store(...)`, which records no user. So no per-user quota counted them (one `eddi-user` could store 100 MB per discussion, without bound), deleting the discussion left the blobs behind with nothing pointing at them, and nothing keyed on the user could find them.

- **Owner.** `GroupAttachmentBinder` stores with the discussion's `userId`, so `eddi.attachments.max-per-user` and `max-total-bytes-per-user` count group uploads. A quota refusal no longer silently drops the file: the blobs the request had already stored are removed, the just-created discussion is failed, and the caller gets `400` (`GroupAttachmentRejectedException`; a `group_error` event on `/stream`).
- **REST edge.** `RestGroupConversation` re-checks the inline attachments against `eddi.attachments.max-size-bytes` per file and `max-per-conversation` / `max-total-bytes-per-conversation` for the discussion before anything is decoded or stored (`GroupAttachmentLimits`, computed from the base64 lengths), as it already re-checks its other request ceilings. The body itself stays bounded by `RequestBodyLimitGuard` before it is read. Human input (`submitHumanInput`) carries no attachments, so there was nothing to bound there.
- **Delete.** `GroupLifecycleOps.deleteGroupConversation` deletes the discussion's blobs before the document. A failure keeps the document (500, retryable) rather than orphaning the blobs.
- **`storageRef` guard.** `materializeAttachments` used to pass a `storageRef`-only attachment straight to `grantAccess` for every member. It now accepts one only from a trusted source conversation that can read the blob — the parent discussion, for a nested `GROUP` member, which `MemberTurnExecutor` passes through a new `discuss(...)` overload — and grants the child discussion access so the next level can repeat the check. A reference with no trusted source (anything a client could send) is refused. Unreachable from REST today; the guard keeps it that way.

### Files

[`GroupAttachmentBinder.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupAttachmentBinder.java), [`GroupAttachmentLimits.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupAttachmentLimits.java), [`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java), [`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java), [`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java), [`RestGroupConversation.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java), [`IGroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/api/IGroupConversationService.java); docs [`attachments-guide.md`](../attachments-guide.md), [`group-conversations.md`](../group-conversations.md).

### Not done here

- Blobs stored by earlier releases still have no owner recorded, so they do not count towards a per-user quota. Deleting their discussion, or erasing its owner, now removes them.
- `close` still keeps the blobs (it keeps the transcript readable); only `delete` and erasure remove them.
- `McpGroupTools`' `delete_group_conversation` description and `docs/mcp-server.md` still name only artifacts and ephemeral agents (left to the workstream that owns the MCP group tools).

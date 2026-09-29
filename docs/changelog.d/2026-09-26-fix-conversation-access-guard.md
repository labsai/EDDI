## 🔒 fix(security): a deleted conversation no longer opens to every caller; `/active` and `/end` gated (2026-09-26)

**Repo:** EDDI (`fix/conversation-access-guard`)

### What changed and why

**C1a: soft delete bypassed the conversation owner check.** `ConversationAccessGuard.requireConversationOwner`
returned `null` ("allowed") whenever the live descriptor was missing. A soft delete (the default of
`DELETE /conversationstore/conversations/{id}`, and what the Manager's delete does) archives the descriptor
but keeps the memory snapshot, so from then on any authenticated caller could read the raw memory, run turns
as the owner over `POST /agents/{id}` (longTerm memory written under the owner's id), use SSE, the tool
history, the MCP conversation tools and the audit trail, and permanently delete the conversation.

- The guard now resolves the owner from the live descriptor and, if that is gone, from the archived copy
  (`readDescriptorWithHistory`). A soft-deleted conversation is owner-checked exactly like a live one.
- With no descriptor at all (live or archived), only an admin is let through (so an operator can still reach
  an orphaned snapshot or the audit trail of a deleted conversation). Everyone else gets a 404. With
  authorization disabled `isAdmin` is true, so nothing changes there.
- `requireExistingConversationOwner` resolves through the same method, so the two variants cannot drift.
- Soft delete now **ends** the conversation first, through `IConversationService.endConversation`, so a
  paused conversation's approval is resolved (attributed to the deleting caller, `system:delete` if there is
  no named one) and an in-flight turn does not write back. It also records ENDED on the descriptor before
  archiving it.
- **Conversations soft-deleted by earlier releases** are still READY. The conversation-id `say` and
  `sayStreaming` entry points (REST, SSE, MCP, Slack, `/v1`) now check for them: a conversation with no live
  descriptor but an archived one is ended on the first turn attempt and refused like any ended conversation.
  This is a lazy migration, at the cost of one descriptor read per turn on those entry points. The internal,
  agent-driven overloads (group members, schedules, A2A) are not checked.
- The retention sweep read "no live descriptor" as "orphan, delete now". Soft-deleted conversations now reach
  it as ENDED, so it checks the archived descriptor's age as well: they age out on the normal schedule, and an
  archive with no date is treated as expired. Aged-out soft deletes are counted in the sweep's total.
  Snapshots with no descriptor anywhere are still removed straight away (and not counted).
  **Retention effect:** a conversation ended and then soft-deleted used to be purged by the next daily sweep.
  It now stays until `deleteEndedConversationsOnceOlderThanDays` (default 365) has passed since its last
  interaction. Documented in `configuration-reference.md` and `gdpr-compliance.md`.
- **Stale managed-conversation mappings.** A permanently deleted or swept conversation leaves its
  intent→conversation mapping behind (only GDPR erasure removes it). The guard's new 404 escaped MCP
  `chat_managed` before the mapping was dropped, which failed every later call for that user. It now counts as a
  stale mapping: the mapping is deleted and a new conversation started. The REST twin
  (`RestAgentManagement.isConversationEnded`) failed on a missing conversation before this branch; it now
  recreates as well.
- The MCP conversation tools answer a guard 404 with `"Conversation not found"` and log it at debug. Before,
  it fell into the generic handler, which logged an ERROR with a stack trace for every probed id.

**NEW (audit trail readable after delete).** Audit entries are not deleted with a conversation, and the MCP
`read_audit_trail` and `read_agent_logs` (conversation-scoped) tools passed the missing descriptor.
Closed by the guard change: a non-admin gets "not found", while an admin can still read them for compliance.
The ledger is append-only by design (EU AI Act), so the entries are guarded, not deleted. The REST
`/auditstore` is already `eddi-admin` only.

**C1b: `/active` and `/end` had no role.** `GET /conversationstore/conversations/active/{agentId}` and
`POST …/end` let any authenticated principal (even one without an `eddi-*` role) list every user's open
conversation ids and end any of them. `/end` trusted the client's `conversationState`. A paused
conversation sent as `READY` skipped the HITL cleanup. Any conversation sent as `AWAITING_HUMAN` wrote a
forged `hitl.approval` cancellation to the audit trail.

- Both now take `@RolesAllowed({"eddi-admin", "eddi-editor"})` plus EDIT access on the agent. That is the
  same gate as undeploy, which already ends every active conversation of an agent. **The EDIT check is enforced
  only with workspaces on (`eddi.workspaces.enabled=true`, off by default).** Without it, the role is the
  whole gate and any editor can list and end any agent's open conversations. That is the reach
  undeploy-with-end already gives an editor, and a listed id grants nothing else, because every per-conversation
  endpoint is owner-or-admin.
- `/end` reads only the conversation ids from the request. It reads each conversation's state through the
  state projection and its agent from the conversation descriptor (live or archived), without loading the
  memory snapshot. Only a conversation with no descriptor at all falls back to the snapshot.
- **Authorization is all-or-nothing:** EDIT is checked for every conversation's agent before anything is
  ended. **Ending is per conversation and continues on error:** unknown and already ENDED ids are skipped.
  Every other conversation goes through `endConversation`, which decides server-side whether a pause is being
  terminated and attributes it to the calling principal (`system:admin-end` only if there is no named caller).
  The response body lists `ended`, `skipped` and `failed`, with status 200, or 500 if anything failed.
  Marking the descriptor ENDED is best-effort, since the listing re-derives the state from the snapshot.
  Undeploy-with-end now refuses to undeploy when the end reports a failure. The old raw
  `setConversationState(ENDED)` for non-paused conversations also skipped the in-flight signal and the state
  cache. A null body is a 400.

**NEW (soft-deleted active conversation broke undeploy).** `getActiveConversations` read the live descriptor
of every open conversation and threw NotFound for one that had been soft-deleted while open. That failed the
listing, and undeploy-with-end with it (500), and any user could trigger it with their own conversation. It
now falls back to the archived descriptor (`lastInteraction` null if neither exists). `endActiveConversations`
likewise ends a soft-deleted conversation.

**C1c: ownerless snapshot after a failed start.** `startConversation` stored the memory, then wrote the
descriptor (which records the owner). If that write failed, the snapshot stayed with no owner on record. The
start now discards the snapshot, the state-cache entry and any armed HITL timeout before rethrowing. The guard
change also denies non-admins any such snapshot.

**M-E5 plus NEW: 500 instead of 404.** The conversation-id overloads of `undo`, `redo`, `isUndoAvailable` and
`isRedoAvailable`, `PATCH /agents/{id}/state` (`resetState`) and `GET /llm/tools/history/{id}`
dereferenced the null snapshot the store returns for an unknown id. They now answer 404.

### Decisions

- **`/active` and `/end` are editor + agent EDIT, not admin-only** as the review suggested. Undeploy
  (`eddi-admin`/`eddi-editor` + EDIT) already ends all of them, and calls these methods in-process. The
  Manager's conversation-monitoring page calls both, and editors should keep it. The real hole was "no role at all" plus trusting client state.
- **Admins keep access to descriptor-less conversations.** The guard gives them `null`, so an operator can
  still inspect orphans and the audit trail of a deleted conversation. `requireExistingConversationOwner`
  (attachments) stays a 404 for admins too, as before.
- **Owners keep read access to their soft-deleted conversations.** The snapshot is kept on purpose, and it is
  now ENDED, so it can be read but not continued.

**Files:** [`ConversationAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/ConversationAccessGuard.java),
[`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java),
[`IRestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/IRestConversationStore.java),
[`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`RestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`RestToolHistory.java`](../../src/main/java/ai/labs/eddi/modules/llm/rest/RestToolHistory.java),
[`McpConversationTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`RestAgentManagement.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestAgentAdministration.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java),
[`gdpr-compliance.md`](../gdpr-compliance.md), [`configuration-reference.md`](../configuration-reference.md).
Regression tests are in `ConversationAccessGuardTest`, `RestConversationStoreTest`, `RestAgentEngineTest`,
`McpConversationToolsOwnershipTest`, `McpConversationToolsTest`, `ConversationServiceTest`,
`RestToolHistoryTest`, `RestAgentManagementExtendedTest` and `RestAgentAdministrationTest`.

```decision-log
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
```

```regression-note
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
```

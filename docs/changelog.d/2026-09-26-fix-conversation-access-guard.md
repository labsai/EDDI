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
- Soft delete now **ends** the conversation first, through `IConversationService.endConversation` (actor
  `system:delete`), so a paused conversation's approval is resolved and an in-flight turn does not write back,
  and records ENDED on the descriptor before archiving it. A deleted conversation can no longer be driven.
- The retention sweep read "no live descriptor" as "orphan, delete now". Soft-deleted conversations now reach
  it as ENDED, so it checks the archived descriptor's age as well: they age out on the normal schedule.
  Snapshots with no descriptor anywhere are still removed straight away.

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
  same gate as undeploy, which already ends every active conversation of an agent.
- `/end` reads only the conversation ids from the request. It takes each conversation's agent and state from
  the stored snapshot and checks EDIT once per agent, for the whole batch before it ends anything, so a mixed list is refused rather than half-applied. Unknown and already ENDED conversations are skipped.
  Every other conversation goes through `endConversation`, which decides server-side whether a pause is being
  terminated. The old raw `setConversationState(ENDED)` for non-paused conversations also skipped the in-flight
  signal and the state cache. A null body is a 400.

**NEW (soft-deleted active conversation broke undeploy).** `getActiveConversations` read the live descriptor
of every open conversation and threw NotFound for one that had been soft-deleted while open. That failed the
listing, and undeploy-with-end with it (500), and any user could trigger it with their own conversation. It
now falls back to the archived descriptor (`lastInteraction` null if neither exists). `endActiveConversations`
likewise ends a descriptor-less conversation on its snapshot.

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
[`RestToolHistory.java`](../../src/main/java/ai/labs/eddi/modules/llm/rest/RestToolHistory.java), with
regression tests in `ConversationAccessGuardTest`, `RestConversationStoreTest`, `RestAgentEngineTest`,
`McpConversationToolsOwnershipTest`, `ConversationServiceTest` and `RestToolHistoryTest`.

```decision-log
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
```

```regression-note
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
```

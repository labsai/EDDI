## 🔒 fix(security): close the M9, M10, L6, L9, L10 and L12 findings of the 2026-10-15 review (2026-10-15)

**Repo:** EDDI (`fix/authz-lifecycle`)

### What changed and why

Six residual findings of the security review of `main` at `d49e855`, each a gap that
the live RBAC/IDOR tests did not reach. One commit per finding; the code comments
name the finding so a later reader can find this entry.

- **M9 — A2A JSON-RPC skipped the per-agent USE check.** `POST /a2a/agents/{agentId}`
  required a role, but not the `USE` grant that `POST /agents/{id}/start` and `/v1`
  apply under enforced workspaces, so any `eddi-user` could converse with any agent.
  `RestA2AEndpoint.handleJsonRpc` now calls `ResourceAccessGuard.requireAgentUseAccess`
  before dispatching `SEND` and `STREAM` — outside the method's catch-all, so the
  refusal is a 403 and not a JSON-RPC "internal error". `tasks/get` and `tasks/cancel`
  stay principal-scoped: they reach only a task this peer created after passing the
  gate. `A2aEndpointPermissionsTest` and `RestA2AEndpointTest` cover every send and
  stream method name in all three dialects.
- **M10 — GDPR erasure and export did not reach the A2A task store.** A peer's tasks
  hold the message it sent and the reply, keyed by its principal, and outlived an
  erasure until the cache expired. `IA2ATaskStore` gains `tasksOf` and
  `eraseForPrincipal` (the length-prefixed key makes a principal's prefix exact), and
  `A2ATaskGdprParticipant` registers both with `GdprComplianceService` as step
  `a2aTasks`. It also joins the cascade's stop phase: `beginErasure` closes the store
  to the principal's writes for five minutes, so a peer request admitted while the
  cascade runs cannot leave a task behind the sweep; on one node the check and the
  write are serialised against the sweep by a read/write lock, and in cluster mode the
  marker lives in a shared `A2A_ERASING` bucket so every node refuses the writes, while a
  shared per-principal `A2A_INDEX` lets a sweep or export on one node reach the records
  other nodes wrote (bucket keys are hashed, so nothing else can enumerate them; the two
  narrow cross-node residuals are in [`clustering.md`](../clustering.md#residual-limitations)). [`gdpr-compliance.md`](../gdpr-compliance.md) lists it and now has a
  section naming what erasure does **not** reach — RAG knowledge bases, application
  log files, telemetry exports and LLM-provider retention — with why, and what bounds
  each.
- **L6 — dead letters kept the raw turn input for seven days.** `describeTurn` runs
  before the pipeline, so the `scope: secret` scrub could not help, and only a client
  that set `context.secretInput` was spared. The captured input and its string
  context values now pass through `SecretRedactionFilter` — string leaves inside
  object values included, with credential-named keys masked — an entry the client
  marked `secret` or whose key names a credential is dropped, and a turn addressed to an
  agent whose workflows carry any `scope: secret` property instruction is recorded as
  `secretInput: true` without input or context, like a client-flagged one. The fact is
  computed once at deployment: `PropertySetterConfiguration.hasSecretInstructions()`,
  carried by `IExecutableWorkflow` and aggregated on `IAgent`, and read from the agent
  `say` already resolved for the turn — no second lookup on the hot path. A turn with
  no resolved agent is treated as one that expects secrets.
- **L9 — three ownership gaps on the internal REST surface.**
  `RestAgentManagement` never bound the `{userId}` path segment to the caller (its
  only check asked whether the caller was anonymous), and `initUserConversation`
  deleted the ended-conversation mapping before the engine's transitive 403; every
  method now runs `OwnershipValidator.validateUserAccess` first. The group write paths
  `followUp`, `continue` and `continue/stream` used `requireOwnerOrAdmin`, which admits
  any authenticated caller to a legacy record with no owner; they use
  `requireOwnerOrAdminStrict`, as delete and close already did. And an `eddi-approver`
  could cancel any user's *executing* turn through `POST /agents/{id}/cancel`: a caller
  who is only an approver now goes through `cancelPausedConversation`, a single
  compare-and-set on `AWAITING_HUMAN` that never signals an executing turn, so the
  entitlement check and the cancel are one atomic operation (any other state is a 403;
  documented in [`hitl.md`](../hitl.md#who-may-decide)); the MCP `cancel_conversation` tool
  applies the same rule through `HitlAccessGuard.mayCancelExecuting`; owner and admin are
  unchanged.
- **L10 — unbounded listings and unbounded per-user attachments.**
  `?limit=2147483647` returned every descriptor in one response (verified live on
  `/agentstore/agents/descriptors`). `PageBounds` applies one ceiling —
  `eddi.rest.listing.max-limit`, default 1000, the Manager's largest descriptor page — to every
  config store's descriptor listing, the cross-resource `/descriptors`, the
  conversation listing (which used to clamp silently at 100), the visible user
  memories and both `/administration/logs` listings; a limit above it, or a negative
  index, is a 400 with a JSON `{"error": …}` body. `eddi.attachments.max-per-user` and
  `eddi.attachments.max-total-bytes-per-user` default to 500 files and 1 GiB instead of
  off ([`attachments-guide.md`](../attachments-guide.md#quotas)).
- **L12 (remaining) — legacy salt and request logging.** A boot that adopts the
  legacy fixed KEK salt (DEKs present, no per-deployment salt) records the fact in the
  vault metadata (`vault-kek-legacy-salt-seen`); every later boot still on it logs an
  **ERROR** naming `POST /secretstore/secrets/admin/rotate-kek` and the date the salt
  was first adopted, where before there was one WARN per boot. A persisted or migrated
  salt clears the record. `logRequests` on the LLM decorators wrote 200 characters of
  the last message — the user's own text — at INFO; the line is now DEBUG and both it
  and the `logResponses` line run through `LogRecordRedactor` (before truncation, so a
  key straddling the cutoff cannot leave its first characters behind), which is public
  for that.

**Files:** [`RestA2AEndpoint.java`](../../src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java),
[`A2ATaskGdprParticipant.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskGdprParticipant.java),
[`CachedA2ATaskStore.java`](../../src/main/java/ai/labs/eddi/engine/a2a/CachedA2ATaskStore.java),
[`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`WorkflowStoreClientLibrary.java`](../../src/main/java/ai/labs/eddi/engine/runtime/client/workflows/WorkflowStoreClientLibrary.java),
[`RestAgentManagement.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestGroupConversation.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java),
[`RestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`PageBounds.java`](../../src/main/java/ai/labs/eddi/utils/PageBounds.java),
[`VaultSaltManager.java`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultSaltManager.java),
[`ObservableChatModel.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ObservableChatModel.java),
[`application.properties`](../../src/main/resources/application.properties),
[`configuration-reference.md`](../configuration-reference.md), [`gdpr-compliance.md`](../gdpr-compliance.md),
[`hitl.md`](../hitl.md), [`secrets-vault.md`](../secrets-vault.md), [`langchain.md`](../langchain.md).

```decision-log
| 2026-10-15 | A listing above the page ceiling is refused with 400, not clamped | A clamp returns a short page a client mistakes for the whole; a refusal tells it to page. The ceiling (1000) is the Manager's largest page — it resolves agent names and dashboard counts from one page of 1000, and 200 broke both | Silent clamping, as the conversation listing did at 100; a 200 ceiling with the Manager paging |
| 2026-10-15 | An agent with any `scope: secret` instruction never has its turn input dead-lettered | The capture runs before the pipeline, so the scrub cannot help, and a client cannot be relied on to flag every secret turn; losing replayability for those agents' failed turns is the lesser cost | Per-turn detection of secret-shaped input (the redaction filter still runs on everything captured) |
| 2026-10-15 | An approver-only caller may cancel only an `AWAITING_HUMAN` conversation | The role exists to decide pauses; a cancel of an executing turn is the owner's or an admin's to make | A separate cancel permission |
```

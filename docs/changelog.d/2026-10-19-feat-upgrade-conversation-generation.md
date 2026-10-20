## ✨ feat(engine): move already-pinned conversations onto the current agent version (2026-10-19)

**Repo:** EDDI (`feat/upgrade-conversation-generation`)

### What changed and why

A ZIP import and an agent sync write a breaking version unless declared compatible, a breaking save
leaves the same result, and conversations that
started before compatibility generations existed carry none, so after a promotion every
running conversation stays pinned to the version it began on. Operators ended them, which is
a bad experience for the user in the middle of a conversation. This adds an operator-declared,
previewable repair: "this agent's conversations on an older generation (or none) are
compatible with the current version, move them onto it."

- **Endpoint.** `POST /administration/{environment}/upgradeconversations/{agentId}` with
  `dryRun` (default `true`), repeatable `fromVersion`, `limit` (default 5000, so a call stays inside a proxy timeout) and `after` (the cursor: a truncated report carries `nextAfter`). Same path
  style, roles and EDIT access check as `undeploy`. The response has the same shape for dry
  and real runs: target version and generation, and per source version `matched`, `eligible`,
  `upgraded` and `skipped` by reason. MCP: `upgrade_conversations`, same dry-run default
  (86 tools now). The REST call is recorded by the existing administrative-action audit filter;
  deploy and undeploy over MCP write no audit record either, so the tool adds none.
- **Target.** The generation of the agent's latest version. A version of it must be `READY`
  in the environment on the serving node, else `409`; an agent whose latest version has no
  generation also gets `409`. A generation is never lowered, and conversations already on the
  target are not touched.
- **Only the generation is written.** The existing per-turn machinery
  (`resolveConversationAgent`, `recordAgentVersionMove`) then moves the conversation to the
  newest `READY` version of the generation and records the move, so `agentVersion`, steps and
  the descriptor are not rewritten here.
- **States.** `READY`, `ERROR` and `EXECUTION_INTERRUPTED` are moved. `ERROR` is included on
  purpose: `say` refuses only ended and paused conversations, so an errored conversation is
  live and the new version often repairs it. `ENDED`, `AWAITING_HUMAN` (a resume must finish
  on the version that paused it) and a persisted `IN_PROGRESS` are skipped and counted.
- **Concurrency.** A say turn never persists `IN_PROGRESS`, so the store cannot see a running
  turn. Each write is therefore a task in the conversation's own coordinator queue
  (cluster-wide lease in cluster mode) and never overlaps a turn. The write is one conditional
  update and also moves `_rev` and `_histRev`: a turn queued behind it is rebuilt over the new
  document by the existing stale-snapshot guard, and a writer holding an older load is refused
  (append: history revision; full write: revision) instead of putting the old generation back.
  Tasks that do not start within `eddi.conversation-upgrade.turn-wait-ms` (30 s) are withdrawn
  and reported as `busy`.
- **Scale.** Candidates are read as a keyset-paged projection (id, state, version, generation),
  100 per round, never loaded in full and never via the active-conversation listing. Implemented
  for MongoDB (pipeline update) and PostgreSQL (`jsonb_set`).
- **Observability.** `eddi_conversation_generation_upgrade_count`; one INFO line per run with
  the counts.

### Tests

`ConversationUpgradeServiceTest` (selection, skips, target validation, never lower, dry run
writes nothing, timeout withdrawal, real-coordinator ordering against a running turn),
`RestAgentAdministrationUpgradeConversationsTest`, and a `GenerationUpgrade` group in both
`MongoConversationMemoryStoreTest` and `PostgresConversationMemoryStoreTest` (Testcontainers),
including the stale-append and stale-full-write interleavings.

### Docs

`docs/deployment-management-of-agents.md` ("Moving running conversations onto the current
version") and `docs/mcp-server.md`.

### Design decisions

- Order of preference: save or promote compatibly where possible; this action is for
  conversations that are already pinned.
- Ended conversations are not scanned (they would stay candidates forever and crowd out the ones that
  can move); they are counted per version with one query. The cursor exists because conversations
  skipped without change (paused, in progress, busy) stay candidates.

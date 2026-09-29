## ✨ feat(agents): each agent version records whether it is compatible with the previous one (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 1 of
[`planning/agent-version-following-plan.md`](../../planning/agent-version-following-plan.md). No
behaviour changes yet; Phase 2 makes conversations act on it.

### What changed

- **`AgentConfiguration.compatibilityGeneration`**, assigned by `AgentStore` on every write and
  never taken from the body: `create` starts at 1; `update(id, version, config, compatible)` keeps
  the previous generation when `compatible` is true and advances it otherwise; the plain `update`
  is breaking. A previous version without a generation (stored before this existed) starts a new
  chain either way, so its conversations stay pinned. Only the current version can be updated,
  so the previous version always holds the highest generation and no other version is read.
- **REST**: `PUT /agentstore/agents/{id}?version=N&compatible=true` and the same flag on
  `PUT /agentstore/agents/{id}/updateResourceUri`. Absent means `false`.
- **MCP**: `apply_agent_changes` takes `compatible` (default `false`) and reports
  `compatibleWithPreviousVersion` when it wrote a new agent version. It is the only MCP tool that
  creates an agent version from an existing one.
- **Import and sync** (`RestImportService`, `UpgradeExecutor`) always write a breaking version: the
  configuration comes from elsewhere and nobody has judged it against the conversations running
  here. `StructuralMatcher` leaves `compatibilityGeneration` out of the agent comparison — each
  instance numbers its own, and comparing them made an unchanged agent UPDATE on every sync.
- **`IAgent.getCompatibilityGeneration()`**, set when a version is deployed.

## 🐛 fix(conversations): an ended conversation no longer strands a Slack thread or a heartbeat (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 0 of
[`planning/agent-version-following-plan.md`](../../planning/agent-version-following-plan.md)

### Why

The idle sweep and `undeploy?endAllActiveConversations=true` both end conversations, and two
callers never recovered from that: a Slack thread kept sending to its ended conversation and was
refused on every further message, and a `conversationStrategy=persistent` schedule kept firing into
its ended conversation until the FAILED fires dead-lettered it.

### What changed

- **`ConversationService.say` / `sayStreaming`** refuse an ENDED conversation before looking its
  agent up (`rejectIfEnded`). The ended check used to come after the lookup, so an ended
  conversation whose version had been undeployed — the normal state after `endAll` — answered
  "agent not ready", which no client recovers from.
- **Slack** (`SlackEventHandler.openThreadConversation` / `sendInThread`): a mapped conversation
  that has ended or vanished is replaced by a fresh one, and a conversation that ends between
  opening and sending (including a queued turn dropped as ENDED) is replaced once and the message
  resent. When the old conversation ended because its agent version was retired, the thread is told
  first. A state that cannot be read keeps the conversation — a store hiccup never costs a thread.
- **`IUserConversationStore.deleteUserConversationIfMatches`** (Mongo + Postgres): the mapping is
  removed only while it still names the ended conversation, so two messages racing on one ended
  thread converge on one new conversation. Both stores now also report a raced duplicate insert as
  `ResourceAlreadyExistsException` (Mongo `E11000`, Postgres `23505`) instead of a raw driver
  error, which is what the existing create-race recovery listens for.
- **`ScheduleFireExecutor.resolveOrCreatePersistent`** treats an ENDED conversation like an
  unreadable one and creates a fresh conversation.
- **End reason**: `IConversationService.endConversation(id, endedBy, endReason)` records why a
  conversation ended (`endReason` on the stored and client-facing snapshots, a narrow field update
  on both stores). `END_REASON_AGENT_VERSION_RETIRED` is the first value; the undeploy path sets it
  in Phase 3.

## 📝 docs(planning): conversations follow compatible agent versions (2026-09-29)

**Repo:** EDDI (`docs/agent-version-following`)

### Why

A conversation is pinned to the agent version it started on for its whole life, so fixes never
reach long-running conversations and old versions cannot be undeployed without ending
conversations that could have carried on.

### What changed

- New plan: [`planning/agent-version-following-plan.md`](../../planning/agent-version-following-plan.md).
  No code changes.

### Design decisions

- **Breaking is the default.** Every save produces a breaking version unless the save request
  explicitly passes `compatible=true`. Existing agent versions carry no compatibility value and
  stay pinned, so upgrading EDDI changes nothing.
- **A server-owned compatibility generation** (semver-major equivalent) decides which versions a
  conversation may move between. It is assigned by the store and never read from the config body,
  so copies, exports and agent sync cannot carry a "compatible" forward by accident.
- **The version is resolved on every turn** (highest ready version of the conversation's
  generation on this node), which covers the 10 s cluster rollout window and makes undeploying a
  faulty version an automatic rollback. Compatibility is therefore bidirectional by definition.
- **No new end-conversation policy**: keeping or ending conversations on an older generation uses
  the existing undeploy options, with a new `system:agent-version-retired` reason.
- **Undeploy counts only conversations that cannot move**, so compatible conversations neither
  block undeploy nor get ended by `endAllActiveConversations`.

- **The generation is a store-owned `AgentConfiguration` field.** Only the latest version can be
  updated (`HistorizedResourceStore.update`), so the previous version always holds the highest
  generation and the assignment needs no other lookup.
- **Two existing gaps found and scheduled as Phase 0**: a Slack thread whose conversation has ended
  fails on every further message, and a `persistent` schedule whose conversation has ended is
  retried until it dead-letters. Both will start a fresh conversation instead. Both already happen
  today (the idle sweep and `endAllActiveConversations` end conversations).

### Next

Phase 0 of the plan (ended-conversation recovery for Slack and persistent schedules), then
Phase 1 (generation stored and exposed, no behaviour change).

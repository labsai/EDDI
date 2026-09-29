## 🐛 fix(conversations): review findings for version following (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — found by reviewing Phases 0–3 against the code
paths around them.

- **The daily deployment sweep ended conversations that could have moved.** It retires old versions
  by ending their idle conversations, then undeploying once none are left. For an old version with a
  newer compatible version ready, it now undeploys at once and ends nothing
  (`AgentDeploymentManagement.retireIfConversationsCanMove`): those conversations continue on the
  newer version whenever they return.
- **A group member's private conversation that ended mid-discussion failed the member for every
  remaining turn** (`MemberTurnExecutor`). It now continues in a fresh conversation, once; a second
  end in a row is an ordinary member failure.
- **The move was recorded before the turn was admitted.** The descriptor update and the switch
  counter ran when the version was resolved, before the quota check, so a refused turn left the
  descriptor naming a version the conversation never ran on. Both now run when the turn completes.
- **Docs**: [`docs/deployment-management-of-agents.md`](../deployment-management-of-agents.md#running-conversations-and-new-agent-versions)
  has the full model; `hitl.md`, `architecture.md`, `scheduling.md`, `slack-integration.md`,
  `mcp-server.md` and `metrics.md` link to it, and the full-metrics dashboard charts
  `eddi_conversation_agent_version_switch_count`.

## ✨ feat(deployment): undeploy and deploy understand compatible versions (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 3 of
[`planning/agent-version-following-plan.md`](../../planning/agent-version-following-plan.md)

### What changed

- **Undeploy** (`RestAgentAdministration.undeployAgent`): open conversations on a version no
  longer block it with 409, and are not ended by `endAllActiveConversations`, when another
  deployed version in the same environment has the same compatibility generation — they move
  there on their next turn. "Deployed" is the deployment records plus this node's registry (which
  also holds `autoDeploy=false` deployments). A version undeployed by the same call never counts:
  `undeployThisAndAllPreviousAgentVersions` runs its undeploys asynchronously, and counting them
  would strand the conversations. Everything else — no generation, a breaking successor, a record
  that cannot be read — behaves exactly as before.
- **Conversations ended by an undeploy record `agent-version-retired`**, via a new optional
  `endReason` on `POST /conversationstore/conversations/end`. Only known reasons are accepted
  (400 otherwise): the reason is shown to users, so it must never be caller-supplied text.
- **Schedules survive retiring an old version.** Undeploy disabled every schedule of the agent on
  any undeploy, so "deploy v6, undeploy v5" — the normal rollout — silently switched off every
  heartbeat. They are now disabled only when the call leaves no version of the agent deployed in
  that environment.
- **`GET /administration/{env}/deploymentimpact/{agentId}?version=N`**: for every other deployed
  version, its open conversations and whether they `FOLLOW` version N (same generation, older) or
  `STAY` (another generation, none, or newer). Requires EDIT on the agent, like undeploy.

## ✨ feat(conversations): conversations follow compatible versions of their agent (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 2 of
[`planning/agent-version-following-plan.md`](../../planning/agent-version-following-plan.md)

### What changed

- **Per-turn resolution** (`ConversationService.resolveConversationAgent`, used by `say` and
  `sayStreaming`): a conversation with a compatibility generation runs on the highest `READY`
  version of that generation on this node, moving to it if it is elsewhere — forward when a
  compatible version is deployed, back when the newer one is undeployed (a rollback) or has not
  reached this node yet. A conversation without a generation, a paused one, and one whose
  generation has nothing ready here take the old path unchanged (`getAgent`, including its
  on-demand deploy).
- **Memory**: `compatibilityGeneration` on the conversation (set from the version it starts on,
  persisted on the snapshot); `agentVersion` is no longer final. `IAgentFactory` gains
  `getLatestReadyAgentOfGeneration`.
- **Every step records its version** as step data `agent:version`, and the first step after a
  move records `agent:switch = {from, to}`. The marker is not `agent:version…` because step
  lookups match keys by prefix — the first name tried shadowed the version key in the tests.
- A move updates the conversation descriptor's agent URI (best-effort; listings filter on it),
  logs at INFO and increments `eddi_conversation_agent_version_switch_count`. Undo/redo read the
  memory config from any version of the generation when the conversation's own is gone.

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

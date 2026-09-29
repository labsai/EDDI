# Agent Version Following — Conversations Move to Compatible Versions

> **Status:** planned, not started. Written 2026-09-29 on `docs/agent-version-following`.
>
> **One-line summary:** a running conversation moves to the newest deployed version of its agent **when, and only when, that version was explicitly saved as compatible**. Every save is a breaking change unless the author says otherwise, so nothing changes for anyone who does not opt in.

## Prerequisite Reading

1. [`docs/deployment-management-of-agents.md`](../docs/deployment-management-of-agents.md) — deploy, undeploy, `autoDeploy`, and the `endAllActiveConversations` conflict
2. [`docs/architecture.md`](../docs/architecture.md) — the configuration-as-code model (agent → workflows → extensions, all versioned)
3. [`docs/hitl.md`](../docs/hitl.md) — why a paused conversation must resume on the version that paused it
4. Key files:
   - [`ConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java) — `say()` / `sayStreaming()` resolve the agent from `conversationMemory.getAgentVersion()` (two copies of the same block, ~L672 and ~L832)
   - [`AgentFactory.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentFactory.java) — the per-node registry of deployed `(agentId, version)` pairs; `getAgent` returns `null` for a version this node has not deployed
   - [`AgentDeploymentManagement.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java) — the 10 s sweep that brings other nodes in line with the deployment records
   - [`RestAgentAdministration.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java) — undeploy, the active-conversation 409, `endAllActiveConversations`
   - [`ConversationMemory.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationMemory.java) — `agentVersion` is `final` today
   - [`ConversationHitlService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java) — resume reads the snapshot's version (`readAgentConfigPinned`)
   - [`update-usage-dialog.tsx`](../ui/manager/src/components/editors/update-usage-dialog.tsx) — the Manager's post-save dialog that cascades a resource change into new workflow and agent versions

---

## 1. The Problem

A conversation is pinned to the agent version it started on, for its entire life:

- `ConversationService` creates it on `getLatestReadyAgent(env, agentId)` and writes that version into the memory and the conversation descriptor's agent URI.
- Every turn calls `getAgent(env, agentId, memory.getAgentVersion())`. Nothing ever moves it forward.

Consequences today:

- **Fixes do not reach running conversations.** A prompt correction, a bug fix in a behavior rule, a tightened tool whitelist or a new HITL gate only applies to conversations started afterwards. Long-lived conversations — managed conversations (`/managedagents/{intent}/{userId}`), channel threads, `/v1` chats — can run on a stale config for weeks.
- **Old versions pile up in deployment.** Undeploying a version with active conversations returns 409; the only way past it is `endAllActiveConversations=true`, which ends conversations that could have carried on perfectly well.

Most agent changes are safe to apply mid-conversation. Some are not: a renamed property, a removed action that a rule on `lastStep` matches, a restructured multi-step flow. The author knows which is which; EDDI cannot tell by itself.

## 2. Goals and Non-Goals

**Goals**

- An author can declare, per save, that a new version is **compatible** with the previous one; conversations then follow it from their next turn.
- Anything not declared compatible behaves **exactly** as today.
- Operators can see, before deploying, which conversations will move and which will stay.

**Non-goals (explicitly out of scope)**

- Automatic detection of breaking changes. A diff-based *hint* in the Manager is a possible follow-up (§9), never a decision.
- Migrating conversation state across a breaking change (property rename hooks etc.). Possible follow-up (§9).
- Changing how new conversations pick a version. They already start on the latest ready version.

## 3. The "Nobody Else Gets Issues" Guarantees

These are hard requirements. Each one must be covered by a test (§8).

1. **Upgrading EDDI changes nothing.** Existing agent versions carry no compatibility value; a version without one is pinned, exactly like today. No data migration runs.
2. **Saving without saying "compatible" changes nothing.** Every save path — Manager, REST, MCP, ZIP import, agent sync — produces a *breaking* version unless the request explicitly asks for compatible. A pinned conversation resolves to exactly the version it is on, and fails exactly as today when that version is not deployed.
3. **Compatibility cannot be inherited by accident.** The declaration is a property of the *save request*, not a field an author edits in the JSON, so copying a config forward, exporting it or syncing it can never carry a "compatible" into a version nobody looked at.
4. **No REST or MCP contract breaks.** Only new optional parameters and new response fields. Existing clients that ignore them behave as before.
5. **Undeploy behaves as today for pinned conversations.** The 409 and `endAllActiveConversations` change only for conversations that have a compatible version to move to.
6. **A paused (HITL) conversation resumes on the version that paused it.** Unchanged.

## 4. Design

### 4.1 The compatibility generation

Each agent version gets a server-owned integer, the **compatibility generation** — think of it as the agent's semver major version.

- Two versions of the same agent with the **same** generation are compatible; a conversation may move between them in either direction.
- A version with **no** generation (every version that exists before this feature, and nothing else) is compatible only with itself.

**Assignment, on every create/update of an agent version, in the store layer** (so every save path goes through it):

```
previous = the version being updated — always the latest one, because
           HistorizedResourceStore.update rejects anything else (checkIfFoundAndLatest)
if request.compatible == true and previous.generation != null:
    new.generation = previous.generation
else:
    new.generation = (previous.generation, or 0) + 1                   // breaking
```

Because only the latest version can be updated, the previous version always carries the agent's highest generation, so this rule keeps generations monotonic without looking at any other version.

- The request's intent arrives as an **explicit parameter** — `PUT /agentstore/agents/{id}?version=N&compatible=true` on REST, a `compatible` argument on the MCP tools — defaulting to `false`. It is never read from the config body.
- Any `compatibilityGeneration` value in an incoming body is **ignored** and overwritten. ZIP import and agent sync therefore always produce a breaking version on the target instance, which is the only safe reading: the target cannot know what the source's generation meant.
- Generations only ever go up, so "same generation at both ends" implies "no breaking version in between". No range scan is needed at runtime.

**Where it is stored — decided: `AgentConfiguration.compatibilityGeneration`**, a field the store owns: written by the store, ignored on input, stripped from export and from agent-sync diffs. The deployed `Agent` is built from the configuration, so it exposes the value as `IAgent.getCompatibilityGeneration()` without another read.

**First opt-in on an existing agent.** A version saved as compatible on top of a legacy version (no generation) gets a fresh generation, because the legacy version has none to share. Conversations already running on that legacy version therefore stay pinned — consistent with guarantee 1. From the next compatible save on, the chain links. The Manager says so in the save dialog (§5.1).

### 4.2 Per-turn resolution

One method resolves the agent for an existing conversation, and every path that runs or inspects a conversation on its agent uses it — `say`, `sayStreaming`, undo, redo, rerun, and `applyAgentMemoryConfig`. The duplicated block in `say()` / `sayStreaming()` is folded into it.

```
resolveAgentFor(environment, memory):
    if memory.state == AWAITING_HUMAN:          // guarantee 6; say() already rejects this state,
        return getAgent(env, id, memory.version) // the check documents the rule for other callers
    g = memory.compatibilityGeneration
    if g == null:
        return getAgent(env, id, memory.version) // pinned — today's behaviour, byte for byte
    candidate = highest READY version on this node with generation == g
    if candidate == null:
        return getAgent(env, id, memory.version) // today's path: deploys the version on demand if this node lacks it
    if candidate.version != memory.version:
        switchVersion(memory, candidate)          // §4.3
    return candidate
```

**The stored version is "the version that ran last", not a hard pin** — for conversations with a generation. This matters for three reasons:

- **Cluster rollout.** Other nodes pick up a deployment in the 10 s sweep. A conversation that moved to v6 on node A and whose next turn lands on node B, which has not deployed v6 yet, runs on B's highest compatible version (v5) instead of stalling while B deploys v6 on demand (`ConversationService.getAgent` deploys a missing version inline). Pinned conversations keep today's on-demand deploy.
- **Rollback.** Undeploying a faulty v6 sends its conversations back to v5 automatically, if v5 is still deployed.
- **Freeing old versions.** v5 can be undeployed as soon as v6 is ready; its conversations move on their next turn (§4.4).

**This makes compatibility bidirectional by definition.** "Compatible" means *either version can continue a conversation the other one started*. A new version that adds a property it then relies on must tolerate its absence. The docs must say this in so many words.

Resolution runs where the turn binds its memory and agent today, so the conversation coordinator's per-conversation serialization covers it: a version never changes mid-turn.

### 4.3 Switching a conversation's version

`switchVersion` does four things:

1. Sets the new version on the memory (`agentVersion` stops being `final`). Both conversation stores already persist `agentVersion` on every save (Postgres `UPDATE … SET AGENT_VERSION = ?`, Mongo `replaceOne`), so the active-conversation queries stay correct without store changes.
2. Updates the agent URI in the conversation descriptor.
3. Stores a small marker on the step the new version runs, `agent:switch = {from, to}` (`MemoryKeys.AGENT_VERSION_CHANGE`), so the debugger and the conversation view can show where the version changed. Not `agent:version…`: step lookups match keys by prefix, so it would shadow the per-step key below.
4. Increments `eddi_conversation_agent_version_switch_total{agentId}` and logs once at INFO with conversation id, agent id, from and to.

In addition, **every step records the agent version that produced it** (step data `agent:version`, `MemoryKeys.AGENT_VERSION` — data rather than a new step field, so no store or snapshot schema changes), so "which version gave this answer?" has an answer even with the audit ledger switched off. The audit ledger and `{conversationInfo.agentVersion}` already read the memory's version per turn, so they become correct automatically.

`compatibilityGeneration` is also stored on the conversation memory — written at creation and on every switch — so resolution never has to read an undeployed version's config to find out which chain the conversation is on. Conversations that exist before this feature have none and stay pinned.

### 4.4 Undeploy

Today the 409 and `endAllActiveConversations` count every active conversation on the version being undeployed. After this change, many of those just have not had a turn since a compatible successor was deployed. Ending them would be wrong.

New rule, for both the 409 and `endAll`: a conversation on version *v* counts only if **no other version with its generation is deployed** in that environment. "Deployed" is read from the deployment records (cluster-wide), not from one node's registry.

- Pinned conversations: nothing else shares their generation, so they always count — unchanged (guarantee 5).
- `undeployThisAndAllPreviousAgentVersions` follows the same rule per version.
- Paused (HITL) conversations keep their existing exclusion.

### 4.5 Breaking changes and existing conversations

No new engine policy is needed. What happens to conversations on the older generation is already expressible:

- **Keep them:** leave the old version deployed; it serves them until they end.
- **End them:** `undeploy …?endAllActiveConversations=true`.

The one addition is a **reason**: conversations ended this way carry `endedBy: system:agent-version-retired` so clients can say "this assistant was updated" rather than a bare "conversation ended". Managed conversations and the `/v1` bridge already start a fresh conversation when theirs has ended.

## 5. Surfaces

### 5.1 Manager

- **Save dialogs** — the agent editor's save and the cascade dialog ([`update-usage-dialog.tsx`](../ui/manager/src/components/editors/update-usage-dialog.tsx)), which is where most breaking edits actually happen (behavior, property, workflow changes cascade into a new agent version). A checkbox, **unticked by default**: *"Compatible with the previous version — running conversations may switch to it"*, with a one-line explanation of what "compatible" requires and a link to the docs. When the previous version has no generation, the dialog says that conversations started before this save will stay on their version.
- **Version list** — mark each version as *compatible with the previous* or *breaking*, so the chain is visible.
- **Deploy dialog** — an impact preview: *"12 active conversations on v5 will continue on v6"* / *"4 active conversations on v3 will stay on v3 (breaking change)"*, and for the latter the choice to keep or end them (§4.5). Needs a small read endpoint (§5.2).
- **Conversation view / debugger** — show the per-step version and the switch marker.

### 5.2 REST

- `compatible` query parameter on agent create/update (default `false`).
- `compatibilityGeneration` in the agent read response (read-only).
- `GET /administration/{env}/deploymentimpact/{agentId}?version=N` — counts of active conversations per currently deployed version, split into *will follow* and *will stay*. Read-only.

### 5.3 MCP

- `compatible` argument (default `false`) on `apply_agent_changes`, the only MCP tool that creates an agent version from an existing one. (`update_agent` only patches the name and description, and `update_resource` writes the resource without touching the agent; `setup_agent` and `create_api_agent` create version 1.) The tool description explains the choice, so an LLM operator makes it deliberately rather than by default.

## 6. Interactions with Other Features

| Feature | Behaviour |
|---|---|
| **HITL** | A paused conversation resumes on its pause version (guarantee 6). The first turn after the resume follows. A pending approval is never re-evaluated against a newer version's gates. |
| **Group conversations** | Members' private conversations go through `say()`, so they follow like any other conversation. The group itself already resolves members with `getLatestReadyAgent`. |
| **Managed conversations, `/v1`** | Follow automatically. On an ended conversation both already start a new one ([`RestAgentManagement`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java) replaces the user mapping; [`OpenAiConversationBridge`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java) drops the stale mapping and retries once). |
| **Slack channels** | Follow automatically. **Ended conversations are a dead end today** — see §6.1; fixed in Phase 0. |
| **Schedules** | `conversationStrategy=new` starts a fresh conversation per fire on the latest version — unaffected. `persistent` follows compatible versions like any conversation, which is the point for heartbeats. **An ended persistent conversation is a dead end today** — see §6.2; fixed in Phase 0. A Dream schedule's `agentVersion` is an explicit operator pin (`0` already means "current version") and is left as is. |
| **Undo / redo / rerun** | Use `resolveAgentFor`. Undoing past a switch does not move the version back — versions only follow deployment, not history. |
| **Security fixes** | A pinned conversation keeps its old config, including old HITL gates and tool whitelists. The docs must tell authors: never mark a security fix as breaking unless the old version is undeployed with it. |
| **`autoDeploy` / restarts** | Unaffected. Resolution only reads what is deployed. |

### 6.1 Slack: an ended conversation strands its thread (existing gap)

[`SlackEventHandler.getOrCreateConversation`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java) returns the mapped conversation for a `channel:slack:<channel>:<agent>:<thread>` intent **whatever its state**. Once that conversation is ENDED, every further message in the thread hits `ConversationEndedException` and the thread is unusable for good. This already happens today — the idle sweep in `AgentDeploymentManagement` ends conversations, as does `undeploy …?endAllActiveConversations=true` — and the "end them" option for breaking changes (§4.5) would make it routine.

**Decision:** on `ConversationEndedException`, the Slack path replaces the mapping and starts a fresh conversation in the same thread, **once per incoming message**, then sends the message to it — the same pattern `OpenAiConversationBridge` already uses.

- The mapping is replaced only if it **still points at the ended conversation** (compare-and-replace). Two messages racing on one ended thread must end up on one new conversation, not two; the existing `ResourceAlreadyExistsException` "winner" handling in `getOrCreateConversation` covers the create side.
- When the end reason is `system:agent-version-retired` (§4.5), the bot posts one short line in the thread first — e.g. *"I've been updated, so I'm starting a fresh conversation here."* For any other end reason it continues silently; the old conversation's messages stay visible in Slack, so nothing is lost for the user.
- **Not** on `AgentNotReadyException`. That is transient (a deployment in progress, a node that has not swept yet) and must never cost a thread its conversation.
- Long-term properties are per user, so what the agent remembers about the person carries over; only the conversation-scoped state is fresh, which is what an ended conversation means.

### 6.2 Persistent schedules: an ended conversation dead-letters the schedule (existing gap)

[`ScheduleFireExecutor.resolveOrCreatePersistent`](../src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java) treats the stored conversation as valid if it can be read and the owner matches. It does not look at the state, so an ENDED conversation is reused, `say` throws `ConversationEndedException`, the broad catch records FAILED, and the retry/backoff machinery eventually dead-letters the schedule. A heartbeat stops for good because of an idle sweep or an undeploy.

**Decision:** `resolveOrCreatePersistent` treats an ENDED conversation like an unreadable one — it creates a new conversation and records it with the existing single-field `setPersistentConversationId` write. The fire then proceeds normally and is recorded COMPLETED. A log line at INFO names the old and new conversation ids. Only ENDED counts; `AWAITING_HUMAN` keeps its existing SKIPPED handling, and a busy conversation is untouched.

## 7. Risks

- **Blast radius of a wrong "compatible".** A compatible deploy changes every active conversation on the chain at its next turn, not only new ones. Mitigations: breaking by default; the deploy dialog's impact preview; the automatic fallback when the new version is undeployed; the docs' guidance to try a compatible change in `test` first.
- **Bidirectional compatibility is easy to get wrong** during a cluster rollout (§4.2). The docs must spell out what "compatible" means with concrete examples:
  - **Breaking:** renamed or removed properties; removed actions (rules matching `lastStep` stop firing); removed or reordered workflow steps that a flow depends on; changed output or quick-reply contracts that a client relies on; a new version that *requires* state older conversations do not have.
  - **Compatible:** prompt wording, a different model, new tools, new rules that do not depend on new state, output text changes.
- **Resolution cost.** A scan of the node's registry per turn — the same map `getLatestReadyAgent` already scans. Negligible.

## 8. Implementation Plan

Each phase is one PR and leaves `main` releasable. Phase 1 alone changes no behaviour.

**Phase 0 — ended-conversation recovery (a bug fix that stands on its own)**
- Slack (§6.1) and persistent schedules (§6.2) replace an ENDED conversation instead of failing forever. Worth shipping first: it fixes stranded threads and dead-lettered heartbeats that already occur today, and Phase 3's `system:agent-version-retired` depends on it to be usable.
- Tests: Slack — an ended mapped conversation is replaced and the message is delivered to the new one; two concurrent messages on one ended thread converge on one new conversation; `AgentNotReadyException` does **not** replace the mapping; the notice is posted only for the version-retired reason. Schedules — an ended persistent conversation is replaced and the fire completes; `AWAITING_HUMAN` stays SKIPPED; the owner-mismatch path is unchanged. Mutation-check each by reverting the fix.

**Phase 1 — generation, stored and exposed (no behaviour change)**
- Generation field + assignment in the agent store; `compatible` parameter on REST create/update; ignored on import and sync; stripped from export and sync diffs.
- `IAgent.getCompatibilityGeneration()` populated at deploy.
- Tests: assignment table (compatible/breaking × previous with/without generation × body carrying a forged value); import and sync always breaking; generations monotonic.

**Phase 2 — per-turn resolution and switching**
- `resolveAgentFor`, used by every caller in §4.2; `agentVersion` mutable; `compatibilityGeneration` on the memory; `switchVersion`; per-step version; metric and log.
- Tests: pinned conversation resolves to exactly its version and fails as today when it is undeployed (guarantee 2, mutation-checked); follows a compatible version; does not follow a breaking one; falls back when the newer version is not on this node; falls back on undeploy of the newer version; `AWAITING_HUMAN` never switches; step and descriptor record the switch; audit entry carries the new version.

**Phase 3 — undeploy and deployment impact**
- Undeploy counting rule (§4.4) for the 409, `endAll` and the all-previous-versions loop; `system:agent-version-retired` reason; the impact endpoint.
- Tests: pinned conversations still block undeploy (guarantee 5); followable conversations do not block and are not ended; HITL exclusion unchanged.

**Phase 4 — MCP**
- `compatible` on `apply_agent_changes`; description.

**Phase 5 — Manager**
- Save/cascade checkbox, version-list markers, deploy impact dialog, per-step version in the conversation view and debugger; i18n for all strings.

**Phase 6 — Docs**
- New section in [`docs/deployment-management-of-agents.md`](../docs/deployment-management-of-agents.md): the model, the compatible/breaking examples, the bidirectional rule, the security-fix rule; cross-link from [`docs/hitl.md`](../docs/hitl.md) and [`docs/architecture.md`](../docs/architecture.md).

## 9. Possible Follow-Ups (not planned)

- **Diff hint in the Manager** — pre-flag a save as *likely breaking* when properties, actions or workflow steps were removed or renamed. A hint only; the author still decides.
- **State migration across a breaking change** — a version declares how to carry a conversation over (e.g. property renames), turning some breaking changes into compatible ones.
- **Per-agent UI preference** for the checkbox's default, kept in the Manager only, never in the config.

## 10. Decisions and Remaining Checks

**Decided (2026-09-29)**

1. **The generation lives in `AgentConfiguration`**, owned by the store (§4.1).
2. **Channels and schedules** — Slack and persistent schedules recover from an ENDED conversation by starting a fresh one (§6.1, §6.2, Phase 0). `new`-strategy schedules and Dream schedules need no change.
3. **Editing an older version is impossible** — `HistorizedResourceStore.update` only accepts the latest version, so "previous" is always the latest and carries the highest generation (§4.1).

**Checked in Phase 1:** every path that creates an agent version — REST, MCP (`setup_agent`, `create_api_agent`, `apply_agent_changes`), ZIP import (`RestImportService`), agent sync (`UpgradeExecutor`) — goes through `IRestAgentStore.createAgent` / `updateAgent` and from there the agent store, so the assignment rule cannot be bypassed.

**To check in Phase 2:** how a group member's private conversation behaves when it has ended (`MemberTurnExecutor` reuses it by id), and whether it needs the same recovery as §6.1.

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

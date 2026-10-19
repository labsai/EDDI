## fix(runtime): refuse a conversation start at capacity before any read (2026-10-19)

**Repo:** EDDI · **Branch:** `feat/turn-admission-hardening` (PR #1085)

### What changed and why

A profile of a 5,000-user burst against a node held to 2 CPUs (JFR, 2026-10-19) found that turns got
only about 17 % of the CPU. Starting conversations took 19 %, and the HTTP event loop took 27 %. Most of
those starts were refused for capacity, and each client came back after `Retry-After`. #1085 already moved
the capacity check before any read for `say`, `rerun` and streaming. A start, though, still ran the
agent access check, resolved the user, read the GDPR restriction from `usermemories` and looked up the
agent before `admitTurn` refused it.

- `RestAgentEngine.startConversationWithContext` now calls `refuseIfAtCapacity` first. A refused start
  answers the same 503 `capacity_exceeded` as before, now with no database read. `admitTurn` stays the
  authoritative check.
- `RestAgentEngineCapacityPrecheckTest` pins both cases: refused before the access guard and before
  `startConversation`, and going ahead when there is capacity. Removing the call fails both tests.
- [`docs/metrics.md`](../metrics.md) names the start path among the pre-checked entry points.

### Design decisions

- The internal starts (group members, schedules, sub-agents, A2A) do not pass through this method and are
  unchanged. They reach `admitTurn` as before.

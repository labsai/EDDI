## docs(planning): conversation storage layout design, steps as records (2026-10-17)

**Repo:** EDDI · **Branch:** `docs/conversation-storage-design`

### What changed and why

[`planning/memory-architecture-plan.md`](../../planning/memory-architecture-plan.md) gains **§11, Conversation
Storage Layout (Phase G)**. This is the long-term design the throughput plan's WS6/WS7 asked for. Today
one conversation is one document (MongoDB) or one JSONB value (PostgreSQL), loaded in full twice per
turn and, on PostgreSQL, rewritten in full on every append.

Load tests on the WS2 build measured the cost over 200-turn conversations:
- turns slow down 1.6–1.9× on MongoDB and 2.2–2.5× on PostgreSQL;
- PostgreSQL runs at 61–83 % of MongoDB's throughput;
- 62 % of PostgreSQL's database time goes to the append;
- PostgreSQL stores 256 kB of live data in 28 MB.

The design:
- **Layout:** a small header (CAS-updated every turn, with new `stepCount` and `lastInteraction`
  fields) and one record per step (MongoDB `conversationsteps`, keyed `{c, i}`; PostgreSQL
  `conversation_steps` with primary key `(conversation_id, step_index)`).
- **Reads:** a turn loads the header and a window derived from the agent's existing
  history/summary settings; older steps are fetched lazily by page, so every consumer keeps its
  semantics and absolute indexes.
- **Writes:** an append writes only new records, in one transaction on PostgreSQL and through a
  unique-key claim on MongoDB (no transactions needed). Undo, redo, rerun and HITL commits become
  narrow updates instead of full rewrites.
- **Data and rollout:** full history stays stored and readable through every surface (AGENTS.md
  §4.7). Migration is online behind `eddi.conversation.storage.layout`, with conversion on first
  write, a background sweep and its reverse for downgrades. Header digests later replace the
  commonest all-history scans.

Also:
- Phase G is added to §7 and its targets to §10.
- §3.4.1 now says that compaction changes what the LLM is given, not what is stored. It used to say
  the summary "replaces the raw history in `IConversationMemory`", which contradicted §4.7.

### Next

G1 (header counters) is small and independent. The rest waits for WS3a (one full load per turn).

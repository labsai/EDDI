## Platform Operator explains 503 capacity_exceeded and node sizing (2026-10-17)

**Repo:** EDDI · **Branch:** `feat/operator-capacity-limits`

The 2026-10-17 throughput work made `eddi.conversation.max-in-flight-turns` (heap-derived with `auto`) the node's turn ceiling, but the Platform Operator knew nothing about it: asked why a turn came back 503 `capacity_exceeded`, or how big a node should be, it had no text and no tool to answer from.

**What changed**

- `ui/manager/src/lib/operator/system-prompt.ts` — a new "Turn capacity" section in the default body, in both scopes. It tells the three backend sources of `capacity_exceeded` apart by their `message` (the in-flight limit, `eddi.coordinator.max-active-conversations` for a new conversation, a draining node), states that the refusal is retryable backpressure with `Retry-After`, gives the `auto` formula as `InFlightTurnLimit` computes it ((max heap − 256 MB) ÷ `in-flight-turn-memory-mb`, floor 32), the 10–20 % headroom advice from the [configuration reference](../configuration-reference.md), the meters and alert (`eddi_conversation_max_in_flight_turns`, `eddi_turn_rejected_total{reason="in_flight_limit"}`, `EddiTurnCapacityReached`), and the remedies in order: turn latency, replicas ([clustering](../clustering.md)), heap, with the database pool kept in step.
- Two lines are added only when their tool is granted: what the coordinator status read shows (`activeConversations`, `queueDepths`, not the in-flight limit) and what the log read shows: a refused say/rerun is a WARN `Turn rejected for conversation …` carrying the refusal message, a refused new conversation under the coordinator cap is a WARN, and only a conversation start refused by the in-flight limit stays at DEBUG.
- `operator-revision.json` bumped to 18, so existing operators are offered the upgrade.
- Tests in `system-prompt.test.ts`.

**Decisions**

- **No allow-list change.** The numbers live at `/q/metrics`, which is not in EDDI's OpenAPI spec, so it cannot become a generated tool; the prompt says the operator cannot read them and names what the admin should look at. Exposing the limit and the in-flight count on `GET /administration/coordinator/status` would be the way to let the operator see them — a backend change, left as a follow-up.
- The per-conversation queue cap, the bounded MongoDB pool wait and the pool-wait meters (`feat/turn-admission-hardening`, PR #1085) are not described: that branch has not merged. They need a follow-up revision bump once it lands.
- Docs named in the prompt are top-level pages only: the image copies `docs/*.md`, so the monitoring guide is not served to the operator.

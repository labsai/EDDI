## 📝 docs(planning): Agent Health plan (2026-11-10)

**Repo:** EDDI (`feat/agent-health`)

### What changed and why

[`planning/agent-health-plan.md`](../../planning/agent-health-plan.md) proposes **Agent Health**: a per-agent
status with reasons built from turn facts, conversation outcomes, ratings, cost and reliability, plus business
outcome events from other systems matched by the agent's own references (order id, ticket id). It surveys how
Intercom Fin, Zendesk, Agentforce, Langfuse, Gainsight, Datadog SLOs and Apdex measure this, lists what EDDI
already records, and phases the work H0–H7.

The plan was revised twice before implementation: a self-review against the code and an independent review.
Both are recorded in its §0 with the evidence for each change.

### Design decisions

- **Status with reasons, not a single score** — a weighted index exists only within a preset.
- **Outcomes are evidence-labelled** — confirmed, agent-closed and silence-assumed resolutions are always
  shown split.
- **Health is derived and not billing-grade** — it never changes conversations; quotas and cost ceilings stay
  the authoritative counters.
- The eight product decisions settled with the maintainer are in the plan's §9.

## docs(planning): human takeover / handoff design for review (2026-10-15)

**Repo:** EDDI (`docs/planning-human-takeover`; planning document only, no production code)

### What changed and why

WS7 of the competitor-research roadmap needs a maintainer-approved design before any code: a live 1:1 conversation handed to a person, who talks to the user while the pipeline is off and then hands it back. [`planning/human-takeover-design.md`](../../planning/human-takeover-design.md) is that design, written against `origin/main` @ `aa811c838`.

- **Inventory of every branch on `ConversationState`.** Engine, both conversation stores, cluster leases and fencing (#956), idempotent turns (#1000), the idle sweep and undeploy (#990), HITL, `/v1`, Slack, A2A, MCP, group members, schedules, Chat UI and Manager. Each entry gives the location, what it does today and what the new state needs. Allow-list sites are safe by default. The deny-list sites are listed as must-fix: `NON_APPENDABLE_STATES` in both stores, undo/redo, the admin state reset, the per-agent idle sweep, `TurnIdempotencyService.isReplayable`, and the A2A outcome `switch`, whose `default -> completed` would report an empty finished task.
- **Recommendations:** a new `HUMAN_CONTROL` state rather than reusing `AWAITING_HUMAN`; the reserved action `HANDOFF_TO_HUMAN`, applied at the end of the turn; an opt-in `request_human_agent` tool that is never selected by an empty built-in whitelist; operator endpoints under `/agents/{conversationId}/takeover`. Takeover writes run as coordinator tasks under the conversation lease, which is never held for the length of a takeover. One step per message, with the operator's identity kept out of user-visible output. `operator.*` audit entries with the actor set server-side and message text stored only as a digest. Operator turns go into LLM history annotated as a human's. A dedicated role that never admits the conversation owner.
- **Separable first PR:** a property patch alongside a HITL `/resume` decision. It is allow-listed per agent, refuses `longTerm` and `secret` scopes, is audited by digest, and is validated before the resume CAS so a refused patch does not use up the pause.

### Design decisions

- `AWAITING_HUMAN` means "refuse input, then resume the pipeline at a bookmark", and HITL admits the owner. Takeover means "accept input, return to idle", and the owner must be excluded. Reusing the state would take a sub-branch at about 20 sites, and a missed one silently drops customer messages.
- Paths avoid the word "operator", because in the Manager the Operator is the Platform Operator LLM.

### What's next

Maintainer sign-off on the nine questions in §11 of the design, then PR 1 (property patch) and PR 2 (state and API) as laid out in §13.

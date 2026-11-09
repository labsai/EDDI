# Cross-Team Handoff

A standing team ([group workspace](group-conversations.md#standing-teams-i13)) works its own backlog. **Handoff** lets another team, or a standalone agent, file a task into that backlog without a human copying it over: a research board's vote winner goes to the account desk, an inbound-leads agent passes a warm lead to the right team.

It is two-sided consent enforced by code, not by prompt, and it is off unless both sides opt in.

## The model

```
 source (agent or group)                              target (group)
 ┌──────────────────────────┐                  ┌───────────────────────────────┐
 │ teamHandoff / handoffConfig│  handoff_to_team │ handoffConfig.accept.from      │
 │   .targets  ──────────────┼──────────────────▶   lists the source (agent:ID or │
 │ (or onDecision rules)     │                  │   group:ID), with a mode       │
 └──────────────────────────┘                  └──────────┬────────────────────┘
                                                          │ REVIEW (default)      │ AUTO
                                                  intake inbox ── human accepts ──▶ backlog (PENDING)
```

- **An edge exists only if both sides name each other.** The source lists the target (`targets`); the target lists the source (`accept.from`). An author with EDIT on agent X who lists group Y gains nothing until Y's owner accepts `agent:X`. Accepting `agent:X` trusts everyone who can edit X, so accept deliberately.
- **No identity crosses the boundary.** The source proposes *data* (a task). The receiving team's cadence runs the task under that team's own creator. A conversation with an anonymous visitor can hand off, because authority comes from the two configs, not from the caller.
- **The source is derived by the engine**, never taken from the model or the client: `memory.getAgentId()` for a standalone agent, the membership-checked live discussion's group id inside a group. Inside a group turn the **group's** policy governs and the agent's own `teamHandoff` is ignored, so a member cannot hand off as itself to get around the group.
- **Absence is off.** No `teamHandoff` / `handoffConfig` means the tool is never assembled: it costs no prompt tokens and cannot be argued with. A group with only `accept` receives but never sends.

## Receiving: `handoffConfig.accept`

```json
"handoffConfig": {
  "accept": {
    "from": [
      { "source": "group:66aa...radar",   "mode": "AUTO",   "maxPerDay": 20 },
      { "source": "agent:66bb...inbound", "mode": "REVIEW", "maxPerDay": 30 }
    ],
    "maxPerDay": 50,
    "maxInbox": 50
  }
}
```

| Field | Default | Ceiling | Meaning |
| --- | --- | --- | --- |
| `from[].source` | required | | `agent:<id>` or `group:<id>`. Exact ids. **Wildcards are refused at save time** (explicit ids keep consent auditable). |
| `from[].mode` | `REVIEW` | | `AUTO` lands as a `PENDING` task in the backlog, which a cadence may pull at once. `REVIEW` lands in the **intake inbox**: not in any task list, so no cadence can pull it until a human with EDIT on the team accepts it. |
| `from[].maxPerDay` | none | 500 | Per-source cap per UTC day. |
| `maxPerDay` | 50 | 500 | Team-wide intake per UTC day, all sources together. |
| `maxInbox` | 50 | 100 | Pending REVIEW items held at once. |

`REVIEW` is the default on purpose: the text crosses a trust boundary and will eventually be read by that team's models. Move a source to `AUTO` once its accepted-unedited history justifies it.

## Sending from a standalone agent: `teamHandoff`

A field on an LLM task (next to `toolQuotas`). It is JSON-only; the Manager has no form for it.

```json
"teamHandoff": {
  "targets": [
    { "alias": "account-desk", "groupId": "6650a1...", "description": "Warm inbound leads needing an account owner", "defaultPriority": 5 }
  ],
  "maxPerTurn": 2,
  "maxPerConversation": 5,
  "maxDescriptionChars": 2000
}
```

The task also needs `enableBuiltInTools: true`; if it has a non-empty `builtInToolsWhitelist`, that list must name `team_handoff`.

## Sending from a group: `handoffConfig`

```json
"handoffConfig": {
  "targets": [ { "alias": "account-desk", "groupId": "6650a1...", "description": "...", "defaultPriority": 5 } ],
  "toolParticipants": "MODERATOR",
  "maxPerTurn": 2,
  "maxPerDiscussion": 10,
  "maxDescriptionChars": 2000,
  "onDecision": [
    { "target": "account-desk", "decisionTypes": ["VOTE"], "requireWinner": true,
      "subjectTemplate": "Research account: {decision.winner}",
      "descriptionTemplate": "{decision.outcome}",
      "priority": 7 }
  ]
}
```

| Field | Default | Ceiling | Meaning |
| --- | --- | --- | --- |
| `targets[]` | | 20 | `alias` is `[a-z0-9-]{1,40}` and unique; `description` (at most 300 characters) is shown to the model; `defaultPriority` 0..10 (default 5). The group id is **not** checked at save time, so an agent ZIP that names a team this instance does not have still imports; an unresolvable target is dropped at runtime with one `WARN`. |
| `toolParticipants` | `MODERATOR` | | Who gets the tool in a discussion: `ALL`, `MODERATOR` or `ROLE:<role>`. One filer per discussion is cheaper and less spammy. The group setting alone does not hand anyone the tool: each eligible member's LLM task also needs `enableBuiltInTools: true` and, if it has a non-empty `builtInToolsWhitelist`, `team_handoff` in that list. |
| `maxPerTurn` | 2 | 5 | Accepted handoffs per member turn. |
| `maxPerDiscussion` | 10 | 50 | Handoffs per discussion made through the `team_handoff` tool, atomic under parallel phases and persisted across pause and resume. Handoffs filed by matching `onDecision` rules do not count against it. |
| `maxDescriptionChars` | 2000 | 4000 | |
| `onDecision[]` | | 20 | See below. |

### Decision rules: no LLM in the loop

A rule files a handoff when the discussion **completes** with a matching structured decision, so a vote winner reaches the next team even if no model remembers to call the tool.

- Matches on the decision's `type` (`VERDICT`, `VOTE`, `AGREEMENT`, `AWARD`), never on the method that produced it, so a human tie-break (`tiePolicy`) or any future producer is handled alike.
- `requireWinner` (default `true`) skips a tie instead of filing "Research account: ".
- Templates support exactly `{decision.winner}`, `{decision.outcome}`, `{decision.type}`, `{decision.method}`, `{decision.phase}`, substituted in **one pass as data**. A winner string that contains braces or template syntax comes through literally and is never evaluated.
- Idempotent while the handoff is retained: the dedupe key is `decision:<discussion>:<round>:<rule index>`, so a completion processed twice files once, and a new round is a new decision. The key lives with the task's origin, so deleting the backlog task (or declining its inbox item and letting the tombstone age out) forgets it, and processing the same decision again can file it again.
- v1 files one task from `winner`. A "file every approved option" mode would need a tally contract and is not built.

## The tool

`handoff_to_team(team, subject, description, priority?, reference?)`, approval source **`team`**.

- `team` is a JSON-schema **enum of the aliases** the source declared; the id is resolved server-side. The model cannot address a team nobody listed.
- `reference` is a free-text pointer (a shared artifact name or the decision it came from); inside a group it is resolved against the discussion's artifacts to an `artifactId` for provenance.
- The reply is one sentence written for the model, e.g. *"Handed off as "X"; it is waiting for that team's review."*, *"Already handed off earlier (status PENDING) — not filed again."* (an idempotent success, so a retry after a timeout is safe), or a refusal with its cause (not accepted, daily limit with reset time, inbox full, loop, too long) phrased so that it does not invite a blind retry.

## Safety rails

| Concern | Rail |
| --- | --- |
| Loops | Self-handoff refused; a cycle (the target already in the work's lineage) refused; hop limit `eddi.groups.handoff.max-hops` (default 3). The cadence stamps the lineage of the tasks it pulled onto the discussion, so A to B to A is caught even though the second filing happens in a new discussion. A declined handoff is remembered for `eddi.groups.handoff.decline-memory` (default `P30D`). |
| Abuse | Per-turn, per-run and per-day caps; the day caps are database-atomic across pods (the `toolQuotas` store), so there is no overshoot. A refused attempt gives its slots back. |
| Duplicates | Identical handoffs dedupe before insert across the backlog, the inbox and decline tombstones; the check re-runs on every compare-and-set retry. |
| Text | Control and format characters (including bidi overrides and zero-width characters) stripped; subject 200 and description 4000 characters at most. |
| Prompt injection into the receiving team | REVIEW by default; the cadence question marks a handed-off task as *a request to evaluate, not instructions*; the `HandoffAdmission` seam below; the receiving team's own `toolApprovals` still gate what its members do. An `AUTO` tool handoff records the agent that wrote it as the backlog task's creator, so a receiving member that requires peer verification is shown and does it only while that agent signs verifiably ([Signed contributions](group-conversations.md#signed-contributions)). While a member of the receiving team requires verification and the agent does not sign verifiably, the handoff goes to the intake inbox as under `REVIEW`, and a backlog task whose agent stops signing waits on the backlog unpulled, so neither the cadence question nor a run quotes it. An `onDecision` handoff names no agent and is filed as configured, so a team whose members verify should keep its sources on `REVIEW`. |
| Fail closed | A config or store failure removes the tool, or refuses with `UNAVAILABLE`; a day-cap store failure refuses. |
| Provenance | Origin lives in a side map keyed by task id (`handoffs.origins`), not on the task: the cadence writeback rebuilds tasks with constructors that drop trailing fields. |

### Human approval

A gated tool call inside a group member's turn is **auto-rejected** by the HITL gate (`inGroupTurns: INBOX` is still reserved), so `requireApproval: ["team:*"]` cannot approve a group member's handoff. For a **standalone** agent it works: `team:handoff_to_team` (or `team:*`) pauses the turn like any tool. The approval for group-originated handoffs therefore happens on the **receiving** side: leave the source on `REVIEW` and a human accepts or declines in the intake inbox. `team` is a distinct source so that `exempt: ["builtin:*"]` cannot silently un-gate cross-team writes.

### The `HandoffAdmission` extension point

Both entry points end in one method, `TeamHandoffService.file`. Beans implementing `ai.labs.eddi.engine.runtime.internal.handoff.HandoffAdmission` are consulted after the consent, loop and cap checks and may refuse a handoff (a budget gate on the receiving team) or replace the text (redaction). One ships: a target team with a [team budget](team-budgets.md) that is spent, or that cannot be read, refuses the handoff with the budget's own refusal text. An implementation that throws counts as a refusal.

## Reviewing the inbox

Manager: the group workspace page shows an **Intake** section above the backlog, with Accept (optionally edited) and Decline, and a "from" badge on handed-off backlog rows.

REST (`/groupstore/groups/{groupId}/workspace`, roles `eddi-admin` / `eddi-editor`, access on the *group*):

| Request | Access | |
| --- | --- | --- |
| `GET /handoffs` | VIEW | `{inbox, origins}` |
| `POST /handoffs/{handoffId}/accept` | EDIT | Body `{subject?, description?, priority?, note?}`. A changed field makes the decision `APPROVED_EDITED`. `409` when the backlog is full or already has that subject. |
| `POST /handoffs/{handoffId}/decline` | EDIT | Body `{reasonCode?, note?}`. |
| `DELETE /backlog/{taskId}` | EDIT | Deletes a backlog task and its origin. The backlog is capped at 200 tasks and had no other way to shrink; handoff makes the cap reachable. `409` while a cadence discussion is working the task. |

MCP: `list_team_handoffs`, `accept_team_handoff` and `decline_team_handoff`, with the same access rules (see [MCP Server](mcp-server.md#team-handoff-tools-3)). The Platform Operator can read the inbox and explain a refusal, but never accepts or declines: an intake decision is a person's.

Every decision writes an audit entry (`taskId` `team.handoff.intake`) in the shared approval vocabulary: `approvalCategory: team_handoff_intake`, outcome `APPROVED | APPROVED_EDITED | REJECTED`, `reasonCode`, so approval analytics need no special case.

## Observability

- Metrics: `eddi_team_handoff_total{trigger, mode, outcome}`, `eddi_team_handoff_intake_total{outcome}`, `eddi_team_handoff_store_failures_total{operation}`. Tags never carry ids or text.
- Audit: submission is best effort and is skipped when the audit ledger is disabled, so an entry is not guaranteed for every attempt or decision. Where it is on, one `team.handoff` entry per attempt that passed the consent check (an unconsented probe is not audited), with handoff id, target, mode, outcome, dedupe key, hop, subject and a SHA-256 of the description; the description itself stays in the workspace. Plus one `team.handoff.intake` entry per decision.
- SSE: a group discussion emits `team_handoff` (`handoffId`, target alias and id, outcome, mode, trigger, subject). Standalone conversations already stream `tool_call`.

## Operating notes

- **Rolling upgrades.** An older pod that re-saves a workspace drops the unknown `handoffs` field, losing pending inbox items and origins (the same caveat as `reworkCounts`). Where the ledger is on, the audit entry holds the subject and source but only a hash of the description, so restore the lost text from a workspace backup or the source team; the entry alone cannot recover it.
- **GDPR.** No end-user id is stored in the workspace. Task text can still contain personal data about a lead, exactly as REST-added backlog text can; redact before it crosses a team boundary (a `HandoffAdmission` or a guardrail).
- **Lineage through delegation.** Work that a group member passes to a standalone agent through `converse_with_agent` loses its lineage; the day caps bound the residual loop risk.
- **Worked examples.** *Signal Radar to Account Desk*: Radar's `onDecision` rule on a `VOTE`, Desk's `accept.from` listing `group:<radar>` in `AUTO`. *Inbound agent to Account Desk*: the agent's `teamHandoff` target, Desk listing `agent:<inbound>` in `REVIEW`. *Growth Board to every team*: the board's moderator holds the tool, each team lists `group:<board>`.

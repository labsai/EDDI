# HITL Oversight — Approval Analytics, Sampled Audit and Earned Autonomy

Approval gates have a capacity. A gate that asks about everything gets rubber-stamped; a gate that asks about nothing
is no oversight at all. This page covers the three tools EDDI gives you to put human attention where it matters:

- **Approval analytics** — how the decisions on each kind of pause turn out: approved as is, edited, rejected, why, how
  fast, on which channel.
- **Sampled audit** — a deterministic share of the tool calls the approval gate let through *without* asking is copied,
  redacted, into a review queue for a human to look at afterwards.
- **Earned autonomy** — a gated category whose human decisions show it no longer needs a gate gets a *recommendation*
  to stop asking (sampled instead). A human applies it. When a reviewer flags what a promoted category did, the category
  is **suspended automatically**: its calls need approval again until a human lifts the suspension.

The asymmetry is deliberate: nothing ungates without a human click; re-gating is automatic. This page builds on
[Human-in-the-Loop (HITL)](hitl.md) and [HITL Review & Audit](hitl-review-and-audit.md), whose decision ledger and
approval categories it reads.

## Configuration

Everything lives in the **agent's** `hitlConfig.oversight` — never in an LLM task, so an `llmstore` write can never touch
it, and the self-ungating guard protects it like the rest of `hitlConfig`. Absent, nothing is sampled, nothing is
recommended or suspended; decisions are still counted for analytics.

```json
"hitlConfig": {
  "toolApprovals": { "requireApproval": ["http.post:*"], "exempt": ["http.get:*"] },
  "review": { "reasonCodes": { "FALSE_CLAIM": "States something untrue", "WRONG_RECIPIENT": "Wrong recipient", "OTHER": "Other" } },
  "oversight": {
    "sampledAudit": {
      "rules": [
        { "match": "http.get:/crm/*", "rate": 0.02, "category": "crm.lookups" }
      ],
      "maxSamplesPerCategoryPerDay": 50,
      "reviewWithin": "P3D",
      "includeResult": false
    },
    "autonomyPolicy": {
      "defaults": { "promoteAfter": 30, "minUneditedRate": 0.9, "window": "P30D", "demoteOnReasonCodes": ["FALSE_CLAIM"] },
      "categories": {
        "tool:http.post:*": {},
        "crm.notes": { "promoteAfter": 50, "minUneditedRate": 0.95 }
      }
    }
  }
}
```

### `sampledAudit`

| Field | Default | Rules |
| --- | --- | --- |
| `rules[].match` | required | A tool-approval pattern (same language as `requireApproval`). Matched against the calls the gate **allowed** — exempt or matched by no gating pattern. The most specific matching rule wins (fewest wildcards, then longest). |
| `rules[].rate` | required | 0.0–1.0, the share of matching calls sampled. |
| `rules[].category` | `tool:<match>` | The category samples are filed under (and an autonomy policy can name). Letters, digits and `_ . : / * { } -`, at most 128. |
| `maxSamplesPerCategoryPerDay` | `50` | 1–1000. A per-(agent, category, UTC day) cap, so one busy category cannot crowd out the others' review capacity. |
| `reviewWithin` | `P3D` | PT1H–P30D. A sample still PENDING after it becomes EXPIRED. |
| `includeResult` | `false` | `true` also stores the tool's result, secret-redacted and capped at 4 KB. |

At most 50 rules; a `match` may appear once.

### `autonomyPolicy`

Only the categories named in `categories` are evaluated; `defaults` fills the fields a category leaves out. A category
key is an approval category as [HITL Review & Audit](hitl-review-and-audit.md#configuration) assigns it — `tool:<the
requireApproval pattern that gated the call>`, a rule's `approvalCategory`, or a `sampledAudit` category.

| Field | Default | Rules |
| --- | --- | --- |
| `promoteAfter` | `30` | 5–10000. Decisions in the window before a recommendation. |
| `minUneditedRate` | `0.9` | 0–1. Share of those decisions that were approved **unedited**. An edited approval counts as a decision, not as unedited. |
| `window` | `P30D` | P1D–P365D. |
| `demoteOnReasonCodes` | `[]` | Codes from the agent's reason-code vocabulary (`review.reasonCodes`, else the built-in set; checked at save time). A rejection with one resets the evidence; a flagged sample with one suspends at once. |
| `demoteOnFlaggedSamples` | `1` | 0–100. Flagged samples within the window before an automatic suspension; `0` never suspends on a count. |
| `promotedSampleRate` | `0.10` | 0.01–1. The `sampledAudit` rate a promotion writes for the category — a promoted category is always sampled. |
| `cooldownAfterDemotion` | `P14D` | P0D–P365D. Evidence after a suspension starts counting only after it. |
| `evidenceChannels` | `["MANAGER","REST","SLACK"]` | Channels whose decisions count. `MCP` may be added; `TIMEOUT` and `SYSTEM` are refused — they are decisions nobody made. |

## Approval analytics

Every decided unit (rule-pause answer, tool call, group phase, group task) becomes one row in the `approval_decisions`
store, fed asynchronously by the `HitlDecisionRecorded` event. The audit ledger stays the evidence; the row is a
projection that can always be rebuilt:

- on first start, the last `eddi.hitl.analytics.initial-backfill` (default 90 days) of `hitl.approval` entries are read
  into it once;
- `POST /hitl/analytics/rebuild?from=&to=` (eddi-admin) re-reads a window in the background — idempotent, so it repairs
  a gap left by a lost event without duplicating anything.

Entries written before 6.7 have no category: they get a `legacy:` category (`legacy:tool:<name>`, `legacy:rule:<agentId>`,
`legacy:group:<groupId>`) and are **never** promotion evidence.

`GET /hitl/analytics/summary` groups by `none | category | agent | group | day | week | channel | agentVersion` and returns
per bucket: totals by outcome, the unedited / edit / rejection rates over human decisions, the top reason codes, latency
p50/p90 (approximate, from fixed buckets: 1 s, 5 s, 15 s, 1 m, 5 m, 15 m, 1 h, 4 h, 1 d, 3 d), the share decided by a
timeout, the channel mix, and — for `none`, `category`, `agent`, `day`, `week` — the sampled-audit counts (sampled,
pending, reviewed, flagged, expired, the expired share and the flagged reason codes). A window defaults to the last 30
days and may span at most 365. One summary reads at most 50,000 decision rows (`truncated: true` beyond); results are
cached for 60 seconds.

## Sampled audit

After an allowed call ran, the recorder checks the agent's `sampledAudit` rules. The choice is
`u = HMAC-SHA256(key, agentId|conversationId|stepIndex|callId|toolName)` read as a fraction, sampled when `u < rate`:

- **Deterministic** — the key is derived from the audit signing key (label `eddi-hitl-sampling-v1`), so an auditor
  holding it can show why a call was or was not sampled. Without a signing key a random per-node key is used.
- **Not steerable** — neither the model nor the agent can compute the HMAC or choose the step index.
- **Fair** — uniform across conversations, capped per category per day.

Not sampled: calls without an id, calls that produced no result (refused, rate-limited, quota-refused, failed), calls
answered by an [agent-test](agent-testing.md) mock, group member turns, and everything when
`eddi.hitl.samples.enabled=false`. Writes go to a bounded queue off the turn's thread; a full queue drops the sample
(`eddi_audit_sample_total{outcome="dropped"}`).

**Privacy.** Arguments are stored as an approval card shows them — secret-redacted, capped at 32 KB — plus the SHA-256
of the raw arguments; never raw. The result only with `includeResult`. `userId` is returned to administrators only.

### Reviewing

`GET /hitl/samples` lists the queue (PENDING by due date), `GET /hitl/samples/{id}` returns one sample with the reason
codes it may be flagged with, `GET /hitl/samples/pending-count` counts the backlog. A verdict is one sample at a time:

```http
PATCH /hitl/samples/{id}
{ "verdict": "FLAGGED", "reasonCode": "WRONG_RECIPIENT", "note": "sent to a customer", "expectedVersion": 1 }
```

`FLAGGED` needs a reason code from the agent's vocabulary (400 `UNKNOWN_REASON_CODE`); the reviewer is always the
caller; a sample reviewed, expired or changed since it was read answers 409 `SAMPLE_ALREADY_REVIEWED`. There is no bulk
verdict on purpose — approving many samples in one click is the rubber-stamping the queue exists to prevent. The
**Manager → Oversight** page is the review UI. Expired samples are shown, not hidden: a rising expiry share means the
deployment's oversight capacity is short.

## Earned autonomy

### Recommendations

Every `eddi.hitl.autonomy.maintenance-interval` (default 1 h) the `hitl-autonomy-maintenance` system schedule evaluates
the categories the **deployed** agents' policies name (round-robin, `eddi.hitl.autonomy.max-agents-per-run` per run).
Evidence is the category's decisions since the latest of `now − window`, the last evidence reset and the end of the
cooldown after the last suspension, counting only:

- decisions by a **verified human** (`system:*`, `unknown`, `anonymous` and `unverified:*` deciders never count),
- on an **evidence channel**,
- with outcome `APPROVED`, `APPROVED_EDITED` or `REJECTED`.

When there are at least `promoteAfter`, the unedited share is at least `minUneditedRate`, and no rejection carries a
demote reason code, an OPEN `PROMOTE` recommendation is stored with the evidence and a preview of the change:

- a category that **is** a `requireApproval` pattern is removed from that list;
- a narrower one is added to `exempt` (and a friction rule identical to it is removed);
- removing the last `requireApproval` pattern drops `exempt` and `rules` too — the preview then says *after this the
  agent has no gated tools*;
- the pattern is added to `sampledAudit` at `promotedSampleRate`.

The change always passes `HitlConfigValidation`. A recommendation for a behavior-rule or group category is
**advisory** (`applicable: false`): its text names what to change by hand. A newer agent version makes an open
recommendation STALE; it is recomputed on the next run.

### Applying and dismissing

```http
POST /hitl/autonomy/{agentId}/apply?category=tool:http.post:*
{ "recommendationId": "…", "expectedAgentVersion": 7 }
```

Administrators, or `EDIT` on the agent. Apply re-checks the evidence **now** (409 `EVIDENCE_CHANGED`), requires the
agent to still be at `expectedAgentVersion` (409 `AGENT_CHANGED`), validates the result, and writes a **new agent
version** through the agent store — it does **not** deploy it; deploying is the usual separate step. It writes an
`autonomy.promotion` ledger entry and resets the category's evidence. `POST …/dismiss {recommendationId, note}` dismisses
one; no new recommendation until the evidence count has grown by `promoteAfter`.

### Suspension (automatic demotion)

A category is suspended when a reviewer flags one of its samples with a demote reason code, when flagged samples within
the window reach `demoteOnFlaggedSamples`, or by hand (`POST …/suspend {note}` — reviewers may, it only adds a gate).
A suspension:

- is a **runtime overlay** on the gate, never a configuration change: a call the configuration would allow (exempt or
  unmatched) that matches the suspended pattern is gated with the reason `suspended:<pattern>`, shown on the approval
  card;
- always **waits for a human** — the overlay adds a `WAIT_INDEFINITELY` friction rule for the pattern, and an
  overlapping `AUTO_APPROVE` rule waits instead; a suspension never auto-executes;
- resets the evidence and starts the cooldown;
- opens a `REVERT` recommendation ("make permanent"): applying it writes the gate back into configuration and clears
  the suspension;
- **never expires**. `POST …/lift {note}` (administrators or `EDIT`) lifts it and writes `autonomy.suspension.lifted`.

Each node caches an agent's suspensions for `eddi.hitl.autonomy.suspension-cache-ttl` (default 15 s) and drops its own
cache on a write, so a suspension reaches every pod within that time, plus the turn in progress. If the store cannot be
read, the last known list is used, otherwise no extra gate (logged as an error) — a database blip must not pause every
exempt call of a deployment. During a rolling upgrade, pods without this feature ignore suspensions.

A resumed tool loop in `eddi.hitl.tool.task-approvals.mode=replace` with a task-level `toolApprovals` does not see a
suspension until the next turn.

## REST API

All under the `HITL Oversight` OpenAPI tag; roles `eddi-admin`, `eddi-editor`, `eddi-approver`. Administrators and
approvers see every agent; anyone else must pass an `agentId` they hold `EDIT` on (400 `AGENT_ID_REQUIRED`, 403).

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/hitl/analytics/summary` | `agentId`, `groupId`, `category`, `from`, `to`, `surface`, `pauseType`, `channel`, `agentVersion`, `groupBy` |
| GET | `/hitl/analytics/decisions` | Decision rows, newest first; `index`, `limit` (≤ 100); `userId` for administrators only |
| POST | `/hitl/analytics/rebuild` | eddi-admin; `from`, `to` (default last 90 days); runs in the background |
| GET | `/hitl/samples` | `agentId`, `category`, `status` (PENDING default, OK, FLAGGED, EXPIRED, ALL), `from`, `to`, `index`, `limit` |
| GET | `/hitl/samples/pending-count` | `agentId` |
| GET | `/hitl/samples/{id}` | The sample and its reason codes |
| PATCH | `/hitl/samples/{id}` | A verdict |
| GET | `/hitl/autonomy` | States with their tier: `GATED`, `READY`, `PROMOTED`, `SUSPENDED` |
| GET | `/hitl/autonomy/{agentId}/category?category=` | One category with its evidence evaluated now |
| POST | `/hitl/autonomy/{agentId}/apply?category=` | `{recommendationId, expectedAgentVersion}` |
| POST | `/hitl/autonomy/{agentId}/dismiss?category=` | `{recommendationId, note}` |
| POST | `/hitl/autonomy/{agentId}/suspend?category=` | `{note}` |
| POST | `/hitl/autonomy/{agentId}/lift?category=` | `{note}` |

Categories contain `:` and `/`, so they are always a query parameter. Over MCP the reads are `summarize_approvals`,
`list_autonomy_recommendations`, `get_autonomy_state` and `count_pending_audit_samples` (see
[MCP Server](mcp-server.md#hitl-oversight-tools-4)), and the Platform Operator holds the same four reads. There will
never be an MCP tool for a verdict, apply, dismiss, suspend or lift — an MCP client is often a model, and those actions
are evidence or ungating.

## Security

Neither an agent nor a model can influence its own promotion:

- **Evidence** comes only from verified humans on evidence channels; timeouts, system decisions and legacy rows never
  count.
- **Agent-originated requests are marked.** An httpcall aimed at **this deployment** carries the engine-owned header
  `X-EDDI-Agent-Call: true`, set after the configured headers so no configuration removes or replaces it. "This
  deployment" is the own origin (`eddi.self.base-url`, or the derived listener address), any loopback address, and the
  hosts in `eddi.hitl.oversight.self-hosts` (the public or in-cluster names it is also reached by, `host` or
  `host:port`), **and the origin the turn's caller addressed** — the public hostname a browser used, which is exactly
  where `${caller:token}` is released. Independently of the target, **every request that carries a released
  `${caller:token}`** is marked — an httpcall header, or the `Authorization` of an MCP server (`mcpServers`,
  `mcpcalls`): the token is only ever released to this deployment, so such a request is one by definition. Calls to
  any other host carry no EDDI header. Any non-read request with the header to `/hitl/samples/**`,
  `/hitl/autonomy/**` or `/hitl/analytics/rebuild` answers 403 `AGENT_ORIGINATED` — even with a forwarded
  `${caller:token}`. If agents reach this deployment under a name none of those cover, list it in
  `eddi.hitl.oversight.self-hosts`.
- **Agent-originated HITL decisions are refused the same way.** A marked `POST /agents/{conversationId}/resume` (and
  `/resume/stream`), `POST /groups/{groupId}/conversations/{id}/approve` (and `/approve/stream`) and
  `POST /groups/{groupId}/conversations/{id}/human-input` answer 403 `AGENT_ORIGINATED`. Without that, a
  prompt-injected agent chatting with an approver could resume *another* paused conversation as that approver, with
  `${caller:token}`, and the decision would count as a verified human's evidence toward earned autonomy. (Until this
  release only loopback, `eddi.self.base-url` and the self-hosts list were marked, and no decision endpoint read the
  marker.)
- **The self-ungating guard** refuses an approved tool call that writes to those paths — the oversight API and the
  decision endpoints above — and an agent-document write that changes `hitlConfig.oversight` (an agent could otherwise
  lower its own sample rate or loosen its own policy).
- **The overlay field is transient** (`@JsonIgnore`): configuration can neither pre-set nor clear a suspension.
- **Apply** re-checks the evidence server-side, requires the reviewed agent version, validates, writes through the agent
  store and is audited; it never deploys.
- Never give an agent an EDDI administrator credential (`${vault:…}`) — a tool that calls EDDI through a proxy with one is
  the one path the header cannot mark.

## Audit ledger entries

All `taskType: hitl`, with the acting principal as `actor`:

| `taskId` | Written when |
| --- | --- |
| `hitl.sample.review` | A reviewer gives a verdict: `{sampleId, category, verdict, reasonCode, argsDigest, toolName}` |
| `autonomy.promotion` | A PROMOTE recommendation is applied: `{category, pattern, fromVersion, toVersion, changes, evidence, leavesNoGate}` |
| `autonomy.revert` | A REVERT recommendation is applied (the suspension made permanent) |
| `autonomy.recommendation.dismissed` | A recommendation is dismissed, with the note |
| `autonomy.demotion` | A category is suspended: `{category, pattern, trigger, triggerRef, reasonCode, note}` |
| `autonomy.suspension.lifted` | A suspension is lifted, with the note |

## GDPR

The `hitlOversight` participant: deletes the audit samples of the user's conversations; replaces the user as a sample
reviewer, as a decider in the decision facts and as the actor in the autonomy state with the ledger's keyed pseudonym;
removes the `userId` from the decision facts of their conversations (the rows stay — they hold no content, and keep the
analytics correct). Export includes their samples and decision rows.

## Metrics and properties

Metrics are listed in [Metrics → HITL](metrics.md#hitl-metrics) and charted in the Full Metrics Reference dashboard's
Human-in-the-Loop row. Properties (all optional): `eddi.hitl.analytics.retention` (`P400D`),
`eddi.hitl.analytics.initial-backfill` (`P90D`), `eddi.hitl.samples.enabled` (`true`), `eddi.hitl.samples.retention`
(`P180D`), `eddi.hitl.autonomy.maintenance-interval` (`PT1H`), `eddi.hitl.autonomy.suspension-cache-ttl` (`PT15S`),
`eddi.hitl.autonomy.max-agents-per-run` (`500`), `eddi.hitl.oversight.self-hosts` (empty) — see [Configuration Reference](configuration-reference.md).

## Storage

Three stores, each on MongoDB and PostgreSQL (`approval_decisions`, `audit_samples`, `autonomy_state`), indexed for the
query shapes above. Decision facts are kept `eddi.hitl.analytics.retention`, samples `eddi.hitl.samples.retention`; the
ledger itself is never pruned. `autonomy_state` also holds one engine marker row (`agentId: __system__`) recording that the
first-start rebuild ran.

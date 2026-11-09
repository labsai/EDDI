# Spend-Control Changes

An administrator's AI client — Claude Code, Claude Desktop or any MCP client signed in as an `eddi-admin` — can set
up the deployment's spending controls over [MCP](mcp-server.md#spend-control-tools-5): the
[model price catalog](model-pricing.md) and the [tenant quota](tenant-quotas.md). It does not get to loosen them on
its own. This page explains where the line is and how a loosening change reaches a human.

## In short

- **Stricter applies, looser waits.** A write that can only make spending stricter is applied at once, with the
  MCP caller as actor. A write with *any* loosening part writes nothing: it is stored as a **pending change** that an
  `eddi-admin` approves or rejects in the Manager (*Model Prices* or *Quotas*, **Pending changes**).
- **The approver is the actor.** Approving applies exactly the proposed document, under the approver's name; an
  approval by anyone who is not an `eddi-admin` applies nothing.
- **No tool approves.** There is no MCP tool, and no Platform Operator grant, to approve or reject. The proposer may
  withdraw its own pending change — that applies nothing either.
- **Compare-and-set, reason, audit.** Every write names the catalog `revision` or the quota `fingerprint` it was
  computed against, carries a `reason`, and writes an audit-ledger entry.

## Why

The MCP client of an administrator is often a model, and a prompt injection can steer a model — through a web page
it read, a document it summarized, a tool result. Lowering a price to $0 or raising a quota is raising a budget,
which is exactly what an injected instruction wants. A tightening write is safe to automate: the worst it can do is
refuse spend, and a person notices that. The same reasoning keeps [HITL oversight](hitl-oversight.md) decisions off
MCP and makes [budget proposals](agent-ops-tools.md#budget-proposal) wait for an administrator.

## What counts as stricter

The classification errs towards "loosening": a change it cannot prove stricter goes to a human. A false
"loosening" costs one click; a false "stricter" would let a steered model raise a budget.

### Price catalog

| Change | Stricter (applies) | Loosening (proposed) |
| --- | --- | --- |
| Price of an existing entry | every price — input, output, cache read and write, the long-context tier's — is equal or higher | any price goes down, or a set price is cleared (it would fall back to another value) |
| Long-context threshold | lowered over a tier that costs at least the base price | raised, or lowered over a cheaper tier |
| New entry | the policy in force is `ALLOW` **and** its lowest price of every kind is at least the highest price of every existing entry whose pattern could match the same model | otherwise — in particular **any new price while unpriced calls are refused** (`REFUSE`, `REFUSE_WHEN_BUDGETED`): a price admits calls that are refused today |
| Removed entry | — | always: its models fall back to another entry or become unpriced |
| `unpricedPolicy` | `ALLOW` → `REFUSE_WHEN_BUDGETED` → `REFUSE` | the other direction |
| `asOf`, `source`, `note` | always (they change no price) | — |

Two patterns "could match the same model" unless that is decidable as impossible: two exact names, an exact name and
a pattern, two prefix patterns (`gpt-4o*`, `claude*`) or two suffix patterns. Every other pair is assumed to overlap,
and provider `*` overlaps every provider.

Under strict mode this means adding a price for a new model is a proposal. That is the point of strict mode: a
model without a price is refused, and pricing it is admitting it.

### Tenant quota

`-1` (unlimited) counts as infinite. Lowering a limit or enabling the quota is stricter; raising a limit, setting it to
`-1` or disabling the quota is loosening. A write that does both is proposed as a whole — split it to get the
stricter part applied at once.

## The pending change

A loosening write stores the **whole proposed document** — the catalog or the quota — together with the state it was
computed against. Approving applies exactly that, and only while the stored control is still that state; otherwise
the change becomes `STALE` and nothing is applied (propose it again against the current state). An approver therefore
never applies anything other than what the card showed.

| Status | Meaning |
| --- | --- |
| `PENDING` | waits for an `eddi-admin` |
| `APPLIED` | approved and written, the approver as actor |
| `REJECTED` | an administrator said no |
| `WITHDRAWN` | the proposer took it back |
| `STALE` | approved, but the catalog or quota had changed since the proposal: nothing applied |
| `EXPIRED` | undecided for 7 days: nothing applied |

Limits: at most **20** pending changes at once (a further one is refused with `TOO_MANY_PENDING`); the same proposal
filed twice returns the pending one; decided changes are kept for 90 days (the audit ledger keeps the record for
good). Storage: MongoDB `spendcontrolchanges`, PostgreSQL `spend_control_changes`.

## Approving

In the Manager, **Model Prices** and **Quotas** each show a *Pending changes* panel: who proposed what, why, every
change and — highlighted — the parts that loosen spending, with **Approve** and **Reject**. Only `eddi-admin` sees
and decides them.

Over REST, all `eddi-admin`:

| Method | Path | |
| --- | --- | --- |
| GET | `/administration/spend-control-changes?status=&limit=` | `PENDING` by default; `ALL` or one status; `limit` 1..100 |
| GET | `/administration/spend-control-changes/{id}` | one change with the proposed document |
| POST | `/administration/spend-control-changes/{id}/approve` | `{ "note": "…" }`; 409 `STALE` / `ALREADY_DECIDED` |
| POST | `/administration/spend-control-changes/{id}/reject` | `{ "note": "…" }` |

**An agent cannot approve.** A non-read request an agent's own httpcall makes to these paths is refused with 403
`AGENT_ORIGINATED` (the engine marks every httpcall to this deployment; see
[HITL Oversight → Security](hitl-oversight.md#security)), and an approved tool call writing there is refused by the
self-ungating guard. Never give an agent or an MCP client's model an administrator credential to the REST API — that
is the one path neither guard can see.

There is no Slack or e-mail notification yet: the proposing client is told to tell its user, the proposal is logged
at `WARN` and counted in `eddi.spendcontrol.changes{kind, result}`.

## Audit ledger entries

All `taskType: spendcontrol`, the acting principal as `userId`, the action in `input.action` and in `actions`:

| Action | Written when | Actor |
| --- | --- | --- |
| `SPEND_CONTROL_APPLIED` | a stricter write was applied directly | the MCP caller |
| `SPEND_CONTROL_PROPOSED` | a loosening write was filed | the MCP caller |
| `SPEND_CONTROL_APPROVED` | a proposal was approved and applied | the approver |
| `SPEND_CONTROL_REJECTED` | a proposal was rejected | the administrator |
| `SPEND_CONTROL_WITHDRAWN` | the proposer withdrew it | the proposer |
| `SPEND_CONTROL_STALE` | approved, but nothing applied (the control had changed) | the approver |

`output` carries `kind`, `tenantId` (quota), `reason`, `changes`, `loosening`, `channel` and, for a decision, the
`changeId`, `proposedBy` and the decider's `note`. The catalog keeps its own `updatedBy` (the approver for an approved
change), and the REST `PUT` endpoints are unchanged — an administrator in the Manager still edits prices and quotas
directly.

## The tools

| Tool | Tier | Behaviour |
| --- | --- | --- |
| `read_model_prices` | admin, editor | read (unchanged) |
| `read_tenant_quota` | admin | read: quota, `fingerprint`, usage, remaining budget |
| `update_model_prices` | admin | stricter applies, loosening is proposed |
| `update_tenant_quota` | admin | stricter applies, loosening is proposed |
| `list_spend_control_changes` | admin | read |
| `withdraw_spend_control_change` | admin | the proposer's own pending change; applies nothing |

Input bounds: `reason` 1–1000 characters; at most 50 entries upserted and 50 removed per call; every upserted entry
needs an http(s) `source`; prices and every other catalog limit as in [Model Pricing](model-pricing.md#the-deployment-price-catalog);
quota limits `-1` or ≥ 0, `maxMonthlyCostUsd` at most 1 000 000 000.

Related admin tools that need no approval step, because they spend only under ceilings that already exist: the team
cadence and lesson-consolidation tools (see [MCP Server](mcp-server.md#team-cadence-tools-6)). The read-only
`list_vault_keys` lists secret names and grants, never a value.

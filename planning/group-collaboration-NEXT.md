# Group Collaboration — What To Do Next

> **New session? Read this file, not the whole plan.** This is the single source of truth for
> **status and sequencing**. [`group-collaboration-improvements-plan.md`](group-collaboration-improvements-plan.md)
> holds the **design** for each item and does not repeat status — go there only for the one
> item you are about to build, using the section pointers below.
>
> **Last updated:** 2026-08-08 · **the queue is empty.** Every item in §3 (I17, I14, I8, I6,
> I11, I18, I12, I13, I10) shipped and merged to `main` on 2026-08-08 via PRs #636–#645. The
> §2 pre-feature defects shipped 2026-08-07. This file is now a *record*, not a work list —
> see §3 for what landed and §4 for what deliberately remains.

---

## 1. Where things stand

`GroupConversationService` was a 4,417-line monolith that would have absorbed ~80% of the
planned features. PR #626 decomposed it (Wave R), added the foundations everything else
builds on (F1–F6), and shipped six feature items.

| | Status |
| --- | --- |
| **Wave R** — R1 (`GroupConversationService` → 8 collaborators), R2 (`AgentOrchestrator` → `ToolSourceProvider` SPI), R3 (`ConversationService` → HITL + step runner) | ✅ Done |
| **Wave 0** — F1 `LiveDiscussionRegistry`, F2 speaker-level `ResumePoint`, F3 `DecisionRecord`, F4 entry types + visibility, F5 cost ledger, F6 schema versioning | ✅ Done |
| **Wave 1** — I1 cost ceiling, I2 convergence + early exit, I3 structured verdicts, I4 abstention + minority report | ✅ Done |
| **Wave 2** — I5 agent-filed tasks, I7 recruitment + delegation timeout | ✅ Done |
| **Pre-feature defects** — N1 plain-call pricing, N2/I9 transcript windowing, N3 schema sentinel (§2) | ✅ Done (2026-08-07, `fix/group-pre-feature-defects`) |
| **Wave 2** — I6, I8, I14, I17 | ✅ Done (2026-08-08, PRs #637–#640) |
| **Wave 3** — I10, I11, I12, I13, I18 | ✅ Done (2026-08-08, PRs #641–#645) |
| **Excluded — do not start** — I15 cross-team process DAGs, I16 A2A remote members | ❌ Out of scope |

**The refactor is done. Nothing below is blocked on more refactoring.** Every remaining item
now has a natural home: phase behaviour → `PhaseExecutionEngine`, rendered context →
`GroupContextBuilder`, task waves → `TaskForceEngine`, pause/resume → `GroupHitlCoordinator`,
tool surfaces → a `ToolSourceProvider`.

---

## 2. Do these three first — ✅ ALL DONE (2026-08-07, branch `fix/group-pre-feature-defects`)

> N1, N2 and N3 below all shipped on that branch, each mutation-checked; the §4
> nested-group cost gap was folded into N1 as planned. The section is kept for
> the reasoning record — do not redo any of it. Details in `docs/changelog.md`
> (entries of 2026-08-07). **Next up: §3's queue, starting with I17 / I14 / I8.**

These are not new features. They are **defects in what already shipped**, and later items depend
on them. Do them as one small PR before starting any Wave 2 feature.

None of the three blocks the merge of #626 — N1 and N3 cannot misbehave until something else
changes (see each item) — but all three get *harder* the longer they wait, and N3 stops being
free the moment this ships.

### N1 — Price ordinary model calls *(S — do this first)*

**The bug:** `AUDIT_COST` is written from `cascadeCostUsd + toolCostUsd` only
([`LlmTask.accumulateAuditEvidence`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java)).
A plain model call — no cascade, no priced tool — contributes **$0.00**. So for an ordinary
group `totalCost` stays 0, **I1's `maxCostPerDiscussion` ceiling can never trip**, and
`memberCosts`/`totalCost` are serialized into the public REST payload as if authoritative.
A user reading `$0.00` believes it.

**Why it is small (verified 2026-08-04, not assumed):** the pricing arithmetic and the config
fields already exist — `CascadingModelExecutor.computeCost()` (~line 717) reads
`inputPricePer1M`/`outputPricePer1M` from `CascadeStep`/`ModelCascadeConfig`. And **all three
non-cascade paths already emit token usage** into `responseMetadata`:

| Path | Emits `tokenUsage` at |
| --- | --- |
| agent / tool loop | `ToolLoopRunner` ~:175 |
| legacy chat | `LegacyChatExecutor` ~:123 |
| streaming legacy chat | `StreamingLegacyChatExecutor` ~:566 |

All of them produce the keys `computeCost` already reads — the first two via
`ToolContextBudget.tokenUsageMap` (`TOKEN_USAGE_FIELDS = inputTokens, outputTokens,
totalTokens`), the third building the same shape inline. **So only the price is missing at the
non-cascade level.** No new plumbing, no token-counting work.

**Approach:**
1. Add `inputPricePer1M` / `outputPricePer1M` to the plain LLM task config (same names, same
   nullable semantics — null means "unpriced", cost contribution 0).
2. Extract `computeCost`'s arithmetic into one shared helper and call it from both paths
   (AGENTS.md §4.7 *unification over duplication* — do **not** copy the formula).
3. Keep it **config-driven**: no hardcoded provider price table. Prices are an agent-designer
   concern (Golden Rule 1). A table would be wrong within weeks anyway.

**Guardrails:** unpriced configs must keep contributing exactly 0 (no behaviour change for
anyone not setting prices) — and `accumulateCost` already early-returns on `delta <= 0.0`.

**Tests:** priced non-cascade call accumulates the expected dollar amount; unpriced stays 0;
cascade path unchanged; **a group with a ceiling and priced members actually aborts** (this is
the test that proves I1 works — it cannot pass today).

### N2 — I9 group transcript windowing *(M)*

Plan §`### I9` (line ~363). Not a new collaboration mode — **a live cost bug**. FULL-scope
phases re-feed the entire transcript to every member every turn (~quadratic). Every item in §3
makes transcripts longer, so this compounds until it is fixed.

Reuses the existing single-conversation rolling-summary service (unification rule). Lands in
`GroupContextBuilder`. Read the plan section before starting — the ANONYMOUS label-preservation
and truncation-fallback requirements are easy to miss and both have tests specified.

**Sequencing note:** N1 first, because I9's summarization calls cost money and should be
attributable the moment they exist.

### N3 — F6's migration ladder never runs on legacy documents *(S — do it before this ships)*

Found in the round-5 review of #626. Three related defects in the F6 foundation.

**(a) The version field masquerades as current.** `GroupConversation` (~:44) declares:

```java
private int schemaVersion = CURRENT_SCHEMA_VERSION;   // = 3
```

Every group conversation in production today was written **before** F6 existed — `main` has
*zero* occurrences of `schemaVersion` in that class — so those documents have no such key.
Jackson leaves the initialiser standing, and they load claiming **schema 3**, the current
version, while being version-1-shaped. `prepareForResume` then loops `for (v = 3; v < 3; ...)`
— zero iterations. **The migration ladder never runs on exactly the documents it exists for.**

Verified by deserialising a document with no `schemaVersion` key: it reported `3`.

*Impact today: zero* — `MIGRATIONS` is empty and all three bumps happened inside the unreleased
#626, so no released build ever wrote a versioned document. *Impact at the first non-additive
bump:* a v1-shaped document either skips its transform or receives a `3→4` transform written
for a v3-shaped document.

**Fix:** default the field to a legacy sentinel (`1`) and stamp `CURRENT_SCHEMA_VERSION` at the
single creation point, `GroupConversationService` ~:1692. The initialiser alone cannot
distinguish "absent" from "current" — Jackson runs the no-arg constructor either way — so the
stamp must be at creation, not in the field declaration.

**Do it before #626 ships.** It is free only while no production document carries a version;
afterwards it needs a real data migration to tell v1-shaped from v3-shaped documents apart.

⚠️ `ConversationMemorySnapshot` ~:35 has the identical pattern. It is correct *by coincidence*
(its `CURRENT` is `1`, which is also the floor) and inherits this bug the moment it bumps to 2.
Fix both, or the single-conversation side repeats the group side's mistake.

**(b) Stale Javadoc.** `GroupConversationSchemaMigrations` (~:31-33) asserts
`CURRENT_SCHEMA_VERSION` is `1`, "the first version that has ever existed, so there is nothing
yet to migrate from." It is `3`. Someone adding v4 reads that and does not realise the
identity-default path has already been exercised twice.

**(c) The case that matters is untested.** `GroupConversationSchemaMigrationsTest` covers only
documents that *have* a version. Nothing deserialises a document without the field — which is
every document in production, and is why (a) survived four review rounds. Add that test first,
watch it fail, then fix (a).

---

## 3. The queue after that — ✅ ALL DONE (2026-08-08)

Every row below shipped in the recommended order, one PR per item, merged to `main` on
2026-08-08. The table is kept for the dependency reasoning; details for each item are in
[`docs/changelog.md`](../docs/changelog.md) (entries of 2026-08-07 and 2026-08-08) and the
user-facing behaviour is documented in
[`docs/group-conversations.md`](../docs/group-conversations.md).

| Item | PR |
| --- | --- |
| I17 shared artifacts | #637 |
| I14 voting | #638 |
| I8 retro → group memory | #639 |
| I6 human as group member | #640 |
| I11 NEGOTIATION style | #641 |
| I18 bid-based assignment | #642 |
| I12 facilitator | #643 |
| I13 standing teams | #644 |
| I10 preset templates + integration | #645 |

**Deviations from the plan, recorded:** none of substance — each item followed its design in
[`group-collaboration-improvements-plan.md`](group-collaboration-improvements-plan.md). Three
constraints emerged during implementation and are now enforced in code: `HUMAN_DECIDES` tie-break
remains save-time rejected (it needs its own resume machinery on top of I6), the facilitator's
`ESCALATE_HUMAN` yields to a phase's own approval gate rather than bypassing it, and
`CALL_VOTE` is refused when a `DecisionRecord` already exists.

| # | Item | Size | Plan § | What it unlocks | Depends on |
| --- | --- | --- | --- | --- | --- |
| 1 | **I17** Shared artifacts | M | line ~412 | Agents **co-edit a document** instead of only talking (blackboard-lite). Own collection, not embedded. | F1 ✅ |
| 2 | **I14** Voting | M | line ~404 | Explicit ballots, quorum, weights, tie policy. Independence enforced structurally (PARALLEL + `ContextScope.NONE`), not advised. | F3 ✅ |
| 3 | **I8** Retro → group memory | S–M | line ~355 | Discussions stop evaporating; lessons compound run-over-run. **Substrate for I13.** | F4 ✅ |
| 4 | **I6** Human as group member | M | line ~335 | Humans can currently only *gate* (approve/reject), never **speak**. Makes hybrid teams real. Needs a new `AWAITING_HUMAN_INPUT` state — do **not** overload `AWAITING_APPROVAL`. | R3 ✅ F2 ✅ F6 ✅ |
| 5 | **I11** NEGOTIATION style | M | line ~380 | Agents **trade** — proposals, concessions, a ledger — rather than opine. | I2 ✅ F3 ✅ |
| 6 | **I18** Bid-based assignment | M | line ~426 | Members **bid** for tasks instead of the planner guessing fit/load. | I5 ✅ |
| 7 | **I12** Facilitator | L | line ~388 | Adaptive orchestration via *enumerated* moves only (deterministic governance). **Its moves are the other items** — build after I6/I14. | I2 ✅ I6 I7 ✅ I14 |
| 8 | **I13** Standing Teams | L | line ~396 | **Flagship.** A group stops being an episode and becomes a persistent team: backlog, memory, cadence, metrics. | I1+N1, I5 ✅, I8 |
| 9 | **I10** Preset templates | S–M | line ~372 | Packaging — "research pod", "decision board". **Ships last on purpose**: templates may only reference features that exist. | everything above |

**Why this order:** I17/I14/I8 are independent and unblocked, so they parallelize across PRs.
I6 needs care (new conversation state) but derisks every agent-only weakness — ties, arbitration,
judgment. I12 and I13 are last-but-one and flagship respectively because they are *composition*
of what precedes them; building them early means building them twice.

---

## 4. Other known gaps (not on the critical path)

Recorded so they are not rediscovered. Fix opportunistically, or fold into a related item.

- ~~**`decision_reached` never fires.**~~ ✅ Fixed with I14 (2026-08-08): the phase decision
  block fires it after the dissent round, so the event carries the merged dissents. Covers
  I11's arbitration verdicts through the same firing.
- ~~**Nested-group cost is overwritten, not accumulated.**~~ ✅ Fixed with N1 (2026-08-07):
  attribution is keyed per child discussion (`agentId:childId`), so children sum.
- **`allowAbstention` on a SYNTHESIS phase** yields a `COMPLETED` discussion with no answer.
- **Parallel-phase late entries are lost.** After the batch deadline a member finishing
  milliseconds late has its real entry replaced by `SKIPPED`. **Both obvious fixes were tried
  and rejected** — cancelling makes `get()` throw `CancellationException` (5 entries → 1);
  draining extended a 3s deadline to 8s. Recovering this needs the deadline contract
  renegotiated, not a patch. Failure modes are documented in the code.
- **`getRecruitedAgentIds()` check-and-add is guarded by the caller,** not the model. The right
  fix moves the atomic operation into `GroupConversation` (as `SharedTaskList.addAgentTask`
  already does). Latent today — copy-on-write list, single mutator.
- ~~**`docs/group-conversations.md` drift.**~~ ✅ Fixed 2026-08-08: REST/MCP tables completed,
  `BLOCKED`/`AWAITING_APPROVAL` added, plus new sections for per-phase controls, dissent, and
  the SSE event catalogue.

### Checked and cleared — do not re-investigate

Each of these looks like a bug on a first read. All three were traced to ground in the round-5
review and are sound; the reasoning is recorded so the next reviewer does not spend the time again.

- **`ToolLoopResumer` ~:161 falls through a null verdict into the approved path.** Unreachable:
  all five entry surfaces reject a null top-level verdict first — `RestAgentEngine` ~:366,
  `RestGroupConversation` ~:413 and ~:579, `McpHitlTools` ~:215 and ~:395,
  `SlackInteractivityHandler` ~:209. `HitlVerdict.fromString` returning null for an
  unrecognised value (rather than throwing) is deliberate and is caught at every surface.
- **`McpToolsProvider`'s first-write-wins collision check.** Correct — `executors` is declared
  at ~:116, *outside* the per-server loop at ~:127, so cross-server collisions are genuinely
  detected. (Loop-scoped, it would have been decorative.)
- **The HITL tool journal (claim → replay → outcome-unknown).** Sound, including the
  crash-inside-the-tool case, which reports `EXECUTION_OUTCOME_UNKNOWN` rather than guessing.

---

## 5. Working conventions for this area

Read [`AGENTS.md`](../AGENTS.md) first — this section is only what is *specific to group work*
and was learned the hard way on #626.

**Sequencing**
- **One wave per branch/PR.** #626 reached 3–5× reviewable size and every extra commit made
  review worse. Branch from `origin/main`, never from another feature branch.
- Update `docs/changelog.md` **in the same commit** as the work it documents.

**Testing — the two that actually catch things here**
- **Mutation-check every fix.** Revert the fix, confirm *exactly* the intended test fails,
  restore. On #626 this caught surviving mutations 3+ times, including three "fixes" that were
  themselves wrong. A test that passes both with and without your change pins nothing.
- Beware `any()` in the *input* position of a Mockito verification — it makes the assertion
  vacuous. Use `eq(...)` for the value you actually care about.
- `-Dtest=Class#method` silently runs **0 tests** (exit 0, looks green) when the method is in a
  `@Nested` class. Filter by whole class.
- The local suite is red out of the box (~8 failures / ~294 errors, all environmental —
  Mongo/Postgres/network/A2A/embedding). **Baseline before blaming your change.**
- Integration tests (`*IT.java`) and anything binding a loopback socket **cannot run locally**.
  CI is the source of truth for those.

**Group-specific traps**
- `@Vetoed` is **required** on any `@Tool`-bearing class constructed per-turn with runtime
  values — langchain4j otherwise registers it as a CDI bean and the **application will not
  start**. No unit test catches this; only CI does.
- `context:groupConversationId` is **caller-supplied**. Any tool that writes to a discussion
  must resolve it through `LiveDiscussionRegistry.getForMember(gcId, conversationId)`, never
  `get(gcId)` — that was an IDOR on #626.
- A tool that mutates the roster or task list must be reachable by the **documented** approval
  config. Check the gate actually covers your new tool name.
- Concurrency and authorization are where group code breaks — a discussion is many
  conversations mutating one document.

**Build**
- Every `mvnw` run reformats non-compliant tracked `.java` in place. Expect spurious diffs;
  don't commit them.
- Type-signature refactors need `mvnw clean` — incremental builds reuse stale `.class` files
  and hide breaks in callers you didn't edit.
- Never run two Maven builds concurrently against the same `target/` (corrupts jandex).

**Git**
- **Ask before `git push`.** `--force` / `--force-with-lease` are forbidden.
- No AI co-authorship trailers or tool-advertising footers.
- Stage files individually — never `git add .` / `-A`.
- A **CONFLICTING/DIRTY PR never runs `ci.yml` at all**, while CodeQL/Codacy/CodeRabbit still
  report — so it *looks* green. Verify with
  `gh run list --workflow=ci.yml --branch <branch>` before trusting a green PR page.

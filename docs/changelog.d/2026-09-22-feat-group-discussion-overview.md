## ✨ feat(groups): member stances and live cost/stance SSE for the discussion overview (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

### Why

A group discussion is readable as a transcript only while it is short. Seven members across
five phases is ~40 messages of prose, and the questions an observer actually has — where are
we, who thinks what, who disagreed, what has this cost — are answerable only by reading all
of it. The Manager and the Workforce board both render that transcript and nothing else.

The planned answer is a second *view* of one discussion (not a cross-conversation metrics
screen): a dashboard of self-hiding bands — phase rail, roster, members × phases matrix —
that any discussion style can drive, because every style reduces to the same
phases × members × entries shape underneath. This entry is the backend half.

Two things that view needs did not exist.

**Cost was invisible while it mattered.** `memberCosts`/`totalCost` live on the persisted
`GroupConversation`, and no SSE event carried them — so a *running* discussion could show no
spend at all, and the figure appeared only once the document was reloaded. That is exactly
backwards: spend is worth watching while it accrues.

**There was no "where this member stands".** Reconstructing a member's position meant
re-reading every turn they took.

### What changed

**`StanceSummaryEngine`** (new) — a one-line stance per member, recomputed at phase
boundaries. Two producers, and which one ran is carried to the UI rather than hidden:

- **Lead-sentence extraction** — the default. No config, no cost, and it is the member's
  *own words*. `llmGenerated=false`.
- **LLM summarization** — opt-in via `stanceSummary.llmProvider`/`llmModel`. Reads
  everything the member has said. `llmGenerated=true`.

The distinction is surfaced because an extracted line is a quote and a generated one is a
paraphrase; presenting the second as the first would be a misattribution.

A summarizer failure (throw, or a blank response) **degrades to extraction, not to nothing** —
the roster is the band's headline content, and an empty roster is worse than a rougher one.
Failures are WARN and never propagate: a display projection must not be able to fail a
discussion.

Modelled deliberately on the I9 window summarizer — same provider/model pair, same optional
price fields feeding the same I1 ledger via `GroupCostLedger.recordSystemCost`, same
warn-don't-reject treatment at save time. A second piece of machinery that spends on the
group's behalf should not be a second thing to learn.

**`StanceSummaryConfig`** has **no `enabled` flag**, unlike `ContextWindowConfig`. Stances
always exist, so a boolean could only ever have meant "may this spend money?" — which is
already what naming a provider and model means. A flag that could be `true` with no model
would be two ways to say the same thing and one way to contradict it.

**Recomputation is skipped** for a member whose stored stance already covers the whole
transcript. That skip is the difference between one summarizer call per member per
*discussion* and one per member per *phase*.

**Two new SSE events:**

- `cost_updated` — fires after every attribution, including system spend. Carries the key's
  **cumulative** cost, not a delta: the ledger records by replacement so a duplicate is
  idempotent, and a delta would throw that away — a reconnecting client replaying one frame
  would double-count. A PARALLEL phase's turns can interleave, so `totalCost` may arrive out
  of order; a consumer keyed on `attributionKey` that sums its own map is order-independent,
  which is what the UI does.
- `stance_updated` — fires only when a stance's *text* actually changed.

`MemberTurnExecutor.announceCost` is called strictly **after** the ledger write returns.
Emitting from inside `GroupCostLedger` would hold `memberCosts`' monitor across an SSE
callback, letting one backpressured client stall every concurrent turn's attribution — the
exact failure `announceArtifactChanges` already documents and avoids.

**`memberStances` on `GroupConversation` does not bump `CURRENT_SCHEMA_VERSION`.** The bump
rule is scoped to fields "resume-time logic depends on", and this is a display projection:
fully reproducible from the transcript, read by nothing in the discussion, refilled at the
next boundary if empty. An older pod re-saving the document loses a cache, not state.

### Design decisions

- **Extraction is the default, not the fallback.** Opting in is opting into *spend*, not
  into the feature — so a deployment that never configures a summarizer still gets a
  populated roster.
- **`executeGroupMemberTurn` gained a listener parameter** rather than a parallel
  announce-queue. Its only production caller already had one in scope; the queue machinery
  `announceArtifactChanges` needs exists because tools hold no listener reference, which is
  not the case here.
- **Save-time warning only for a *half*-configured summarizer** (one of provider/model, or
  prices with neither). Naming neither is the documented default and is not warned about.

### Tests

`StanceSummaryEngineTest` — 39 cases across extraction, entry-type selection, summarization,
degradation, idempotence, `leadSentence`, `clean`, config normalisation and `announceCost`.
Two mutation checks were run and both were caught by exactly the intended test: forcing the
coverage skip off broke `unchangedMemberNotResummarized`, and adding `ABSTAINED` to the
stance-bearing set broke `abstentionDoesNotReplaceRealStance` (a member's real position would
otherwise be overwritten by "I have nothing to add").

**Files:**
[`StanceSummaryEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/StanceSummaryEngine.java),
[`GroupConversationEventSink.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/GroupConversationEventSink.java),
[`GroupConversation.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java),
[`AgentGroupConfiguration.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/AgentGroupConfiguration.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`AgentGroupStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java),
[`StanceSummaryEngineTest.java`](../../src/test/java/ai/labs/eddi/engine/internal/groups/StanceSummaryEngineTest.java)

```decision-log
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
```

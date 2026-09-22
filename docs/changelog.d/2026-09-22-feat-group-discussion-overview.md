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

## ✨ feat(manager): a dashboard view of a running group discussion (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

### Why

A group discussion is readable as a transcript only while it is short. Seven members across
five phases is ~40 messages of prose, and the questions an observer actually has — where are
we, who thinks what, who disagreed, what has this cost — are answerable only by reading all
of it. All three surfaces rendered that transcript and nothing else.

### What changed

A second **view** of one discussion (not a metrics screen across discussions), offered by a
`Transcript / Overview / Both` switch. The transcript stays the default and is never
replaced — the node is passed through untouched, so approvals, human turns and the composer
keep working exactly as before.

**Every style, one renderer.** A group discussion reduces to the same shape whatever its
style: ordered **phases × members × entries**. So the dashboard is one set of bands driven by
one adapter, and per-style difference is *band ordering*, not a code path
(`style-recipe.ts`). A style this build has never heard of falls through to the default order
and renders sensibly rather than blankly.

The bands, each hiding itself when it has nothing — the pattern `DiscussionInsights` already
set, so a caller can mount the panel unconditionally:

- **Headline** — question, state, round, elapsed, phases done, members, turns, cost
- **Phase rail** — the spine: each phase's status, a dot per member who has spoken, the
  convergence score where one was judged, and an approval-gate marker
- **Roster** — "who thinks what": each member's one-line stance, turn count, spend and
  whether they are speaking, dissenting or broken
- **Matrix** — members × phases, the band that does the actual compression
- **Outcome / extras** — slots filled by the components that already exist (decision card,
  task board, negotiation ledger, artifacts)

**`useDiscussionDigest` is the load-bearing piece.** Three independent transcript renderers
exist here, fed by two different shapes (live `GroupStreamState`, persisted
`GroupConversation`), and a group feature wired into only some of them has already drifted
once — a DISSENT rendered as an ordinary opinion on two of the three. One adapter means the
dashboard cannot acquire that class of drift: exactly one place knows how a live discussion
differs from a reloaded one.

### Design decisions

- **`absent` and `pending` are different cells and must not collapse.** `absent` means the
  phase's selector never included that member (a MODERATOR-only synthesis, a `ROLE:PRO`
  rebuttal); `pending` means they are expected and have not spoken. Drawing both blank tells
  a reader that a debate's PRO side went quiet during a CON phase. A first cut rendered every
  not-yet-reached phase as `absent` — i.e. "excluded" rather than "not yet" — which the
  digest tests caught.
- **The participant selector is only resolved when it says `ALL`.** `MODERATOR` and `ROLE:…`
  need the roster, which not every surface has, so they resolve to *unknown* and the matrix
  falls back to `pending`. Guessing the other way silently hides a member who was expected.
- **Total cost is summed from the per-key map, never read off a frame.** PARALLEL turns
  interleave, so frame order is not value order; and a member's spend can span several ledger
  keys (a nested GROUP member gets one per child discussion), matched on a `:` boundary so
  `agent-a` does not absorb `agent-abc`.
- **No cost is `null`, not `$0.00`.** An unpriced LLM config reports nothing at all, and
  "$0.00" reads as "this was free" rather than "this was not measured", so the stat hides.
- **Whether a stance is a quote or a paraphrase is shown, not flattened** — different icons
  and different labels. Presenting an LLM paraphrase the way a quotation is presented would
  misattribute it.
- **DELPHI's roster is anonymised.** Its method is that members judge the argument, not its
  author; naming everyone next to their position would undo that for the one human watching.
- **`split` is a layout, not a capability.** It is a pure container query, so narrowing the
  pane restacks the two panels instead of discarding the half the user chose — and the stored
  preference never silently changes to something they did not pick. Which element scrolls
  changes with width, because a stacked transcript whose parent is auto-height collapses to
  nothing (`flex-1 min-h-0` resolves to zero).
- **Sized by container query throughout**, not viewport: this renders in a column the group
  config panel can squeeze to half the window, which `task-board.tsx` already records getting
  wrong the other way.

### Tests

`use-discussion-digest.test.ts` (30) over phase derivation, the matrix's five cell kinds,
cost attribution and source precedence; `discussion-overview.test.tsx` (26) over the bands,
the style recipe, the stored view preference and the panel's switching. Full suite **6682
passing (419 files)**, lint and `tsc -b` clean, i18n drift + plural-completeness +
translation-debt gates green across all 11 locales.

Three mutation checks, each caught by exactly the intended test: dropping the `:` boundary
from cost-key matching, forcing `rosterIsAnonymous` false, and the `absent`/`pending`
collapse (which was a real bug, not a seeded one).

**Files:**
[`use-discussion-digest.ts`](../../ui/manager/src/hooks/use-discussion-digest.ts),
[`discussion-overview.tsx`](../../ui/manager/src/components/groups/overview/discussion-overview.tsx),
[`discussion-panel.tsx`](../../ui/manager/src/components/groups/overview/discussion-panel.tsx),
[`phase-rail.tsx`](../../ui/manager/src/components/groups/overview/phase-rail.tsx),
[`member-roster.tsx`](../../ui/manager/src/components/groups/overview/member-roster.tsx),
[`participation-matrix.tsx`](../../ui/manager/src/components/groups/overview/participation-matrix.tsx),
[`style-recipe.ts`](../../ui/manager/src/components/groups/overview/style-recipe.ts),
[`group-detail.tsx`](../../ui/manager/src/pages/group-detail.tsx),
[`workforce-board.tsx`](../../ui/manager/src/pages/workforce/workforce-board.tsx),
[`conversation-viewer.tsx`](../../ui/manager/src/components/workforce/conversation-viewer.tsx)

```decision-log
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
```

```regression-note
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
```

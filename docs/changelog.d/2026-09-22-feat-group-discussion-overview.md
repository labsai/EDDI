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

## 🐛 fix(groups): review remediation for the discussion overview (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

### Why

A high-effort adversarial review of the two commits above found eleven defects worth fixing,
five of them producing **wrong numbers** and four making the dashboard **assert things that
are not true**. Each is recorded here because most were invisible to the tests that existed —
several were guarded by assertions that would have passed with the bug in.

### Wrong numbers

- **The documented "a silent member costs nothing" skip was never implemented.** Coverage was
  keyed to the *transcript length*, which grows whenever anyone speaks, so the skip only fired
  for a phase nobody spoke in. A six-member discussion re-summarised all six at every
  boundary — the exact "one call per member per phase" the Javadoc, the docs and the changelog
  all claimed to avoid. `MemberStance.upToTranscriptIndex` is now `coveredContributions`,
  counting that member's own stance-bearing entries.
- **The stance summariser escaped the I1 cost ceiling.** Every other optional spender (the I9
  window summariser, the convergence judge, the dissent round) checks
  `wouldExceedCeiling`; this one did not, so a boundary ran one priced call per member *after*
  the budget was gone and before the next phase's pre-wave check could fire. Past the ceiling
  it now downgrades to extraction — `wouldExceedCeiling`, not `enforceCeiling`, because
  declining optional work is not the same event as a phase running out of budget.
- **A re-summary landing on the same wording billed the ledger and emitted no
  `cost_updated`.** Results were reported on text change alone; they are now reported when the
  text changed *or* the call cost something.
- **The I9 window summariser's spend was never announced**, though four places (two Javadocs,
  the docs and the changelog) said system spend is emitted "after every attribution". Any
  windowed discussion's live total sat below the ledger's for its whole run.
  `updateWindowSummary` now returns the ledger key it billed, for the caller to announce.
- **The live cost map replaced the persisted one instead of overlaying it.** A stream carries
  only the keys it announced *this session*, so pressing Continue on a $4.10 discussion made
  the headline read $0.02 until the document was refetched. Now merged per key — correct
  precisely because each frame carries that key's *cumulative* cost.

### The dashboard stating something false

- **Continuation rounds were conflated.** The backend restarts `phaseIndex` at 0 each round,
  so bucketing the whole transcript by phase index put round 1's turns in round 2's cells, let
  a round-1 ERROR mark a round-2 cell "failed", and showed members as having already spoken in
  phases the current round had not reached. The digest now slices at
  `roundStartTranscriptIndex`.
- **`leadSentence` returned `"1."`** for the numbered list LLM replies open with constantly —
  rendered as that member's position, attributed as *their own words*. A candidate sentence
  containing no letter is now rejected. Conversely the abbreviation rule swallowed
  `"Weigh option B. Option A is worse."`, so it now also requires a lower-case continuation.
- **Extraction quoted JSON.** `VOTE`, `BID`, `RETRO`, `PLAN`, `TASK_RESULT` and `VERIFICATION`
  carry a JSON contract; the lead "sentence" of a ballot is `{"choice":"pgvector",` — and being
  the newest entry it *replaced* the member's real prose position after every vote or retro.
  Excluded from extraction on both sides; the summariser still reads them.
- **"Has not spoken yet" was shown beside a turn count.** A moderator's only contribution is a
  SYNTHESIS, which is not a position of its own, so it had no stance while plainly having
  spoken. Now two messages.
- **A dropped turn was labelled "not in this phase".** `absent` means the selector excluded
  the member; a finished `ALL` phase with nothing from them means their turn was dropped
  (`maxTurns`, a ceiling, a `SYNTHESIZE_NOW` jump). New `silent` cell kind, shown only where
  the selector is *known* to have included them.
- **The headline's "Turns" counted rows the rail excluded** (QUESTION, CONVERGENCE,
  FACILITATION, system SKIPPED), so the two disagreed. Same filter now.

### Also

- **A vacuous negative assertion.** `verify(never()).summarizeWithUsage(anyString(), …)` could
  not fail: a half-configured summariser passes a **null** model, and `anyString()` does not
  match null. Replaced with `verifyNoInteractions`.
- **Dead API surface removed** — `DigestPhase.expectedSpeakers` (always null),
  `streamTotalCost` (no consumer), and the `onSelectPhase`/`onSelectMember`/`onSelectCell`
  props no surface passed. The phase interaction that was worth keeping is now owned by
  `DiscussionPanel` itself (picking a phase switches to the transcript, where turns are)
  rather than being an optional callback nobody supplied — a click target that silently does
  nothing is worse than none.
- **An orphaned Javadoc**: the new save-time warning had been inserted between
  `warnOnSummarizerlessWindow`'s doc comment and its body.
- **Literal NUL bytes** were in `use-discussion-digest.ts` (a matrix key separator written as
  a raw character rather than an escape), which made `file` report the source as binary.
- The question in the headline is now **clamped to three lines**. `originalQuestion` is not
  always a question — the grant-board fixture pastes an entire application — and rendering it
  whole pushed the rail, roster and matrix below the fold, which is the wall of text this view
  exists to replace. Found by running the UI, not by a test.

### Tests

Backend 48 (up from 39), frontend 42 in the digest suite (up from 30). Four further mutation
checks, each caught by exactly the intended test: dropping the round slice, swapping the cost
overlay back, and (backend) the per-member coverage skip and the ceiling gate.

**Files:** as the two entries above, plus
[`GroupContextBuilder.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupContextBuilder.java).

```regression-note
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
```

## 🐛 fix(groups): second review round — continuation rounds, missing bands, per-member ceiling (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

### Why

A second reviewer (Copilot) found eleven further defects on the branch, clustered in three
places the first round had touched but not finished: **continuation rounds**, **content the
Overview simply did not render**, and the **cost ceiling**. All eleven were confirmed against
the code before being fixed.

### The ceiling and the cost/stance events

- **The budget was decided once per boundary, not per member.** If the first member's call
  pushed `totalCost` past the ceiling, every remaining member still took the LLM path — so a
  boundary could add N paid calls after the budget was exhausted. Re-checked before each
  member now.
- **`stance_updated` fired when nothing had changed.** Fixing the earlier "cost with no frame"
  bug had put cost-only results into the same list the stance event iterates, so every paid
  re-summary announced a stance change that had not happened. `StanceResult` now carries
  `textChanged`; the stance event keys off it and the cost event off `cost()`, because the two
  are genuinely independent.
- **`clean(text, 1)` returned two characters**, violating its own documented hard cap — one
  retained character plus the ellipsis. A cap of 1 now yields the ellipsis alone.

### Continuation rounds, again

The first round's fix sliced the *persisted* transcript at `roundStartTranscriptIndex` but
forced the offset to zero while live — on the assumption that a continue-stream starts at the
round boundary. **It does not.** `continueStream` deliberately preserves `s.transcript` and the
`group_start` handler *appends* the new question, so a live continuation carried every round in
one array and re-created exactly the cross-round contamination the slice exists to prevent.
`GroupStreamState` now tracks its own `roundStartIndex`, set at `group_start`.

**The headline also showed the wrong question.** A continuation records its follow-up as a
`QUESTION` entry rather than rewriting `originalQuestion` (which stays the title), so round 2+
displayed the *first* round's prompt above a summary of answers to a different one. The digest
now resolves the newest `QUESTION` in the current round, falling back to `originalQuestion`.

### Content the Overview did not render

- **No synthesised answer.** The outcome band rendered only the caller's node, and all three
  callers pass a decision card. Most ROUND_TABLE and PEER_REVIEW runs produce no structured
  decision — so an ordinary completed discussion showed **no conclusion at all** in Overview,
  reachable only by switching back to the transcript. Now rendered as its own card, *alongside*
  a decision rather than instead of it: the decision carries the tally and minority report, the
  synthesis carries the reasoning.
- **No task board, on any of the three surfaces.** It lives inside the transcript renderers,
  which Overview mode unmounts — so TASK_FORCE, the one style whose recipe puts `extras`
  first, lost its principal working surface. Added to all three `extras` nodes, reusing each
  surface's already-computed live/persisted/placeholder state rather than deciding again.
- **Members silent in the current round disappeared.** Roster-only members were collected from
  the optional `rosterDisplayNames` prop alone, but the Workforce history viewer passes none —
  so a member with no turn this round vanished from the roster *and* the matrix instead of
  showing `silent`/`absent` cells. Now taken from the merged display-name map, which includes
  the persisted `memberDisplayNames`.
- **`group-detail` nulled the conversation while streaming**, copying what the transcript needs
  — which defeated the persisted/live cost overlay the previous round had just introduced.
  `continueStream` seeds neither its cost nor its stance map from the stored document, so a
  continuation dropped the earlier round's spend and positions. The panel now always receives
  the persisted document; only the transcript keeps the nulling.

### Verified by running it

The Overview was re-opened in the Manager's mock mode after the fixes. The Conclusion band is
present where there was previously nothing, the matrix rows align, the moderator reads "No
position of their own" rather than the false "has not spoken yet", and the headline's turn
count now agrees with the rail (7, not 8 — the `QUESTION` row is excluded on both sides).

### Tests

Backend 748 across the group suites and repo guards; frontend 76 in the two overview suites,
with new cases for the live round boundary, the current-round question, the silent-member
roster entry, and the synthesis band's three states.

**Files:** as above, plus
[`use-group-discussion-stream.ts`](../../ui/manager/src/hooks/use-group-discussion-stream.ts).

```regression-note
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
```

## 🐛 fix(manager): the overview was losing the mechanics of its two most-used styles (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

### Why

Auditing the dashboard against every discussion style turned up information the digest
*collected and then never rendered* — the same dead-surface class two reviewers had already
flagged elsewhere, but here it cost the reader real signal rather than just carrying an unused
field.

- **A repeating phase looked identical to a single-pass one.** `ROUND_TABLE` puts
  `repeats = rounds - 1` on its "Discussion" phase and `DELPHI` is built on repeats
  end-to-end, so for the two most-used styles the rail was quietly flattening the mechanic
  that defines them. `DigestPhase.repeats` was populated and read by nothing; it now renders
  as a `×N` badge.
- **Convergence showed the score but not the saving.** `convergence.repeatsSkipped` is the
  concrete outcome a DELPHI reader is looking for — "it stopped after 2 of 4" — and only the
  bare agreement percentage was shown. A converged phase now says how many repeats it skipped.
- **A continuation's round scope was ambiguous.** The bands are correctly scoped to the
  current round (phase indices restart each round, so mixing them would be wrong), but the
  headline said only "Round 2". A reader could not tell whether a low turn count meant a quiet
  round or a view that had lost the earlier ones. It now reads "Round 2 only", with the
  transcript named as where the rest is.

**Files:**
[`phase-rail.tsx`](../../ui/manager/src/components/groups/overview/phase-rail.tsx),
[`discussion-overview.tsx`](../../ui/manager/src/components/groups/overview/discussion-overview.tsx)

```regression-note
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
```

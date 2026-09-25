# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Not here.** Write your entry as a new file in
[`changelog.d/`](changelog.d/README.md) — `YYYY-MM-DD-<slug>.md`, with the slug
unique to your branch — and leave this file alone. The same goes for the two
running registers at the bottom: their rows ride along in the fragment, in a
fenced `decision-log` or `regression-note` block.

Entries used to be inserted at the top of this file, and the registers appended
to at the bottom. Both are a fixed point in a shared file, which git cannot
merge: with several PRs open, every one of them conflicted with every other over
a document that had nothing to do with the code under review. A fragment is a new
file under a name no other branch picks, so the same two PRs merge without
touching each other.

`.github/workflows/changelog-collate.yml` runs nightly, merges the fragments in
here **by date** — a PR that stayed open for weeks lands among its
contemporaries rather than on top — trims this file back under its rotation
target, and opens a PR. Until that PR merges, `changelog.d/` holds the newest
history, so read it alongside the top of this file. To do it by hand:

```bash
python scripts/collate-changelog.py   # fragments -> this file
python scripts/rotate-changelog.py    # this file -> docs/changelog/<YYYY-MM>.md
```

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. Rotation runs at a
lower threshold than the cap, trimming back to **200 KB** whenever the file is
over that, so the session whose entry tips it over is not the one made to rotate
it. Rotation moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by date,
adds one `../` to the relative links it moves (an archive sits a directory deeper
than this file) without touching the ones inside code spans, and regenerates both
the Archive table below and the changelog list in [`SUMMARY.md`](SUMMARY.md) from
what is on disk. Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [September 2026](changelog/2026-09.md) | 84 | 400 KB |
| [August 2026](changelog/2026-08.md) | 212 | 837 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 🐛 fix(manager): a past round and an approved resume, in the overview (2026-09-24)

**Repo:** EDDI (`feat/group-discussion-overview`)

Three findings from review, all confirmed against the code:

- **A past round read as still running.** `state`, `currentPhaseIndex` and the stream's
  convergence map all describe the newest round. When an earlier round was selected while
  a later one ran, its phases past the live phase index showed as "pending" even with
  turns in them, and a member who stayed silent showed as "pending" instead of "silent".
  An earlier round now counts as ended, reusing the existing terminal handling.
- **A past round showed the live round's convergence.** The digest read convergence by
  phase index alone. No record of an earlier round's check survives in the transcript or
  the stored document, so a past round now shows none rather than a wrong one.
- **Approving a paused discussion dropped its history from the Overview.** Approval clears
  the selection so the transcript follows the resumed stream, and that also disabled the
  stored-conversation query the Overview reads. The stream is seeded from none of the
  stored document, and after a reload the store holds nothing from before the pause, so
  the paused rounds' spend and every stance vanished until the stream settled.
  `group-detail.tsx` now snapshots the paused conversation at approval and feeds it to
  the Overview and the insights panel. It is used only while the stream is still on that
  conversation's id, so "New Discussion" or a fresh start can't surface a stale snapshot.
  It is a snapshot rather than a second query, because those figures change only through
  live frames the digest already overlays.

Four new digest tests and one page test cover these. All but the "live round still
running" control fail with their fix reverted.

---

## 🐛 fix(manager): a resumed or continued stream starts from the whole discussion (2026-09-24)

**Repo:** EDDI (`feat/group-discussion-overview`)

A review follow-up turned up a larger defect underneath it.

- **Live continuations never recorded their round.** `/continue/stream` opens round 2 and
  later with `round_start`, never with `group_start`, and the stream handled only
  `group_start`. So a live continuation appended no question and never moved
  `roundStartIndex`. The Overview bucketed every round's turns into one round's phases,
  exactly the merge the round boundary was added to prevent, and the transcript showed no
  question for the new round. The existing tests modelled a continuation with
  `group_start`, a frame the backend does not send there, so they passed. `round_start`
  is now handled, and `RoundStartPayload` is typed.
- **A resumed or continued stream started from whatever the store held.** Neither
  endpoint replays the discussion so far. After a reload the store is empty, so the first
  resumed turn made the live transcript the only one, and the Overview lost every earlier
  phase and round. If the user had just watched a different discussion stream, the store
  still held that discussion's rows, costs and answer, and they appeared under this one.
  `approveAndStream` and `continueStream` now take the stored document as a seed, and a
  stream switching conversations starts from a clean state. The stored transcript wins
  unless the store is ahead of it, which happens when the page's copy was fetched before
  the last streamed rows. Both the Manager and the Workforce board pass the seed.
- **A resume that restarts at phase 0 re-announces its round.** Against a seeded
  transcript that would duplicate the question and count a round that never ran, so a
  question matching the transcript's last row is not appended again.

Six new stream tests. Each of the four changes (the `round_start` case, seeding, the
duplicate guard, and "the store wins when ahead") was reverted in turn, and each revert
fails at least one of them.

---

## 🐛 fix(test): RestImportServiceRagCronTest passes the source-policy constructor args (2026-09-24)

**Repo:** EDDI (`feat/group-discussion-overview`, a separate commit so it can be cherry-picked to `main` by itself)

### What

`main` stopped compiling its test sources. Two commits landed independently:
`b8814bf8a` (fix(rag): arm ingestion schedules) added `RestImportServiceRagCronTest`, which
builds a `RestImportService` with twelve constructor arguments, and `52c85736b` (fix(backup):
make agent sync work more than once) added three more to the constructor:
`requireHttpsSource`, `allowPrivateSources` and `allowedSources`. `52c85736b` updated every
caller it could see. The new test was not one of them, because the two branches never saw each
other before merging. Each commit was green on its own branch, and together they fail
`testCompile`. That fails every unit test in the module, not just this one.

The test now passes `true, false, Optional.empty()`, the same production defaults its sibling
tests use. It exercises cron arming only, so the source policy has no effect on it.

---

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
[`StanceSummaryEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/StanceSummaryEngine.java),
[`GroupConversationEventSink.java`](../src/main/java/ai/labs/eddi/engine/lifecycle/GroupConversationEventSink.java),
[`GroupConversation.java`](../src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java),
[`AgentGroupConfiguration.java`](../src/main/java/ai/labs/eddi/configs/groups/model/AgentGroupConfiguration.java),
[`MemberTurnExecutor.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`GroupConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`AgentGroupStore.java`](../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java),
[`StanceSummaryEngineTest.java`](../src/test/java/ai/labs/eddi/engine/internal/groups/StanceSummaryEngineTest.java)

---

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
[`use-discussion-digest.ts`](../ui/manager/src/hooks/use-discussion-digest.ts),
[`discussion-overview.tsx`](../ui/manager/src/components/groups/overview/discussion-overview.tsx),
[`discussion-panel.tsx`](../ui/manager/src/components/groups/overview/discussion-panel.tsx),
[`phase-rail.tsx`](../ui/manager/src/components/groups/overview/phase-rail.tsx),
[`member-roster.tsx`](../ui/manager/src/components/groups/overview/member-roster.tsx),
[`participation-matrix.tsx`](../ui/manager/src/components/groups/overview/participation-matrix.tsx),
[`style-recipe.ts`](../ui/manager/src/components/groups/overview/style-recipe.ts),
[`group-detail.tsx`](../ui/manager/src/pages/group-detail.tsx),
[`workforce-board.tsx`](../ui/manager/src/pages/workforce/workforce-board.tsx),
[`conversation-viewer.tsx`](../ui/manager/src/components/workforce/conversation-viewer.tsx)

---

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
[`GroupContextBuilder.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupContextBuilder.java).

---

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
[`use-group-discussion-stream.ts`](../ui/manager/src/hooks/use-group-discussion-stream.ts).

---

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
[`phase-rail.tsx`](../ui/manager/src/components/groups/overview/phase-rail.tsx),
[`discussion-overview.tsx`](../ui/manager/src/components/groups/overview/discussion-overview.tsx)

---

## ✨ feat(manager): directional turns, bids, a round switcher and a bounded roster (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

### Why

A sweep of the dashboard against every style, every length and every purpose found four
things it did not cover. Three were signal the discussion produces and the view discarded.

**Who addressed whom.** PEER_REVIEW and DEVIL_ADVOCATE are *directional* — a critique lands
on someone, a challenge is aimed at a position — and `TranscriptEntry.targetAgentId` carries
that on every such turn. The dashboard collected it and threw it away, so it could say
"Security spoke during Critique" but never "Security critiqued the Architect", which is the
content of those styles rather than a detail of them. New `interactions` band, grouped by
speaker and led by the heaviest exchange, plus a "nobody addressed" line — in a peer review
that names the contribution which drew no scrutiny, which is exactly what a reviewer of the
review is looking for.

Rendered as a list rather than a graph on purpose: a force-directed diagram of five nodes is
decoration and at twenty is unreadable, while a list answers the two real questions at any
size and needs no layout engine. **DELPHI omits this band** — naming who answered whom would
undo the anonymity the method rests on, which is the whole reason its later rounds run
`ANONYMOUS`.

**Who bid for what.** TASK_FORCE's contract-net phase (I18) produces `BID` entries that
nothing rendered. The task board shows who *holds* a task; this shows who *wanted* it, and the
difference matters: a task three members bid on and a task nobody bid on look identical in an
assignment list, and the second is the one worth acting on. Grouped by task, because
contention is per task. Reuses `readEntryBody` rather than adding a second parser for the same
JSON contract.

**Earlier rounds were unreachable.** The bands are necessarily scoped to one round (phase
indices restart each round), which left a reader comparing round 1 to round 2 no option but to
leave for the transcript. There is now a round selector.

Recovering the earlier boundaries needed care, because only the *current* round's start is
persisted. They are read from the `QUESTION` entries the backend writes at every round start —
but **validated against the stored boundary** rather than trusted: the recovered list's last
entry must equal the round start the backend recorded, and if it does not, the recovery is
discarded in favour of the stored boundary alone. That narrows the switcher instead of slicing
the view wrongly, and it is not hypothetical — the validation was added because a test with a
stray `QUESTION` row split a single-round discussion in two.

**The roster had no bound.** Twenty members meant twenty stance cards, which is the wall of
text this view exists to replace. Collapsed past eight.

### Screen sizes, languages, dark mode

Verified in the running Manager rather than asserted: **390 / 768 / 1600px × German (longest
strings) and Arabic (RTL) × light and dark**. No horizontal page scroll and no band overflow
in any combination; the container queries step the rail 2 → 3 → 5 columns and the roster
1 → 3 as the *pane* widens, not the window. The interaction band's arrow carries
`rtl:-scale-x-100`, the idiom three existing components already use — an arrow that keeps
pointing right in Arabic reverses the sentence.

### Tests

Frontend 92 across the two overview suites, including the interaction pairs, bid flattening,
round selection and clamping, and the boundary-validation fallback. The style-recipe invariant
was rewritten: "every band in every recipe" is no longer true now that DELPHI deliberately
omits one, so it asserts no repeats plus the core four, with the DELPHI omission as its own
named test.

**Files:**
[`interaction-map.tsx`](../ui/manager/src/components/groups/overview/interaction-map.tsx),
[`bid-board.tsx`](../ui/manager/src/components/groups/overview/bid-board.tsx),
[`use-discussion-digest.ts`](../ui/manager/src/hooks/use-discussion-digest.ts),
[`style-recipe.ts`](../ui/manager/src/components/groups/overview/style-recipe.ts),
[`member-roster.tsx`](../ui/manager/src/components/groups/overview/member-roster.tsx)

---

## ♿ fix(manager): the overview's roster toggle and interaction rows, for screen readers (2026-09-22)

**Repo:** EDDI (`feat/group-discussion-overview`)

Two accessibility gaps flagged in review, both confirmed:

- **The roster's show-all toggle didn't expose its state.** A screen reader user couldn't
  tell whether the list was expanded. The toggle now carries `aria-expanded` and an
  `aria-controls` pointing at the list, matching the task board's toggle. The id comes
  from `useId` so it can't collide.
- **An interaction row lost its direction when read aloud.** The arrow between speaker and
  target is decorative (`aria-hidden`) and nothing replaced it, so the row was announced
  as two names side by side, dropping the one fact the band exists to state. A visually
  hidden connector now reads "Architect addressed Security". It is phrased per language
  rather than translated word for word: most locales use a verb, but Japanese and Korean
  put the verb last, so a bare verb there would announce the relationship backwards. They
  use a possessive label instead ("Architect's addressees: Security").

The connector test checks the text a screen reader actually announces, skipping
`aria-hidden` subtrees. A plain `textContent` check would include the hidden arrow and
pass whether or not the connector existed. Both tests fail with their fix reverted.

---

## 🔁 fix(backup): agent sync works more than once, can create, and says what it did (2026-09-22)

**Repo:** EDDI (`fix/agent-sync-promotion`)

### What changed and why

Live Agent Sync was tested end to end against two real instances for the first
time, and it did not survive the ordinary use it exists for — promoting an agent
from staging to production and then keeping it current. Four defects, each in the
seam between `UpgradeExecutor` and something it does not own, plus two decisions
that made the feature unusable on the network shape most self-hosted deployments
have.

**Version resolution always answered 1.** Both
[`StructuralMatcher`](../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java)
and [`UpgradeExecutor`](../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java)
asked for the target's current version with `readDescriptor(id, null)`. The
descriptor store is historized and its read does `checkNotNull(version)`, so that
call *always* threw; the exception was swallowed and a fallback of 1 stood in. So
every sync diffed against the target's version 1 — showing the operator pre-sync
content as "target" — and then wrote against version 1, which the store refuses
once the first sync has moved the resource to version 2. A sync therefore worked
exactly once per target agent and then answered `207` with "the store did not
accept the update", writing nothing, for ever. Both now use
`readCurrentDescriptor`.

**Nothing moved the descriptors.** An upgrade calls the configuration stores
in-process, so no JAX-RS filter runs and nothing did what `DocumentDescriptorFilter`
does for a `PUT` through the API. The descriptor is what `WorkflowStoreService`
reads to deploy an agent — so a synced version was written correctly and then
refused to deploy at all, with "Resource not found" for a workflow that was
demonstrably in the database — and it is what the Manager lists, so the UI kept
showing the pre-sync version. `UpgradeExecutor` now moves the descriptor after
each write, and reports a resource whose descriptor could not be moved as a
failure rather than as a success.

**A successful sync answered 500.** The endpoint answers `201` with the agent's
new-version URI, which `DocumentDescriptorFilter` read as a creation and tried to
give a second descriptor — duplicate key, after every write had already landed.
Backup endpoints keep their own descriptors and are now skipped by that filter.

**A first promotion was impossible.** With no `targetAgentId` — which the API
documents as "create new" and which the Manager sends for any agent it cannot
match by name — the request went into `UpgradeExecutor`, whose every path assumes
a target. It read the agent `null`, answered `500`, and left the workflow it had
already created behind: one orphan per attempt, pointing at resource ids that
only exist on the other instance. A sync with no target now fetches the source's
own export archive and imports it with `strategy=create`, so a first promotion
lands exactly what the same archive would land by hand — schedules, connections,
capability registration and rollback included — rather than through a second,
thinner create path that would drift from it.

**Snippets are scoped to the agent.** `readSnippets` returned the remote
instance's entire snippet store, so promoting one agent proposed copying
staging's whole snippet library — unreleased drafts and other teams' snippets —
onto production. It now scans the agent's own documents for `{snippets.<name>}`,
which is what the export has always done; the one definition now lives in
`SnippetReferences` and both sides use it.

**The source address policy is configurable.** HTTPS-only and the
private-address refusal were compiled in, so two instances on one internal
network — staging and production as neighbouring services, the ordinary
self-hosted shape — could not sync at all, whatever the operator wanted, and no
setting existed to say otherwise. `eddi.backup.sync.require-https`,
`eddi.backup.sync.allow-private-targets` and an exact-origin
`eddi.backup.sync.allowed-sources` now express it, all defaulting to today's
strict behaviour, each independent of the others. A refused URL is a `400`
naming the setting that would allow it, not an unexplained `500`.

The Manager stops reporting a failed sync as a successful one. It read only "the
mutation resolved", and `executeSyncBatch` deliberately resolves on `500` too —
that status means every mapping failed and the body carries the reasons — so a
batch in which nothing was written rendered a green "Sync complete". It now reads
the outcome from the results, lists the per-resource reasons, and surfaces the
server's own message instead of `res.statusText`.

### How it is guarded now

[`AgentSyncIT`](../src/test/java/ai/labs/eddi/integration/AgentSyncIT.java)
runs the promotion an operator performs — create, update, update *again*, a
no-op, then deploy what was promoted — over real HTTP against the real stores.
The existing 768 backup unit tests could not have caught any of this: every one
of them mocks the stores, and every mocked target sat at version 1, the one
version the broken resolution answered correctly.

**Files:**
[`UpgradeExecutor.java`](../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`StructuralMatcher.java`](../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`RestImportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`RemoteApiResourceSource.java`](../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java),
[`SourceUrlValidator.java`](../src/main/java/ai/labs/eddi/backup/impl/SourceUrlValidator.java),
[`SnippetReferences.java`](../src/main/java/ai/labs/eddi/backup/impl/SnippetReferences.java),
[`DocumentDescriptorFilter.java`](../src/main/java/ai/labs/eddi/engine/runtime/rest/interceptors/DocumentDescriptorFilter.java),
[`sync-page.tsx`](../ui/manager/src/pages/sync-page.tsx),
[`backup.ts`](../ui/manager/src/lib/api/backup.ts),
[`agent-sync-guide.md`](agent-sync-guide.md)

---

## 🐛 fix(llm): `includeFirstAgentMessage` dropped the user's own first turn (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

The flag exists to strip EDDI's opening greeting so the history starts on a user turn —
`docs/langchain.md` advised setting it `false` for Anthropic, which used to reject an
assistant-first conversation. It was implemented as *remove the first message*, full stop.

For the agent shape it was written for — one with an `ai.labs.output` step producing a
greeting at `CONVERSATION_START` — those are the same thing, and it has worked for years.
An agent built with **no** output step (a group member that only answers) opens on the
**user's** turn. That turn was deleted, the history went out empty, and Anthropic answered:

```
invalid_request_error: messages: Field required
```

A flag whose only purpose is to satisfy Anthropic's first-message rule was, in that
configuration, what made Anthropic reject the request. Two things hid it: Ollama accepts an
empty message list, so a local smoke test passes on a config that cannot work against the
real provider; and the documented advice reads as universal while only covering the
standard agent shape.

The premise has also expired. The Anthropic Messages API reference no longer documents a
"first message must be the user's" rule, and an assistant-first history is accepted.

### What changed

- [`ConversationLogGenerator.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationLogGenerator.java)
  drops the first message only when its role is `assistant`. Behaviour is unchanged for
  every agent that has a greeting — for those the first message genuinely is the agent's —
  so the fix is strictly additive.
- [`ConversationHistoryBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java)
  carried a second copy of the same unconditional `removeFirst()` in
  `generateMessagesFromOutputs`. Both callers pass `skipSteps > 0` today, so that branch is
  currently unreachable and has no behavioural test of its own; it is written correctly
  rather than left as a trap for the next caller, and the comment says so.
- [`docs/langchain.md`](langchain.md): the blanket "set it `false` for Anthropic" is gone
  from the parameter table, the Anthropic example and the troubleshooting section, replaced
  by an entry for the `messages: Field required` failure itself.

**Tests:** four cases in `ConversationLogGeneratorTest` and four in
`ConversationHistoryBuilderTest` (including the token-aware path). The pre-existing
`excludeFirst` case asserted the bug — it built a **user**-first history and asserted the
message was removed — and was rewritten. Mutation-checked: restoring the unconditional
`removeFirst()` fails five of them.

### And now deprecated

The flag's only documented reason to exist has expired, so it is marked **deprecated**:
`LlmTask` logs a WARN naming the task the first time each configured task uses it, the
parameter table says not to use it in new configs, and `docs/langchain.md` gains a
*Deprecated parameters* section explaining what to do instead.

**Deprecated rather than removed**, deliberately. Agent behaviour lives in JSON stored in
MongoDB and imported from ZIPs — per `AGENTS.md`, the one backward-compatibility boundary
this codebase has. Silently ignoring a parameter an author set on purpose would be *worse*
than honouring it: an agent that genuinely wants its greeting withheld would start sending
it, with no diagnostic. The flag keeps working exactly as before.

Once per task, not once per turn: an LLM task runs on every message of every conversation,
and a per-turn WARN is a flood operators learn to filter out — which is the same as not
warning at all.

---

## 🔒 fix(security): a missing `role-claim-path` 403'd every admin, silently (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

`quarkus.oidc.roles.role-claim-path=realm_access/roles` is one line in
`application.properties`, and the released 6.4.0 image shipped without it. quarkus-oidc then
falls back to its default `groups` claim — which is also `eddi.workspaces.groups-claim`'s
default — so any account belonging to a Keycloak group had its EDDI roles **replaced** by its
group paths, and every `@RolesAllowed` endpoint answered **403 with an empty body and not one
log line**. The shipped realm puts the seeded `eddi` administrator in `/engineering`;
accounts in no group fell through to `realm_access` and worked. It presents as "this one
account is broken", not as a configuration gap, which is what made it cost hours to find.

### What changed

- [`AuthStartupGuard.java`](../src/main/java/ai/labs/eddi/engine/security/AuthStartupGuard.java)
  gains `rolesClaimDiagnostic()`, logged at ERROR on startup whenever OIDC is enabled and the
  roles claim path is unset, blank, or equal to the workspaces groups claim. It names
  `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH` and the 403 symptom. Pure and package-private, so the
  branch is assertable without a container. It runs before the launch-mode branch: the auth
  E2E tier runs in `TEST` and the diagnostic matters there too.
- [`OidcRolesClaimConfigTest.java`](../src/test/java/ai/labs/eddi/configs/OidcRolesClaimConfigTest.java)
  pins the property in the source tree — present, non-blank, `realm_access/roles`, and not
  equal to `eddi.workspaces.groups-claim`. Value-pinned rather than presence-only: an edit to
  `groups` would reintroduce exactly the failure and still pass a presence check.

The two halves are deliberate: the test stops it being dropped from the source, so the
runtime ERROR stays a warning about an operator override rather than about us.

---

## 🐛 fix(ui): `/manage/` answered 200 with an empty body (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

`GET /manage` served the app; `GET /manage/` served `content-length: 0`. The trailing slash
reaches `{path:.*}` as an **empty** path, `normalizeSlashPath` drops empty segments, and the
lookup became `getResourceAsStream("META-INF/resources/")` — a directory entry, which a
classloader answers with an open, empty stream rather than `null`. The missing-asset check
was satisfied, the `manage.html` fallback never ran, and the browser got nothing.

### What changed

[`RestManagerResource.java`](../src/main/java/ai/labs/eddi/ui/RestManagerResource.java):
a path that normalizes to nothing resolves straight to the SPA shell, and the two fallback
sites are one `serveManagerIndex()` helper.

**Tests:** `RestManagerResourceTest` asserts the **lookup sequence** rather than the body —
the unit run resolves off an exploded `target/classes`, where directory names behave
differently from jar entries, so what must hold on every layout is that the resource base is
never asked for at all. Covers `""`, `"/"`, `"//"`, `"./"` and `"/./"`. Mutation-checked.

---

## ✨ feat(groups): a rejected decision is `REJECTED`, not `FAILED` (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

Rejecting a group discussion's recommendation at a HITL gate set the conversation to
`FAILED`, and the Manager rendered a red "Failed" badge on it. Semantically defensible — the
run did not complete — but it reads as a system error rather than as a recorded human
decision, and in a product whose selling point is the human in the loop that conflation is
the wrong way round: the run did exactly what it was asked.

### What changed

`GroupConversationState.REJECTED`, set by
[`GroupHitlCoordinator`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java)
on a `REJECTED` verdict. It permits **exactly** what `FAILED` permitted — the label is the
only thing that changed:

- terminal in `GroupLifecycleOps.isTerminalState` and in the coordinator's
  `persistedTerminalOverride` and cancel guards;
- closeable — `closeGroupConversation`'s CAS chain and its refusal message now come from one
  `CLOSEABLE_STATES` list, so a new terminal state cannot be added to the chain and left out
  of the error, which is what three hand-written `if` blocks beside a hard-coded sentence
  invited;
- `availableActions` answers `["close"]`, as for `FAILED`;
- ephemeral agents are reclaimed immediately, as for `FAILED`.

Documents written before this carry `FAILED` for a rejection and are **left alone**: nothing
in them distinguishes the two, so a migration could only guess.

**Rolling downgrade is one-way.** Jackson serializes the enum by name, so a document written
by this version and read by an older EDDI fails `valueOf`. Upgrading a cluster is safe;
rolling back a node that has already served a rejection is not, until those documents age
out. The same goes for a Manager older than its backend: `GroupConversationState` is a
closed union there too.

The cadence reconciler in `TeamCadenceService` was the one place the new state had to be
routed by hand — it switches on the enum with a `default` arm rather than exhaustively, so
`REJECTED` fell through to "still running" and wedged the standing team's claim for
`eddi.groups.cadence.claim-ttl` (default 24 h). Caught in review; it is the only such switch
in `src/main`, and it now carries a comment saying so.

### Also

[`RestGroupConversation.setDecidedByFromIdentity`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java)
writes a blank principal name as `null` rather than `""`. The server has always overwritten
the client's claimed `decidedBy` from the authenticated principal, which is correct for an
audit ledger — but with `eddi.security.allow-unauthenticated=true` there is no principal, and
the ledger recorded `"decidedBy": ""`. The audit writer already renders a null decider as
`"unknown"`, so the unauthenticated case now lands on that same honest value.

---

## ✨ feat(groups): say at save time when debate roles turn a synthesis into a verdict (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

Giving members structural roles switches the SYNTHESIS phase onto the debate-judgment
prompt: the moderator is asked for `{"winner": …, "scores": …}` and its own synthesis
instruction is not used. A grant board whose members carried `role: PRO` and `role: CON` had
its chair return a scoring verdict instead of the recommendation its system prompt specified.
Correct for a debate-scoring exercise, wrong for anything else — and discoverable only by
running it, because nothing in the configuration says so.

### What changed

[`AgentGroupStore.debateVerdictSynthesisPhaseNames`](../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java)
reports the phases that will take the verdict path, logged at **INFO** on create and update.
INFO rather than WARN, deliberately: for a real debate this is the intended behaviour and the
note is its documentation, not a complaint.

It mirrors `GroupContextBuilder.isDebateJudgment` with the two substitutions a config-time
check has to make — an `ARGUE`/`REBUTTAL` *phase* before the synthesis in place of argument
entries on the transcript, and only `participants: "MODERATOR"` phases, which are the only
ones whose speaker is resolvable from configuration. A synthesis open to other participants
is left unreported rather than guessed at. Preset-expanded, or it would be inert for exactly
the style it matters most for. `moderatorlessPhaseNames` now shares the same `resolvedPhases`
helper so the two cannot drift.

**Tests:** eleven cases in `AgentGroupStoreTest`, one per condition — two sides, a chair that
is itself a debater, a moderator-less roster, an explicit `inputTemplate` (the documented
opt-out), arguments after the synthesis, and a ROUND_TABLE with debate roles.

---

## 🐛 fix(manager): "Show more" on a group's question could not be undone (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

The question header is pinned above a group discussion's transcript and clamps a long
brief to four lines. Expanding removed the clamp and nothing replaced it: the header is
`shrink-0` inside an `h-full` flex column, so a long brief grew it past the bottom of an
`overflow-hidden` pane and took the transcript, the composer **and its own "Show less"
button** with it. Nothing could be scrolled back to reach the toggle — the scroll container
is the transcript below, not the header — so the only way out was to reload the page.

### What changed

[`discussion-transcript.tsx`](../ui/manager/src/components/groups/discussion-transcript.tsx):
an expanded question is height-bounded (`max-h-[30vh]`) and scrolls in place, so the header
cannot outgrow the pane and the toggle stays where it was.

**Tests:** four cases in `discussion-transcript.test.tsx` — clamped by default, bounded and
self-scrolling once expanded, collapsible again, and untouched for a short question.

---

## 🐛 fix(manager): "New Discussion" was a no-op on a group with history (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

The handler cleared the selection; the auto-select effect, which lists `selectedConvId` in
its own dependencies, immediately put the newest conversation back. Worse than cosmetic:
attachments are accepted only on a **new** discussion (the backend rejects a continuation
carrying any), so the upload control is not rendered while a conversation is selected —
**a group that had ever held one discussion could never accept a file again**, with no
error to explain it.

### What changed

[`group-detail.tsx`](../ui/manager/src/pages/group-detail.tsx) records an explicit clear
in a ref the auto-select effect honours. It is set wherever the page deliberately empties
the selection (New Discussion, starting a stream, switching to a resumed stream) and
cleared when a conversation is deliberately selected again — so a plain load still
auto-selects. The Workforce board already avoided this, with a one-shot restore ref.

**Tests:** five cases in `group-detail-selection.test.tsx`, the load-bearing one being that
the attachment control comes back. Mutation-checked: removing the ref check fails four of
them. The MSW group-conversation fixture also gained the `availableActions` field the
backend always serializes — without it the Manager read `[]` and disabled the composer, so
a fixture-backed test could not tell "continue this discussion" from "this discussion is
over".

---

## ✨ feat(manager): the Workforce advisor thread can start over (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

Every other chat surface in the Manager offers one — `chat-panel` and `chat-drawer` ("New
Conversation"), `operator-chat`, and the Workforce board ("New"). The 1:1 advisor thread
did not, and its conversation is pinned in `localStorage` by (board, member): the thread it
resumes on every visit is the one it started the first time. Escaping a derailed thread
meant clearing site data.

### What changed

[`workforce-thread.tsx`](../ui/manager/src/pages/workforce/workforce-thread.tsx) gains a
"New conversation" control beside the details toggle. The "start fresh" half of the init
effect is now a callback both paths share, so the button and a first visit take the same
route. Registering the thread repoints the stored (board, member) entry at the new
conversation; the old one is left on the server, which is what "New Conversation" means
everywhere else in the Manager.

**Tests:** `workforce-thread-new-conversation.test.tsx` — the control exists, it starts a
conversation and repoints the store, it clears the transcript, the composer stays usable,
and a failed start leaves the thread on the old working conversation rather than a dead id.

---

## 🐛 fix(manager): the log SSE stream no longer opens on every page (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

`main.tsx` imported `session-log-store` for its side effect and that module connected on
load, so **every Manager tab held an open `/administration/logs/stream` SSE connection on
every page**, for the whole lifetime of the tab, whether or not anyone ever opened the Logs
page.

EDDI serves HTTP/1.1, where Chrome allows **six concurrent connections per origin for the
entire profile**, and a live group discussion opens another. Measured with the demo idle,
Chrome sat at exactly six, saturated. The symptom is pages hanging on skeleton loaders
forever, intermittently, while the server answers every request in 0.21 s with zero
variance and no errors — it looks exactly like a dead backend and is not.

Verified two ways: a *fresh* tab opened directly on `/manage/audit`, with no prior
navigation, issued `GET /administration/logs/stream`; and after 48 s on that page
`performance.getEntriesByType('resource')` carried no completed entry for it at all, while
an ordinary request on the same page reported `responseEnd: 547`.

### What changed

The stream is lazy and reference-counted. `connect()` returns an idempotent release (safe
as a React 19 double-invoked effect cleanup), a second consumer reuses the open socket, and
it closes when the last one leaves. `useLogStream` holds it only while mounted and
unfiltered; the filtered path already opened and closed its own, as does the debugger's
live log viewer. The bare import is gone from `main.tsx`.

What was lost is small: the buffer no longer accumulates from app boot. It never needed to
— the store seeds from `getRecentLogs` on open, so arriving at the Logs page still shows
history.

**Tests:** seven lifecycle cases in `session-log-store.test.ts` (including that importing
the module opens nothing, asserted through a new `isStreamOpen()`), three ownership cases
in `use-logs.test.tsx`, and a source-level guard that `main.tsx` never imports the module
again — the runtime tests cannot see that, and `main.tsx` is not importable from a test.

---

## ✨ feat(manager): a rejected discussion reads as a decision (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### What changed

The UI half of the backend's new `REJECTED` state. The transcript badge and the discussion
list carry a neutral "Rejected" rather than a red "Failed"; the composer says the
recommendation was rejected rather than "this discussion has ended"; the Workforce
analytics, history and session views label it too — TypeScript's exhaustive
`Record<GroupConversationState, …>` maps found every one of those reads, which is why the
state was added to the union first.

The SSE hook now honours the `state` the backend puts on `group_complete` instead of
hardcoding `COMPLETED`. `group_complete` is the terminal notification for every outcome,
and a rejection ends the run as `REJECTED` — rendering it as "Completed" for the seconds
before the persisted conversation loads says the opposite of what happened.

The page's settle effect — which switches the transcript from the live stream to the
persisted conversation and refreshes the sidebar — listed its states by hand, so `REJECTED`
matched nothing and a live rejection stranded the page: no conversation selected, the
composer inviting a *new* discussion, the Close action unreachable, and the sidebar saying
"Awaiting Approval" indefinitely (the conversation-list poll only runs while a discussion is
IN_PROGRESS/SYNTHESIZING). Caught in review. That list is now a named constant with the
reasoning attached, including why FAILED and CANCELLED are deliberately not in it.

`GROUP_CONVERSATION_STATES` is now a runtime array in `lib/api/groups.ts` with the union
derived from it, because several render sites key translations off the state name with a
template literal — ``t(`groups.state.${state}`)`` — which `npm run i18n:check` cannot see.
`groups.state.REJECTED` was missing from all eleven locales with every gate green; the
transcript export would have written the raw token into a downloaded file.

**Tests:** `discussion-rejected-state.test.tsx` — the label, the absence of the destructive
badge, the declined synthesis staying visible, and the live-stream label; a rejection driven
through the approve path in `group-detail-selection.test.tsx`; and a locale sweep over
`GROUP_CONVERSATION_STATES` in `i18n-quality.test.ts`.

---

## ✨ feat(manager): the editor says when a debate synthesis answers with a verdict (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### What changed

`debateVerdictSynthesisPhaseNames` in
[`lib/group-config.ts`](../ui/manager/src/lib/group-config.ts) mirrors the backend helper
(and through it `GroupContextBuilder.isDebateJudgment`), and the group config panel renders
an informational note naming the phases. Same mirror-and-surface shape as the existing
moderator-less and role-coverage warnings, which exist because the backend only ever wrote
those to its own log.

Styled as a note rather than a warning: for a real debate the verdict path is what was
asked for. The point is that nothing else in the configuration said so.

**Tests:** nine cases on the helper in `group-config.test.ts`, one per condition, plus three
on the panel.

---

## 🐛 fix(manager): a plain Save no longer claims a change is live (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

The config editor offers two actions: `handleSave`, which cascades resource → workflow →
agent, and `handleSaveAndDeploy`, which cascades and then deploys, polling for up to 30 s.
The first reported "Saved successfully" with nothing to say the running agent was still
serving the previous version.

Measured on an eligibility gate (no model, sub-second, so the effect is unambiguous) with
the ceiling lowered from 150,000 to 50,000 and a case of 85,000: cascade **+ deploy** gave
`gate_fail_over_cap`, cascade **alone** gave `gate_pass` — no change at all. Resource,
workflow and agent versions had advanced to v4/v5 while the deployed version stayed at v3.

### What changed

[`resource-detail.tsx`](../ui/manager/src/pages/resource-detail.tsx): the cascade path's
toast is now "Saved — not yet live", explains that the running agent still serves the
deployed version, and carries a **Deploy** action that deploys the agent version the
cascade just produced (not the stale one the URL carried). A failed deploy surfaces its
error rather than doing nothing.

**Tests:** `resource-detail-save-not-live.test.tsx` — the wording, the explanation, the
version the action deploys, and the failure path.

---

## 🛠️ chore(skills): a `ship-pr` skill that carries a PR from push to every review comment resolved (2026-09-21)

**Repo:** EDDI (`chore/ship-pr-skill`)

### Why

Shipping a PR here has a long tail of repo-specific traps, and every session rediscovers them.
The two that cost the most: the `CodeRabbit` check reads **`pass` while the description column
says `Review rate limited`** — so "green checks, zero unresolved threads"
can mean *nothing was reviewed*; and a targeted `-Dtest='A,B'` **silently drops a missing class
and exits 0**, because surefire's `failIfNoSpecifiedTests` only trips when zero tests ran.

### What changed

- `.claude/skills/ship-pr/SKILL.md` — the workflow: pre-push gates, a PR-description shape built
  from the diff, what actually gates a merge (branch protection requires only `Build & Test` and
  `CodeQL Analysis`; everything else is advisory), and a review loop that covers all three places
  feedback lands.
- `.claude/skills/ship-pr/pr-threads.sh` — audits review threads via GraphQL, prints the node id
  the reply/resolve mutations take, and drops CodeRabbit's collapsed `<details>` block so the
  preview shows the finding rather than `🏁 Script executed:`.
- `.gitignore` — un-ignore `.claude/skills/` only; `settings.local.json`, `workflows/` and
  `worktrees/` stay ignored. Without this the skill files never land.

### Decisions

- **Tracked, not personal.** The content is repo-specific team knowledge, mirroring the precedent
  in `ui/manager/.claude/skills/`.
- **Nitpicks are handled separately from threads.** CodeRabbit puts nitpicks, duplicates and
  outside-diff-range findings in the *review body*, where they have no thread and cannot be
  resolved — and some are 🟠 Major. They get one disposition comment instead.
- **Only bot threads get resolved.** A human closes their own, whether the finding was fixed or
  argued down.

Sibling skills for `gnowbe-frontend` and `gnowbe-api-2` ship in those repos.

---

## RAG docs: document the workflow step that actually binds a knowledge base (2026-09-21)

**Repo:** EDDI · **Branch:** `docs/rag-workflow-step-binding`

A user reported that `product-docs` retrieval "does not work" on their deployment and concluded the
`RagContextProvider` wiring was missing from the build. The wiring is present and has been since
Phase 8c — `LlmTask` calls `retrieveContext` and appends `## Relevant Context:`. What was missing was
the third side of the binding, which the docs never showed.

### The failure the docs were producing

A knowledge base reaches an agent through three configurations, not two:

1. the `RagConfiguration` at `/ragstore/rags/{id}`,
2. an `eddi://ai.labs.rag` **step in the agent's workflow**,
3. `knowledgeBases` / `enableWorkflowRag` on the LLM task.

`RagContextProvider` matches `knowledgeBases[].name` against configs discovered from the **workflow
document** (`WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.rag", …)`). With no such step
`ragSteps.isEmpty()` is true and retrieval returns at a `LOGGER.debug` line, before the trace entry,
before the store build, before any INFO log. Every symptom in the report falls out of that one early
return: no `## Relevant Context:` block, no `rag:trace:*`/`rag:context:*`, and no `RagContextProvider`
or `EmbeddingStoreFactory` lines while other tool providers logged on every turn.

A second cause presents identically and the troubleshooting section now says so: when the workflow
*does* bind a step but no `knowledgeBases[].name` matches its KB name, every step is `continue`d past,
`traceEntries` stays empty so no trace is stored, and `allResults.isEmpty()` returns null just the
same. Only the `DEBUG` line distinguishes them — it is logged for the missing-step case alone.

A third cause sits even earlier and was missed in the first pass at this section: `retrieveContext`
checks the *task* before it ever looks at the workflow — `if (!hasExplicitRefs && !useWorkflowDiscovery)
return null;` at the top of the method. A task with an empty `knowledgeBases` and no
`enableWorkflowRag: true` returns here, before `WorkflowTraversal.discoverConfigs` runs at all, so it
produces no discovery, no trace, no store build, no INFO log — and, unlike the missing-step cause, no
`DEBUG` line either, because that line lives inside the `ragSteps.isEmpty()` branch this return never
reaches. The troubleshooting table now leads with checking the task's own RAG settings before touching
the workflow.

`rag.md` mentioned the requirement only in a subordinate clause ("Each reference names a KB from the
workflow") and in a Status bullet at the bottom of the page. Its setup path showed the KB config and
the LLM task and nothing else — so a reader who followed it end to end built exactly the broken
two-sided configuration that was reported. That is a documentation defect, not a user error.

### `docs/rag.md`

- **Configuration** now opens with the three-sided binding as a table, and names configuring 1 and 3
  without 2 as the most common failure — explicitly including that it produces no context, no trace,
  no error and no log above `DEBUG`.
- **New `### 2. Workflow Step`** section with the workflow JSON, the `{ragId}.rag.json` ZIP name, why
  `RagTask.execute()` is a no-op while `configure()` resolves the KB, and the
  `GET /extensionstore/extensions` check for builds where `ai.labs.rag` is not registered.
- Existing sections renumbered to `1.`/`3.` to match. No inbound anchor links existed.
- **Fixed a dangling sentence**: "Three of them choose what is retrieved:" was followed by nothing.
  The three are now named.
- Options 1 and 2 state that the name matches the KB's `name` (not its id) and that an unmatched name
  is skipped silently; Option 3 states that `httpCallRag` calls an *external* API and cannot query an
  EDDI knowledge base.
- **REST API**: states outright that no `/query`, `/search` or `/retrieve` endpoint exists and that
  those return `404`. Users were trying `httpCallRag` against their own KB as a workaround and
  hitting 404s with nothing telling them the endpoint was never meant to exist.
- **Document Ingestion**: warns off the `kbId` query parameter, found while reviewing this change.
  `RagContextProvider` keys the embedding store on `ragConfig.getName()` and cannot be pointed
  elsewhere, but `RestRagIngestion` lets the caller override that key (`effectiveKbId`, favouring
  the `kbId` param over the name). Any `kbId` other than the KB's exact `name` therefore ingests
  into a store nothing reads — `202`, status `completed`, documents genuinely embedded and stored,
  retrieval empty for ever. Ingestion *sources* are unaffected: `IngestionPipeline` keys on
  `knowledgeBase.getName()`, and `IngestionRetrievalRoundTripTest` pins that round trip after an
  earlier draft shipped exactly this divergence on the source path.
- **Vector Stores**: `in-memory` is no longer described only as "ephemeral, for dev/test only". The
  cached object *is* the data, and `EmbeddingStoreFactory` holds it in a Caffeine cache bounded at 50
  stores with a 30-minute `expireAfterAccess` and a full invalidation on any secret or global-variable
  change — so an in-memory KB empties itself after 30 idle minutes, on restart, and on credential
  rotation, then returns no context rather than an error.
- **New `## Troubleshooting`** section: an ordered seven-step check for the silent-no-context case
  (task-level RAG settings checked first, ahead of the workflow-binding checks), and the
  `quarkus.log.category` line that makes the missing-step early return visible — called out as not
  covering the task-level or unmatched-name causes, which log nothing at any level.
- **Status**: the workflow-step bullet said "Options 1 and 2 below" while they are above it.

### `docs/langchain.md`

The task-parameter reference documented `maxRagContextChars` and nothing else about RAG — the one
knob that merely *bounds* a feature the page never introduced. Added `knowledgeBases`,
`enableWorkflowRag`, `ragDefaults` and `httpCallRag` under a **Retrieval (RAG)** group whose header
carries the workflow-step requirement and links to `rag.md`.

### Not changed

The engine. No defect was found on the retrieval path itself. Three gaps are recorded here rather
than fixed, each arguably worth its own issue:

1. **`kbId` on `/ingest` can write where nothing reads** (above). The parameter has no correct
   non-default value, because retrieval cannot be pointed at a custom key — so the fix is probably
   to reject a `kbId` that does not equal the KB's `name`, or to drop the parameter, rather than to
   document it. Documented here because a doc change cannot make a `202` mean something else.
2. **No retrieval REST endpoint** for a knowledge base, so there is no supported way to test that
   ingestion worked without running a conversation — which is what sent the reporting user to
   `httpCallRag` and a wall of 404s.
3. **`ai.labs.rag` registration is in no release tag.** It is on `main`; the newest tag is `6.4.0`.
   Deployments on `6.4.0` or earlier cannot wire up vector RAG at all, whatever the docs now say.

---

## 📊 feat(llm): per-call LLM telemetry on every provider and both execution paths (2026-09-21)

**Repo:** EDDI (`feat/llm-observability`)

An agent that names a single model — almost every agent — produced **no LLM telemetry at all**:
no latency timer, no token counter, no error counter, and no span below `eddi.pipeline.task`. A
turn that spent eleven seconds waiting on a provider was indistinguishable from one that spent
eleven seconds in EDDI's own code. The `eddi.llm.cascade.*` meters that did exist live in
`CascadingModelExecutor`, which `LlmTask` reaches solely under `if (cascadeActive)`.

### Why there was nowhere to put it

`ChatModel.chat(ChatRequest)` delegates to `chat(ChatRequest, ChatRequestOptions)`, and *that*
is where the interface merges `defaultRequestParameters()` and fires
`onRequest`/`onResponse`/`onError` from `listeners()` around `doChat`. It is the only per-call
hook langchain4j offers, and it covers every provider because the dispatch lives on the
interface rather than in each binding.

`ObservableChatModel` overrode `chat(ChatRequest)`, which bypassed that default entirely — so
the decorator had no listener dispatch of its own. And `wrapIfNeeded` returned the **bare**
model unless `timeout`/`logRequests`/`logResponses` was configured, which is the default, so
most deployments had no decorator at all.

### What changed

- **`ObservableChatModel`** now overrides `doChat` and delegates `defaultRequestParameters()`,
  `provider()` and `supportedCapabilities()`, so the inherited `chat` behaves as the provider's
  own would and fires listeners.
- **Both decorators always wrap.** Returning the bare model is what left the default path with
  nowhere to attach a listener.
- **`LlmTelemetryListener`** (new) emits `eddi.llm.request.duration`, `eddi.llm.tokens` and
  `eddi.llm.request.errors`, plus a `gen_ai.client.inference` span.

### Three design decisions that are not the obvious ones

- **`doChat` forwards to `delegate.chat(...)`, not `delegate.doChat(...)`.** A `ChatModel` may
  implement either, and `JlamaChatModel` implements `chat` — forwarding to `doChat` would throw
  `"Not implemented"` on every Jlama turn, i.e. on the provider the previous PR just fixed.
- **`listeners()` returns EDDI's listener only, never the delegate's.** Because the delegate
  re-enters its own `chat`, it dispatches its own listeners; combining the lists would fire
  every provider-registered listener twice. Mutation-tested: the combining version produces
  `[request, request, error, error]`.
- **`ObservableStreamingChatModel` moved from overriding both `chat` overloads to overriding
  `doChat`.** This was a live defect in an earlier revision of this branch: the two-argument
  `chat(request, options, handler)` is where the streaming interface reads `listeners()`, so
  overriding it meant EDDI's listener never fired on a streaming turn — silently, while the
  javadoc claimed otherwise. Nothing caught it because every streaming test passed `null` for
  the listener and asserted only on tokens.

### Also fixed

The timeout path boxed the provider's exception in a bare `RuntimeException`. Harmless before;
now that the error tag and `error.type` are derived from the exception class, it made every
failure on a timeout-configured agent read as `RuntimeException`. The cause is rethrown
directly, and the timeout itself gets a named `ChatTimeoutException`.

`LlmTelemetryListener.guard` logs at WARN, not DEBUG. langchain4j already contains a throwing
listener (`ChatModelListenerUtils` wraps each in `try/catch` and logs WARN), so catching at
DEBUG would only have downgraded upstream's message — meaning a misconfigured `MeterRegistry`
would lose every LLM meter with nothing in the log at default levels.

### Semantic conventions

The span uses the **current** names — `gen_ai.provider.name` (renamed from `gen_ai.system` in
semconv v1.37.0) and `gen_ai.usage.input_tokens`/`output_tokens`. That namespace is still
Development-status and moved to its own repository in v1.42.0 precisely so it can keep changing,
so the span also carries `eddi.semconv.schema_version` recording the revision the names came
from. Micrometer meter names stay EDDI-owned (`eddi.llm.*`) so an upstream rename cannot break a
dashboard.

### Tests

`ObservableChatModelDecoratorTest` (9, new) uses a delegate shaped like a real binding rather
than a mock stubbing `chat`, and pins the properties the old tests could not see: a `chat`-only
delegate still works, each listener fires exactly once, the decorator carries only EDDI's
listener, and the legacy `chat(List<ChatMessage>)` route — the default non-JSON path through
`LegacyChatExecutor` — is observed too. `LlmTelemetryListenerTest` (8, new) grades the meters,
including that absent token usage records *nothing* rather than a zero. Two new streaming tests
assert a listener actually fires; mutation-checking the old override shape turns them red with
`[]` against `[request, response]`.

`docs/metrics.md` and the full-metrics Grafana dashboard gained the three meters —
`MetricsDashboardCoverageTest` enforces both.

### Follow-up: the p95 panel had no series to query

Raised in review of [#809](https://github.com/labsai/EDDI/pull/809). A Micrometer timer
publishes `_count`, `_sum` and `_max` and no buckets at all, so
`eddi_llm_request_duration_seconds_bucket` — the series panel `id: 169` runs
`histogram_quantile(0.95, ...)` over — was never exported. The panel would have rendered
empty forever, which on a latency chart reads as "no LLM traffic" rather than "this metric
does not exist".

`eddi.llm.request.duration` is now registered with `publishPercentileHistogram()`, the same
way `eddi.pipeline.task.duration` in `LifecycleManager` already is — which is also why the
pipeline p95 panels next to it do work. The cost is one series per bucket per
`provider`/`model`/`outcome`, and the tag set is bounded the same way: providers are a fixed
list, `outcome` is success or error, and model names come from configuration rather than from
user input. If a deployment does find that too much, the dashboard-side alternative is to plot
`_sum / _count` as a mean and drop the line; that is recorded at the call site.

`LlmTelemetryListenerTest.durationTimerPublishesHistogramBuckets` asserts against a real
`PrometheusMeterRegistry.scrape()` rather than a `takeSnapshot().histogramCounts()`, because
the scrape text is literally what the dashboard queries — a snapshot assertion would pass on a
registry that never exports the buckets. Mutation-checked: removing
`publishPercentileHistogram()` turns exactly that test red.

---

## 📄 feat(rag): ingest uploaded PDF, Word, Excel, PowerPoint and text files (2026-09-21)

**Repo:** EDDI (`feat/rag-file-upload`)

### What it adds

A second kind of ingestion source. `type: "upload"` holds files EDDI stores on the knowledge base's
behalf; running the source extracts their text and embeds it, through the same pipeline, state store
and reconciliation a crawl uses. In the Manager, a source of that type shows a drag-and-drop zone
instead of the crawl settings, with per-file progress and the list of what the source holds.

Formats: PDF (PDFBox), Word, Excel and PowerPoint (`.docx` / `.xlsx` / `.pptx`), plain text, Markdown,
JSON, XML, YAML, CSV, TSV and HTML. Excel becomes one Markdown table per sheet, PowerPoint one section
per slide, Word keeps its headings — tabular and sectioned content survives chunking only if it keeps
the header or heading a retrieved passage would otherwise have lost.

### Design decisions

**The files are kept, not just their embeddings.** That is what makes this a *source* rather than a
one-way import: re-running after a model or chunk-size change re-ingests from what is stored, a purge
stays recoverable, and deleting a file removes its vectors through the ordinary reconciliation.
Embedding on upload and keeping nothing would make each of those "ask the operator to upload two
hundred files again".

**No Apache POI.** It reads these formats and much more, at seven extra jars and ~14.5 MB, built on
reflection and with a long history of parser CVEs — measured against the constraint that EDDI's
dependencies stay small. For text out of a handful of known parts, the JDK's own `java.util.zip` plus
StAX is the smaller surface and the one whose limits can be stated exactly. PDFBox was already a
dependency (3.0.8, current).

**Every bound is explicit, because the file is not the operator's data.** 100,000 characters per
document (`settings.maxContentLength`, shared with the crawl), 500 parts, 5,000 rows × 64 columns per
sheet, 64 MB decompressed per archive — counted across every entry the reader walks over, since moving
to the next ZIP entry inflates the rest of the current one and an entry nobody wants is otherwise the
cheapest place to hide a bomb. DTDs and external entities off (billion-laughs); an archive naming the
same part twice refused outright, since two entries under one name let two readers disagree about the
contents.

**Content decides the format, not the name.** `.docx`, `.xlsx` and `.pptx` are all ZIP archives, so
neither the file name nor the browser's MIME type distinguishes them — a spreadsheet saved under a
`.docx` name would have gone to the Word extractor and been refused as corrupt. The extension is
consulted only for text formats, which carry no signature. A legacy `.doc`/`.xls`/`.ppt` is named as
such, with the fix, rather than reported as unreadable.

**A file's identity is its name.** Re-uploading `handbook.pdf` replaces it — blob, ingestion-state row
and vectors all key on an id derived from the name — so the corrected version supersedes the old one
everywhere at once. A generated id would leave last quarter's handbook retrievable beside this
quarter's with nothing to say which is current.

**Change detection uses the file's bytes, not its extracted text.** An unchanged 20 MB manual costs one
metadata query per run rather than a download and a full parse, and improving an extractor does not
silently re-embed an entire knowledge base.

**One HTTP request per file, not one per batch.** A 200 MB batch that fails three quarters of the way
through would otherwise lose what had already arrived with nothing to say which files those were. Each
file gets its own progress bar (`XMLHttpRequest`, since `fetch` cannot report upload progress) and its
own error, and the server's sentence — "This PDF is encrypted" — is what the operator sees.

**Deleting a file removes its vectors immediately**, not at the next run. A source with no cron has no
next run, so deferring it would mean an operator is told a document is gone while agents keep answering
from it. Where the vector store cannot delete by metadata, the response says so rather than reporting a
clean success.

**Removing an upload source takes its content with it.** Dropping it from `sources[]`, changing its
`type`, or deleting the knowledge base removes the files *and* the vectors they produced. The first
draft deleted only the files, which left every chunk retrievable and unreachable: no endpoint lists
them, because the source they belong to is gone.

**Deleting a file takes the source's run claim.** Checking for a run first is not enough — one that
starts between the check and the delete re-embeds the file and clears the tombstone the delete wrote.

### Bugs found and fixed while testing

**An upload source could never delete its last documents.** `reconcileDeletions` refused to conclude
anything when a run produced no usable document and learned nothing definitive — a guard written for
crawls, where "nothing came back" usually means the site was unreachable. An upload source that lists
an empty store has learned something definitive: the operator deleted the files. The guard moved into
the crawl branch (`learnedSomething`), where it belongs; the pre-existing crawl tests still pin it.

**A reordered PowerPoint deck came back in creation order.** `r:id` and `id` share a local name on
`<p:sldId>`, so reading the attribute by local name returned the slide's own number instead of the
relationship — the index resolved to nothing and the fallback (part numbering) ran. Part numbers
survive a reorder, so the deck read correctly right up until somebody moved a slide.

**An encrypted PDF was reported as corrupt.** PDFBox refuses one of those with
`InvalidPasswordException` rather than by opening it and answering `isEncrypted()`, so the only branch
that mentioned a password never ran, and the operator was told their working file was broken.

**A 25 MB file — the default limit — could not be uploaded at all.** `quarkus.http.limits.max-body-size`
was 25M, so the request was refused with a bare 413 before the code that knows what the limit is could
say anything. Raised to 60M, with the configurable ceiling held at 50 MB and both ends commented.

### Files

**Backend — extraction** (`src/main/java/ai/labs/eddi/modules/ingestion/extract/`): `DocumentExtractors`
(registry + content-based type resolution), `DocumentTextExtractor`, `ExtractionLimits`,
`PdfTextExtractor`, `WordTextExtractor`, `ExcelTextExtractor`, `PowerPointTextExtractor`,
`PlainTextExtractor`, `CsvTextExtractor`, `HtmlDocumentExtractor`, `OpenXmlPackage` (bounded ZIP+StAX),
`MarkdownTable`, `Extraction`, `UnreadableDocumentException`.

**Backend — storage** (`modules/ingestion/files/`): `IIngestedFileStore`, `IngestedFileIds`,
`IngestedFileService`; `MongoIngestedFileStore` (GridFS), `PostgresIngestedFileStore` (`bytea`, upsert
on `(source_key, file_id)`), wired in `DataStoreProducers`.

**Backend — pipeline and API**: `IngestionPipeline` (upload branch, `SourceRun`, `forgetDocument`,
`learnedSomething`), `IngestionSource` (`TYPE_UPLOAD`, `UploadSource`), `RagSourceIngestionService`
(files deleted with their source), `IRestRagIngestion` / `RestRagIngestion` (three endpoints),
`ContentHashes.sha256Bytes`.

**Manager**: `ingestion-files-panel.tsx` (drop zone, per-file progress, file list, delete),
`ingestion-sources-panel.tsx` (Website/Files chooser, conditional fields), `lib/api/ingestion-sources.ts`,
`hooks/use-ingestion-sources.ts`, 22 new i18n keys across all 11 locales, MSW handlers, refreshed
`openapi-operations.json`.

**Tests**: 40 extraction cases (zip bombs in both a wanted and a skipped entry, billion-laughs, a
duplicate part, an encrypted PDF, a scan with no text layer, a misnamed spreadsheet, a legacy Office
file, a reordered deck, UTF-16, and text whose first bytes look like an image), a 10-case
`IngestedFileStoreContract` run against in-memory, MongoDB and PostgreSQL, 8 `IngestedFileIds` cases,
16 `IngestedFileService` cases, 13 pipeline cases for the upload path, 6 source-removal cases, 14 REST
cases, and 10 Manager cases.

Three of them are the ones that matter: a real `.docx` is stored, read by the real extractor, split by
the real chunker and answers a real retrieval — and stops answering once it is deleted. Every unit test
on the way there can pass while that one fails, which is exactly what happened to the draft this
feature builds on: ingestion and retrieval keyed on different names, and nothing noticed, because no
test ever performed a retrieval after an ingest.

**Docs**: `docs/rag.md` — the two source types, the upload block, what can be read, every limit and why,
the file endpoints, and what a purge, a source removal and a ZIP export each do to stored files.

---

## 🐛 fix(llm): a configuration reference in an LLM parameter no longer breaks its templating (2026-09-21)

**Repo:** EDDI · **Branch:** `fix/llm-config-reference-templating`

`LlmTask` runs the Qute engine over every LLM parameter before the model is built, and escaped
`${vault:...}` mentions so a prompt could document the syntax. The other three reference namespaces —
`vars`, `connection`, `caller` — were not escaped, although they are resolved the same way: AFTER
templating, by `ChatModelRegistry`/`SecretResolver`, with deliberately no Qute namespace resolver.

So a parameter carrying one of them threw on every turn. Observed on a live deployment, where an agent
sets `"modelName": "${vars:gemini-model}"` so one global variable drives every agent's model:

```
ERROR Template processing failed for LLM parameter 'modelName':
      No namespace resolver found for [vars] in expression {vars:gemini-model}
```

The turn still worked — the catch keeps the parameter's RAW value, which is exactly right when the value
is *only* a reference, and the registry resolves it afterwards. Two things were wrong anyway:

- an ERROR per turn per such parameter, which buries real errors in the log;
- a reference sitting **beside** a real expression abandons the whole render, so `{properties.x}` next to
  it reaches the model as literal text. Silent, and wrong.

`escapeVaultMentions` becomes `escapeConfigReferenceMentions` and now covers `vault`, the legacy
`eddivault`, `vars`, `connection` and `caller`. Httpcall templating is untouched and still fails loudly
there, as the `CallerNamespaceResolver` security decision requires.

**Tests:** `LlmTaskVaultMentionTest` — one case per namespace (each asserting the un-escaped form still
throws, so the guard cannot go vacuous), the legacy prefix, and the reference-beside-expression case.
Narrowing the pattern back to `vault` fails 5 of them with the exact production message.

The escape has two halves that can drift: the regex, and a cheap `contains` pre-check that decides whether
the regex runs at all. The pre-check list deliberately omits the legacy `eddivault:`, which is only covered
because `"eddivault:"` contains `"vault:"` — correct, but invisible, and a namespace added to the regex
alone would silently keep crashing. `preCheckCoversEveryNamespaceInThePattern` derives the namespaces from
the pattern instead of restating them and asserts each round-trips, so the halves cannot diverge unnoticed.
Mutation-checked both ways: dropping `"vault:"` from the pre-check fails 5 tests; adding a namespace to the
pattern alone fails that guard and only that guard.

---

## 🔒 fix(security): sanitize the CWE-117 log sinks PR #799 left uncovered (2026-09-21)

**Repo:** EDDI (`fix/log-injection-log-admin-conversation-store-nats`)

Six open `java/log-injection` alerts on `main` — #105, #106, #112, #113, #114 and #121, across five log
statements in three files — that [PR #799](https://github.com/labsai/EDDI/pull/799) did not touch: it closed the 14 alerts in
`RestAgentAdministration` and `AgentFactory` only. Like those, these surfaced while triaging the
community logger-rename PRs #558 and #561, were correctly judged pre-existing and out of scope there,
and were then tracked by nothing at all. Same one-line fix, same test shape.

### What changed

- **[`RestLogAdmin`](../src/main/java/ai/labs/eddi/engine/internal/RestLogAdmin.java)** (#105, #106)
  — the `streamLogs` "SSE log stream started" DEBUG quoted `agentId` and `level` raw. Both are
  `@QueryParam`s on `GET /logs/stream`, so both arrive unvalidated. The irony is specific to this
  endpoint: it *is* the log viewer, so the forged line is served straight back to whoever is tailing
  the stream. The file gained the static `sanitize` import it did not have. `listenerId` on the same
  line is left alone deliberately — `BoundedLogStore` generates it, no caller supplies it, and CodeQL
  did not flag it.
- **[`RestConversationStore`](../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java)**
  (#112, #113, #114) — the descriptor loop's "Skipping descriptor due to error" DEBUG, and both
  `deleteAttachmentsForConversation` lines ("Deleted %d attachments" and "Failed to delete
  attachments"). `conversationId` is the path parameter on `DELETE
  /conversationstore/conversations/{id}`; the exception message is not the developer's text either,
  since a store routinely quotes back the value it was handed. The file already static-imports
  `sanitize` and applies it on the neighbouring permanent-delete, soft-delete and not-found lines, so
  these three were missed rather than deliberately left.
- **[`NatsConversationCoordinator`](../src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java)**
  (#121) — `publishAndExecute`'s "Published to NATS subject" DEBUG. This one logs the *subject*, not
  the conversation id, which is why the pass that sanitized the rest of the file went past it.

### Tests

Three new classes in the shape `RestAgentAdministrationLogInjectionTest` established —
`captureLogsOf(<Class>.class, …)` plus `assertNoForgedRecordBoundary(…)` against
`LogCaptureSupport.FORGED_RECORD`, attaching by logger category rather than by field name, so PR
#558's renames cannot break them. Seven tests: 2 + 3 + 2.

Each also asserts the benign half of the value still reaches the log, so a "fix" that dropped the
argument instead of sanitizing it would fail. All seven of the newly wrapped arguments were
mutation-checked one at a time — revert exactly that call, run the class, require the named test to
fail. **7 mutations, 7 killed, 0 survived.** The driver lived in the session scratchpad and is not
committed.

### Decisions

- **The NATS fix goes at the log call — and, after review, in `sanitizeSubject` as well.** `subject`
  is `SUBJECT_PREFIX + sanitizeSubject(conversationId)`, and `sanitizeSubject` is not a log sanitizer
  despite the name: it replaces `.` and space because a NATS subject token may not contain them. The
  log call is fixed the way the file already does it one method down, where `routeToDeadLetter` logs
  `sanitize(deadLetterSubject)`.

  This entry first argued CR and LF should be left in `sanitizeSubject`, because widening it would
  move the subject namespace every deployment already publishes and consumes under. **That was
  wrong, and the review caught it.** The argument holds for `.` and space, which remap ids that
  work. It does not hold for CR, LF and tab: `Validator.validateSubjectTerm` in the NATS client
  refuses a subject containing any of them, so an id carrying one produced a subject the broker
  never accepted — there was no namespace to move, and every id that works maps where it always did.

  Worse, that rejection is an `IllegalArgumentException`, which is *unchecked*. The publish is
  wrapped in `catch (IOException | JetStreamApiException)`, whose whole purpose is to degrade to
  local execution when NATS is unavailable; an invalid subject escapes it instead. Not reachable
  today — `ConversationService.say` loads the conversation from the store before it ever reaches
  the coordinator, so an id that is not store-issued fails first — but it cost nothing to close.

  Both sanitizers stay. They answer different questions — what the broker will accept, and what
  cannot end a log record — and several lines in the file log the conversation id directly, building
  no subject to be cleaned. The test that pinned "CR/LF survives `sanitizeSubject`" is inverted: it
  now asserts the method produces a token the broker accepts, and a second test pins that the publish
  line still logs through `sanitize`.
- **Behavioural tests rather than entries in `SanitizedLogSinksTest`.** That source guard exists for
  the ~38 group-conversation sinks that need a whole discussion to reach, where building one to
  observe a single WARN tests the harness more than the fix. All four sinks here are reachable from a
  public method with mocks, so they get real tests; the guard's hard count of 38 stays honest.
- **A frozen clock in the `RestLogAdmin` test, not a mocked one.** `streamLogs` spawns a cleanup
  virtual thread that logs its own "listener removed" line. A frozen clock parks it in its 2-second
  sleep — it can neither reach the heartbeat branch nor fall out of the max-lifetime loop — so the
  only line inside the capture window is the one under test. Without that the cleanup line could race
  in and satisfy the assertion in place of the real subject, and the per-site mutation check would
  stop localising. `@AfterEach` closes the sink so the thread finishes rather than outliving the test.
- **A 24-character hex conversation id in the `RestConversationStore` test.** `extractResourceId`
  resolves an id only out of a hex path segment; a semantic name yields a null id and the descriptor
  is skipped at the top of the loop, well before the line under test — a test that would have passed
  without ever reaching it.

### Still open

`main` carries **85** open `java/log-injection` alerts across 31 files, the largest clusters being
`RestScheduleStore` (9), `RestUserMemoryStore` (8) and `VaultSecretProvider` (6). Whether to keep
closing them file by file or take one broader pass is a maintainer call and was deliberately not made
here. Two adjacent sinks in `RestConversationStore` were also noticed and left, because CodeQL has not
flagged them and this branch is scoped to what it did flag: the HITL-cleanup WARN at the top of
`deleteConversationLog` sanitizes its `conversationId` but not its `e.getMessage()`, and
`populateDataToDescriptor`'s "Memory snapshot not found" WARN quotes `resourceId.getId()` raw.

---

## 🎯 fix(rag): stop embedding queries as documents (2026-09-21)

**Repo:** EDDI (`fix/rag-embedding-input-type`)

Every Gemini knowledge base has been embedding its **queries** as though they were
documents, quietly costing retrieval quality. Nothing failed; recall was just worse than
the model is capable of.

### The mechanism

Some embedding models are asymmetric — the same text produces a different vector
depending on which side of the search it is on. `GoogleAiEmbeddingModel.toTaskType` maps a
per-request `EmbeddingInputType.QUERY` to `RETRIEVAL_QUERY` and `DOCUMENT` to
`RETRIEVAL_DOCUMENT`, and **when no input type is given falls back to whatever
`taskType` the model was built with**.

`RagIngestionService` and `RagContextProvider` both called
`embeddingModelFactory.getOrCreate(ragConfig)` with the same configuration, so they hit
the same cache entry and shared one instance. `EmbeddingModelFactory` defaults Gemini's
`taskType` to `RETRIEVAL_DOCUMENT`. Retrieval therefore embedded the search key as a
document.

### What changed

- `getOrCreate` now takes the role as a **required** parameter. Required rather than an
  optional overload deliberately: a caller cannot forget it, and the compiler names every
  site that has to choose. There are three: retrieval, and the two ingestion
  paths.
- The role is part of the cache key — to an asymmetric provider the two roles are two
  different models, and sharing one entry is the defect.
- `InputTypedEmbeddingModel` (new) attaches the role via `defaultRequestParameters()`,
  which is what carries it through the `embed(String)` convenience overload that
  `EmbeddingStoreContentRetriever` uses. There is no way to pass a per-call parameter
  through that retriever, so the role has to travel with the instance.

### Why the capability check is not optional

`EmbeddingModel.embed` validates against `supportedParameters()` and throws
`UnsupportedFeatureException` for anything unsupported **rather than ignoring it**.
Verified against the resolved jars: only **2 of the 8** providers EDDI builds declare
`INPUT_TYPE` — `gemini` and `cohere`. Setting it unconditionally would have turned every
OpenAI, Azure, Ollama, Bedrock, Vertex and Mistral knowledge base into a hard failure.
Those six are handed the provider's model unchanged.

The check reads the live model's own `supportedParameters()` rather than a table of
provider names, so it cannot go stale on a dependency bump. Mutation-tested: removing it
produces exactly `UnsupportedFeatureException: ... does not support the following
per-call parameter(s): inputType`.

### No re-ingestion needed

Stored vectors were always correct — ingestion's implicit `RETRIEVAL_DOCUMENT` was the
right value for documents. Only the query side was wrong, so existing knowledge bases
improve on the next query with no migration.

### Tests

`InputTypedEmbeddingModelTest` (9, new) covers both roles reaching the provider, the
unsupported-provider passthrough, delegation, and that attaching a role does not drop the
delegate's `modelName`/`dimensions`. Its first test pins the *defect* — an undecorated
model reports no input type at all. Two call-site tests assert ingestion asks for
`DOCUMENT` and retrieval asks for `QUERY`, which is what makes the fix real: the decorator
is useless if both sites still ask for the same thing.

`EmbeddingModelFactoryTest.PinnedTaskTypeDecision` (7, new) grades
`pinsNonRetrievalTaskType` directly. It is package-private and tested on its own because
every path through `build()` constructs a live provider client, which needs a socket — so
the model-level tests only run where one is available, and the decision itself has to be
gradeable anywhere. `PinnedTaskType` (6, new) covers the same decision through
`getOrCreate` and runs in CI.

`docs/rag.md` gained an "Asymmetric models" section covering which providers are affected,
what happens to a pinned `taskType`, and that no re-ingestion is required.

### Follow-up: a pinned Gemini `taskType` is no longer overridden by the role

Raised in review of [#810](https://github.com/labsai/EDDI/pull/810). Attaching the role to
every RAG call fixes the query side, but `GoogleAiEmbeddingModel.toTaskType` falls back to
the build-time `taskType` **only when no input type is given**; a role maps
unconditionally onto `RETRIEVAL_QUERY` / `RETRIEVAL_DOCUMENT`. A knowledge base configured
with `taskType: SEMANTIC_SIMILARITY` (or `CLASSIFICATION`, or `CLUSTERING`) would
therefore have stopped sending it, and everything ingested afterwards would have used
`RETRIEVAL_DOCUMENT` — two incompatible geometries in one index, with nothing failing to
say so.

`EmbeddingModelFactory.pinsNonRetrievalTaskType` now detects that case and leaves the role
off, so the configured task type keeps reaching the provider on both sides. Three
boundaries are deliberate:

- **`RETRIEVAL_DOCUMENT` and `RETRIEVAL_QUERY` do not pin.** The first is the default this
  factory applies and is precisely the value that produced the defect; honouring it would
  leave the bug in place for anyone who had written the default out by hand.
- **`gemini-embedding-2` never pins.** langchain4j sends no `task_type` for any model whose
  name contains `embedding-2` and applies a role instruction instead, so a pinned task type
  is already inert there and skipping the role would cost the instruction for nothing.
- **`taskType` is a Gemini parameter.** A stray one on Cohere or OpenAI pins nothing.

### Follow-up: an unconfigured embedding provider names itself

Raised by the static-analysis reviewer on [#810](https://github.com/labsai/EDDI/pull/810).
`EmbeddingModelFactory.build` switches on the provider string, and
`RagConfiguration.validate()` guards its own provider check with
`embeddingProvider != null && !embeddingProvider.isBlank()` — it rejects providers it does
not *recognise*, not ones that are absent. A knowledge base saved with an explicit null
`embeddingProvider` therefore saved cleanly and only failed at first use, as a bare
`NullPointerException` thrown by switching on null from inside the factory, naming nothing
the operator could act on. A blank provider already fell through to the switch's `default`
and was reported properly; null now says the same thing.

The supported-provider list moved into one `SUPPORTED_PROVIDERS_HINT` constant shared by
both rejections, so the absent-provider and unrecognised-provider messages cannot drift
apart as providers are added.

### Follow-up: the crawler ingestion path, which `main` added underneath this branch

`main` gained `IngestionPipeline` (the crawl-and-ingest path) while this branch was open,
and it calls `embeddingModelFactory.getOrCreate(knowledgeBase)`. That is a third call site
for a method this branch had made two-argument, so the merge of the two did not compile —
which is the required parameter doing exactly the job it was chosen for. An optional
overload would have merged silently and left the crawler sharing retrieval's cache entry,
reintroducing the original defect on the one ingestion path that did not exist when the
defect was found.

`IngestionPipeline.Collector.model()` now asks for `DOCUMENT`; it is storing vectors.
`IngestionPipelineTest` pins that directly, and `IngestionRetrievalRoundTripTest` — which
drives a real crawl and a real retrieval against one factory — now records the role each
half asks for and asserts the pair is `[DOCUMENT, QUERY]`. That assertion is the one that
would have caught this: the two halves sharing a role is the whole defect, and the
round-trip test is the only place both halves are visible at once.

---



---

## 🐛 fix(rag): a scheduled ingestion source was stored enabled and never ran (2026-09-21)

**Repo:** EDDI (`fix/rag-ingestion-schedule-never-fires`) — backend, `ui/manager/`, docs

Five defects that reached `main` because [PR #790](https://github.com/labsai/EDDI/pull/790)
was merged with its review threads still unresolved. The first one made the feature that
PR added not work at all.

### 1 — the schedule had no fire time, so no poll could ever select it

`RagSourceIngestionService.buildSchedule` built a CRON `ScheduleConfiguration` and never
set `nextFire`. Neither store computes one: `MongoScheduleStore.createSchedule` and
`PostgresScheduleStore.createSchedule` both persist whatever they are handed. Both
`findDueSchedules` implementations then select on `enabled = true AND nextFire <= now`,
and a null `nextFire` matches neither backend's comparison — BSON type bracketing
excludes null on Mongo, and `next_fire <= ?` is UNKNOWN for NULL on Postgres. So a source
with a cron was stored reading back `enabled`, showed as scheduled on every screen, and
**never fired**.

`buildSchedule` now arms the schedule the way `RestGroupWorkspace.addCadence` does, with
the same time-zone handling: the cron is read in a fixed **UTC**, and `timeZone` is
written onto the row rather than left null. That second half matters — `SchedulePollerService`
re-arms a fired schedule through `resolveTimeZone(schedule.getTimeZone())`, which falls
back to the deployment's `eddi.schedule.default-timezone`, so a first fire computed in UTC
and every later fire computed in some other zone would have drifted by the offset, once,
silently.

**Where the fix belongs.** In the caller, not in `createSchedule`:

- Putting it in `MongoScheduleStore.createSchedule` would **not** cover Postgres. The two
  stores share no base class, so "fix the store" means writing it twice — and fixing only
  the Mongo one leaves a Postgres deployment broken while looking fixed.
- The arming *policy* already exists, once, in `RestScheduleStore.computeRearmNextFire`:
  heartbeat interval, one-shot `oneTimeAt`, the unsatisfiable-cron translation, the
  configured default zone. A second, subtly different copy of it inside the persistence
  layer is exactly the divergence `MongoScheduleStore.updateSchedule`'s own comments
  record as having bitten before.
- A store that silently rewrites the object it is given also hides real bugs:
  `markCompleted(id, null)` *means* "one-shot finished, disable it".

Every other direct-to-store creator already arms its own schedule
(`RestGroupWorkspace`, `ConversationHitlService`, `GroupHitlCoordinator`,
`HitlCrashRecoveryObserver`). This one had simply forgotten, and that is where it is fixed.

**Existing rows are already broken**, so a fix to `buildSchedule` alone would leave every
schedule created before it dead for ever — repaired only if somebody happened to re-save
the knowledge base, which nothing would tell them to do. A startup sweep
(`RagSourceIngestionService.repairUnarmedSchedules`, `@Observes StartupEvent`, new
`eddi.rag.ingestion.schedule-repair.enabled`, default on) gives a fire time to any
*ingestion* schedule that is enabled, carries a cron and has no `nextFire` at all. It is
safe to run repeatedly by construction: after the first pass nothing matches. It arms
through the existing store-agnostic `setScheduleEnabled`, so it needs no new store
method, and it touches nothing outside this feature's metadata — a schedule left unarmed
on purpose is not a thing this repair can invent a cadence for.

**Two corrections from review (Copilot, #818).** The first version of this paragraph
claimed two nodes "compute the same next occurrence", which is only true if both compute
inside the same cron period. They need not: the listing is a snapshot and
`setScheduleEnabled` overwrites `nextFire` unconditionally, so a slower node crossing a
cron boundary could replace the first node's occurrence with the following one and skip a
fire. Arming now goes through **`IScheduleStore.armIfUnarmed`**, which carries the "still
unarmed" condition in the write predicate itself — `AND next_fire IS NULL AND
enabled=true` on the Postgres `UPDATE`, `eq(NEXT_FIRE, null)` in the Mongo filter, which
matches a stored null and a missing field alike. That is the only place the two nodes
meet, so the first writer wins and every other one is a no-op that reports `false`; a
lost race is treated as success, because the row is armed either way. An earlier draft
re-read the row before writing instead, which narrowed the window to one store
round-trip without closing it.

**A third, from CodeRabbit (#818): the repair could not arm in UTC, and should not have
tried.** `buildSchedule` writes `timeZone` for rows it creates, but the repair arms through
`IScheduleStore.setScheduleEnabled`, which takes only `enabled` and `nextFire` — a legacy
row's null `timeZone` stays null whatever the repair does. Every fire after the first is
therefore re-armed by the poller through `resolveTimeZone(null)`, the deployment's
`eddi.schedule.default-timezone`, so computing the first fire in UTC regardless would hand
a non-UTC deployment exactly one interval of the wrong length — the same drift this PR
fixed in `buildSchedule`, arriving on the repair path instead. The repair now reads the
cron in the zone the poller will use for that row (its own if it names one, the deployment
default otherwise), which is the only choice that makes the row internally consistent
without a new store method. CodeRabbit's alternative — widen the store's re-arm to carry a
zone — would normalise legacy rows to UTC as well, at the cost of a store-API change
implemented twice; noted, not taken.

The second: the 40-page bound used to end the walk **silently**, so past 20,000 schedules
an operator read "armed 12 schedules" with no way to tell a finished repair from one that
stopped a page short of the row they were waiting on. `repairUnarmedSchedules` now returns
`RepairResult(armed, complete)` and logs a warning naming the bound when it is the reason
the walk stopped. The bound itself stays: an unbounded scan at startup is the thing it
exists to prevent.

Also refused now: a cron that parses but can never match a date (`0 0 30 2 *`).
`CronParser.validate` accepts it; `computeNextFire` gives up after two years with an
`IllegalStateException`. Stored, it was another source that showed as scheduled for ever.

### 2 — a ZIP could store a cron the REST API refuses

`RestImportService.createNewRags` writes straight to the store, so
`RestRagStore.prepareForWrite` never runs. Its `prepareImportedRag` assigned source ids
and called `RagConfiguration.validate()`, which does not look at the cron — so an archive
could carry a six-field Quartz expression that `POST /ragstore/rags` rejects with a 400.

The rule moved out of `RestRagStore` into `RagIngestionSchedules.requireValidCrons` and
both paths call it. A second copy of the check would only have been a second chance to
forget it.

**Reconciling `schedule.setNextFire(null)` with defect 1.** That line, in
`prepareScheduleForImport`, is about an *agent's* schedules from the archive's
`schedules/` directory, and it is correct: every write on that path goes through
`IRestScheduleStore`, which re-arms the schedule before storing it, and an archived
`nextFire` is either long past (the schedule fires during the import) or absent. The two
are not in contradiction — they are the same rule seen from both sides: **nothing in
either store computes a fire time, so whoever writes has to arm.** Via the REST bean, the
bean does it; direct to `IScheduleStore`, the caller does it. The javadoc now says so
explicitly and names the ingestion sync as the path that did not.

### 3 — run reports named a null source

`IngestionPipeline` used `source.getId()` at six call sites — the failed/skipped/already-running
reports, the abandoned reservation, the released reservation and `toReport`. A source that
arrived without an id is addressed, keyed and scheduled by its **name** everywhere else
(`IngestionSource.effectiveId()`, which `stateKey` already used), so those six reports
carried `sourceId: null` into the run history, the REST answer and the fire log. All six
now use `effectiveId()`, the record component documents the contract, and
`RagSourceIngestionService.sourceIdOf` delegates to `effectiveId()` rather than keeping a
third copy of the rule.

### 4 — `docs/rag.md` said every ingestion field has a default

It does not, and the table three lines below said so itself (`startUrl` | required).
Corrected against the code: `name`, the `web` block and its `startUrl` have no default and
are rejected when missing; `type` defaults to `web`; `id` is generated; `cron` and
`costPerThousandSegments` are simply absent when unset. `userAgent`'s "EDDI's default" is
now the actual string. The cron paragraph gained the unsatisfiable-expression rule, the
import path, and the UTC note.

### 5 — a Manager test passed for the wrong reason

`resource-detail-rag-sources.test.tsx` → "does not offer Run for a disabled source"
clicked the enabled toggle and then asserted the Run button was disabled. Toggling marks
the editor dirty, and `hasUnsavedChanges` disables Run on its own — so the assertion held
with `source.enabled === false` deleted from the button entirely. The source now **arrives**
disabled from an MSW override, nothing is dirty, and the test additionally asserts that
the unsaved-changes hint is absent and that Preview — gated on the same read-only and
dirty guards but not on `enabled` — is still offered. Only the disabled-source rule can
explain the result.

### Mutation checks

| Mutation | Test that failed |
| --- | --- |
| `buildSchedule` drops `setNextFire` | `RagSourceIngestionServiceTest` → "a source with a cron is due once its fire time arrives" |
| `buildSchedule` drops `setTimeZone` | "the fire time is the cron read in UTC, and the schedule says so" |
| `repairUnarmedSchedules` returns early | "an ingestion schedule with no fire time is given one" |
| `prepareImportedRag` drops `requireValidCrons` | `RestImportServiceRagCronTest` → both refusal cases |
| Run button drops `source.enabled === false` | "does not offer Run for a source that is saved as disabled" |
| `armIfUnarmed` is replaced by the unconditional `setScheduleEnabled` | "a row another node armed while the sweep was listing is left alone" |
| The walk always reports itself complete | "stopping at the page bound is reported, not swallowed" |
| The repair arms in fixed UTC instead of the poller's zone | "a legacy row with no zone is armed in the zone the poller will use, not in UTC" (expected hour 2, got 11) |

### Files

`modules/ingestion/RagSourceIngestionService.java`,
`modules/ingestion/RagIngestionSchedules.java`,
`modules/ingestion/IngestionPipeline.java`,
`configs/rag/rest/RestRagStore.java`, `backup/impl/RestImportService.java`,
`docs/rag.md`, `docs/configuration-reference.md`,
`ui/manager/src/pages/__tests__/resource-detail-rag-sources.test.tsx`.
Tests: `RagSourceIngestionServiceTest` (two new nested groups),
`RestImportServiceRagCronTest` (new).

---

## 🧩 chore(changelog): per-branch fragments instead of one shared file (2026-09-21)

**Repo:** EDDI (`chore/changelog-fragments`)

### The problem

[`AGENTS.md`](../AGENTS.md) §2 rule 8 requires every branch to add a changelog entry, and it
said to add it **directly below the `---` that closes the header** of [`changelog.md`](changelog.md).
Every open PR therefore inserted text at the same point in the same file. Git has no way to merge two
insertions at one point, so with a dozen PRs in flight every one of them conflicted with every other —
over a document that had nothing to do with the code under review. Resolving it rebased the branch,
which re-ran the identical collision at the next merge. The two running registers at the bottom of the
file (`## Decision Log`, `## Regression Notes`) behaved the same way: a fixed append point in a shared
file.

The rule was right and the storage was wrong. Nothing about "every session records what it did"
requires every session to write into the same twenty lines.

### The shape of the fix

An entry is now a **new file** in [`docs/changelog.d/`](changelog.d/README.md), named `YYYY-MM-DD-<slug>.md` with
the slug unique to the branch. Two branches adding an entry add two files, and git takes both without
asking. The entries are merged **once, afterwards, on `main`**, by
[`changelog-collate.yml`](../.github/workflows/changelog-collate.yml) — where there is no competing
branch to conflict with. The changelog gains a third depth:

```
docs/changelog.d/<date>-<slug>.md   pending, written by a PR
        |  collate-changelog.py  (nightly)
        v
docs/changelog.md                   the live file, newest first
        |  rotate-changelog.py   (nightly, when over the 200 KB target)
        v
docs/changelog/<YYYY-MM>.md         monthly archive
```

**The fragment format is what used to be pasted into the live file**, so collation is a move rather
than a translation — the entry that lands in `changelog.md` is byte-for-byte the one that was reviewed
on the PR that wrote it, apart from link depth. That mattered more than a tidier format would have: a
collator that reformats is a collator whose output has to be re-reviewed.

### Decisions

**Links change depth, and the transform refuses rather than guesses.** A fragment sits one directory
below `changelog.md`, exactly like an archive sits one below it in the other direction, so collation
removes one `../` and rotation adds one back. Both live in the new
[`changelog_common.py`](../scripts/changelog_common.py) rather than being written twice, masking
code spans and fenced blocks out of the substitution — some of the text that moves is documentation
*of* link syntax, and re-depthing an example corrupts it. `undepth()` **exits** on a link with no
`../` to remove instead of passing it through: that case is a fragment linking to a sibling, which
resolves where it is written (so `DocumentationLinksTest` passes it) and breaks the moment the entry
moves up — in the bot's commit, days later, blamed on the bot.

**Register rows ride along in fenced blocks.** ` ```decision-log ` and ` ```regression-note ` blocks
anywhere in a fragment are lifted out and inserted at the top of the matching table. A heading would
have been prettier to write, but `split_sections()` keys on the heading text: a bare `## Decision Log`
is classified as the register itself, so the section is filed below the bottom rule instead of being
collated, and a dated `## Decision Log (2026-09-22)` collates normally and is then *reclassified on
the next run* — moving an entry that has already been reviewed and merged, with no commit to explain
it. `REGISTER` is anchored to the exact heading and `ChangelogFragmentTest` rejects both spellings in
a fragment, so neither half depends on the other being right.

**Every scan is fence-aware.** A changelog entry routinely quotes the markdown it describes, and the
first draft read a `## ` inside a fenced block as a heading and a ` ```decision-log ` inside a
` ````markdown ` example as a real register block — splitting an entry at a code line, and filing an
example row into the live Decision Log while gutting the fence around it. `fence_mask()` closes a
fence only on a run of the **same character**, at least as long as the one that opened it, and the
heading scan, the register scan and both link transforms all go through it.

CommonMark allows `~~~` as well as ``` ``` ```, and the first version of that mask knew only about
backticks — so a `~~~markdown` example was not a fence at all, and everything inside it was read as
structure. Both are recognised now, in the scripts and in the test. The test had the mirror-image
bug: `registerRowsCarryADate` opened only on a register fence, so it graded the placeholder row in
the README's own nested example as real and was *stricter* than the collator, against a class
Javadoc promising it enforces exactly what the collator enforces.

**The nightly job opens a PR and skips while one is open.** `main` requires a PR, and a bot pushing
straight to it would be a hole in that requirement even where the token allows it. The skip matters
more than it looks: a still-open collation PR has already claimed those fragments — deleted on its
branch, still present on `main` — so collating again would produce a second PR proposing the same
entries, and whichever merged second would conflict. That would reintroduce, inside the fix, the exact
failure the fix exists to remove. For the same reason the open-PR lookup **fails the job** rather than
reporting "none" when the API call errors: the next step deletes the branch, so a lookup that returns
an empty string on failure would close the PR it exists to protect.

**The PR needs a non-default token to be mergeable, and says so when it does not have one.** GitHub
does not fire `pull_request` workflows for events created with the default `GITHUB_TOKEN`, so a PR it
opens sits at *Expected — waiting for status* against the required `Build & Test` and
`CodeQL Analysis` checks forever. `base-image-check.yml` has the same shape and has never actually
been exercised — the repository has no PR authored by `github-actions`. The job therefore prefers a
`CHANGELOG_BOT_TOKEN` secret and, without one, still opens the PR but states in the run summary and
the PR body that the checks need a close/reopen. A silently unmergeable nightly PR would stall the
whole mechanism on day one.

**Adding an entry in place now fails CI.** The `Changelog Discipline` job rejects a PR that *adds*
an entry heading or a dated register row to `docs/changelog.md`. Prose in AGENTS.md is what every
session follows, but it is not a guard — and twenty-nine PRs were open against the old rule when
this was written.

Two things the first draft of that job got wrong, both found by review. It required a closing `)`
after the date while `DATE` deliberately does not, so the fourteen entries headed
`(2026-07-02, session 2)` or `(2026-04-08 cont.)` — the house style — walked straight past a guard
that the collator would still have treated as entries. And it counted additions only, so correcting
a typo in a past entry's heading failed, with a summary telling the author to move their correction
into a new fragment. It now compares added against **removed**: an edit is one `+` and one `-`, nets
to zero, and is allowed; rotation removes entries and nets negative; only a genuine insertion raises
the count. All ten cases are exercised. It is deliberately not a required check — it reports rather
than blocks, which matters while those twenty-nine PRs are still open.

**Rotation now maintains `SUMMARY.md` itself.** A new archive that is not listed there fails
`DocumentationLinksTest` — the page exists and nothing navigates to it. That used to be a printed
reminder at the end of the script, which is a step a tired human skips and an automated rotation
cannot perform at all. It is regenerated from the files on disk, like the Archive table beside it.

### Keeping the guards pointed at the right files

`docs/changelog.md`, `docs/changelog.d/**` and `docs/changelog/**` were added to `ci.yml`'s
`operator_docs` path filter. The `code` and `backend` filters both exclude `docs/**`, and the nightly
collation PR touches nothing else — so the one PR that rewrites the entire changelog was the one PR
that would have skipped `build-and-test` entirely, and a skipped required check still satisfies branch
protection.

Two existing guards needed the new directory carved out, each with a compensating assertion:

- `DocumentationLinksTest` requires every page under `docs/` to be reachable from `SUMMARY.md`.
  Fragments are pending entries, not pages, and live about a day. The exemption is kept narrow by
  `ChangelogFragmentTest`, which allows nothing but a `README.md` and dated fragments in that
  directory — so it cannot become somewhere to park a real page.
- `DocumentationAccuracyTest` already skips `changelog.md` and its archives, because they record what
  was true at the time. A fragment is the same historical record one step earlier; without the
  exclusion its assertions would fire on an entry and stop firing on the identical text the next
  morning. `DocumentedRestPathsTest` and `ConfigurationReferenceCoverageTest` carry the same pair of
  exemptions and needed the same third one, for the same reason. `changelog.d/README.md` is **not**
  exempted from the accuracy sweep: it is a current page that documents the format, not a record.

`ChangelogFragmentTest` also asserts that `AGENTS.md` still sends sessions to `changelog.d/`. Every
session follows the instruction it is given, and a convention nobody is told about fixes nothing.

### Entries merge by date, and the separator stops multiplying

Two bugs a sandbox caught that reading would not have. The first draft **stacked** the collated block
above everything, so a PR that sat open for three weeks pushed a three-week-old entry above last
night's — in a file whose whole contract is newest-first. Entries are now woven into the existing
list by date, ties placing the new one first.

The second: `split_sections()` hands back the last entry's body *including* the `---` that closes it,
and the writer appended another one. Every collation added a rule, permanently — 48, 49, 50, 51 over
three nightly runs in the sandbox — and rotation only reset the count on the runs that happened to
move the last entry. `register_separator()` now emits one only when the body does not already carry it.

### Also fixed in passing

- The **Regression Notes** table had a header row and no `|---|` separator, so it had never rendered
  as a table. The collator writes into it, so it needed one.
- Dates are checked against the calendar, not just bounded by a regex. `MONTH` and `DAY` stop
  `2026-99-99` (which used to sort lexically into the live file and then crash rotation inside
  `pretty_month()`, weeks later, in the nightly job), but they still admit `2026-02-30` — so both
  the scripts and the test now parse the date as well.
- `--check` validates the live file it would write into, not only the fragments. Returning early
  meant a `changelog.md` missing a register section, or a table missing its `|---|` row, passed
  "validate, change nothing" and then failed the real run — on main, with nobody's change to blame.
- A UTF-8 BOM no longer reports a fragment as having no `## ` heading when the heading is plainly
  there; `read()` uses `utf-8-sig` and the test strips it. The BOM constant in the test is written
  numerically on purpose — the project formatter rewrites a `\uXXXX` escape into the raw character,
  which would have put an invisible BOM into the source file.
- Register rows are sorted newest-first before insertion. They arrive in fragment-filename order —
  oldest first — and were inserted as one block at the top, so a night that collated several days'
  fragments would have put 09-20 above 09-21 inside a table whose whole ordering is newest-first.
- A row bound for a register must lead with a real date, because that date is what places it. The
  three legacy `2026-03-05` rows at the top of the **Decision Log** were left where they are: moving
  rows reads as adding them, and the `Changelog Discipline` job below would have rejected this PR
  over a purely cosmetic reorder.
- `update_summary()` regenerates the month list in `SUMMARY.md` between the anchor and the first line
  that is not an archive row. Putting the *Pending entries* link directly under the anchor — the
  natural place for it — would have had the regenerated months inserted above it and the old rows
  kept below, silently doubling the list; every duplicated link still resolves, so nothing would have
  caught it. It now consumes every child of the anchor and re-emits the non-month ones after.

### Migrating the PRs already open

Twenty-nine open PRs carry an entry in `docs/changelog.md` under the old rule, and seven of them also
carry a branch-local rotation of `docs/changelog/2026-09.md`. Each needs its entry cut into a fragment
— the steps are in [`docs/changelog.d/README.md`](changelog.d/README.md) — which also removes the
conflict that branch currently has with every other open PR. [`.github/PULL_REQUEST_TEMPLATE.md`](../.github/PULL_REQUEST_TEMPLATE.md)
and [`ui/chat/AGENTS.md`](../ui/chat/AGENTS.md) were updated to point at the new location; the
`planning/*.md` documents that mention editing the changelog are historical plans and were left as
written.

**Files:** [`scripts/changelog_common.py`](../scripts/changelog_common.py),
[`scripts/collate-changelog.py`](../scripts/collate-changelog.py),
[`scripts/rotate-changelog.py`](../scripts/rotate-changelog.py),
[`.github/workflows/changelog-collate.yml`](../.github/workflows/changelog-collate.yml),
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml),
[`ChangelogFragmentTest.java`](../src/test/java/ai/labs/eddi/docs/ChangelogFragmentTest.java),
[`DocumentationLinksTest.java`](../src/test/java/ai/labs/eddi/docs/DocumentationLinksTest.java),
[`DocumentationAccuracyTest.java`](../src/test/java/ai/labs/eddi/docs/DocumentationAccuracyTest.java),
[`DocumentedRestPathsTest.java`](../src/test/java/ai/labs/eddi/docs/DocumentedRestPathsTest.java),
[`ConfigurationReferenceCoverageTest.java`](../src/test/java/ai/labs/eddi/docs/ConfigurationReferenceCoverageTest.java),
[`AGENTS.md`](../AGENTS.md), [`docs/changelog.md`](changelog.md),
[`docs/changelog.d/README.md`](changelog.d/README.md), [`docs/SUMMARY.md`](SUMMARY.md),
[`.github/PULL_REQUEST_TEMPLATE.md`](../.github/PULL_REQUEST_TEMPLATE.md),
[`ui/chat/AGENTS.md`](../ui/chat/AGENTS.md)

## 🚧 fix(ingestion): a reaped run can no longer write over the run that replaced it (2026-09-21)

**Repo:** EDDI (`feat/ingestion-state-store`)

### Why

Review of PR #785 found a write-after-reap hole in the ingestion state store, and left it
open because no fix fitted in that PR's shape. Worker A holds the `RUNNING` run row for a
source, stalls past the stale threshold, and `reapStaleRuns` fails its run. Worker B claims
a replacement run. A then wakes up and calls `recordIngested` / `recordSeen` /
`recordUnreachable` with a run id that is dead, mutating document rows that now belong to B.

The severity was originally judged bounded — a late `recordIngested` sets `missedRuns = 0`
and `tombstoned = false`, so it preserves a document rather than losing one. Writing the
contract cases turned up two worse paths that the "it only keeps data" reading misses:

- **Silent, permanent loss of a page.** Tombstoning deletes a document's vectors. A stale
  `recordIngested` lifts the tombstone *and* restores the hash those deleted vectors
  matched, so `hasChanged` reports "unchanged" on every later run and the page is never
  embedded again. It is gone from retrieval for good, with nothing in any log.
- **Vector deletion of a live page.** `recordUnreachable` writes only the run marker, which
  looks harmless until you follow it: stamping that marker is exactly how a document escapes
  the owning run's miss count. A zombie overwriting it hands a page the live run has just
  seen back to `tombstoneMissing`.

### What changed

A fencing token both backends apply **in the same statement as the document write**, because
the alternatives re-introduce backend divergence: MongoDB cannot join collections in an
update, and multi-document transactions need a replica set while EDDI supports standalone
MongoDB (`docker-compose.yml` ships `mongo:7` standalone). A PostgreSQL-only
`AND EXISTS (SELECT 1 FROM rag_ingestion_runs …)` fence would leave the two stores behaving
differently — the exact drift `IngestionStateStoreContract` exists to prevent.

Ownership is denormalized onto the document row instead:

- `fencing_run_id` / `fencingRunId` — the run that owns the row. `startRun` takes ownership
  of the source's rows when it claims a run; `reapStaleRuns` releases it (to `NULL`) when it
  actually reaps something, so a reaped worker is fenced from that moment rather than only
  once a replacement claims.
- `fencing_generation` / `fencingGeneration`, paired with a new `generation` on the run row —
  the claim's sequence number, which orders the ownership stamps themselves. Without it, a
  stamp delayed past its own run's reaping could land after the replacement's and take the
  source back.
- Every `record*` and `tombstoneMissing` call puts its `runId` into the update's own filter.
  **No signature changed and the caller makes no extra round trip** — the `runId` the pipeline
  already passes *is* the fencing token. PostgreSQL uses `ON CONFLICT … DO UPDATE … WHERE
  fencing_run_id = EXCLUDED.fencing_run_id`; MongoDB puts the field in the upsert filter and
  reads the resulting `(sourceId, documentId)` duplicate-key error as the same answer.

A fenced write is a no-op logged at DEBUG, not an exception: the reaper already decided the
run is dead and `finishRun` logs once that its result was discarded, so failing every document
of a doomed crawl would only add noise to a result that is thrown away.

**What the fence does not cover,** stated in the interface Javadoc rather than glossed: a
document the superseded run is the first ever to see has no row to own, so its insert still
lands. That is the benign direction, and the owning run's `tombstoneMissing` reconciles it
away over the following runs.

### Design decisions

- **Ownership stamped at claim, not propagated lazily.** Comparing a per-document generation
  on the write alone only fences rows the *replacement* run has already touched — a zombie
  would still be free to write every row the live run had not reached yet, which is most of
  them early in a crawl. Taking ownership of the source's rows at claim time costs one bulk
  update per run and is the only shape that fences the whole source.
- **`fencing_run_id` is separate from `last_run_id`.** They mean different things:
  `last_run_id` is the run that last *wrote* the row and drives the miss count; ownership is
  about who is *allowed* to write. Overloading one field would have made `recordUnreachable`
  grant itself the write permission it is being checked for.
- **`ALTER TABLE … ADD COLUMN IF NOT EXISTS` alongside the `CREATE TABLE`.** `CREATE TABLE IF
  NOT EXISTS` is a no-op against a database an earlier build of this branch already created,
  which would have left the fence silently un-enforceable there.

### Verified

`MongoIngestionStateStoreTest` and `PostgresIngestionStateStoreTest` — 38 cases each, both
green. Non-vacuity checked by reverting `src/main` and re-running: **6 of the 7 new cases fail
identically on both backends** (the seventh, `fencingIsScopedPerSource`, is the guard against
over-fencing and must pass either way).

### Follow-up for the downstream stack

`InMemoryIngestionStateStore` (test double, added in #787) implements the same contract, so it
will need the same fence when this branch merges forward into #787 / #789 / #790. Nothing in
`IngestionPipeline` needs to change — preview mode never calls `record*`, and a reserved run id
comes from `startRun` like any other.

### Files

- `src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java`
- `src/main/java/ai/labs/eddi/modules/ingestion/mongo/MongoIngestionStateStore.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresIngestionStateStore.java`
- `src/test/java/ai/labs/eddi/modules/ingestion/IngestionStateStoreContract.java`

---

### Review follow-ups (2026-09-21)

- **The reaper released ownership for the whole source, not for the runs it reaped.**
  `reapStaleRuns` fails the stale runs and clears `fencing_run_id` in two writes, not one. Once the
  first commits, the partial unique index on the source is free: a replacement run can claim the
  source and stamp every document with its own id before the second one runs, and a source-wide
  release then wipes the live run's fence. Every `recordSeen`, `recordIngested`,
  `recordUnreachable` and `tombstoneMissing` of that run silently matches nothing while it carries
  on crawling and embedding, so it finishes looking healthy having recorded no document at all, and
  the whole source is re-fetched and re-embedded on the next run. PostgreSQL now reaps with
  `UPDATE ... RETURNING run_id` and releases `WHERE fencing_run_id = ANY (?)`; MongoDB claims each
  stale run with `findOneAndUpdate` — the ids have to come back *with* the write, not from a read
  after it — and releases with `in(fencingRunId, reapedIds)`.
- **`startRun`'s insert was the one operation still outside `translating(...)`.** It handled
  `MongoWriteException` and let everything else out, so a connection failure, a timeout or a
  step-down during the insert escaped as a raw `MongoException` while PostgreSQL answered the same
  outage with `IngestionStateStoreException` — the backend-dependent exception contract the helper
  was added to remove. The duplicate-key case is now handled inside the supplier, so it still
  returns `Optional.empty()`, and everything else falls through to the helper.

The contract gained `reapingDoesNotReleaseAnotherRunsOwnership` and one hook,
`forceDocumentOwner`, implemented per backend. The hook is needed because the interleave cannot be
produced through the public interface: the partial unique index means a stale `RUNNING` run and its
replacement can never both exist, so there is no sequence of `startRun` calls that leaves a document
owned by a run other than the one about to be reaped.

### Still open

Review also found that `recordIngested` inserts a newly discovered document with a
`fencing_run_id` but no `fencing_generation`, and `takeOwnership` matches
`fencing_generation IS NULL` (the arm that exists for rows predating the fencing columns). A
`takeOwnership` delayed past its own run's reaping can therefore land on a row the replacement run
inserted and take that one document back. The scoped release above does not close it. Every fix
needs the claiming run's generation at insert time, which `recordIngested` is not given: a scalar
subquery works on PostgreSQL and has no MongoDB equivalent, a per-call lookup is symmetric but adds
a read per ingested document, and carrying the generation through `startRun`'s return type is the
cleanest but changes `IIngestionStateStore`, which two further open PRs are built on. Left for that
decision rather than picked unilaterally here.

### Merging `main`: two designs for the same concern

`main` reworked tombstoning while this branch was open, and the two changes met
head-on. This branch made `tombstoneMissing` atomic -- `UPDATE ... RETURNING` on
PostgreSQL, `findOneAndUpdate` in a loop on MongoDB -- so two runs finishing together
could not both report the same document as newly gone. `main` split it instead, into
`bumpAndFindMissing` + `markTombstoned`, so a caller can delete the vectors *before*
marking: marking first is durable in the wrong order, because a crash between the two
leaves a document flagged gone while its chunks stay retrievable, and a tombstoned
document is never reported again.

**`main`'s split is kept, and this branch's fencing is grafted onto it.** The two
rationales are not symmetric: the durability ordering is a property nothing else
provides, whereas the atomicity the `RETURNING` clause bought is already delivered by
the fence. Only the owning run satisfies `fencing_run_id = ?`, so two runs cannot both
reach the same document to report it in the first place. Keeping both would have meant
choosing the weaker guarantee and losing the stronger one.

Three consequences, each of which is now pinned by a test:

- **The search is fenced, not just the bump.** Fencing the miss counter alone leaves a
  hole: the counter is shared, so a document another run has already bumped to the
  threshold still satisfies an unfenced search. A superseded run would hand its caller
  a list of documents to delete, and the caller deletes the vectors before anyone
  checks who owned them. Both statements now carry the predicate, in both backends.
  This was not covered -- removing the predicate from the PostgreSQL search left all 39
  contract cases green -- so `supersededRunReportsNothingMissing` was added to the
  shared contract, where all three implementations run it.
- **`tombstoneMissing` reports the transition it just performed.** `bumpAndFindMissing`
  reports its candidates *before* marking them and `DocumentState` is immutable, so the
  convenience default was handing back a list that still said `tombstoned=false`
  although marking had succeeded. A caller that believed it would re-report the same
  documents on the next run. `DocumentState.asTombstoned()` restamps them.
- **The in-memory double was fenced too.** `main` added
  `InMemoryIngestionStateStore` with no fencing, which would have left the double
  behaving differently from both backends on exactly the property this branch exists to
  add -- the failure the shared contract was written to prevent. It now takes
  ownership on `startRun`, fences all four document writes, and releases only the
  ownership a reap actually took. All three implementations run the same 39 cases.

The double deliberately reproduces one thing it could have quietly fixed: a document
row inserted for the first time lands with an owner and **no** generation, because that
is what both backends do. Stamping the claiming generation there is the subject of an
open review thread and is a sequencing decision, not a merge one -- it changes
`IIngestionStateStore`, which two further PRs are built on.

---

## 🐛 fix(llm): Jlama was running on the scalar fallback in every JVM we ship (2026-09-20)

**Repo:** EDDI (`fix/jlama-vector-api`)

The `jlama` provider is registered in `LlmModule`, documented in `docs/langchain.md`, counted
among the supported providers, and offered by the agent wizard, the setup API, `McpSetupTools`
and `CreateSubAgentTool`. It was also, in every JVM this repository starts, running Jlama's
pure-scalar tensor backend.

### Why it was invisible

Jlama picks its backend once, at first use, in `TensorOperationsProvider.pickFastestImplementation()`:

1. it tries `NativeSimdTensorOperations`, which needs `com.github.tjake:jlama-native` — not on
   our classpath — logs "Native operations not available" and moves on;
2. it falls back to `MachineSpec.VECTOR_TYPE`, which reads `FloatVector.SPECIES_PREFERRED`
   inside a `catch (Throwable)`. Without `--add-modules=jdk.incubator.vector` that throws, the
   catch logs one line through Jlama's own logger, and the type stays `NONE`;
3. `NONE` selects `NaiveTensorOperations` — scalar Java matrix arithmetic.

So a Jlama agent *worked*. It answered correctly, orders of magnitude too slowly to use, and
nothing in the build could see it: no test configured a Jlama agent, and the container ITs
exercise the image over HTTP.

### What changed

- **`src/main/docker/Dockerfile`** — the flag on a new `ENV JDK_JAVA_OPTIONS`, **not** on
  `JAVA_OPTS_APPEND` where EDDI's other JVM settings live. The launcher reads
  `JDK_JAVA_OPTIONS` itself, so the flag survives an operator overriding `JAVA_OPTS` or
  `JAVA_OPTS_APPEND` — and a `docker run -e JAVA_OPTS_APPEND=...` *replaces* the image's value
  rather than appending to it. `docs/setup-eddi-on-aws-with-mongodb-atlas.md` does exactly that
  to pass a MongoDB connection string, so the obvious placement would have been silently dropped
  by a deployment shape we document ourselves.
- **`src/main/docker/Dockerfile.demo`** — the flag in the `ENTRYPOINT` array. The demo image
  starts EDDI with a bare `java` command, so it has neither `run-java.sh` nor any ENV to inherit
  from.
- **`pom.xml`** — the flag on the Surefire fork's `argLine`, and deliberately **not** on the
  Failsafe fork's.
  Failsafe's `argLine` *parameter* defaults to the `${argLine}` property, which both
  `jacoco:prepare-agent-integration` and the Quarkus Maven extension populate; declaring an
  explicit element there replaces the lot, dropping the JaCoCo IT agent that feeds the merged
  90/80 coverage gate, the add-opens Quarkus injects, and the serialized app-model path. An
  earlier revision of this branch did exactly that. ITs exercise the image over HTTP and never
  build a Jlama model in the test JVM, so the flag buys nothing there.
- **`mise.toml`** — the flag via `-Djvm.args` on **both** tasks that fork a dev JVM, `dev` and
  `debug`. `debug` was missed initially and the test could not see it, because it matched only
  the first `quarkus:dev` line.
- **`JlamaRuntimeSupport`** (new) — reads `MachineSpec.VECTOR_TYPE`, the field Jlama's own
  `TensorOperationsProvider` branches on, and logs one warning that distinguishes the two causes:
  a missing flag (fixable in one line) versus a CPU exposing no vector species Jlama accepts (not
  fixable by any flag). Warns rather than fails — a degraded Jlama still answers correctly, and
  failing closed would turn a slow deployment into a broken one on upgrade, for a condition the
  operator may not be able to fix.
- **`JlamaLanguageModelBuilder`** — exposes four of the five settings Jlama accepts and EDDI
  was dropping: `modelCachePath`, `quantizeModelAtRuntime`, `workingDirectory`,
  `workingQuantizedType`. Booleans go through `ModelParameterValues.applyBoolean` rather than
  `Boolean.parseBoolean`, so `"ture"` leaves the provider default instead of silently meaning
  `false`; `workingQuantizedType` resolves against Jlama's `DType` enum with an unknown name
  logged and ignored rather than thrown on every turn. The parameter mapping moved into a
  package-visible `applyTo` that stops short of `build()`.
- **`threadCount` is deliberately NOT exposed**, though Jlama's builder accepts it and an
  earlier revision of this branch mapped it. `JlamaModel.Loader` hands the value to
  `ModelSupport.loadModel`, which calls the process-global
  `PhysicalCoreExecutor.overrideThreadCount` — a one-shot latch
  (`if (!started.compareAndSet(false, true)) throw new IllegalStateException(...)`) that the
  executor's memoized `instance` supplier also arms merely by running inference. A per-model
  parameter cannot honour a process-global one-shot setting: the second Jlama model built in
  a process would throw `"Executor already started"` during load, even with an identical
  value. And `ChatModelRegistry` rebuilds models on cache eviction, secret rotation and a
  30-minute idle TTL, so a second build is routine rather than exotic. Caught in review;
  pinned by a test, because the setter sits on the builder right next to the mapped ones and
  re-adding it looks like an obvious omission being corrected.

`modelCachePath` is the one that matters operationally, though not for the reason that first
looked obvious. Jlama caches weights under `${user.home}/.jlama/models`, and in the EDDI image
that path *is* writable — including for an arbitrary OpenShift UID, because the base image sets
`HOME` and the JDK falls back to it. The real problem is that it resolves to the pod's ephemeral
writable layer: the multi-gigabyte weights live exactly as long as the pod, so every restart
re-downloads them from Hugging Face before the first turn can be answered, and an air-gapped
deployment cannot start at all.

### Design decisions

- **No `jlama-native`.** It would put platform-specific, glibc-sensitive shared objects into a
  digest-pinned UBI image, add Trivy scan surface and require `--enable-native-access`. The
  Vector API path is pure Java and gets most of the benefit.
- **No live-inference smoke test.** It would download gigabytes from Hugging Face on every CI
  run. `JlamaRuntimeSupportTest#simdBackendIsAvailableInThisJvm` asserts that *Jlama's own*
  backend selection finds a vector type in the test JVM instead — which is the thing that was
  silently false, and it costs microseconds.
- **Probe Jlama's decision, not the module.** An earlier revision asked whether
  `jdk.incubator.vector` was resolved. That is a strictly weaker question: `MachineSpec` accepts
  only a 512- or 256-bit preferred species (or 128-bit on ARM), so on an x86 host exposing a
  128-bit species — a hypervisor masking AVX2, or `-XX:UseAVX=0` — the module resolves, the class
  loads, and Jlama still picks the scalar backend with no warning. Reading
  `MachineSpec.VECTOR_TYPE` cannot diverge from what Jlama actually does.
- **`timeout` stays a pipeline key.** `JlamaChatModel.builder()` has no timeout setter; the
  value is applied by `ObservableChatModel` as a wall-clock bound. Both tempting "fixes" —
  adding it to `recognisedParameters()`, or deleting it from the documented example — would be
  wrong, so `JlamaTests#timeoutIsNotABuilderParameter` pins the current behaviour.

### Verified against the real base image

Rather than reasoning about whether `run-java.sh` forwards a non-`-D` flag, this was run inside
`ubi10/openjdk-25-runtime` at the exact digest the Dockerfile pins:

- without the flag: `moduleResolved=false classLoadable=false` — the bug, reproduced in the
  published base image;
- with it: `moduleResolved=true classLoadable=true`, plus the expected
  `WARNING: Using incubator modules` line;
- with it on `JDK_JAVA_OPTIONS` and `JAVA_OPTS` *or* `JAVA_OPTS_APPEND` overridden by the
  operator: still `true`. That is why the flag lives there and not on `JAVA_OPTS_APPEND`.

### Tests

`JlamaRuntimeFlagsTest` (5) greps both Dockerfiles, every `<argLine>` in `pom.xml` and every mise
task that forks a dev JVM, so losing the flag from any of the five places it has to appear fails
the build where the change was made. It also asserts the flag is on *exactly one* `<argLine>`, so
re-adding it to failsafe fails here rather than quietly costing the IT coverage data.
`JlamaRuntimeSupportTest` (10) proves the SIMD backend is genuinely selected in a running JVM —
the two are complementary: a typo fails both, a flag written into a config block Maven never
applies fails only the second. `LanguageModelBuildersTest.JlamaTests` (10) covers the parameter
mapping, including that every key in `recognisedParameters()` actually changes builder state,
that a mistyped boolean does not silently mean `false`, that an unknown `DType` name is ignored
rather than thrown, that `threadCount` stays unmapped, and that the Hugging Face token stays
masked.

### Docs

`docs/langchain.md`'s Jlama section rewritten: a full parameter table, the required JVM flag and
why its absence is silent, and the three container behaviours that differ without erroring —
`modelCachePath` landing on the ephemeral layer, inference threads being sized from a CPU
*limit* but not a *request* (cgroup shares have been ignored since JDK 19), and memory sizing for
memory-mapped safetensors that count against the container limit but not the heap. Plus the
air-gap caveat that an empty cache with no egress fails rather than degrades.

Assessment behind this work: [`planning/inference-stack-fit.md`](../planning/inference-stack-fit.md).
It also carries a correction — an earlier draft wrongly called `timeout` an unrecognised
parameter.

---

## 🐛 fix(ui): the agent wizard could only produce a Jlama agent that never loads (2026-09-20)

**Repo:** EDDI (`fix/manager-jlama-wizard`)

Every Jlama path through the Manager's agent wizard produced an agent that failed on
its first message. Three defects, all of the same shape — the wizard offering a value
the backend cannot use, and reporting success anyway.

### What was wrong

1. **The model suggestions were not resolvable.** `MODEL_SUGGESTIONS.jlama` offered
   `llama-3.2-1b` and `tinyllama`. Jlama resolves a model through `JlamaModelRegistry`,
   which downloads it from Hugging Face and wants an `owner/name` repository id. A bare
   name has no owner to look up, so the download fails on the agent's first turn — long
   after the wizard said "created". `LLM_PROVIDERS`'s `defaultModel` carried the same
   bare name, which is what the user sees as the model placeholder before typing.
2. **A `baseUrl` field for a provider with no endpoint.** The wizard offered Jlama a
   Base URL with a `http://localhost:8080` placeholder. Jlama runs *in-process* inside
   the EDDI JVM; `AgentSetupService` drops `baseUrl` for `jlama` before the builder is
   reached, and `JlamaLanguageModelBuilder.recognisedParameters()` does not contain it
   either. Anything typed there vanished with no visible trace.
3. **…and the field was marked required.** `isBaseUrlRequired` returned true for
   `jlama`, which in `operator-activation.tsx` is a hard gate (`modelStepValid`): an
   admin activating the Platform Operator on Jlama could not proceed without filling in
   a field whose value was then thrown away.
4. **A hidden field still submitted its stale value.** A consequence of fixing (2):
   with the field no longer rendered, a base URL typed for a previous provider stayed
   in wizard state and was still sent, with no way for the user to see or clear it.
   `handleProviderChange` now clears it — in the wizard and in operator activation —
   when the incoming provider has no endpoint.

Worth noting that the *rule-based* reference config in `docs/agent-configs/` already had
this right — its Jlama chooser offers `tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4`. The
Manager wizard was the outlier.

### What changed

**Manager (`ui/manager`)**

- `src/lib/model-suggestions.ts` — Jlama now suggests only ids this repository already
  treats as real: `tjake/Llama-3.2-1B-Instruct-JQ4` (from `docs/langchain.md`) and
  `tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4` (from the shipped reference config). Any
  other `owner/name` repo can still be typed.
- `src/lib/model-suggestions.ts` — new `supportsBaseUrl()` backed by an
  `IN_PROCESS_PROVIDERS` set; `isBaseUrlRequired()` no longer returns true for `jlama`.
- `src/pages/agent-wizard.tsx` — the Base URL block is omitted entirely when
  `supportsBaseUrl` is false, and a Jlama note replaces it: no endpoint, weights come
  from Hugging Face on first use, and the tuning parameters worth setting afterwards.
  The model hint becomes Jlama-specific (the `owner/name` requirement).
- `src/lib/api/agent-setup.ts` — `defaultModel` is a real repo id; the provider label
  is "Jlama (In-Process)" rather than "Jlama (Local)", which read like Ollama.
- Four new i18n keys across all 11 locales.

**Backend**

The deployment parameters the wizard now points users at did not exist yet —
surfacing them without adding them would have been a fresh instance of the same bug.
`JlamaLanguageModelBuilder` now reads `modelCachePath`, `quantizeModelAtRuntime`,
`workingDirectory` and `workingQuantizedType`, all of which `JlamaChatModel.builder()`
has always accepted. `modelCachePath` is the one that matters operationally: its
default is `~/.jlama/models`, which in a container is the ephemeral writable layer, so
every pod restart re-downloads multiple gigabytes.

**Merge note (main):** this branch originally also mapped `threadCount`. While this
branch was in flight, `fix/jlama-vector-api` landed on `main` and deliberately
removed `threadCount` from `recognisedParameters()` — it reaches Jlama's
process-global, one-shot `PhysicalCoreExecutor.overrideThreadCount`, which throws on
any second call, so it cannot be a safe per-model setting (`ChatModelRegistry` rebuilds
Jlama models on cache eviction, secret rotation and idle TTL). Merging this branch with
`main` kept `main`'s exclusion rather than reintroducing `threadCount`; the wizard-facing
fixes below are unaffected, since the wizard never exposed `threadCount` itself. `main`
also factored the parameter mapping into a static `applyTo` for testability and added
the `JlamaRuntimeSupport.warnOnceIfDegraded()` call — both preserved as-is by the merge.

`ModelParameterValues` gains `applyPath`, following the existing lenient-read
convention — an unusable value is logged and skipped so the model default stands,
rather than throwing out of the build path and failing every conversation the agent
serves. `main`'s version of `JlamaLanguageModelBuilder` had applied `modelCachePath`
and `workingDirectory` inline instead (an `isNullOrEmpty` check plus a bare
`Path.of`), which meant a NUL-containing or otherwise unusable configured path threw
`InvalidPathException` during model construction instead of leaving Jlama's default
in place — the same failure mode `applyPath` exists to prevent. Review feedback on
this PR caught the mismatch after the merge, so `applyTo` now routes both settings
through `applyPath`, and `LanguageModelBuildersTest` gained a case asserting that an
unusable path for either setting falls back to the default rather than propagating.

**Review round 3 (2026-09-24):** two more CodeRabbit findings, both minor.

- `ModelParameterValues.applyPath`'s rejection warning logged the sanitized-but-not-redacted
  configured path (`sanitize(raw)` only strips control characters — see
  `src/main/java/ai/labs/eddi/utils/LogSanitizer.java` — it does not remove the printable
  path itself). An invalid `modelCachePath` or `workingDirectory` could therefore place a
  username's home directory or an internal project path into the warning log (CWE-532).
  The log line now names the rejected parameter key only and omits the value; the other
  `ModelParameterValues` warnings (`applyBoolean`, `booleanValue`, `rejected`) and
  `JlamaLanguageModelBuilder.applyWorkingQuantizedType` were swept for the same pattern —
  none of them carry filesystem paths, so they were left as-is.
- The `jlamaNoteTuning` copy claimed the container-cached model is "re-downloaded on every
  restart." A container restart (`docker restart`, a crash restart) preserves its writable
  layer, so the cache actually survives a restart; it is lost only when the container is
  removed or replaced (a redeploy, `docker rm`, a Kubernetes pod recreation). Reworded the
  English fallback in `agent-wizard.tsx` and all 11 locale translations to say the model
  "may be re-downloaded after the container is removed or replaced."

### Design decisions

- **Omit the field rather than disable it.** A disabled or ignored Base URL input still
  tells the reader an endpoint exists. For an in-process provider it does not, so the
  field is not rendered at all — the same reasoning the group-collaboration configs use
  for tools that are never assembled (root `AGENTS.md` §4.2).
- **Only ship model ids the repo already vouches for.** A longer catalogue would have
  meant guessing at Hugging Face repo names, which is exactly the failure being fixed.
  The hint text carries the `owner/name` rule so a user can supply their own.
- **`workingQuantizedType` is parsed case-insensitively** and an unknown value keeps
  Jlama's default. `q4` is what a user writes; `Q4` is what the enum calls it.
- **`jlama-core` stays undeclared in `pom.xml`.** `DType` is imported from it
  transitively via `langchain4j-jlama`; pinning a second version of a library the
  langchain4j artifact already manages would be the worse hazard.

### Files

- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/JlamaLanguageModelBuilder.java`
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/ModelParameterValues.java`
- `src/test/java/ai/labs/eddi/modules/llm/impl/builder/LanguageModelBuildersTest.java`
- `src/test/java/ai/labs/eddi/modules/llm/impl/builder/ModelParameterValuesTest.java`
- `docs/langchain.md` — the Jlama section now states the `owner/name` requirement, that
  there is no `baseUrl`, and documents the five parameters in a table
- `ui/manager/src/lib/model-suggestions.ts`
- `ui/manager/src/lib/api/agent-setup.ts`
- `ui/manager/src/lib/api/operator.ts` — stale "(Ollama, Jlama)" doc comment
- `ui/manager/src/components/operator/operator-activation.tsx`
- `ui/manager/src/pages/agent-wizard.tsx`
- `ui/manager/src/lib/__tests__/model-suggestions.test.ts` (new)
- `ui/manager/src/pages/__tests__/agent-wizard.test.tsx`
- `ui/manager/src/i18n/locales/*.json` (11 files)

### Verification

- `./mvnw compile` clean; `LanguageModelBuildersTest` + `ModelParameterValuesTest` — the
  new Jlama and `applyPath` cases pass (the pre-existing failures in that class are the
  sandbox's "Unable to establish loopback connection", not this change).
- Repo-wide guards green: `ImportStyleTest`, `DocumentationLinksTest`,
  `StrictBoundaryShippedConfigsTest`, `RuleSetStoreShippedRulesetsTest`,
  `ChangelogRotationTest`, `BuildQualityGatesTest`.
- Round 3: `.\mvnw.cmd compile` (Checkstyle + `formatter:validate` clean); `.\mvnw.cmd test
  "-Dtest=LanguageModelBuildersTest,ModelParameterValuesTest,JlamaRuntimeSupportTest,ChangelogFragmentTest"` —
  all Jlama- and `applyPath`-specific cases pass (same pre-existing loopback-socket
  failures as above, unrelated to this change).
- Manager: `npm run typecheck`, `npm run lint`, `npm run i18n:check`, `npm run build`
  and the full suite (413 files, 6556 tests) all pass.
- **The six new UI tests were mutation-checked.** Reverting the suggestions, the
  default model or `IN_PROCESS_PROVIDERS` fails the five that pin the wizard's Jlama
  behaviour; dropping the `handleProviderChange` clear fails the sixth. They would
  have caught this.

---

## 📝 docs(mcp): how to reach an authenticated `/mcp`, and the plan to stop needing this (2026-09-20)

**Repo:** EDDI (`docs/mcp-oauth-plan`)

A local MCP client — Claude Desktop, Claude Code, Cursor, LM Studio — cannot practically
manage an EDDI instance that has OIDC enabled. `/mcp` carries an `authenticated` policy
(its own `quarkus.http.auth.permission.mcp` rule), EDDI is bearer-only (`application-type=service`), and it
advertises no OAuth metadata, so a client that would log in by itself gets a bare 401 with
nothing to discover. The only way in is a hand-pasted token that the shipped realm lets
expire after Keycloak's default five minutes, and there is no long-lived key for `/mcp`
(the only api-key surface is the `/v1` adapter).

The Quick Start in `docs/mcp-server.md` only ever showed the unauthenticated
`localhost:7070` case, so nothing said any of that.

### What changed

- **`docs/mcp-server.md`** — new *Connecting to an authenticated instance* section under
  Authentication & Authorization: get a token from the public `eddi-frontend` client, pass
  it either as a header on a Streamable-HTTP client or through `mcp-remote` (whose argument
  splitting means the value belongs in an env var), and four caveats in the order they
  bite — expiry, no api key, roles decide which tools work, and `/mcp` cannot be opened
  selectively. The Quick Start now points at it.
- **`docs/mcp-server.md`** — the Configuration block documented `quarkus.mcp-server.http.root-path`.
  That hyphenated form is not a key the extension knows; `application.properties`
  already says so. Corrected to `quarkus.mcp.server.http.root-path` with the warning kept.
- **`planning/mcp-oauth-protected-resource-plan.md`** (new) — the fix: advertise `/mcp` as an
  RFC 9728 protected resource so the client runs the OAuth flow and refreshes its own token,
  removing the shared long-lived credential rather than automating its rotation.

### Decisions

- **Rotation is the wrong problem to solve.** The instinct is to reuse **Connections**, which
  already does lazy OAuth refresh with a single-flight claim. It cannot apply: a connection
  resolves to a header on a request *EDDI originates*, and here EDDI is the callee. Connections
  exists because EDDI holds a credential it must refresh; inbound, the client holds it.
- **Serving the metadata is configuration, not code.** Quarkus OIDC 3.39.3 already ships
  `ResourceMetadataHandler` and appends `resource_metadata="…"` to the 401 challenge. The
  plan's Increment 1 is four properties, a permit rule and a Keycloak client.
- **A permit rule is mandatory, not a precaution.** That handler registers as
  `FilterBuildItem(handler, 50)`, and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — it
  runs *after* authorization, so the catch-all at `/*` would 401 the discovery document and
  the flow could never start.
- **Pre-registered client over dynamic registration.** The realm defines no `roles` client
  scope; `eddi-frontend` gets `realm_access.roles` only from its own protocol mapper. A
  dynamically registered client cannot carry mappers, so its tokens authenticate and then
  fail every tool with "requires role" — the worst failure shape available.
- **Review follow-up (2026-09-21).** The §3.2 configuration block quoted a hardcoded `/mcp`
  in both `resource-metadata.resource` and the permit rule's second path. What ships derives
  both from `${quarkus.mcp.server.http.root-path}`, so an operator who moves the MCP root
  moves the metadata document and its permit rule with it; a hardcoded permit path would
  leave the relocated document behind the `authenticated` policy and 401 the discovery
  request before it starts. The snippet now matches `application.properties`.

- **The EDDI → client direction is deliberately out of scope** and recorded as such in the
  plan, so it is not re-derived: it needs Claude Code channels rather than MCP, and two
  design answers first — attribution (nothing reads the token's `azp`, so a model answering
  a HUMAN member's turn is recorded as the person) and keeping HITL decisions out of an
  AI client's reach.

### Files

- `docs/mcp-server.md`
- `planning/mcp-oauth-protected-resource-plan.md` (new)

## 🔑 feat(keycloak): ship an `eddi-mcp` client for MCP clients to log in through (2026-09-20)

**Repo:** EDDI (`feat/keycloak-mcp-client`, stacked on `feat/mcp-oauth-discovery`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md),
second half. The previous entry made EDDI tell a client *where* to authenticate; this gives
it something to authenticate as.

### What changed

- **All three realm copies** (`keycloak/`, `helm/eddi/files/`, `k8s/overlays/auth/`) gain
  `eddi-mcp`: public, authorization code + PKCE `S256` required, direct access grant / implicit
  / service accounts all off, redirect URIs `http://localhost:*` and `http://127.0.0.1:*`, no
  web origins, and the `realm-roles`, `eddi-backend-audience` and `groups` protocol mappers
  copied from `eddi-frontend`.
- **`DeploymentManifestsTest`** — a case per realm copy asserting the flow settings, the PKCE
  requirement, the mappers (by claim name and by *access* token, not just id token), that no
  redirect is `*` or a remote http URL, that `webOrigins` is empty, and that neither `name` nor
  `description` exceeds 255 characters: Keycloak stores them in `VARCHAR(255)` and an over-long
  value does not truncate — **the realm import fails and Keycloak exits 1**, which is how the
  first draft of this client took down every stack that imports the realm. Found by running the
  import, not by reading the file.
- **`helm/eddi/templates/NOTES.txt`**, **`k8s/overlays/auth/kustomization.yaml`**,
  **`docs/security.md`** — every place that told an operator to grant an account "those two
  roles" now names all three. Following the old instruction built an administrator that logs in
  and is refused every MCP read tool, which is the trap the realm change exists to close.
- **`.github/workflows/ci.yml`** — `keycloak/**` added to the `code` and `backend` path
  filters. `k8s/` and `helm/` were already there, so the compose realm was the one copy whose
  change ran no CI — including the audience mapper every accepted token depends on.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the client, how to point a client at it,
  why dynamic registration is not an option here, and what to do on an **existing** realm:
  `--import-realm` never re-imports into a realm that already exists and both auth stacks keep
  Keycloak's database in a named volume, so an upgrade leaves the client absent and the flow
  ends in `invalid_client`. The manual steps are listed, `realm-roles` first.

- **All three realm copies** — the seeded `eddi` administrator gains `eddi-viewer` alongside
  `eddi-admin`/`eddi-editor`. There is no role hierarchy, so without it the account an operator
  points their first MCP client at completes the login and is then refused all 27 viewer-gated tools.
  A test pins it. `scripts/make-test-realm.mjs` guards that fixture set against the realm and
  fails the auth E2E run when the two drift, so `ROLE_FIXTURES` and `e2e/auth/auth-helpers.ts`
  move with it — which is how CI caught this change the first time it ran.

### Decisions

- **Pre-registered client, not dynamic registration.** Not a preference: this realm supplies its
  own `clientScopes` and defines no `roles` scope, so `realm_access.roles` comes only from a
  client's own protocol mapper. RFC 7591 registration carries no mappers, so a self-registered
  client would mint tokens that authenticate and then fail every tool with "requires role" —
  login succeeded, everything forbidden. Keycloak's default registration policies would also
  have to be loosened in at least three places to get there.
- **Loopback redirects only; `https://claude.ai/api/mcp/auth_callback` is not shipped.** Claude
  Desktop connectors redirect to that remote callback, so the authorization response for a
  self-hosted EDDI would pass through a third party. That is an operator's decision, documented
  in `docs/mcp-server.md`, rather than a default inherited from us.
- **No `webOrigins`, not even `+`.** These clients are native processes; `eddi-frontend` needs
  browser origins and this one never makes a browser request.
- **The redirect list is the one `[ext]` assumption in the plan.** Which loopback path each
  client uses is documented client behaviour rather than something verified here, so the entries
  are the broad `localhost` / `127.0.0.1` wildcards the realm already uses for the SPA, and the
  docs say to add anything else in the admin console.

### Files

- `keycloak/eddi-realm.json`, `helm/eddi/files/eddi-realm.json`, `k8s/overlays/auth/eddi-realm.json`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/mcp-server.md`, `docs/security.md`

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
| 2026-09-22 | Round boundaries are recovered from QUESTION entries but validated against the stored index | Only the current round's start is persisted. Trusting the QUESTION invariant blindly split a discussion on a stray marker; validating and falling back narrows the switcher instead of slicing the view wrongly. | EDDI Manager |
| 2026-09-22 | The interaction band is a list, not a graph | A force-directed diagram of five nodes is decoration and of twenty is unreadable; a list answers "who did this member take on" and "who went unchallenged" at any size with no layout engine. | EDDI Manager |
| 2026-09-22 | DELPHI gets no interaction band | Naming who answered whom would undo the anonymity the method rests on — the reason its later rounds run ANONYMOUS. | EDDI Manager |
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call, and also widen `sanitizeSubject` to strip CR, LF and tab | The log call and the subject token answer different questions, so both are fixed. Widening was first rejected as moving the subject namespace; that was wrong — NATS refuses a subject containing those characters outright, so nothing was published under one, and the rejection is an unchecked `IllegalArgumentException` that escapes the publish's `IOException \| JetStreamApiException` handler | Leave CR/LF in `sanitizeSubject` and rely on the log call alone — keeps a latent unchecked-exception path for no gain |
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with a repeatable startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
| 2026-09-21 | Arm a legacy row in the zone the poller will use, rather than in UTC or by widening the store's re-arm to carry one | `armIfUnarmed` writes only `nextFire`, so a legacy row's `timeZone` stays null and the poller re-arms it in the deployment default; arming the first fire in UTC regardless would make exactly one interval the wrong length | Adding a zone to the store's re-arm on both backends (CodeRabbit's suggestion — it would also normalise legacy rows to UTC, at the cost of a third field in a predicate that exists to do one thing), leaving the fixed-UTC arm and the drift with it |
| 2026-09-21 | Close the two-node repair race with a conditional `armIfUnarmed` on both stores rather than a re-read | A re-read narrows the window to one store round-trip and still reads a snapshot; the predicate is the only place two nodes meet, and ~20 lines per backend is a small price for a write that cannot skip a fire | A re-read before writing (narrows, does not close), a distributed lock for a startup sweep, leaving the inaccurate idempotency claim in place |
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-17 | Keep `deny-licenses` in dependency-review, broadened to GPL-2.0, LGPL-2.0/2.1/3.0, SSPL-1.0, BUSL-1.1 and Elastic-2.0 | An allow-list would fail today on the `LicenseRef-bad-non-standard` values GitHub reports for jsoup and classgraph, and would gate nothing extra — unknown licences are informational in both modes | Migrate to `allow-licenses` (needs two permanent per-package exclusions to work around GitHub's normalisation); leave the list at GPL-3.0/AGPL-3.0 (misses the source-available relicensing hazard that actually threatens a project depending on MongoDB and Elasticsearch clients) |
| 2026-09-17 | Mark secret context on the value (`"secret": true`), scrub every copy when the turn ends | A per-user credential sent as context was stored, echoed and copied into properties; `scope: secret` holds one vault slot per agent | A list of secret keys in the agent configuration — couples every agent to one client's field names |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
| 2026-09-20 | Escape record boundaries in the throwable's MESSAGE before the trace is rendered, not in the rendered `%s%e` output | `%e` prints `toString()` as the trace's first line, so a CR/LF in an exception message forged a record past every call-site `sanitize(...)` | Scan the rendered trace and keep the breaks that begin `\tat ` / `Caused by:` / `\t... N more` — an attacker can write all three into a message, so the scan has to guess; or drop the throwable at the ~415 call sites — the stack trace is often the only diagnostic left |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus a repeatable startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |

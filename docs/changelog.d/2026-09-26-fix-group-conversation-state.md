## 🐛 fix(groups): group-conversation state races, deleted discussions coming back, workspace lost updates (2026-09-26)

**Repo:** EDDI (`fix/group-conversation-state`)

### What changed and why

**H14a — a cross-pod cancel was undone by the running leg.** Group control is per-pod: the cancel token lives in
the process that runs the leg, so a cancel landing on another pod can only CAS the persisted state
`IN_PROGRESS → CANCELLED` and report success. The running pod's next whole-document write
(`conversationStore.update(gc)`, an upsert) wrote `IN_PROGRESS` back, and its completion CAS then committed
`COMPLETED`. The phase-boundary re-read narrowed the window without closing it, and ten writes were unconditional:
the phase-boundary persist, the four cancel writes (top of the phase loop, before the HITL gate, and the two
cancel branches of the catch blocks), the facilitator phase-list persist, and the four pause / no-progress commits
in `GroupHitlCoordinator`. Every one of them now goes through
`RunningDiscussionWrites.updateWhileRunning`: a compare-and-swap on the persisted state still being `IN_PROGRESS` or
`SYNTHESIZING` (a leg flips to `SYNTHESIZING` in memory before it persists the flip, so both are tried). A lost CAS
raises `DiscussionSupersededException`; `executeDiscussion` catches it, adopts the persisted outcome, aligns the
in-memory state so the `finally` makes the right ephemeral-agent decision, and always sends the listener a terminal
event so a streaming client's sink closes: `cancelled` for CANCELLED, `group_complete` for COMPLETED/CLOSED, a
curated `group_error` for anything else (FAILED, REJECTED, a pause committed elsewhere). The completion CAS uses the same helper, which also fixes a completion that failed when
the in-memory `SYNTHESIZING` had not been persisted yet.

**H14b — deleting a running discussion let it come back.** Two parts. (1) `GroupConversationStore.update` is now a
replace of an existing row only (`storeIfCurrentVersion(SINGLE_VERSION)`), raising `GroupConversationGoneException`
when the row is gone — **the same change, byte for byte, as `fix/gdpr-erasure`**, so the merge is trivial; the
`stopAsDeleted` / `isDeletedWhileRunning` / `persistCancelled` helpers and the catch blocks are ported from that
branch verbatim (its `activeTokens` → `discussionControls` rename too); only `persistCancelled` differs, because here
it writes conditionally.
(2) `GroupLifecycleOps.deleteGroupConversation` now signals a leg running on this node (`CANCEL_IMMEDIATE`) before it
ends the members and deletes the ephemeral agents — the first `discuss()` leg is not an "operation in progress", so
the existing guard never saw it. A leg on another node has no token here. It finds out one of two ways, and both
end it as a cancel, not a failure (no ERROR log, no failure metric, no 5xx): its next conditional write finds the
document gone, or — because the deleting node also ended the member conversations and deleted the ephemeral agents —
a member turn fails first, and before treating that failure as the discussion's the catch blocks re-read the
document (`stopIfEndedElsewhere`): gone means deleted, a non-running state means superseded.

**H14c — the workspace store had three write schemes that ignored each other.** `casRevision` compared the revision,
`casRunningDiscussion` compared the run claim, and cadence add/delete wrote unconditionally — each a whole-document
replace. A backlog add read before a cadence claim passed its revision check after it and wrote the claim away
(run orphaned, pulled tasks back to PENDING and pulled again); a claim wrote back a backlog missing a concurrently
added task. Now there is one scheme: every write compares and bumps the revision. (Every workspace document carries
a revision — the field defaults to `"0"` and exists since workspaces do — so a missing one is refused as corrupt;
`casRunningDiscussion` no longer takes the expected claim, which an unchanged revision already implies.)
`IGroupWorkspaceStore.update` is gone. Cadence add/delete are revision-checked with the same re-read-and-retry as
backlog adds and answer **409** after `MAX_CAS_ATTEMPTS`. A lost add deletes the schedule it just created; a delete
writes the workspace first and deletes the schedule only once that write has landed, so a 409 leaves cadence and
schedule intact together. Because a revision guard also
loses to unrelated edits, `TeamCadenceService` re-reads on a lost claim or settle: a claim is re-applied to the fresh
document while it is still idle and every pulled task still executable; a settle is redone on the fresh document
while it still names the discussion being settled. Both are bounded (`MAX_WRITE_ATTEMPTS = 3`).

**Cadence claim loss ran phase 0.** `startCadenceDiscussionAsync` created *and submitted* the discussion before the
claim, so a lost claim could only cancel — gracefully — a leg that might already be inside phase 0. It is replaced by
`prepareCadenceDiscussion`, returning a `CadenceDiscussion` handle: the conversation exists (so the claim carries its
id), nothing runs until `launch()` after the claim is won, and a lost claim calls `abandon()` (CAS
`IN_PROGRESS → CANCELLED` of a discussion that never ran, and drops its control token).

**Group prompt and record bounds (M-G1, M-G2, M-G3, M-G4, G3)** — the bounds are configurable, with the values
below as defaults and hard ceilings no config can exceed (see "Config surface").
- *M-G1* `NegotiationEngine`: one BARGAIN turn records at most 5 concessions, each stored truncated to the quoting
  bound (600 chars); the ledger stops at 50 (WARN, earliest kept — they are the record the outcome quotes); a turn's
  prompt quotes only the newest 20 with "(N earlier concession(s) omitted)", which also bounds ledgers stored before
  the caps.
- *M-G2* `VoteTallyEngine`: only what was cast counts. For a JSON ballot only the `vote`/`votes` values are read,
  never the free-text `statement`, which the old fallback scanned; an explicitly empty ballot (`"votes": []`,
  `"vote": null`, or no vote field) is a non-vote; a cast in a shape the method did not ask for still counts when it
  names exactly one option (`{"votes": ["X"]}` under MAJORITY, `"votes": "X"` under APPROVAL). Prose ballots and the
  tiebreak's `resolveChoice` match an option as a whole word/phrase, so "No" is not found inside "not"/"know" — but
  the boundary applies only between words of space-separated scripts, so Chinese/Japanese/Thai options still match
  by substring ("我支持方案A。" votes `方案A`).
- *M-G3* `PhaseExecutionEngine.setDecisionCarryingDissents`: earlier dissents are merged with the new decision's
  (earlier first, identical ones once) instead of being replaced whenever the new decision carried any.
- *M-G4* `RetroEngine`: a stored lesson value (lesson + "applies:" context) is bounded to 1000 chars — the default of
  an agent's `memoryGuardrails.maxValueLength`. The context is truncated first and dropped when too little room is
  left. The idempotency key hashes the **untruncated** lesson, so a lesson stored before the cap is recognised rather
  than stored a second time.
- *G3* `StanceSummaryEngine`: summarizer input is the member's newest contributions within 8,000 chars (2,000 per
  contribution), with "[N earlier contribution(s) omitted]" — it used to be every contribution concatenated, re-sent
  at every boundary and eventually past the summarizer's context window.

**Timed-out PARALLEL members were anonymous.** When the batch deadline released a parallel speaker (or its turn
errored), the orchestrator recorded a `SKIPPED`/`ERROR` entry for `"unknown"` and fired no `speaker_complete` for it —
only successful entries did. The transcript could not say who timed out, and a client that showed the member typing
on `speaker_start` kept showing it forever (the backend half of the Workforce "typing indicator forever" finding).
Every outcome is now attributed to its speaker and closed with `speaker_complete`. `speaker_complete` gains an
additive `outcome` field — `TIMEOUT`, `SKIPPED` or `ERROR`, `null` for a contribution — and a turn without a
contribution carries no content (`response: null`; its reason stays in the transcript's `errorReason`, so no raw
exception text goes over the wire), letting a client tell a timeout from something the member said.

**Workforce history owner filter ran after pagination.** `GET /groups/{groupId}/conversations` (and MCP
`list_group_conversations`) fetched a page of everyone's conversations and then removed the ones a non-admin does not
own, so non-admins got short or empty pages while their conversations sat on later ones. The owner restriction is now
part of the store query (`IGroupConversationStore.listByGroupId(groupId, ownerUserId, index, limit)`, the userId
escaped and anchored like the erasure sweep and re-checked exactly), so `index`/`limit` page through the caller's own
conversations. The REST shape is unchanged; the UI needs no change to benefit.

### Deferred / notes

- *Timed-out member writes*: read as the attribution + `speaker_complete` defect above. A related gap stays open: with
  `onAgentFailure: RETRY`, a member whose attempt timed out is re-sent a turn while the first may still be running on
  the coordinator (the conversation queue serializes them), so the member can execute twice. Fixing it needs the
  member turn cancelled through the coordinator, which is its own change.
- Merges with `fix/gdpr-erasure` (#834): this branch carries that PR's rename and its lost-ownership helpers
  verbatim, so a merge leaves six small conflict hunks, five in `GroupConversationService`: (1) the cadence
  `launch()` block — keep this branch; (2, 3) the two catch blocks — keep this branch (it adds the
  `stopIfEndedElsewhere` re-read after the lines both branches share); (4) `persistCancelled` — keep this branch's
  conditional `persistWhileRunning`; (5) the `discussionControls` field — take #834's `erasureStepName` /
  `stopInFlightWork`. The sixth is one line in `GroupConversationServiceBranchCoverageTest`, where this branch
  carries #834's deleted-while-running tests with the Gone stub moved to `updateIfState` — keep this branch.
  `GroupLifecycleOps`, `GroupHitlCoordinator` and `GroupConversationStore` merge cleanly, and the resolved merge
  compiles (main and tests) with #834's erasure and in-flight tests passing.
- Review refutation: `speaker_complete` for a timed-out or failed parallel turn never carried raw exception text —
  the text sat in the transcript entry's `errorReason`, and the event carried the (null) `content`. The new `outcome`
  flag makes the distinction explicit.

### Config surface

All optional; absent means the defaults, and every value is clamped to its ceiling (non-positive = default):

```json
"negotiationConfig": { "maxConcessionsPerMove": 5, "maxLedgerConcessions": 50, "maxRenderedConcessions": 20 },
"retroConfig": { "maxLessonsPerRun": 3, "maxStoredLessons": 50, "maxLessonChars": 1000 },
"stanceSummary": { "maxChars": 160, "maxInputChars": 8000, "maxEntryChars": 2000 }
```

Ceilings: 20 / 500 / 100 for the negotiation caps, 4000 for `maxLessonChars`, 32000 / 8000 for the stance input
caps. Documented in [`group-conversations.md`](../group-conversations.md).

### Compatibility

- Stored group configs: new optional fields only; existing configs load unchanged and get the defaults.
- REST: `POST` and `DELETE /groupstore/groups/{id}/workspace/cadences…` can now answer **409** when the workspace is
  modified concurrently through three attempts (they could not conflict before — they overwrote).
  `GET /groups/{groupId}/conversations` pages differently for non-admins (their own conversations only, in the query).
- SSE: `speaker_complete` gains the additive `outcome` field and fires for timed-out parallel members.
- Internal API only: `IGroupWorkspaceStore.update` and `GroupConversationService.startCadenceDiscussionAsync` are
  removed; `casRunningDiscussion` takes one argument.

**Files:** [`RunningDiscussionWrites.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/RunningDiscussionWrites.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`GroupHitlCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java),
[`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java),
[`GroupConversationStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/GroupConversationStore.java),
[`GroupWorkspaceStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/GroupWorkspaceStore.java),
[`RestGroupWorkspace.java`](../../src/main/java/ai/labs/eddi/configs/groups/rest/RestGroupWorkspace.java),
[`TeamCadenceService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/TeamCadenceService.java)

**Tests:** `GroupConversationStateRaceTest` (a CAS fake store: a cancel landing between the boundary re-read and the
write; a delete mid-run; delete signalling the leg; superseded to FAILED/CLOSED sends a terminal event; a member
turn failing after a cross-node delete ends as a cancel), `RunningDiscussionWritesTest`, `GroupWorkspaceStoreTest`,
`RestGroupWorkspaceTest` (cadence 409s, schedule kept on a lost delete), `TeamCadenceServiceTest` (claim/settle
retries on the fresh document, prepare → claim →
launch order, abandon on a lost claim), `NegotiationEngineTest`, `VoteTallyEngineTest`, `PhaseExecutionEngineTest`,
`RetroEngineTest`, `StanceSummaryEngineTest`, `GroupConversationServiceConcurrencyTest` (timeouts attributed and
closed), `GroupConversationStoreTest` / `RestGroupConversationTest` / `McpGroupToolsTest` (owner in the query).

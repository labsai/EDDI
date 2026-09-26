## 🐛 fix(groups): group-conversation state races, deleted discussions coming back, workspace lost updates (2026-09-26)

**Repo:** EDDI (`fix/group-conversation-state`)

### What changed and why

**H14a — a cross-pod cancel was undone by the running leg.** Group control is per-pod: the cancel token lives in
the process that runs the leg, so a cancel landing on another pod can only CAS the persisted state
`IN_PROGRESS → CANCELLED` and report success. The running pod's next whole-document write
(`conversationStore.update(gc)`, an upsert) wrote `IN_PROGRESS` back, and its completion CAS then committed
`COMPLETED`. The phase-boundary re-read narrowed the window without closing it, and ten writes were unconditional:
the phase-boundary persist, the three cancel writes, the facilitator phase-list persist, and the four pause /
no-progress commits in `GroupHitlCoordinator`. Every one of them now goes through
`RunningDiscussionWrites.updateWhileRunning`: a compare-and-swap on the persisted state still being `IN_PROGRESS` or
`SYNTHESIZING` (a leg flips to `SYNTHESIZING` in memory before it persists the flip, so both are tried). A lost CAS
raises `DiscussionSupersededException`; `executeDiscussion` catches it (directly or in a cause chain), adopts the
persisted outcome, aligns the in-memory state so the `finally` makes the right ephemeral-agent decision, and tells
the listener on a cancel. The completion CAS uses the same helper, which also fixes a completion that failed when
the in-memory `SYNTHESIZING` had not been persisted yet.

**H14b — deleting a running discussion let it come back.** Two parts. (1) `GroupConversationStore.update` is now a
replace of an existing row only (`storeIfCurrentVersion(SINGLE_VERSION)`), raising `GroupConversationGoneException`
when the row is gone — **the same change, byte for byte, as `fix/gdpr-erasure`**, so the merge is trivial; the
`stopAsDeleted` / `persistCancelled` handling in `executeDiscussion` follows that branch's shape and names too.
(2) `GroupLifecycleOps.deleteGroupConversation` now signals a leg running on this node (`CANCEL_IMMEDIATE`) before it
ends the members and deletes the ephemeral agents — the first `discuss()` leg is not an "operation in progress", so
the existing guard never saw it. A leg on another node has no token here; its next conditional write finds the
document gone and it ends as a cancel, not a failure (no ERROR log, no failure metric, no 5xx).

**H14c — the workspace store had three write schemes that ignored each other.** `casRevision` compared the revision,
`casRunningDiscussion` compared the run claim, and cadence add/delete wrote unconditionally — each a whole-document
replace. A backlog add read before a cadence claim passed its revision check after it and wrote the claim away
(run orphaned, pulled tasks back to PENDING and pulled again); a claim wrote back a backlog missing a concurrently
added task. Now there is one scheme: every write compares and bumps the revision (the run-claim value is only the
fallback guard for a pre-revision document, whose first write stamps one). `IGroupWorkspaceStore.update` is gone.
Cadence add/delete are revision-checked with the same re-read-and-retry as backlog adds (409 after
`MAX_CAS_ATTEMPTS`, and the just-created schedule is deleted rather than orphaned). Because a revision guard also
loses to unrelated edits, `TeamCadenceService` re-reads on a lost claim or settle: a claim is re-applied to the fresh
document while it is still idle and every pulled task still executable; a settle is redone on the fresh document
while it still names the discussion being settled. Both are bounded (`MAX_WRITE_ATTEMPTS = 3`).

**Cadence claim loss ran phase 0.** `startCadenceDiscussionAsync` created *and submitted* the discussion before the
claim, so a lost claim could only cancel — gracefully — a leg that might already be inside phase 0. It is replaced by
`prepareCadenceDiscussion`, returning a `CadenceDiscussion` handle: the conversation exists (so the claim carries its
id), nothing runs until `launch()` after the claim is won, and a lost claim calls `abandon()` (CAS
`IN_PROGRESS → CANCELLED` of a discussion that never ran).

**Group prompt and record bounds (M-G1, M-G2, M-G3, M-G4, G3).**
- *M-G1* `NegotiationEngine`: one BARGAIN turn records at most 5 concessions, each stored truncated to the quoting
  bound (600 chars); the ledger stops at 50 (WARN, earliest kept — they are the record the outcome quotes); a turn's
  prompt quotes only the newest 20 with "(N earlier concession(s) omitted)", which also bounds ledgers stored before
  the caps.
- *M-G2* `VoteTallyEngine`: a contract-shaped ballot naming no option (`"votes": []`, `"vote": null`) is a non-vote —
  it no longer falls through to the prose scan, which counted whatever option the free-text `statement` mentioned.
  The prose scan (and the tiebreak's `resolveChoice`) match an option as a whole word/phrase, so "No" is not found
  inside "not"/"know".
- *M-G3* `PhaseExecutionEngine.setDecisionCarryingDissents`: earlier dissents are merged with the new decision's
  (earlier first, identical ones once) instead of being replaced whenever the new decision carried any.
- *M-G4* `RetroEngine`: a stored lesson value (lesson + "applies:" context) is bounded to 1000 chars — the default
  cap `UserMemoryTool` applies to every other agent-written memory value. Truncated at parse time, so the idempotency
  key hashes exactly what is stored; the context is dropped when too little room is left.
- *G3* `StanceSummaryEngine`: summarizer input is the member's newest contributions within 8,000 chars (2,000 per
  contribution), with "[N earlier contribution(s) omitted]" — it used to be every contribution concatenated, re-sent
  at every boundary and eventually past the summarizer's context window.

**Timed-out PARALLEL members were anonymous.** When the batch deadline released a parallel speaker (or its turn
errored), the orchestrator recorded a `SKIPPED`/`ERROR` entry for `"unknown"` and fired no `speaker_complete` for it —
only successful entries did. The transcript could not say who timed out, and a client that showed the member typing
on `speaker_start` kept showing it forever (the backend half of the Workforce "typing indicator forever" finding).
Every outcome is now attributed to its speaker and closed with `speaker_complete` (null content for a skip).

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
- Merges with `fix/gdpr-erasure`: `GroupConversationStore.update` is identical on both branches; that branch also
  renames `activeTokens` to `discussionControls`, which touches a handful of the same lines here
  (`GroupLifecycleOps.deleteGroupConversation`, the cancel branches of `executeDiscussion`) — resolve by keeping this
  branch's logic under the new name.

### Compatibility

No stored-JSON, REST or MCP shape changes. `IGroupWorkspaceStore.update` and
`GroupConversationService.startCadenceDiscussionAsync` are removed (internal API, no external consumers).

**Files:** [`RunningDiscussionWrites.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/RunningDiscussionWrites.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`GroupHitlCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java),
[`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java),
[`GroupConversationStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/GroupConversationStore.java),
[`GroupWorkspaceStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/GroupWorkspaceStore.java),
[`RestGroupWorkspace.java`](../../src/main/java/ai/labs/eddi/configs/groups/rest/RestGroupWorkspace.java),
[`TeamCadenceService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/TeamCadenceService.java)

**Tests:** `GroupConversationStateRaceTest` (a CAS fake store: a cancel landing between the boundary re-read and the
write; a delete mid-run; delete signalling the leg), `RunningDiscussionWritesTest`, `GroupWorkspaceStoreTest`,
`RestGroupWorkspaceTest`, `TeamCadenceServiceTest` (claim/settle retries on the fresh document, prepare → claim →
launch order, abandon on a lost claim), `NegotiationEngineTest`, `VoteTallyEngineTest`, `PhaseExecutionEngineTest`,
`RetroEngineTest`, `StanceSummaryEngineTest`, `GroupConversationServiceConcurrencyTest` (timeouts attributed and
closed), `GroupConversationStoreTest` / `RestGroupConversationTest` / `McpGroupToolsTest` (owner in the query).

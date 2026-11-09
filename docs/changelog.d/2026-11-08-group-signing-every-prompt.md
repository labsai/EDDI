## fix(signing): every prompt a verifying member receives is built from its verified view (2026-11-08)

**Repo:** EDDI (`fix/group-signing-every-prompt`, on top of `fix/group-signing-verification`)

### What changed and why

`security.requirePeerVerification` filtered a member's phase prompt and its `groupTranscript`
context, and nothing else. Every prompt the engine composes itself for a role turn quoted the
transcript, or records built from it, as stored: the convergence judge, a dissenter, the
facilitator's briefing, the vote tie-breaker, the task-force execute, verify and bid prompts, a
follow-up's `groupTranscript`, and a nested group's question. In a task force the flag protected
only the side channel: a verifying executor saw a dependency's unsigned result in full, while its
`groupTranscript` showed the same result withheld. The engine's own records (the negotiation table,
the vote tally, a retrospective's lessons) also counted unverified entries and then quoted their
text back to verifying members, and several entries the engine writes for an agent were recorded
unsigned under its id, so verifying peers saw them withheld even when every agent signs.

- **One view, everywhere.** `GroupSigningGuard.View` now answers per stored entry (`of`, `trusts`)
  as well as for the whole transcript, and `GroupSigningGuard.viewOf(guard, reader, gc)` is how
  every engine reads the transcript for an agent. `PhaseExecutionEngine`, `FacilitatorEngine`,
  `TaskForceEngine` and `GroupLifecycleOps` get the guard by setter, as `GroupContextBuilder`
  already did; without one they read the transcript as stored.
- **Role turns.** The moderator judge's input is built from the round as the moderator sees it;
  the `SERVICE` judge, which is no agent but whose verdict ends the phase for everyone, reads it
  through the group's records view (below). Each dissenter reacts to the synthesis
  as it sees it, and one for which this round's synthesis does not verify is not asked. The
  facilitator's excerpt is the latest contribution it may take as written.
- **Task records** (`TaskRecordView`). A result is quoted to a verifying reader only when a trusted
  entry of the assignee holds exactly that text, so an unsigned result or one edited in the store
  reads `[withheld: this text could not be verified]`, the reader's own results included when it
  signs (one that does not sign trusts its own entries, so it still sees its result); a result an approver
  edited (`SharedTaskList.reviewerEdits`) is the reviewer's text and is shown. A subject and
  description are withheld when an untrusted `PLAN` entry parses to them and no trusted one does,
  or when another member's task tool wrote them and that member does not sign verifiably (no key,
  signing off, key revoked or outside its window: `View.vouchesFor`). The engine records a
  tool-filed task under the member whose turn filed it (`createdByAgentId`), so a signing member's
  task is its text and a verifying assignee does it. A verification note is withheld when an untrusted
  `VERIFICATION` entry holds it and no trusted agent entry does. A verifying member whose own
  task's text does not verify fails the task without being asked; a bidder is announced only the
  tasks it may see. A verifying verifier is given only the tasks whose text and result it may see
  (`TaskRecordView.showsWork`); the rest stay `COMPLETED` with an engine note giving their count,
  and with none left it gets no turn. Before, every withheld task carried one shared placeholder,
  so its verdict matched no task and fell through to the fail-open path that marked every task
  `VERIFIED`.
- **Follow-ups** pass the member's view as `groupTranscript`, and the answer is signed.
- **Nested groups.** A `GROUP` speaker reads the parent transcript as strictly as its strictest
  reader: its agent members, its moderator and its facilitator (the set `readersOf` counts for the
  records), groups nested in it included; a group that cannot be read counts as verifying. Its
  prompt becomes the question each of them works from. The guard reads the group store itself (a
  constructor argument), so no separately wired resolver can be dropped.
- **Member tools.** `listGroupTasks` lists each subject as the calling member may see it
  (`GroupSigningGuard.taskSubjectsSeenBy`); the running discussion's guard reaches the tools through
  `LiveDiscussionRegistry.register(gc, guard)`. Shared artifacts are not signed, so the artifact
  tools are not assembled for a member that requires verification or whose setting cannot be read.
- **Cadence feedback.** A standing team's cadence wrote a failed task's raw result or verification
  note into the backlog description, which the next run shows as configured task text. It now
  writes the task as the group's records count it (`GroupConversationService.recordedTask`). A
  rework that did not run keeps the verifier's own feedback as the task's note (the engine says
  the rework did not run in a note of its own), so the note still traces to the verifier's entry;
  it used to be suffixed with " (the rework did not run)", which no entry held, and passed. The
  verification summary renders the verdict array the engine applied before any other array, so a
  reply carrying a second array still shows the decoded feedback the note is traced by.
- **Handoffs.** An `AUTO` tool handoff filed another team's agent's text into the backlog with no
  creator, and the cadence turned it into configured task text. The backlog task now records the
  agent that wrote it (`TeamHandoffService`, `TaskItem.filedBy`); `TaskDefinition` carries it as an
  engine-only `createdByAgentId` (`@JsonIgnore`: never written to or read from a stored config) and
  the run's task keeps it, so `View.vouchesFor` applies. `onDecision` handoffs and inbox items a
  person accepts name no agent; the docs and the operator recommend `REVIEW` intake for a team
  whose members verify, since those and the cadence question still quote the text.
- **User memories.** A group- or global-visible user memory another agent wrote is not loaded as a
  property (`Conversation.loadUserProperties`) or returned by the memory tool's recall and search
  (`UserMemoryTool`) for an agent that requires peer verification. The flag rides from the deployed
  agent (`Agent.setPeerVerificationRequired`) onto every turn's memory, like the continuation
  policy.
- **The engine's records** (`GroupSigningGuard.recordView`, `recordedEntries`). Once any member,
  the moderator or the facilitator requires verification, the `SERVICE` judge's input, the
  negotiation table, an arbitration
  verdict, the vote's options and ballots and a retrospective's lessons count only entries that
  verify for every reader (no reader's own entries are exempt). The ballot prompt now takes its
  options from the voter's own view, which reverses the previous PR's choice of the stored
  transcript: an unverified synthesis offers no options, and the vote does not run on it. A
  `HUMAN_DECIDES` tie resumed by the person or the timeout moderator is rebuilt from the same
  records (`HumanTieBreakEngine.rebuildOutcome` takes `PhaseExecutionEngine.recordsOf`), a
  facilitator's `CALL_VOTE` is refused once a reader verifies unless the facilitator signs
  verifiably, and a facilitator that is switched off is no reader (`readersOf`).
- **Entries written for an agent.** The readable verification summary, the facilitator's notes
  (an escalation dropped for an approval gate or for a tie-break included) and the recruitment note
  are signed for the agent and named by it, as a `DISSENT` or `BID` already is: each carries the
  agent's own text. The tie-break note was an engine entry quoting the facilitator, and the
  recruitment note an unsigned entry named `System` under the recruiter. The task-failure note is
  the engine's own (`system`, with the member as its target).

Docs: [group-conversations.md](../group-conversations.md#signed-contributions) lists every prompt
and record covered, and its "Not yet supported" row now names only what is still unsigned.
[`architecture.md`](../architecture.md#cryptographic-agent-identity) item 8 describes the combined
behaviour of this stack: every prompt filtered, own entries checked when the member signs, the
reserved names, withheld engine-named syntheses, the ballot taken from the voter's view and the
group-wide records rule; the "Not filtered yet" sentence is gone. The Platform Operator's prompt
names the same rules and the two failure messages an admin will ask about (`The vote could not
run: only 0 option(s) were available`, `Its task text could not be verified, and this member
requires peer verification`); operator revision bumped. The Manager's "Require peer
verification" description (all 11 locales) now says the records rule is group-wide and that a
verifying agent gets no artifact tools. [user-memory.md](../user-memory.md) says what such an agent
loads and recalls; [team-handoff.md](../team-handoff.md) and the Dynamic
Agents section say what peer verification means for handoffs and for agents `create_sub_agent`
makes, which cannot sign.

### Design decisions

- Readers reach the guard through setters with a `null` default (unfiltered), the existing
  `GroupContextBuilder.setSigningGuard` pattern, rather than through `MemberTurnExecutor`, which
  about ten test classes mock and whose mock would return no view.
- Task records carry no provenance, so a field is traced to the entry it came from (equal content
  for results, a re-parse for plans, containment for notes) instead of adding a source field to the
  persisted `TaskItem`.
- The records rule is group-wide, not per reader: a negotiation table or a vote is one record for
  everyone, and showing different tables to different members would split the discussion. A
  verifying member that does not sign therefore loses its own proposals, ballots and (as moderator)
  synthesis from the records once anyone verifies; the docs say an agent that verifies should sign.
- A tool-filed task is trusted by its creator, not by a signature over its text. The engine sets
  `createdByAgentId` from the turn that called the tool, so the text is that member's exactly as its
  signed entries are; what a verifying reader must not take is text from a member it could not
  verify. A store edit to the task text is not detected, the boundary configured and plan-derived
  task text already has (descriptions are taint rules). Rejected: a signed transcript entry per
  filed task (a synthetic entry in every prompt, appended mid-turn into the transcript the phase
  loop indexes) and a signature field on `TaskItem` (a persisted record with dozens of
  construction sites, re-signed on every cadence append).
- Artifacts are gated by absence for verifying members rather than filtered by last editor: a
  version merges earlier text, so the last editor says nothing about who wrote what.

### Not done

- Engine notes still quote a few short pieces of agent text (a task subject, rework feedback, the
  convergence check's reason). Listed in the docs.
- The cadence question quotes every pulled task, handed-off ones included, under the user's name,
  and `onDecision` handoffs name no agent; both are documented, with `REVIEW` intake recommended.
- Lessons another team shares, and those the scorecard tool returns, were checked only against
  the storing team's records rule. Documented, not filtered.
- A handed-off task in a run now counts toward `taskListConfig.maxAgentAddedTasksPerDiscussion`,
  since it has a creator; the task-list section of group-conversations.md says so.

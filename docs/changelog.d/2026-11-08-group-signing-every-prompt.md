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
- **Task records** (`TaskRecordView`). A result is quoted to a verifying reader only when it is its
  own task or a trusted entry of the assignee holds exactly that text, so an unsigned result or one
  edited in the store reads `[withheld: this text could not be verified]`; a result an approver
  edited (`SharedTaskList.reviewerEdits`) is the reviewer's text and is shown. A subject and
  description are withheld when an untrusted `PLAN` entry parses to them and no trusted one does,
  or when another member's task tool wrote them. A verification note is withheld when an untrusted
  `VERIFICATION` entry holds it and no trusted agent entry does. A verifying member whose own
  task's text does not verify fails the task without being asked; a bidder is announced only the
  tasks it may see.
- **Follow-ups** pass the member's view as `groupTranscript`, and the answer is signed.
- **Nested groups.** A `GROUP` speaker reads the parent transcript as strictly as its strictest
  agent member (nested groups included, through `GroupSigningGuard.setGroupMembers`), since its
  prompt becomes the question each of them is asked.
- **The engine's records** (`GroupSigningGuard.recordView`, `recordedEntries`). Once any member,
  the moderator or the facilitator requires verification, the `SERVICE` judge's input, the
  negotiation table, an arbitration
  verdict, the vote's options and ballots and a retrospective's lessons count only entries that
  verify for every reader (no reader's own entries are exempt). The ballot prompt now takes its
  options from the voter's own view, which reverses the previous PR's choice of the stored
  transcript: an unverified synthesis offers no options, and the vote does not run on it.
- **Entries written for an agent.** The readable verification summary and the facilitator's notes
  (the suppressed-escalation note included) are signed for the agent, as a `DISSENT` or `BID`
  already is. The task-failure note is the engine's own (`system`, with the member as its target).

Docs: [group-conversations.md](../group-conversations.md#signed-contributions) lists every prompt
and record covered, and its "Not yet supported" row now names only what is still unsigned.

### Design decisions

- Readers reach the guard through setters with a `null` default (unfiltered), the existing
  `GroupContextBuilder.setSigningGuard` pattern, rather than through `MemberTurnExecutor`, which
  about ten test classes mock and whose mock would return no view.
- Task records carry no provenance, so a field is traced to the entry it came from (equal content
  for results, a re-parse for plans, containment for notes) instead of adding a source field to the
  persisted `TaskItem`.
- The records rule is group-wide, not per reader: a negotiation table or a vote is one record for
  everyone, and showing different tables to different members would split the discussion.

### Not done

- `docs/architecture.md` (item 8, "Not filtered yet: ...") and the Platform Operator prompt are
  owned by another PR in flight; both need the role-prompt sentence replaced and the new failure
  modes (a vote that does not run, a task that fails unverified) added.
- Engine notes still quote a few short pieces of agent text (a task subject, rework feedback, the
  convergence check's reason); tasks added with member task tools and recruitment notes are not
  signed. Listed in the docs.

## feat(hitl): a person's note on a rejected tool call asks the model to revise (2026-10-26)

**Repo:** EDDI (`feat/hitl-note-asks-for-changes`)

### What changed and why

An approval card lets a person send a note instead of approving ("use a shorter title"). Until now a rejected call always told the model "the reviewer declined this action, do not retry, offer alternatives", so a note that asked for a change produced an explanation instead of a corrected proposal. Two small changes in [`ToolLoopResumer`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java):

- **The instruction depends on who decided.** When the note is non-blank and `decidedBy` does not start with `system:`, the rejection tool-result says the user did not approve the call and left a note: if the note asks for changes, call the tool again with exactly those changes and nothing else changed (the user will be asked to approve the new call); if it declines or asks for something else, do not call it again and reply briefly. With no note, or for an automated decision (`system:timeout`, `system:group`), the text is exactly what it was. Detection is the existing `system:` convention on `decidedBy`, never the note text, so the group-conversation auto-reject cannot make the model retry a call that can never be approved.
- **A per-call rejection falls back to the top-level note.** A call listed in `toolDecisions` with `REJECTED` and no note of its own (absent or blank) now uses `decision.note`, as an unlisted call already did. A call's own note still wins; approved calls are unaffected.

The re-proposed call is an ordinary new call: it is gated, pauses again with a new `pauseId` and needs its own approval, so an approved call is never silently changed. Each re-proposal counts toward `hitlConfig.toolApprovals.maxPausesPerTurn` (default 3, maximum 10); [`docs/hitl.md`](../hitl.md) says to raise it where notes are expected to come back as revisions.

No REST shape, verdict type or default changes.

### Tests

`ToolLoopResumerNoteTest`: the envelope for a person's note, for no or blank note, and for `system:timeout` / `system:group` with a note; the per-call fallback through the real resume loop (own note kept, absent and blank fall back, unlisted unchanged); and one test that stores a paused conversation as a snapshot document, loads it back, resumes it `REJECTED` with a note and asserts the model's next request carries the new instruction and the note.

```decision-log
| 2026-10-26 | A rejection note from a person asks the model to revise; automated `system:` decisions keep the "declined" text | The note is the user's way to say "not like this"; the model should come back with a new proposal to approve. Automated notes are explanations, and the group auto-reject would otherwise be retried in a loop. | Matching on the note text; a new verdict type that means "revise" |
```

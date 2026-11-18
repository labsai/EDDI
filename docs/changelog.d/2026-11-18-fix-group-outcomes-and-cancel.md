## 🐛 fix(groups): turn and discussion outcomes, cancel that cancels, fail-safe verification (2026-11-18)

**Repo:** EDDI (`fix/group-outcomes-and-cancel`)

Findings K1, K2, K3, K4 (verifier part), K7, K8, K9 of the November 2026 review, live
GR1–GR4.

### What changed and why

1. **A member turn has an outcome** (`ANSWERED`, `SKIPPED`, `FAILED`, `TIMED_OUT`) on its
   transcript entry (`TranscriptEntry.outcome`). A member whose conversation ends in
   `ERROR` without output used to become the text "[Agent failed to produce output —
   conversation entered ERROR state]", signed and stored as a real OPINION/SYNTHESIS,
   quoted to peers and taken as the synthesized answer — a provider outage completed as
   a success with that sentence as its answer. It is now a `FAILED` turn with no
   content that counts against `onAgentFailure` (RETRY retries it, ABORT aborts). The
   record forces content to `null` for every outcome other than `ANSWERED`, so no reader
   (peer prompt, vote, stance, judge, synthesis) can quote a failed turn. The placeholder
   is gone from all four places (`MemberTurnExecutor` twice, `GroupLifecycleOps`
   follow-up — now a 502 — and `ConverseWithAgentTool`, which now reports a failure).
   `errorReason` is a fixed text; exception and provider text goes to the log only (K9).
   `speaker_complete` carries the outcome on the sequential and peer-targeted paths too.

### Behaviour changes for upgraders

- Entries stored before this change carry no `outcome`; they read as `ANSWERED`
  (`SKIPPED`/`ERROR` entries as `SKIPPED`/`FAILED`). Old placeholder entries keep their text.
- A follow-up whose member fails answers 502 instead of recording the placeholder.

**Files:** [`GroupConversation.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`PhaseExecutionEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/PhaseExecutionEngine.java),
[`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java),
[`GroupConversationEventSink.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/GroupConversationEventSink.java),
[`ConverseWithAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ConverseWithAgentTool.java).

## 🐛 fix(hitl): crash recovery also recovers interrupted group discussions (2026-11-06)

**Repo:** EDDI (`fix/group-crash-recovery`)

### What changed and why

A group discussion whose node died mid-run stayed `IN_PROGRESS` (or `SYNTHESIZING`) for good. Its leg lives only in the JVM that ran it, the startup crash recovery deliberately skipped running group discussions, and `continue` and `followup` both require `COMPLETED`, so nothing but a manual cancel could end it. The sharpest case was a crash right after an approve: the approve CAS clears the pause bookmark in the same write that moves the discussion to `IN_PROGRESS`, so there was no pause left to restore either. Only a standing team's run had a backstop, the cadence claim TTL, whose default is 24 hours.

- **`HitlCrashRecoveryObserver`** — the single-node startup pass now scans `IN_PROGRESS` and `SYNTHESIZING` group discussions and cancels each one last written before the JVM started, through `IGroupConversationService.cancelDiscussion` (the ordinary cancel: state CAS, timeout schedule removed). It is gated on `eddi.hitl.crash-recovery.recover-in-progress`, like the regular IN_PROGRESS recovery, and the WARN summary counts the cancelled discussions. The leader-elected cluster sweep (`runClusterRecovery`) is unchanged and does not cancel groups.
- **`GroupHitlCoordinator.resumeDiscussion`** — the approve resume now stamps `lastModified`, as the human-turn and tie-break resumes already did. Without it, a discussion paused before a restart and approved after it would carry the pre-restart timestamp into its new, live leg, and the recovery pass could cancel that leg.
- **Docs** — [`hitl.md`](../hitl.md#crash-recovery) (crash recovery list, multi-pod caveat) a new "Restarts" section in [`group-conversations.md`](../group-conversations.md#restarts), and the `recover-in-progress` row of [`configuration-reference.md`](../configuration-reference.md).

### Design decisions

- **Process start, not a timestamp taken in the observer.** The cut-off is the JVM start time (`RuntimeMXBean.getStartTime()`): nothing this process runs can have been written before it, whatever ran before the `StartupEvent` observer.
- **Cancel, not restore.** A resumed group leg keeps no bookmark, so there is no pause to put back; cancelling ends in a state the user can act on, and a standing team's next fire returns its pulled backlog tasks to `PENDING`.
- **Reuse `cancelDiscussion`** rather than a raw state write, so the transition is the CAS-guarded one a manual cancel makes.
- **Single node only.** In cluster mode a group discussion holds no conversation lease that could prove its leg dead; a cluster variant would need its own staleness rule and is left as a follow-up.

**Files:** [`HitlCrashRecoveryObserver.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserver.java), [`GroupHitlCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java), [`HitlCrashRecoveryObserverTest.java`](../../src/test/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserverTest.java), [`GroupHitlCoordinatorTest.java`](../../src/test/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinatorTest.java)

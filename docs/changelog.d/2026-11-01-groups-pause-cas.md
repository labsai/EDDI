## Group HITL transitions are bound to their pause with a multi-field CAS (2026-11-01)

Repo: EDDI, branch `fix/groups-pause-cas` (stacked on #1188).

**Problem:** the group approve, reject, cancel and human-turn timeout-skip writes compared only `state` in their compare-and-set. A resume followed by a re-pause between the read and the write (ABA) left the state `AWAITING_APPROVAL` / `AWAITING_HUMAN_INPUT` again, so a stale decision could act on a newer pause it never saw. The in-memory pause-id checks narrowed the window but could not close it.

**Change:**
- `IResourceStorage.storeIfFieldsEqual(resource, Map)` — an atomic conjunctive conditional write (a `null` expected value means "absent"), implemented in `MongoResourceStorage` (one `replaceOne` filter) and `PostgresResourceStorage` (one `UPDATE ... WHERE a = ? AND b = ?`), with the same 404/409 zero-match distinction as the single-field CAS.
- `GroupConversation.pauseId` is persisted next to `pausedAt` and kept in lockstep by `setPausedAt`, so no pause or resume site can forget it. `IGroupConversationStore.updateIfStateAndPause` writes with state AND pauseId; `GroupHitlCoordinator` uses it for approve, reject, `cancelStored` (so `cancelDiscussion` and `cancelPausedDiscussion`) and the human-turn timeout skip and submit, each bound to the pause it read.

**Design decisions:** a separate string field rather than comparing `pausedAt`, because an `Instant` is stored differently by the two backends while a string compares identically on both. Documents written before the field existed are matched as absent (still state-bound, still atomic) so pauses in flight at upgrade remain decidable; the next write stamps the id. The pause→cancel conversion inside a running leg is unchanged: it acts on the pause that same leg just committed.

**Tests:** coordinator tests per transition (the CAS names the pause that was read and a stale refusal surfaces), store unit tests, and Testcontainers tests on MongoDB and PostgreSQL showing that a decision read before resume + re-pause is refused while the newer pause is untouched.

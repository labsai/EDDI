## 🐛 fix(health): the outcome evaluation never judges on what it could not read, and the feed always moves on (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Review findings on PR #1389 (H3, session outcomes), documented in [`docs/agent-health.md`](../agent-health.md#the-evaluation).

- **Unreadable inputs fail the evaluation instead of deciding it.** `HealthConfigPolicy.require` throws
  `HealthStoreException` when the agent store fails (an absent version still gives the defaults; `of` stays lenient
  for the self-report tool and the drill-down), and `LazyRatings` throws when the ratings cannot be read. Before, a
  failed read gave the defaults or "no ratings", and the session was stored as final — `ASSUMED_RESOLVED` from
  silence with no `dueAt` — so a later recovery did not judge it again. Now nothing is written and the feed's
  retry, or the session's unchanged due time, judges it again.
- **The retry limit runs from the first failure.** A failed conversation held the watermark only while its *capture*
  was less than an hour old, so a backfilled turn captured days ago was released on its first failure and, with no
  due session, never judged. The evaluator now remembers, per node and bounded (Caffeine), when each conversation
  first failed; a failure never pulls the watermark back past where the pass started.
- **The overlap cannot stall the feed.** Every pass restarts at `watermark - outcome-feed-overlap`; when more than a
  pass's worth of conversations fell in those 15 minutes, `reached` stayed behind the watermark and every pass
  re-read the same prefix. The overlap re-read now gets at most half of the pass's time and conversations, then the
  feed continues from the watermark.
- **Gapless feed pages on equal timestamps.** `IHealthTurnStore.conversationsUpdatedBetween` takes the last
  conversation id of the previous page and continues after the pair (`lastUpdatedAt`, `conversationId`) — a
  `HAVING` in PostgreSQL, a post-group `$match` in MongoDB. Before, more than a page of conversations sharing one
  millisecond made the feed step past it and skip the rest. Contract test added.
- **A rating mark on an already-overdue session survives the running evaluation.** `markDue` refused a mark later
  than a stored due time, and the evaluation's upsert then cleared that older due time, so a rating saved while an
  overdue session was being judged was lost. A mark now always sets the named sessions due at its own time (they
  stay due; callers pass `now`), which the upsert keeps.
- **A changed conversation rating marks every session**, as a withdrawal does: it counts at its new `updatedAt`, and
  the event does not say which session it was in before.
- **First output carried across retention.** Re-judging a session whose first rows the retention removed now takes
  that session's stored `firstOutputStep` when the step it names is no longer read, so the session is not judged
  unengaged.
- **The pending-evaluation hint is appended** to a measure's own hint (low coverage, no data) instead of replacing
  it.
- **Of more than 32 actions a turn keeps the last 32** (was: the first 32), where the ending and mapped actions are.
- **PostgreSQL**: the `self_reported_outcome` migration check looks at the `health_turns` the search path resolves
  (`to_regclass`), not a same-named table in another schema.

### Design decisions

- Left as is: a single session longer than `MAX_RANGE_TURNS` (10,000 rows) is still not judged; resumable partial
  aggregation would need new persisted state in both stores for a conversation of 10,000 turns without a 24-hour
  pause.
- The action cap stays config-free: the factory does not read `healthConfig` on the turn's thread. A mapped action
  followed by 32 or more other actions in the same turn is still dropped.

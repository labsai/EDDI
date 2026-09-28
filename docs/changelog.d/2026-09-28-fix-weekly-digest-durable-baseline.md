## 🐛 fix(ci): weekly digest reported "(0)" for every metric; make both digests reliable (2026-09-28)

**Repo:** EDDI (`fix/weekly-digest-durable-baseline`)

### What happened

The Monday Slack digest showed every delta as `(0)`: pulls, stars, forks, views
and clones. The run logs show why. The Actions cache entry that held the
week/day baselines had been evicted; the repository cache sits at its 10 GB
cap, so this is routine. A cold-start run at 14:55 UTC reseeded both baselines
to the current values. The Monday 07:00 cron, delayed by GitHub to 15:03, then
found a baseline that was non-zero but only eight minutes old. The existing
sanity check only catches a baseline of **0**, so it could not catch a baseline
of "now". The real week (the 2026-09-21 row against 2026-09-28's) was +1,170
pulls, +4 stars and +10,329 clones.

Reviewing the workflow against "both digests must always work" turned up more:

- **A dropped cron lost the digest.** Each digest hung off one `0 7 * * *` /
  `0 7 * * 1` event, and GitHub delays and drops scheduled runs. The workflow's
  own comments record a 9.6-hour gap, and today's event arrived 8 hours late.
- **A zero could enter the history.** On a cache miss, a Docker Hub blip
  carried forward `0`, and `Persist metrics history` wrote it as the day's row.
  Used as a baseline, that row would read "+400,871 pulls". The GitHub stats
  step had no guard at all.
- **Daily windows overlapped.** The digest compared live values against a
  row, giving a window of about 31 hours that overlapped the next day's.
- **A failed history fetch was read as "no branch"** and started a fresh
  orphan branch.
- **Nothing stopped a double send, and a failed Slack post was never
  retried.**

### What changed and why

All in [`docker-pull-notify.yml`](../../.github/workflows/docker-pull-notify.yml):

- **Digests are computed from the `metrics` branch only**, row to row. Daily:
  yesterday's row to today's. Weekly: the previous Monday's row to this
  Monday's. Rows are first-run-of-the-day snapshots, so windows are contiguous
  and a re-run gives the same numbers. A missing past day falls back up to two
  days, bad (zero) rows are skipped, and the message names the dates actually
  compared. A window ending today waits for today's row instead of ending
  early.
- **No digest cron.** Any 15-minute run sends a digest once it is due (daily
  from 07:00 UTC, weekly from Monday 07:00 UTC). A new `data/digests.json` on
  the `metrics` branch records the last window sent, so each digest goes out
  exactly once, however late the first run arrives. A Monday with no runs at
  all is caught up on Tuesday.
- **Claim, send, release.** The marker is pushed *before* the Slack post and
  reverted if the post fails. A failed push therefore sends nothing, a failed
  post is retried by the next run, and a stuck push can never re-post the
  digest every 15 minutes. An unreadable marker file stops all digests with an
  error, rather than reading as "never sent".
- **Only fresh values become history.** The Docker Hub and GitHub stats steps
  report `ok=false` when they carry a value forward, and `Persist` then leaves
  the day's row to a later run. The GitHub stats step gets the same
  digits-only guard as Docker Hub. `git ls-remote` now tells a missing branch
  (create it) apart from an unreachable one (fail).
- The cache now holds only the previous run's values for the 15-minute
  analytics delta and the milestone marker; the `week_*`/`day_*` fields and
  the zero-baseline heuristics are gone.

After merge there are no markers yet, so both digests are due at once: the first
run past 07:00 UTC that finds that day's history row sends the daily and the
weekly for 2026-09-21 → 2026-09-28 with the real numbers. The row is written
only from fresh, numeric metrics; until a run writes it, the digests wait for a
later run instead of going out early.

Verified locally: actionlint/shellcheck clean apart from the pre-existing
SC2129 style notes; the plan step under 10 fake clocks and histories (normal,
already sent, not yet due, dropped Monday, today's row missing, zero row, no
activity, forced, empty history, corrupt markers); both Slack payloads parsed
as JSON; persist and claim end-to-end against a bare copy of the branch
(append, same-day no-op, not fresh, unreachable remote).

```regression-note
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Digests computed row-to-row from the `metrics` branch, sent by any run once due, deduplicated by a claimed marker | fix/weekly-digest-durable-baseline |
```

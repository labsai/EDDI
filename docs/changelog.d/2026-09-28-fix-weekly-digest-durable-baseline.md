## 🐛 fix(ci): weekly digest reported "(0)" for every metric (2026-09-28)

**Repo:** EDDI (`fix/weekly-digest-durable-baseline`)

### What happened

The Monday Slack digest showed every delta as `(0)`: pulls, stars, forks, views
and clones. The run logs show why. The Actions cache entry that held the
week/day baselines had been evicted; the repository cache sits at its 10 GB
cap, so this is routine. A cold-start run at 14:55 UTC found no cache and
reseeded both baselines to the current values. The Monday 07:00 cron, delayed
by GitHub to 15:03, then found a baseline that was valid and non-zero but only
eight minutes old, and reported zero change. The existing sanity check only
catches a baseline of **0** (delta equals the current value), so it could not
catch a baseline of "now". The real week, measured against the
`metrics`-branch row for 2026-09-21, was about +1,213 pulls, +4 stars and
+10,329 clones.

### What changed and why

- **Digest baselines now come from the durable `metrics` branch**, not the
  cache. A new `Load digest baseline` step reads `data/metrics.csv`, which the
  workflow already writes once per UTC day. It takes the latest row at or before
  *today − 7* for the weekly digest, or *today − 1* for the daily one, within a
  two-day tolerance for days GitHub dropped the runs. With no row in that window,
  or a failed fetch, the digest is **skipped with a warning** instead of posting
  made-up numbers.
- The `week_*`/`day_*` fields, their field-presence checks, the `Validate
  baselines` step and the "baseline was 0" heuristics are gone. The cache now
  holds only what it is fit for: the previous run's values for the 15-minute
  analytics delta and the milestone marker.
- The weekly digest footer now reads "Changes since YYYY-MM-DD", so the window
  is visible.

A digest is now a pure function of the live values and a git-tracked row.
Cache eviction, reseeding and a delayed or duplicated schedule no longer change
the numbers.

**Files:** [`docker-pull-notify.yml`](../../.github/workflows/docker-pull-notify.yml)

```regression-note
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Read digest baselines from the durable `metrics` branch CSV | fix/weekly-digest-durable-baseline |
```

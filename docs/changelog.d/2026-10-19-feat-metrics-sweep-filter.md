## 📊 feat(ci): Slack digests estimate real Docker pulls next to the raw count (2026-10-19)

**Repo:** EDDI (`feat/metrics-sweep-filter`)

### What changed and why

The daily digest went from single-digit pull counts to +1,805 overnight. The
arithmetic was right, but the source was not people. Since 2026-10-18 02:47 UTC,
something has pulled all 198 tags of `labsai/eddi` at once, every ~2 hours. The
workflow logs show pull_count rising in steps of exactly 198–200, and Docker
Hub's `tag_last_pulled` puts every tag's last pull within the same millisecond.
The Oct 7 jump of +3,947 has the same shape. Outside those sweeps the counter
rises by a few pulls a day.

The digests keep the absolute count and now add two lines:

- **Likely real pulls**: the pull_count increase minus bot sweeps and our own
  CI pulls, with the number of sweeps filtered out.
- **Versions pulled**: the tags real pulls went to (e.g. `latest ×12, 6.5.0 ×3`).
  The number is how many runs saw that tag pulled, not a pull count, because
  Docker Hub does not publish per-tag counts.

How it works, in [`docker-pull-notify.yml`](../../.github/workflows/docker-pull-notify.yml):

- A new step, **Fetch Docker Hub tags**, reads every tag's `tag_last_pulled`
  after it reads pull_count, and records when it finished. Both Docker Hub
  reads now have a 30-second deadline, so a stalled connection can't hold the
  job, and through the concurrency group every later run, until GitHub's
  6-hour limit.
- **Persist metrics history** appends one line per run to `data/runs.csv` on
  the `metrics` branch. Each line splits the increase since the previous line
  three ways. An interval in which ≥ 20 tags were pulled is a sweep, costing one
  pull per tag. A long gap holding several sweeps is estimated from what is
  left once any carried debt is paid, divided by the tag count. Otherwise each
  `-b<N>` build tag pulled counts as one CI pull, because `ci.yml`'s post-push
  check pulls every build it publishes. The rest is real, and the other tags
  pulled are recorded.
- `bot_debt` / `debt_since` cover Docker Hub showing a sweep's timestamps
  before its pull_count catches up, possibly by more than one run. Debt that
  can't be subtracted yet is always applied to the next interval, which holds
  the late count however long it is. Anything still unpaid carries further only
  while it is younger than 2 hours. The two failure modes are lopsided:
  dropping debt too early books a whole sweep (~200) as real pulls, while
  holding it too long hides a few real ones. A malformed last line starts a new
  baseline instead of blocking the log.
- **Plan digests** sums the log over the same span as the raw delta beside it:
  every line after the first run of the `from` day, up to and including the
  first run of the `to` day (the daily rows are first-run-of-the-day
  snapshots). Consecutive windows share no line and miss none, so an interval
  crossing midnight is counted exactly once. If the log has no line on the
  `from` day, the window is empty rather than borrowing an interval from after
  it. A log younger than the
  window gives a figure labelled "counted since …". With no log, the line is
  left out and the digest is otherwise unchanged.

### Design decisions

- **The run log lives on the `metrics` branch, not in the Actions cache.** Each
  line's baseline is the previous line. The cache is evicted routinely, which is
  how the digest baselines broke before. One commit per run, about 70 a day, is
  acceptable on an orphan data branch.
- **A failed fetch writes no line.** The next interval just gets longer, and a
  failed push heals the same way.
- **Estimates, labelled as such.** A tag pulled by a person and then swept in the
  same interval is lost, and a short burst of real pulls in a sweep interval is
  only roughly separated. Exact per-tag counts would need Docker Hub's publisher
  analytics, e.g. via the Docker-Sponsored Open Source program.

### Verification

The changed step scripts were extracted from the workflow and run in
`ubuntu:24.04` with the runner's tools. They were tested against the live
Docker Hub tag API (198 tags) and a synthetic sequence: quiet interval, CI
build-tag pull, one sweep, a sweep whose count lags one run and then two, two
sweeps in one gap, a late count in the same interval as a new sweep, debt kept
while young and dropped after 2 hours, a refused duplicate timestamp, a failed
fetch, and a malformed last line. Mutants restoring one-run carry, no expiry,
and sweeps counted from the raw delta each fail the suite. Every Slack payload
variant was parsed as JSON. actionlint reports only the four SC2129 style notes
`main` already had.

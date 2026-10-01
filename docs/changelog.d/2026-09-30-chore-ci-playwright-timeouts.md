## 🧹 chore(ci): time limits on the E2E jobs and their browser install (2026-09-30)

**Repo:** EDDI (`chore/ci-playwright-timeouts`)

### Why

On PR 890 the Auth E2E (Keycloak) job sat in "Install Playwright browsers" for
over an hour and a half; the tests never started. Other runs that day passed, so
it was a stalled download on one runner, not a code problem. Nothing in
[`ci.yml`](../../.github/workflows/ci.yml) bounded it, so a stall like that runs
until GitHub's 6-hour default while the PR shows the check as pending.

### What changed

- `timeout-minutes: 10` on each of the three `npx playwright install --with-deps
  chromium` steps. A normal install takes 20 seconds to 5 minutes.
- Job limits: `UI Manager E2E (MSW)` 20 minutes, `Backend E2E` 20, and
  `Auth E2E (Keycloak)` 15. Recent green runs took about 5, 2 to 6, and 2 minutes.

A timed-out step fails the job, and re-running it is the fix. No automatic retry
was added: interrupting `--with-deps` part-way can leave apt holding its lock, so
a second attempt could fail for a different reason.

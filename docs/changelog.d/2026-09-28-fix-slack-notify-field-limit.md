## 🐛 fix(ci): the Slack notification exceeded Block Kit's 10-field limit (2026-09-28)

**Repo:** EDDI (`fix/slack-notify-field-limit`)

### What was wrong

`ci.yml`'s `notify-slack` job posts a Block Kit message whose status icons sit in one section
block's `fields` array. That array had 11 entries. Slack's section-block reference caps `fields`
at 10 ("Maximum number of items is 10"), and a message over the cap is rejected as a whole
(`invalid_blocks`), not truncated. The step posts with `curl -sf`, so the result was not a shorter
message: no failure or release notification reached Slack at all. The only trace was a failed
`Slack Notification` step, and nothing was told about it.

### What changed

- [`ci.yml`](../../.github/workflows/ci.yml): the statuses are split into two section blocks,
  with every icon kept.
  - Build and test gates: Build & Test, Integration Tests, UI Gate, Build Image, Backend E2E.
  - Scan and publish gates: Secret Scan, Vuln Scan, CodeQL SAST, Docker Push, Smoke Test,
    Red Hat Preflight.

  The payload is still built by `jq -n` from `--arg` values, with no inline interpolation, and a
  comment at the payload records the limit.
- [`BuildQualityGatesTest`](../../src/test/java/ai/labs/eddi/BuildQualityGatesTest.java)
  `slackSectionsStayWithinTheFieldCap` checks two things:
  - every `fields` array in `notify-slack` has at most 10 entries;
  - every `--arg *_icon` the job computes is rendered in some section, so a later split cannot
    silently drop a status.

  Mutation-checked both ways: `origin/main`'s workflow fails with "a notify-slack section has 11
  fields", and removing the Red Hat Preflight field fails the rendered-icons check.

No test asserted the payload's shape before this. actionlint reports the same 60 pre-existing
findings on `ci.yml` before and after the change, and none new.

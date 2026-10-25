## fix(manager): https-only webhook check, bulk-acknowledge mock, operator revision 41 (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139) — the Manager side of the alerts backend review fixes (PR #1138)

### What changed and why

- **Rule editor:** `ruleProblems` ([`alert-rules.ts`](../../ui/manager/src/lib/alert-rules.ts)) accepts only an `https://` webhook URL or a `${vault:…}` reference, as the server now requires; the masked `https://host/***` of a stored URL still passes.
- **MSW:** the bulk-acknowledge handler answers 400 `INVALID_PARAMETER` to an empty `ids` list or an empty entry, as the server does; the Manager sends `{}` when it acknowledges by filter and a non-empty list otherwise, so its calls are unchanged.
- **Platform Operator:** the prompt says webhooks must be https and corrects "no conversation text" — a rating comment, or an `OUTPUT_MATCH` value with `includeValue` and conversation review on, can appear, each at most 280 characters. Revision 40 → 41.

### Tests

`npm run typecheck`, eslint on the changed files and `npm run i18n:check` pass; the alert and operator suites (19 files, 530 tests) pass.

## fix(manager): second round of alerts review findings — throttle bounds, bulk-acknowledge body, inbox paging (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139)

### What changed and why

- **Throttle checked before saving:** `throttleProblems` in [`alert-rules.ts`](../../ui/manager/src/lib/alert-rules.ts) uses `AlertRuleValidator`'s bounds exactly (whole numbers, 1–1440 minutes, 1–1000 per window); `ruleProblems` reports `throttle`, which disables Save and Send test, and the editor marks the field with an inline error (`alerts.throttleRange`, all 11 locales).
- **Bulk acknowledge body:** `acknowledgeAlertEvents` sends `{}` only for `null`/`undefined` and `{ ids }` otherwise, so an empty list reaches the server, which refuses it, instead of being widened to everything matching. The page's only "all matching" caller passes `null`.
- **Inbox:** keyed on the agent, so changing it starts from page 1 with nothing selected; the page moves back to the last one that exists when acknowledging empties the one it is on (instead of "No open alerts"); the selection clears when the source or the acknowledged filter changes.
- **Schedules:** a dead-lettered server-owned schedule (`alerts-maintenance`) gets no Retry on its row and no Retry/Dismiss in Failed fires — the server answers 409 `SYSTEM_SCHEDULE` to both.
- **Output-match builder:** switching the path source to `actions` turns the value into text (contains compares strings) and keeps `exists` without one.
- **Full-stack e2e spec:** each resource is recorded as soon as it exists, and cleanup removes every recorded one even when setup failed midway.

### Tests

New: `output-match-builder.test.tsx` (5), `lib/api/__tests__/alerts.test.ts` (3), throttle cases in `alert-rules.test.ts`, four page tests in `alerts.test.tsx` (throttle error, page clamp, selection reset on filters, fresh inbox on agent change) and the server-owned case in `schedules-regressions.test.tsx`. The alerts, schedules, operator, mocks and i18n suites: 34 files, 770 tests. Mutation check: 10 of 10 mutants killed (throttle not reported, window bound off by one, fractions accepted, empty ids widened, no page clamp, selection kept on either filter, Inbox not keyed on the agent, row Retry and Failed-fires actions for a server-owned schedule). The Playwright spec is typechecked and linted, not run.

## fix(manager): https-only webhook check, bulk-acknowledge mock, operator revision 41 (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139) — the Manager side of the alerts backend review fixes (PR #1138)

### What changed and why

- **Rule editor:** `ruleProblems` ([`alert-rules.ts`](../../ui/manager/src/lib/alert-rules.ts)) accepts only an `https://` webhook URL or a `${vault:…}` reference, as the server now requires; the masked `https://host/***` of a stored URL still passes.
- **MSW:** the bulk-acknowledge handler answers 400 `INVALID_PARAMETER` to an empty `ids` list or an empty entry, as the server does; the Manager sends `{}` when it acknowledges by filter and a non-empty list otherwise, so its calls are unchanged.
- **Platform Operator:** the prompt says webhooks must be https and corrects "no conversation text" — a rating comment, or an `OUTPUT_MATCH` value with `includeValue` and conversation review on, can appear, each at most 280 characters. Revision 40 → 41.

### Review follow-ups

- `AlertDeliveryService` derives the send claim lease as the longer of 30 s and three times `eddi.alerts.webhook-timeout`, and `AlertSettings` keeps that timeout between 1 s and 60 s, so a long timeout can no longer make every send skip.
- Manager: test-result rows in the rule editor have unique keys; removing a chat rating clears its reasons and comment; the chat hook reads a conversation's own rating controls even when the current profile is null; the feedback list steps back to the last existing page when triage empties the current one.

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

## fix(manager): carry each webhook target's targetId through the rule editor (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139) — the Manager side of the backend's `targetId` restore (PR #1138)

### What changed and why

- **Types:** `DeliveryTarget.targetId` in [`lib/api/alerts.ts`](../../ui/manager/src/lib/api/alerts.ts), documented as the server's id to send back unchanged.
- **Editor** ([`alert-rule-editor.tsx`](../../ui/manager/src/components/alerts/alert-rule-editor.tsx)): targets are edited, removed and — new — moved up or down as whole objects, so each keeps its own `targetId`; rows are keyed by it. A new target has none. Save and Send test send the ids as loaded. Two new strings (`alerts.moveTargetUp`, `alerts.moveTargetDown`) in all 11 locales.
- **Validation:** `ruleProblems` accepts a masked URL only together with a `targetId`, so a masked URL is never sent without one.
- **MSW** ([`alert-handlers.ts`](../../ui/manager/src/test/mocks/alert-handlers.ts)) now mirrors the server: it stores full URLs and answers reads masked, assigns ids on create (replacing client ones), restores a masked or empty URL on update and test send only from the stored target with the same `targetId`, and refuses one without a known id with 400 `INVALID_RULE`.
- **Platform Operator:** the masked-URL paragraph explains `targetId` and the `INVALID_RULE` refusal. Revision 41 → 42; it ships as **43**, because merging main (where the ratings work had moved the operator to 39) needed a revision above both.

### Tests

New page tests (`alerts.test.tsx`): removing one of two masked same-host webhooks sends the other with its own id, reordering moves ids with their targets, editing a masked target's secret keeps its id, a new target goes without one, a test send of a saved rule sends the ids, and saving the stored webhook rule round-trips through the mock. `alert-handlers.test.ts` (4) pins the mock's contract; `alert-rules.test.ts` covers the masked-URL check. `npm run typecheck`, the full `npm run lint` and `npm run i18n:check` (6,336 keys) pass; the alerts page, alert components, `src/lib` (operator included), `src/test/mocks` (OpenAPI contract included), schedules and i18n suites: 143 files, 2,518 tests. Mutation check: 5 of 5 killed (edit drops the id, removal re-pairs ids by position, reorder leaves ids behind, masked URL accepted without an id, mock restoring without matching the id).

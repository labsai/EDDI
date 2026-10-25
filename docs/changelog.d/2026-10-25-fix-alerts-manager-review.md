## fix(manager): https-only webhook check, bulk-acknowledge mock, operator revision 41 (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139) — the Manager side of the alerts backend review fixes (PR #1138)

### What changed and why

- **Rule editor:** `ruleProblems` ([`alert-rules.ts`](../../ui/manager/src/lib/alert-rules.ts)) accepts only an `https://` webhook URL or a `${vault:…}` reference, as the server now requires; the masked `https://host/***` of a stored URL still passes.
- **MSW:** the bulk-acknowledge handler answers 400 `INVALID_PARAMETER` to an empty `ids` list or an empty entry, as the server does; the Manager sends `{}` when it acknowledges by filter and a non-empty list otherwise, so its calls are unchanged.
- **Platform Operator:** the prompt says webhooks must be https and corrects "no conversation text" — a rating comment, or an `OUTPUT_MATCH` value with `includeValue` and conversation review on, can appear, each at most 280 characters. Revision 40 → 41.

### Tests

`npm run typecheck`, eslint on the changed files and `npm run i18n:check` pass; the alert and operator suites (19 files, 530 tests) pass.

# EDDI Manager — Full Feature-Coverage Implementation Plan

**Date:** 2026-07-20
**Branch (Manager):** `feat/eddi-feature-coverage` (one branch, commit per tranche, single PR)
**Backend (EDDI):** patched where a finding requires it, on its own branch + PR
**Source of truth:** local `labsai/EDDI` checkout (main, PRs #585–#600)

## Goal

Close every verified gap from the coverage audit so the Manager reaches **100% coverage of EDDI's operator-relevant features** with **great UX**, backed by **>90% test coverage on changed code**. 126 verified findings across 14 areas (15 high, 61 medium, 50 low); 0 were refuted under adversarial verification.

## Working method (applies to every tranche)

1. **Re-verify before touching** — for each finding, read the current Manager file + the backend contract, confirm the defect still exists.
2. **TDD** — write a failing test that encodes the correct behavior (against the real backend contract / MSW shape), then implement.
3. **i18n is mandatory** — any `en.json` key added/changed is propagated to all 10 other locales in the same commit (`de, fr, es, ar, zh, th, ja, ko, pt, hi`). Verify each key resolves in 11 files.
4. **MSW parity** — mock handlers must match the real backend JSON shape (several current bugs are hidden by wrong mocks; fixing the mock is part of the fix).
5. **Verify gates before commit** — `npx tsc --noEmit`, `npx eslint --max-warnings 0` on staged files, `npm run test`. Before the final PR: `npm run build`.
6. **Commit per logical fix/group** with conventional-commit messages; group commits under the tranche.
7. **RTL** — logical properties only (`ps/pe/ms/me/start/end`), never `left/right`.

## Decomposition (tranches = sub-projects, executed in order)

### Tranche 1 — Correctness pack (Manager) — real bugs that break on a live backend
| # | File(s) | Current (wrong) | Correct (backend truth) | Test |
|---|---|---|---|---|
| 1 | `rules-editor.tsx` | group `executionStrategy` options `currentStepOnly/lastStepOnly/anyStep`; new group defaults `currentStepOnly` | enum `executeAll` / `executeUntilFirstSuccess`; default `executeUntilFirstSuccess`; tolerate legacy on load | new group serializes valid enum; select renders backend values |
| 2 | `rules-editor.tsx` | condition `dynamicValueMatcher` (camelCase); 4 types missing | `dynamicvaluematcher` (lowercase) + add `contextmatcher`, `contentTypeMatcher`, `dependency`, `sizematcher` (12 total) | picker offers 12 correct IDs; selecting emits lowercase |
| 3 | `output-editor.tsx` | image/link both write `url` | `image` → `uri` (+`alt`); `applicationLink` → `path` (+`label`,`delay`) | image output emits `{type,uri}`; link emits `{type,path}`; load preserves values |
| 4 | `lib/api/schedules.ts`, `pages/schedules.tsx`, `test/mocks/handlers.ts` | `ScheduleFireLog` = `firedAt/success/durationMs/error` | `fireTime/status(COMPLETED\|FAILED\|DEAD_LETTERED)/errorMessage/startedAt/completedAt/attemptNumber/cost/conversationId` | fire-history renders real date/status/duration/error; mocks emit real shape |
| 5 | `pages/channel-detail.tsx`, `hooks/use-channels.ts` | save/delete `mutateAsync` no try/catch → silent | try/catch + `toast.error(getErrorMessage)` (mirror create dialog); success toast | backend 400 surfaces a toast |
| 6 | `pages/orphans.tsx` | "Delete N selected" calls purge-all | Manager: honest "Purge all" until backend supports selection (see B1). Confirm dialog states ALL will be deleted | selecting subset shows purge-all confirm copy |
| 7 | `pages/conversations.tsx`, `conversation-detail.tsx`, `lib/api/conversations.ts`, `hooks/use-conversations.ts` | delete always soft, dialog says "permanently removed" | two-choice Soft vs Permanent (`deletePermanently=true`); correct copy; permanent behind stronger confirm | soft delete copy accurate; permanent passes flag |

Also folds in the two correctness-flavoured mediums that live in these files: channels default-target rename breaking `defaultTargetName` (#5 area), and the quotas honesty labels move to Tranche 4 with the quotas UX group.

### Tranche 2 — Destructive-action safety (Manager, cross-cutting)
Reuse existing `AlertDialog` + `sonner` toasts, and add a `QueryClient` `MutationCache` `onError` baseline. Wrap: HITL approve(tool-call)/reject/cancel (+ group cancel), Quotas reset, Orphans purge, Undeploy (with the two destructive options), dead-letter discard. Add success/error toasts to quotas save/reset and any silent admin mutations.

### Tranche 3 — New operator surfaces (Manager; API clients mostly already exist)
- **Conversation Attachments panel** (`conversation-detail.tsx`): list · download · delete-all (client already in `attachments.ts`), per-turn `attachments:errors` reasons.
- **Group lifecycle** (`groups.ts`, `group-detail.tsx`): `followup`/`continue`(+stream)/`close`, model `availableActions` + `CLOSED`, state-conditional action bar, distinguish continue vs new discussion.
- **Secrets key-lifecycle** (`secrets.ts`, `secrets.tsx`): `rotate-kek`, `rotate-dek`, tenant `reset`, guided master-key-change recovery panel.
- **Error-handling signals** (`chat.ts`, `use-chat.ts`, `use-debug-events.ts`, chat/pipeline components): consume `task_failed` (classified `errorType`), badge partial/truncated responses from `responseMetadata`, parse the error JSON, robust retry flag.
- **Response Validation editor section** (`llm-editor.tsx`): `responseValidation` (5 policies × ignore/warn/fallback/error) + `streamingTimeoutSeconds`.
- **Active-conversation monitoring + bulk end** and **bulk purge of ENDED** (conversations area).

### Tranche 4 — Completeness (medium bulk)
Editor fields: MCP call retry/continueOnError/preRequest/postResponse + discovered-tool schema view + toolName picker; API-call retry/backoff; property-setter `secret` scope + `visibility`; rich output item types; cascade per-run cost; attachments capability matrix. Filters/pagination: conversations (paging + agent/state filter), channels (paging + backend search), logs History (env/userId/agentVersion + paging). Schedules: one-time (`oneTimeAt`), edit, timezone, cron preview, cost/version/userId fields, failed/dead-letter dashboard. Tool observability: cache/rate-limit/cost panels + resets. Quotas: unlimited toggles, window-reset timestamps, honesty labels. Deployment: cross-env dashboard, semantic-parser test console. HITL inbox owner column + per-row countdown.

### Tranche 5 — Low-severity polish
i18n completion (cascade/retry, mcpcalls block), expression/action autocomplete, plaintext-secret warnings, viewState/userId columns, activation-latency hints, misc labels & confirmations, GDPR result fields, capability strategy options.

### Backend asks (B1 — EDDI repo, separate PR)
- **Orphans selective delete** — add `selectedResources`/id list to `DELETE /administration/orphans` (or a new endpoint) so the Manager selection is honored.
- **`maxAgentsPerTenant` enforcement** — enforce at agent deploy so the quota is real (currently stored, never checked).
- **Monthly-cost metering** — wire `recordCost` during LLM execution so `monthlyCostUsd` reflects real spend.
- **`rotateSecret` endpoint** — reconcile the Manager's per-secret value-rotate call with an actual backend endpoint (add or correct).
- Any additional gaps surfaced during Manager work that require backend support are appended here.

Manager tranches that depend on a B1 item ship the honest-UI version first; the richer UI lands once the backend PR merges.

## Verification & coverage strategy

- Every new API function, hook, page, and editor gets unit/component tests (Vitest + RTL + MSW); target **>90% statements/branches on changed files**, checked via `npm run test -- --coverage` scoped to the touched paths.
- Correctness fixes each get a regression test that would fail against the old code AND asserts the real backend shape (so wrong-mock masking can't recur).
- Final: full `npm run test`, `npm run build`, and a self-review pass (requesting-code-review) before opening the PR.

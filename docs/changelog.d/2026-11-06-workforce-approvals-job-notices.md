## ✨ feat(workforce): approvals in the flow and long-job notices on the user event stream (2026-11-06)

**Repo:** EDDI · **Branch:** `feat/workforce-approvals-job-notices` (from `feat/user-event-stream`)

Phase 8 of the frontend unification plan (`planning/frontend-unification-plan.md` §5 Tier 2): **T2.3** (approvals in the flow) and **T2.8** (long jobs with a notification when done), the first UI on the per-user event stream B12 ([user-events.md](../user-events.md)).

### What changed and why

- **One stream per tab, in both signed-in apps.** [`UserEventBridge`](../../ui/packages/app-kit/src/components/layout/user-event-bridge.tsx), mounted by the Manager's `AppLayout` and Workforce's `WorkforceLayout`, starts the tab's stream once someone is signed in and stops it on sign-out. Every event invalidates the queries it concerns ([`staleQueryKeys`](../../ui/packages/app-kit/src/lib/user-event-stream.ts)); every (re)connect refetches them all, since the stream has no replay. The transport is `ApiClient.authorizedFetch`, but the error stays `@eddi/core`'s `ApiError`, which is how the client tells a refusal (401/403/404) it must not retry. `useUserEvent`, `useUserEventStatus` and `useEventDrivenInterval` ([`use-user-events.ts`](../../ui/packages/app-kit/src/hooks/use-user-events.ts)) are how components listen.
- **Manager:** the approvals inbox, the sidebar badge, the chat's pause notice and the notification bell now update the moment an approval is decided, a pause lands or a notification arrives, instead of at the next 5–10 s poll. Its polls are unchanged.
- **Workforce — what waits on a human (T2.3).** [`ApprovalsIndicator`](../../ui/workforce/src/components/workforce/approvals-indicator.tsx) in the topbar, on every viewport: a count of the person's own paused chats and team sessions and, for an approver or admin, the pauses they may decide; a panel listing each with what its timeout policy does and when (`pausedAt + approvalTimeout`), jumping to the paused turn in Workforce or, for a review, to the Manager's approvals inbox. Data from the existing `GET /agents/pending-approvals` and `GET /groups/pending-approvals` ([`use-workforce-approvals.ts`](../../ui/workforce/src/hooks/use-workforce-approvals.ts), [`workforce-approvals.ts`](../../ui/workforce/src/lib/workforce-approvals.ts)), on the Manager's query keys, polled every 30 s only while the stream is not open. The sidebar marks a paused chat or team session.
- **By when.** The read-only [`PauseNotice`](../../ui/packages/app-kit/src/components/hitl/pause-notice.tsx) (the chat in both apps, the Workforce member thread) now says when and how a finite timeout policy decides on its own.
- **Workforce — notices (T2.8, and "told when it continues" of T2.3).** [`WorkforceNotices`](../../ui/workforce/src/components/workforce/workforce-notices.tsx) toasts a finished or stopped long job (turn limit, cost ceiling), a completed or failed scheduled run, a team discussion that ended, a team waiting for *your* turn (`yourTurn: true` only) and a decision on *your own* pause, with "Open" to the conversation or team session — except the one already on screen. [`noticeFor`](../../ui/workforce/src/lib/workforce-notices.ts) picks a generic sentence; events carry no content. Toasts use sonner's polite live region and never take focus.
- **Browser notifications, opt-in.** [`use-browser-notices.ts`](../../ui/workforce/src/hooks/use-browser-notices.ts): off by default; turned on from the indicator's panel, the only place the permission prompt is ever requested; sent only while the tab is hidden.
- **i18n:** 3 `hitl.*` keys in `@eddi/app-kit` and 38 `Workforce.approvals.*` / `Workforce.notices.*` keys in Workforce, translated into all 11 locales.
- **Platform Operator:** the prompt says how the apps use the stream, what Workforce shows and why a user may see no notices. `operator-revision.json` → **58**.
- **Docs:** [user-events.md](../user-events.md) ("In the Manager and Workforce"), [`ui/workforce/AGENTS.md`](../../ui/workforce/AGENTS.md).

### Design decisions

- **Refetch, never render the event.** An event says only that something changed; the lists come from the access-checked endpoints, so the UI cannot show something the stream should not have carried.
- **No new endpoint.** "Who must approve" is shown as the person's own pause versus one they may review, which is what the pending-approvals summaries can say; naming the reviewer would need a backend field.
- **A hand-rolled disclosure panel, not the Radix dropdown,** in the topbar: the topbar is in Workforce's first load, and a menu role would also be wrong for a list of links plus a setting.
- **Toast only what is news.** No toast for `approval.pending` (the count says it, and it is usually what the person just did), `team.started` (they started it) or an approver's queue changing.

### Not done

- **Deciding in place in Workforce** (T2.3's "an approver decides in place"): reviews link to the Manager's inbox; the Operator-style inline approval card is a follow-up.
- **Progress for a running long job** (T2.8's "shown as progress"): the stream has no in-progress event for continuations, only `job.finished` / `job.failed`; a backend event would be needed.
- The pause notice's "Review" link still goes to the Manager's conversation page, which an `eddi-user` cannot open.

```decision-log
| 2026-11-06 | Both signed-in apps open the user event stream through one `UserEventBridge` that only invalidates queries | Events carry ids only, so refetching through access-checked endpoints is the one safe way to show them, and one bridge keeps it to one stream per tab | Each screen opening and interpreting its own subscription |
| 2026-11-06 | Workforce asks for browser-notification permission only from a click in the approvals panel, and notifies only while the tab is hidden | Browsers penalise prompts on load, and a visible tab already has the toast | Prompting on first load; notifying while visible |
```

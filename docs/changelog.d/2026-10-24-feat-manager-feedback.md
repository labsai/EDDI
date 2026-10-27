## feat(manager): Feedback page, rating triage and rating controls (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 3 of [`planning/conversation-rating-plan.md`](../../planning/conversation-rating-plan.md) (§8 and §0.1 "Admin — triage")

### What changed and why

The people running an agent can now see and triage what people said about it, in the Manager.

- **Feedback page** [`pages/feedback.tsx`](../../ui/manager/src/pages/feedback.tsx) at `/manage/feedback` (route registry, sidebar, `SCREEN_ROLES` admin+editor). "Needs attention" first (NEW ratings that are low or commented, newest first) with per-row Mark reviewed / Reopen, internal note (≤ 2000), Open conversation (only when `canOpenTranscript`) and bulk review; "All ratings" with URL-synced filters, KPI tiles against the previous period, trend, distribution, top reasons, a by-version table greying buckets under 10 ratings, CSV export honouring `X-Ratings-Truncated`. A non-administrator is asked to pick an agent instead of hitting `AGENT_ID_REQUIRED`; a 409 `RATING_CHANGED` reloads and says so. Components under [`components/feedback/`](../../ui/manager/src/components/feedback/).
- **Attention badge** on the sidebar entry and a **dashboard tile**, administrators only, sharing one `ratingKeys.attention` query polled every 30 s.
- **Agent editor**: [`rating-config-section.tsx`](../../ui/manager/src/components/editors/rating-config-section.tsx) for every `ratingConfig` field; [`lib/rating-config.ts`](../../ui/manager/src/lib/rating-config.ts) mirrors `RatingConfigValidator` and the page refuses to save invalid values. **Agent detail** gets a 30-day feedback strip; **conversation detail** shows the conversation rating, review status and per-step reply thumbs.
- **Manager chat** gets the Chat UI's rating controls ([`chat-rating.tsx`](../../ui/manager/src/components/chat/chat-rating.tsx), [`use-chat-ratings.ts`](../../ui/manager/src/hooks/use-chat-ratings.ts), [`chat-rating-steps.ts`](../../ui/manager/src/lib/chat-rating-steps.ts)); snapshot messages now carry `stepIndex`.
- **API/hooks**: [`lib/api/ratings.ts`](../../ui/manager/src/lib/api/ratings.ts), [`hooks/use-ratings.ts`](../../ui/manager/src/hooks/use-ratings.ts), `ratingKeys` in `query-keys.ts`. `ApiClientError` now carries the `code` of an `{error, message}` body (with tests), so callers branch on `AGENT_ID_REQUIRED` / `RATING_CHANGED` / `RATING_WINDOW_CLOSED` rather than on translated text.
- **Backend**: the `/ratings` endpoints take `conversationId` (filter + both stores + tests), so the conversation view does not list everything.
- MSW: [`test/mocks/rating-handlers.ts`](../../ui/manager/src/test/mocks/rating-handlers.ts) with an in-memory store shaped like `RatingRow`; e2e spec `e2e/feedback.spec.ts`. i18n: 146 keys in all 11 locales.

### Design decisions

- No chart library is added: the trend and distribution are plain markup in the house style (as the Workforce analytics are), with numbers in text for screen readers. KPI tiles reuse `StatCard`.
- Bulk review of a *selection* PATCHes row by row (each conditional on the rating not having changed); "mark everything here" uses `POST /ratings/review` with the same filter.
- The CSV download uses raw `fetch` (blob body and a response header to read) with `api.getAuthHeader()`, like `backup.ts`.
- The Manager chat offers the card after *End conversation* (when `askOnEnd`) because it does not track the ENDED state otherwise; prompted-once state is per page, not persisted.

### Not done here

The Playwright spec could not run locally (the installed Chromium build is older than the one Playwright 1.64 expects); CI's `UI Manager E2E (MSW)` runs it. MCP tools and the Platform Operator are PR 4, alerts PR 5.

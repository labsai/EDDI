## feat(chat-ui): conversation and reply ratings (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 2 of [`planning/conversation-rating-plan.md`](../../planning/conversation-rating-plan.md) (§7 and §0.1 "User — UX rules")

### What changed and why

The Chat UI now lets the person chatting rate the conversation and, when the agent asks for it, single replies, against the owner endpoints PR 1 added. Everything is driven by the profile's `rating` object; without it the widget renders exactly as before.

- **Reply thumbs** ([`StepRating.tsx`](../../ui/chat/src/components/StepRating.tsx)) under the last agent bubble of each step — hover/focus on desktop, always on touch, `aria-pressed`, 44px targets on touch. Thumbs down rates at once, then offers optional reason tags and a comment.
- **Rating card** ([`RatingCard.tsx`](../../ui/chat/src/components/RatingCard.tsx)), a sibling of `PausedCard`: stars as a radio group (roving focus) or thumbs; offered on end (`askOnEnd`) and/or after `askAfterTurns` replies, at most once per conversation, never to somebody who already rated, never again once closed (localStorage, guarded); always available from a header entry. One tap rates, tags/comment follow (comment per `askBelowScore`), then a thanks state naming who receives the feedback; editable and removable afterwards; never blocks the composer.
- **State** ([`useRatings.ts`](../../ui/chat/src/hooks/useRatings.ts)): restored via `GET /agents/{id}/rating` when a conversation opens; optimistic updates with rollback and a short notice; every backend error code mapped to translated copy ([`rating-api.ts`](../../ui/chat/src/api/rating-api.ts)); a rerun clears the local thumb of that step.
- **Steps** ([`rating-steps.ts`](../../ui/chat/src/rating-steps.ts)): reply ratings are keyed by step. Full conversation reads now stamp `stepIndex` on their messages (`stepsToMessages(…, fullHistory)`, `processSnapshot({ fullHistory })`); live turns are counted from the nearest stamped message.
- **Demo mode** gets an in-memory rating backend and a profile asking for every control. i18n for en/de/fr/es; styles for both themes and phones.
- Docs: Chat UI section in [`conversation-ratings.md`](../conversation-ratings.md); [`ui/chat/AGENTS.md`](../../ui/chat/AGENTS.md) structure and the rating error envelope.

### Design decisions

- The rating endpoints' error body is `{error: CODE, message}`, not the attachments' `{error, code}`; `rating-api.ts` parses its own and never shows the server's message.
- One thumb per **step**, under the step's last bubble, because the server stores one reply rating per step.
- Stars use radio semantics for screen readers, but arrow keys only move focus and Enter/Space rates: with "one tap rates", arrow-selects-and-submits would send a rating per key press.
- A rerun clears the thumb locally only, as plan R8 says; the stored rating reappears after a reload (documented limitation).

### Testing

`npm run typecheck`, `npm run lint` and the full vitest suite (34 files, 564 tests, 67 of them new) pass; key logic was mutation-checked. Vitest needs Node ≥ 22.15 (jsdom 30's undici): the Maven-downloaded `ui/node/v24.*` works.

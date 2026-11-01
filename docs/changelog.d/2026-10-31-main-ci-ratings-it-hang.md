## 🐛 fix(ci): ratings races in the Chat UI and the integration-test stall on main (2026-10-31)

**Repo:** EDDI (`fix/main-ci-ratings-it-hang`)

### What changed and why

Three CI failures present on `main` (and so on every PR) at 01c3250.

- **Chat UI ratings restore clobbered the person's own action.** `useRatings` applied the
  server's restore read verbatim, so a thumb tapped while the read was in flight was undone
  (flaky `ChatWidget.ratings.test.tsx`, and a real bug). Changes made since the read left are
  now kept over the server's older view. The restore effect also closed the rating card
  whenever it re-ran, including when the profile merely arrived after paint, so a fast click
  on "Rate this conversation" opened and immediately closed it (the Playwright
  `ratings-feedback.fullstack` timeout waiting for `rating-star-1`). It now closes only on a
  switch to another conversation. Both have unit tests in `useRatings.test.tsx`.
- **`RagWorkflowExtensionIT` stalled the failsafe fork.** It was the one Mongo-backed
  `@QuarkusTest` without `@TestProfile(IntegrationTestProfile.class)`, so Quarkus restarted the
  application without the Mongo dev-service connection string. The app then pointed at the
  unresolvable `mongodb:27017` and every Mongo-touching bean waited out the driver's 30 s server
  selection: 250 s on the last green run, ~580 s on main since more stores were added. The
  900 s fork timeout then killed whichever class was running (usually `PostgresGroupVerdictIT`,
  which is innocent). The test now carries the profile.

- **The rating card never opened for an agent that sends no greeting** (`ChatWidget.tsx`). With no messages the transcript renders its empty state, and the card was only rendered in the non-empty branch, so "Rate this conversation" set the state and nothing appeared. The Backend E2E agent has an empty workflow, so its ratings test waited for a star that was never rendered. The card now renders in both branches; a ChatWidget test covers the empty transcript.

- **Rating state across the restore read** (`useRatings`, `RatingCard`), from the review of this PR: an edit is now tracked per rating (in flight, confirmed since the read began, revision). A failed write rolls back to the server's value (from the read or the last confirmed write), never to empty, and only when no newer edit has started; a failed write before the read answers lets the read fill the value in. The per-conversation state is no longer cleared when the profile merely reloads for the same conversation, and the protection is retired when the next read begins. A card opened before the read finishes picks up the restored rating until the person has touched it.

### Design decisions

- No timeout was raised and nothing was skipped; the stall's cause was removed.

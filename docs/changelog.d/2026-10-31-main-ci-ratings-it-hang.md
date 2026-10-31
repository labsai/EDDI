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

### Design decisions

- No timeout was raised and nothing was skipped; the stall's cause was removed.

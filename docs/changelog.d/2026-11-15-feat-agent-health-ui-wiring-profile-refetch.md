## 🐛 fix(manager): the health profile editor needs a successful refetch, not cached data (2026-11-15)

**Repo:** EDDI (`feat/agent-health-ui-wiring`)

### What changed and why

Review findings on PR #1390.

- **The profile editor no longer opens on a cached profile after a failed refetch.**
  [`profile-dialog.tsx`](../../ui/manager/src/components/health/profile-dialog.tsx) mounted `ProfileForm` whenever
  `profile.data` was present and no fetch was running. TanStack Query keeps the last successful data when a refetch
  fails. So if a reopened dialog's refresh failed, the editor opened on the old profile, and a save could overwrite one
  that another admin had saved since. The form now also needs `!profile.isError`; the error is shown instead
  (`health-profile-load-error`). New test: "a reopened editor whose refetch fails shows the error and no form".
- **The mocked version comparison agrees with itself.** `compareFor` in
  [`health-fixtures.ts`](../../ui/manager/src/test/mocks/health-fixtures.ts) had hand-written deltas that contradicted
  its own sections. They are now read from the two sides, and a test checks each delta against its two values.
- **Stable selectors.** The profile tests find Cancel by `health-profile-cancel` and the override's section target by
  its option value, not by English copy.

## 🐛 fix(manager): a background refetch no longer throws away the profile being edited (2026-11-15)

**Repo:** EDDI (`feat/agent-health-ui-wiring`)

### What changed and why

Review finding on PR #1390. `ProfileDialog` hid the form whenever the profile query was fetching. So a background
refetch, from window focus or a save elsewhere, unmounted `ProfileForm` mid-edit, and its draft was lost. The dialog
now holds the profile from its first successful fetch after opening (`opened`). It keeps the form mounted on that
profile until the dialog closes, and closing clears it, so the next opening still waits for the server. New test: "a
background refetch while editing keeps the form and what is being typed". It holds the refetch in flight and fails
with the previous code.

## 🐛 fix(manager): each opening of the health profile editor asks the server (2026-11-15)

**Repo:** EDDI (`feat/agent-health-ui-wiring`)

### What changed and why

Review finding on PR #1390. The app's query client treats cached data as fresh for 30 seconds. A profile editor
reopened within that window was handed the cached profile without a request, and accepted it, even if another admin
had saved since. Two changes close this:

- `useHealthProfile` sets `staleTime: 0`, so every opening fetches. Only the editor uses this hook.
- `ProfileDialog` records when each opening began and accepts a profile only once `dataUpdatedAt` is at or after that
  time. Cache data from an earlier opening never starts a draft.

New test: "a reopening asks the server again even when the app's cache would still call the profile fresh". It runs
with `staleTime: 30_000` and fails without the change.

## 🐛 fix(manager): a reopened health profile editor starts its own request (2026-11-15)

**Repo:** EDDI (`feat/agent-health-ui-wiring`)

### What changed and why

Review finding on PR #1390. Reopening could reuse a background GET still in flight from before. If that GET had read
the profile before another admin saved, its late answer looked fresh. Each opening of `ProfileDialog` now calls
`refetch({ cancelRefetch: true })`, which drops any request in flight and starts a new one. The draft starts only from
that request's answer, and a late answer from an earlier request never reaches it. This replaces the `openedAt`
timestamp check. New test: "a reopening ignores an older GET still in flight and starts the draft from a request of
its own". It holds an old answer across close and reopen, and fails with the previous code.

## 🐛 fix(manager): a reopened profile editor replaces a first GET still in flight (2026-11-15)

**Repo:** EDDI (`feat/agent-health-ui-wiring`)

### What changed and why

Review finding on PR #1390. `refetch({ cancelRefetch: true })` cancels a request in flight only when the query
already holds data. With nothing cached, a dialog closed during its first GET and opened again joined that GET, and
could start from a profile read before someone saved. Each opening now calls
`queryClient.cancelQueries(healthKeys.profile(agentId))` before its `refetch()`, so a new request is always made.
New test: "a reopening before the first GET answered starts a request of its own, with nothing cached". The three
in-flight tests now hold responses with a `hold` flag instead of counting requests, because an opening sends a
cancelled GET as well as its own.

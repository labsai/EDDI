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

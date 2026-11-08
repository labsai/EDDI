## 🐛 fix(manager): saves and previews do not overwrite edits made while in flight (2026-11-08)

**Repo:** EDDI (`fix/manager-form-state`)

### What changed and why

Four Manager forms let the admin keep working while a request was running, then
applied the reply (or, for the transfer dialog, a Cancel) to whatever the form had
become by then.

- **Quotas** — a successful save replaced the form with the server's copy and
  cleared the dirty flag, so a limit typed during the PUT vanished and the
  unsaved-changes guard stopped protecting it. The page now counts edits; the
  server copy is taken only when nothing was edited since sending, otherwise the
  form keeps the edit and stays dirty.
- **Connection detail** — the same on success, plus a second loss: following the
  new version reset the form and re-seeded it from the cache. The save now moves
  the baseline to what it stored, keeps the draft when the form (or uncommitted
  chip text) changed since sending, and tells the route-change reset to leave that
  draft alone for exactly the version it created. The edit stays unsaved and the
  next save goes against the new version.
- **Agent sync** — a batch preview landed on rows whose target or "Create new"
  choice had changed during the request (a diff for one target shown under
  another, with Sync armed to send the new one), and a row ticked mid-flight was
  marked "Preview failed". A preview now applies only to a row still checked and
  still naming the target it was sent with.
- **Team budget transfer** — Cancel always started a fresh `Idempotency-Key` and
  an empty form, so after a 5xx or network error (which may have moved the money)
  re-entering the same transfer could move it twice. After such an error, or when
  the dialog is closed while the transfer is still running, closing keeps the
  values, the error and the key; a success or a 4xx starts a new transfer. The table's dead `initialFromId` prop is gone (its only opener passed
  `""`), and its open state is a boolean.

### Design decisions

- Snapshot-and-compare rather than locking the fields while saving: the forms
  stay usable, and nothing typed is thrown away. Quotas uses an edit counter
  because a half-typed number changes the dirty flag without changing the form.
- No new component or hook; each fix stays in the file that owns the state.

**Files:** [`quotas.tsx`](../../ui/manager/src/pages/quotas.tsx),
[`connection-detail.tsx`](../../ui/manager/src/pages/connection-detail.tsx),
[`sync-page.tsx`](../../ui/manager/src/pages/sync-page.tsx),
[`team-budget-dialogs.tsx`](../../ui/manager/src/components/quotas/team-budget-dialogs.tsx),
[`team-budgets-table.tsx`](../../ui/manager/src/components/quotas/team-budgets-table.tsx),
[`team-budgets.md`](../team-budgets.md)

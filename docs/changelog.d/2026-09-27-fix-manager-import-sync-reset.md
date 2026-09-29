## 🐛 fix(manager): import dialog kept the old instance's agents after the sync source changed (2026-09-27)

**Repo:** EDDI (`fix/manager-import-sync-reset`)

### Why

The Import dialog's "Sync from remote instance" path wired the URL and token
fields straight to their setters. The remote agent list, the source agent and
version picked from it, the local target and any preview built on them were
only cleared when the dialog closed. After connecting to instance A, editing
the URL or the token left A's agents on screen with "Preview Changes" still
armed, so A's agent could be previewed and imported against instance B.

The Sync page already handles this on the ops-pages branch (`handleSourceChange`
in `sync-page.tsx`). The dialog did not.

### What changed

[`import-agent-dialog.tsx`](../../ui/manager/src/components/agents/import-agent-dialog.tsx):
a URL or token edit now goes through `handleSyncSourceChange`, which mirrors the
Sync page. It applies the edit, then, if anything was derived from the previous
connection, clears the remote agent list, source agent and version, sync target,
the preview and the state built from it (selection, expanded diff, workflow
order), and the step error. It also resets the sync preview and execute
mutations. As on the Sync page, nothing is reset when there is nothing to drop.

Resetting the preview mutation also detaches a preview that is still in flight.
In TanStack Query v5, `reset()` removes the observer, so that request's
per-call `onSuccess` no longer fires, and a reply for the old source cannot move
the dialog onto the preview step.

[`sync-config-panel.tsx`](../../ui/manager/src/components/agents/sync-config-panel.tsx):
the connect request the panel sends is now tied to the URL and token it was sent
with. Each edit starts a new generation, and a reply or error from an older one
is dropped. Without this, a connect reply that arrived after an edit filled the
list the dialog had just cleared with the old instance's agents. This hunk is
ported byte-for-byte from the ops-pages branch (#854), together with its
panel test, so the two branches merge cleanly in either order and neither has
to wait for the other.

**Tests:** [`import-agent-dialog-sync-source.test.tsx`](../../ui/manager/src/components/agents/__tests__/import-agent-dialog-sync-source.test.tsx)
runs against the real hooks and the MSW sync handlers. The sibling test file
mocks the whole backup hook module. The new tests:

- A URL edit and a token edit after connect, pick and preview each drop the
  list and the selection. After reconnecting, the source and target selects
  come back empty and "Preview Changes" stays disabled.
- A preview still in flight when the URL changes does not land.
- A connect reply still in flight when the URL or the token changes does not
  land.

The in-flight tests hold the MSW reply behind a gate that exists from the start.
They wait until the request has reached the handler before editing, and until
the reply has left it before asserting, so the stale reply is really delivered
and the test cannot pass on a reply that was never sent.

Mutation check:

- Reverting the dialog fix fails the three dialog-state tests.
- Reverting the panel guard fails both connect tests.
- Dropping only the preview mutation reset fails the in-flight preview test.

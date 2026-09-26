## 🐛 fix(manager): pages move onto the version a save created (2026-09-26)

**Repo:** EDDI (`fix/manager-version-after-save`) — UI review High 4, plus the Save & Deploy `ERROR` handling

### The problem

Every config PUT creates a new version and names it in `Location`; the backend
refuses a write to a version that is no longer current with a 409. Several
Manager flows ignored that header and kept the version they were opened with,
so the edit appeared to revert on the next refetch and the next action failed.

### What changed, per flow

- **Cascade save** ([`cascade-save.ts`](../../ui/manager/src/lib/api/cascade-save.ts)).
  Two failure modes, both fixed:
  - *Exact match.* The agent's workflow reference was replaced only on an exact
    string match, and nothing checked that anything had been replaced, so an
    agent that spelled the reference differently was re-saved without the edit,
    and Save & Deploy shipped it. Both parents are now read and checked **before
    the resource is written**. The workflow step must end in
    `/{store}/{plural}/{id}` (the old substring test also matched ids that merely
    start with this one). The agent reference is matched by parsed id and version
    in any spelling. A missing reference throws `CascadeReferenceError` with
    nothing written, so no orphaned resource version is left behind.
  - *Recovery.* A hop that fails after the resource was written throws
    `CascadeSaveError`, carrying the versions that now exist and a
    `retryContext`. When only the agent hop failed, the new optional
    `CascadeContext.agentWorkflowVersion` records that the agent still points at
    the previous workflow version, so the retry repoints it instead of refusing.
    The resource editor, the Studio panel and the prompt editor
    (`useUpdateAgentPrompt`) adopt these versions, so a retry completes instead
    of 409ing until a reload discards the edit. Path B ("update usages") now
    passes `agentWorkflowVersion` for a second agent sharing a workflow that has
    already moved on. Before, the exact-match bug skipped that agent silently.
- **Agent Studio** ([`agent-studio.tsx`](../../ui/manager/src/pages/agent-studio.tsx),
  [`studio-editor-panel.tsx`](../../ui/manager/src/components/studio/studio-editor-panel.tsx)).
  The page, not the per-stage panel, now owns the workflow and agent versions, and the panel
  reports the next cascade context through `onCascadeContextChange`. The
  workflow query is keyed by version, so a reopened stage edits the version the
  save created. Placeholder data keeps the pipeline on screen while it loads.
- **Group detail** ([`group-detail.tsx`](../../ui/manager/src/pages/group-detail.tsx)).
  `useUpdateGroup` now resolves with the version the save created and seeds the
  cache under it. The inline HITL, phase and advanced editors report that version
  through `GroupConfigPanel.onVersionChange`, and the page writes it to the URL.
  `deleteGroupWithMembers` deletes the group **first**, so a refused group
  delete no longer leaves every member agent soft-deleted.
- **Workforce settings** ([`workforce-settings.tsx`](../../ui/manager/src/pages/workforce/workforce-settings.tsx)).
  After a save the URL moves to the new version. The form adopts the server's
  (normalised) copy while it still holds exactly what was saved, and never while
  an edit is in progress. Before, it stayed dirty forever and a second save or a
  delete 409'd.
- **Agent config sections**
  ([`use-agent-section-save.ts`](../../ui/manager/src/hooks/use-agent-section-save.ts)).
  The detail page's inline sections, and the A2A section, save through one hook.
  Its state is **per agent at module scope**, shared by every section, not per
  section: saves to one agent run one at a time, in order, whichever section
  made them. Each save is chained to the version the previous one created for as
  long as the page lags behind it. The fields this render changed are merged onto
  the document that version holds: the last save's, or the cached copy of the
  page's version when the section is still showing placeholder data from another
  version (so a stale placeholder can no longer overwrite a newer version).
  Failures are toasted, and `isPending` counts queued saves as well as the one
  in flight. Before, a quick second edit 409'd and was silently dropped.
  `useUpdateAgent` seeds the new version in the cache. Picking the latest version
  in the agent-detail picker now means "follow the latest" rather than pinning
  its number.
- **Save & Deploy** ([`use-save-and-deploy.ts`](../../ui/manager/src/hooks/use-save-and-deploy.ts)).
  A deployment `ERROR` stops polling and reports "Deployment failed" at once.
  The throw had sat inside the try whose catch swallowed it, so a failed deploy
  was polled for 30 s and reported as "Deploy timed out". Only a failed status
  *read* is retried now.

### Review round

- **Stale parents are refused before anything is written.** `loadParents` also
  reads `/currentversion` for the workflow and the agent. If either is newer than
  the version the page addresses (changed in another tab, say), the cascade
  throws `CascadeReferenceError` (`workflowChanged` / `agentChanged`). Before,
  the resource and workflow were written and only the agent PUT 409'd, and a
  retry with the handed-out context did the same again, leaving an orphan per
  attempt. An unreadable or lower answer is not treated as evidence; the PUTs
  still guard.
- **The cascade's refusals are translated.** `CascadeReferenceError` carries a
  `code` and `params`. `describeSaveError` (`src/lib/save-error.ts`) renders them
  from the new `cascadeSave.*` keys in all 11 locales, for the resource editor,
  Save & Deploy and the Studio.
- **Studio.** The panel is keyed by stage and resource id instead of the full
  URI, so a save no longer remounts it (which discarded anything typed meanwhile
  and reset the saved indicator). `useCascadeSave` seeds the version it created,
  so the editor does not drop to a loading state either. The page also remembers
  the newest version each resource was saved at, so returning to a stage after a
  workflow-hop failure does not address the superseded resource version.
- **The prompt editor's recovery survives unmount.** It is held at module scope,
  keyed by agent and LLM id, instead of in the hook instance.
- **Version lists refresh after a partial failure.** `useCascadeSave` also
  invalidates on a `CascadeSaveError`, so the picker offers the version the page
  moved onto.
- **Workforce settings: the post-save guard waits for the URL once** (an
  `awaitingUrl` flag), instead of refusing every version lower than the last
  save. A move back to an older version is followed again.

The strict `Location` parser moved from `cascade-save.ts` to
[`location-version.ts`](../../ui/manager/src/lib/api/location-version.ts), unchanged, so the
group and agent hooks can share it.

### Backend compatibility

Every version the UI now uses comes from a `Location` header, which is always
the live version. So the flows behave the same against current `main` and
against the pending backend change that makes descriptor PATCH answer 409 on a
non-current version. No REST shape, stored config or ZIP format changed.

### Not done here

- A group URL without `?version=` still defaults to 1. That is correct for a
  freshly created group, but a hand-typed link to a later version will 409 on
  save. Resolving through `/currentversion` is a follow-up.
- `useAgentVersions` (the agent-detail version list) belongs to
  `fix/manager-api-contract`. The "follow latest" change relies only on its
  first entry being the newest.

```regression-note
| 2026-09-26 | Saving a second Studio stage 409'd; group/workforce edits "reverted"; a quick second agent-section edit vanished | Pages kept the version they were opened with instead of the one in the save's `Location` | Pages move onto the saved version; cascade checks references before writing and reports partial progress for retry | (this branch) |
```

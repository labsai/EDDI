## fix(manager): save, version and deploy flows no longer lose edits or save nothing silently (2026-10-04)

**Repo:** EDDI (`fix/manager-version-save-flows`) — Manager only, no backend change.

### What changed and why

A UX review of the Manager found flows that discarded work, wrote versions that
changed nothing, or reported success while doing neither. Each is fixed at its
cause.

**Edits are no longer discarded silently**

- Agent Studio: choosing another pipeline stage (or another workflow) replaced
  the editor and dropped what was typed. The page now asks — Save, Discard or
  Cancel — when the editor has unsaved edits. `ConfigEditorLayout` reports its
  working copy (`onDraftChange`) and `StudioEditorPanel` registers a saver, so
  "Save" in the prompt keeps the edit and stays on the stage if the save fails.
- Workflow editor: Discard asks first, and offers to delete configs that "Add
  Task → Create new" wrote during the edit and that the discarded workflow state
  no longer references.
- The config editor refuses an invalid-JSON save out loud (toast, inline error,
  jump to the JSON tab) instead of doing nothing, for Save and Save & Test.

**"Add step, then configure it" works**

- A step added in this session is not in the saved workflow, so its resource
  editor could not cascade into it (`workflowMissingResource`, after the resource
  was already written). Such steps show "Not saved yet" and a "Save workflow &
  edit" action instead of an Edit link; it saves (and repoints the agent), then
  opens the resource against the new workflow version.
- "Create new" asks for a name (required) and stores it; it no longer writes
  unnamed orphan resources.
- Edit links now carry the version the step references (`?version=`), and the
  resource editor opens it, with a "Switch to latest" notice when it is not the
  newest.

**An agent pinned to an older workflow version**

- Agent → workflow links carry the referenced version. The workflow page opens
  that version and warns when the agent uses another one, with "Open version N"
  and "Point agent at version M".
- Save in an agent context now cascades into the agent like the resource editor
  does (new agent version, "Saved as vN — not live yet" with a Deploy action).
  Save and Save & Test read and check the agent first and refuse before writing
  anything when it would not change (previously an unchanged agent version was
  created and deployed).
- The resource editor's "agent changed since this page was opened — reload"
  error, which reloading could not fix, now says what is wrong and offers
  "Update agent and save".

**Studio test loop**

- Studio gets Save & Test (save, deploy, wait for READY, open the chat beside the
  editor on the new version) and the same "Saved as vN — not live yet" wording as
  the resource editor. The chat panel is bound to the studio's agent instead of
  whichever agent the global chat store last held. A workflow switcher appears
  for agents with more than one workflow.

**Agent page**

- Section toggles (Security, Capabilities, Memory, A2A, ...) each write a whole
  agent version with no feedback: they now toast "Saved as vN — not live yet" with
  a Deploy action. The dead `saveMessage` path is gone.
- The header badge no longer reads "Not deployed" while an older version is live:
  "Live: v5 · Viewing v6 (not deployed)". Header Undeploy names production when
  more than one environment is live. "Deploy & Chat" stops with an error when the
  deployment never becomes ready. The duplicate "v1" is gone.
- Removing a workflow asks first and has an accessible name; workflow rows show
  names.
- Collapsible sections expose `aria-expanded`/`aria-controls`, with the heading
  wrapping the button (not the reverse); A2A skill "+" and the version selects
  have names, A2A labels are tied to their inputs, and the Agent Card preview no
  longer hard-codes the name and "6.0.0".
- The Session Management note showed an unresolved placeholder in its endpoint
  path (i18next read `{{id}}` as an interpolation); the locale strings now say
  `:id`, and the dead disabled "Max Forks" field is removed.

**Smaller**

- Viewers (no EDIT level) get read-only editors on the resource and workflow
  pages: no save/discard/add controls.
- Agents, workflows and resources can be renamed in place (name and description
  on the detail header, `EditableTitle`).
- Workflow save errors show the real reason (`describeSaveError`) instead of a
  generic message. Ctrl/Cmd+S saves in the config editor and the workflow editor.
- The saved tick clears once there are unsaved edits again; the version picker
  explains why it is locked while dirty; the resource editor keeps its tab,
  scroll and state across a save (`placeholderData` on `useResource`).
- Agent create requires a name, and a failed descriptor PATCH after the POST is
  treated as created with a warning (no duplicate on retry).
- Agents list: a name/version sort loads the remaining pages first (the API has
  no sort parameter), with a hint while it does.

### Design decisions

- **Not-live feedback is one shared toast** (`lib/save-not-live-toast.ts`, a stable
  toast id) for the workflow page, Studio and agent section saves. The resource
  editor keeps its own inline copy of the same toast, unchanged.
- **Unsaved steps are blocked, not auto-saved.** Auto-saving the workflow behind an
  Edit click would write versions the user did not ask for.
- **"Update agent and save"** reuses the cascade's existing `agentWorkflowVersion`
  escape hatch (written for the partial-failure retry), so no new backend call.
- Renames patch the descriptor of the newest version (the list reads it) and of the
  viewed one when they differ.

### Deliberately skipped / follow-ups

- Server-side sort of the agents list: the descriptor API takes no sort
  parameter, so the UI loads every page instead.
- An older resource version opened from a pipeline link is editable but a save
  from it 409s (the backend only accepts the current version); the notice offers
  "Switch to latest" rather than blocking the editor.
- Removing a just-created step (rather than discarding the whole edit) does not
  offer to delete its resource.

**Files:** [`workflow-detail.tsx`](../../ui/manager/src/pages/workflow-detail.tsx),
[`agent-studio.tsx`](../../ui/manager/src/pages/agent-studio.tsx),
[`agent-detail.tsx`](../../ui/manager/src/pages/agent-detail.tsx),
[`resource-detail.tsx`](../../ui/manager/src/pages/resource-detail.tsx),
[`config-editor-layout.tsx`](../../ui/manager/src/components/editors/config-editor-layout.tsx),
[`pipeline-builder.tsx`](../../ui/manager/src/components/editors/pipeline-builder.tsx),
[`workflow-step-links.ts`](../../ui/manager/src/lib/workflow-step-links.ts),
[`save-not-live-toast.ts`](../../ui/manager/src/lib/save-not-live-toast.ts)

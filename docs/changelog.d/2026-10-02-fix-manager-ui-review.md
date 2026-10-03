## 🐛 fix(manager): safe deletes, an in-app unsaved-changes guard, role-aware UI and the review's UI findings (2026-10-02)

**Repo:** EDDI (`fix/manager-ui-review`) — Manager only (`ui/manager`); no Java changed.

The first of three PRs answering the Manager findings of the 2026-10-02 review (§2 #7, §3.4 Manager
row, §4.10). This one carries the behavioural fixes that change what the user reads, and with them
every locale; `fix/manager-streams-and-editors` carries the fixes that need no new strings, and
`refactor/manager-dead-exports` the dead-code removal. The three do not depend on each other. Each
behavioural fix has a regression test that was mutation-checked (the fix reverted, the test seen to
fail).

### Deletes are soft unless the user says otherwise

- `deleteGroup` and `deleteConnection` defaulted to `permanent=true`, the opposite of the backend.
  The grey **Delete Group Only** was therefore the unrecoverable one, and a permanent group delete
  also removes the group's standing workspace — backlog, cadences and their schedules
  (`RestAgentGroupStore.deleteGroup`) — while the red **Delete Group + All Agents** was the
  recoverable one. The Workforce card, settings page and bulk delete all hard-deleted too.
- Both API functions now default to soft. Every delete dialog (groups list, group panel, Workforce
  card / settings / bulk, connections list and detail) offers one unticked
  `PermanentDeleteOption` that names what a hard delete removes and relabels the confirm button.
  `deleteGroupWithMembers` applies the same choice to the group and every member agent.
- A member that cannot be deleted is no longer swallowed (`GroupMembersDeleteError`, a 404 counts
  as already gone). On the permanent path the group is checked against its current version first,
  the members go next, and the group is purged **last** — only once every member is gone — so a
  failed member leaves a group to retry from instead of orphaned agents and an unrecoverable group.
- Review follow-up: the sidebar stores collapsed sections by `labelKey` (positions shift when a role
  hides a section; old numeric state is migrated), and the command palette no longer offers a
  recent page the current role cannot open.

### Unsaved changes are guarded inside the app too

- `main.tsx` mounts a data router (`createBrowserRouter`, one catch-all route around the existing
  `<Routes>`), so `useUnsavedChangesGuard` now holds in-app navigation (links, `navigate()`, Back)
  with `useBlocker`, and the config editor, workflow detail, connection detail and the Workforce
  agent sheet render `UnsavedChangesPrompt`. Only a change of path is blocked (a save moving the
  page to `?version=` is not); `allowNextNavigation()` lets a deliberate navigation through.
- Config editor: a save no longer overwrites what was typed while it was in flight (the reset
  decision runs in an effect, not inside a state updater that moved the refs it read), and Save on
  invalid JSON says why and opens the JSON tab instead of doing nothing.
- Agent detail disables its sections while another version's document stands in as placeholder.

### Role-aware UI

[`lib/roles.ts`](../../ui/manager/src/lib/roles.ts) maps the screens whose backend admits only some
roles to the `@RolesAllowed` of the resources they call — eddi-admin for logs, coordinator, audit,
secrets, quotas, orphans and GDPR; eddi-admin and eddi-editor for connections (the connection store
lets an editor list and read them); eddi-admin and eddi-user for user data. The sidebar, command
palette and dashboard quick actions hide what the user's role cannot open; the dashboard no longer
requests coordinator status or vault health for a non-admin and says "requires admin" (also on a
401/403) instead of "Vault unavailable" / "Coordinator —"; the platform probe uses
`/administration/docs` (every role) for them. With auth off, or a token carrying no EDDI role at all
(roles mapped from another claim), everything stays offered and the backend decides.

### Other findings

- Chat: a failed send clears the typing indicator and shows a translated error flagged `isError`
  (Retry no longer matches on translated text); End Conversation reports errors and clears only the
  conversation it was clicked for.
- Workforce **Duplicate** linked to `/workforce/{id}?version=1/settings`; `parseIdFromLocation`
  strips the query. The group wizard keeps the members it created when the moderator fails, so a
  retry no longer creates every member agent again.
- Audit "Load more" with auto-refresh: one list deduplicated by entry id, the newest page always
  refreshed, the next offset taken from the rows loaded.
- Quotas: a cleared field saves `-1` (unlimited), never `0` (which blocks the tenant); text that is
  not a number yet blocks Save; labels are bound to their inputs.
- Approvals: a notice when the 200-entry cap of the 1:1 list is reached (the endpoint has no
  offset — see [`hitl.md`](../hitl.md)).
- Errors now carry the backend's reason: group panel deletes, Workforce history delete, the orphan
  scan (and the purge's `409 incomplete_scan`). The coordinator's refresh selector drives both polls.
- Confirmations for deleting a space variable and a memory entry; the memory row toggle is a real
  button; workspace settings can leave the legacy visibility unset ("default applies") instead of
  storing the default's value on every save.
- Dev mode: the MSW auto-start read the pre-sign-in 401 as "backend unreachable" and served mocks.
- Hard-coded strings and two literal colours moved to i18n keys and theme tokens; all 11 locales.
- Dead code in the files this PR touches anyway: `useStartDiscussion`, `getGroupJsonSchema`,
  `STANCE_SUMMARY_DEFAULT_MAX_CHARS`, `RESUME_KIND_HUMAN_TURN*` and `PROPOSAL_OPEN`/`_SUPERSEDED`.

**Not done:** splitting the 36 files over 800 lines; a global `MutationCache` error fallback (it
cannot see per-call `onError`, so it would double-toast every site already fixed). A soft-deleted
group keeps its workspace, so its cadence schedules stay enabled and each fire fails until the group
is purged or deleted permanently — a backend follow-up, not something the Manager can fix.

```decision-log
| 2026-10-02 | Manager deletes default to soft; hard delete is an explicit unticked option in every dialog | Review #7: "Delete Group Only" sent permanent=true and also deleted the workspace | Keeping permanent defaults with stronger wording; removing hard delete from the UI entirely |
| 2026-10-02 | Data router with one catch-all route around the existing <Routes> to get useBlocker | No in-app unsaved-changes guard was possible under <BrowserRouter> | Rewriting the route table as route objects; intercepting link clicks by hand |
| 2026-10-02 | Role-aware UI fails open when the token carries no EDDI role | A deployment may map roles from another claim; hiding admin screens from a real admin is worse than noise | Hiding everything not positively allowed |
```

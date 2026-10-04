## fix(manager): in-app navigation no longer discards unsaved edits; shell, tour, palette and top-bar fixes (2026-10-04)

**Repo:** EDDI (`fix/manager-navigation-guard`), `ui/manager`

### What changed and why

Workstream A1 of the Manager/Chat UX review: routing, navigation chrome and the unsaved-changes guard.

- **Unsaved-changes guard now covers in-app navigation.** The app moved from `<BrowserRouter>` to a data router (`createBrowserRouter` + `RouterProvider`, `src/app-router.tsx`), so `useUnsavedChangesGuard(isDirty)` can use `useBlocker`. A sidebar link, breadcrumb, command-palette jump or the Back button on a dirty page now asks Stay / Discard (`NavigationGuardDialog`); only a path change is held, so switching a tab through a query parameter is not. The hook's signature is unchanged, so every page that already calls it is protected. `allowNextNavigation()` is the one-shot escape hatch for programmatic navigation whose edits are already resolved (delete, confirmed discard); it is wired into the connection, workflow and resource detail pages.
- **A crashing page no longer wipes the shell.** `SuspendedOutlet` carries an `ErrorBoundary` keyed on the location, so the sidebar and top bar survive and any navigation recovers.
- **Mobile navigation drawer**: closes on route change, is a modal `dialog` with a focus trap, takes focus on open, closes on Escape and gives focus back. The language selector, hidden in the top bar below `sm`, is reachable in the drawer. `isMobile` starts from the real width (no desktop-sidebar flash); sidebar collapse state is now remembered.
- **Guided tour**: keys and the body scroll lock attach only while the target is on screen; Enter on a focused Back / Skip button no longer advances; the chapter ends (not marked done) on route change; a missing target is waited for with a `MutationObserver` and abandoned after 8 s; the Help-menu replay waits for the page instead of a fixed 300 ms; the tooltip takes focus.
- **Unknown routes** show a Page not found screen inside the shell (`/manage/*`, `/workforce/*`, top level) instead of silently redirecting to `/welcome`. `/welcome?choose` shows the chooser even when a landing preference is saved.
- **One route-label registry** (`src/lib/route-registry.ts`) now feeds the breadcrumb, document title, command palette and sidebar. `/manage/groups/wizard` no longer reads "Agent Wizard", nine sections no longer show raw lowercase segments, and an agent detail page shows the agent's name.
- **Command palette**: built on cmdk's `Command.Dialog` (modal, focus trap, focus return); Create New Agent opens the wizard (the old `?action=create` target was read by nothing); lists every page; agents are searched on the server (30 rows, one per agent, debounced, only while open); Ctrl+K is left alone inside Monaco.
- **Top bar**: language selector has an accessible name; the user menu is a Radix `DropdownMenu` (arrow keys, Escape, focus return); the current page name and a compact status dot show on phones; breadcrumb label is translated.
- **Toasts follow the resolved theme.** `ThemeProvider` storage access is wrapped in try/catch.
- **Mode switcher** is a pair of links, not an ARIA tablist, and no longer rewrites the saved landing preference (only the chooser's explicit choice does).
- **Stacked dialogs**: the unsaved-changes dialog handles Escape in the capture phase and stops it, and `AccessibleDialog` ignores an already-handled Escape, so one press closes one dialog.
- Smaller: collapse state per section is stored by stable id (old index saves are still read); Help-menu completion is announced; the untranslated "Checking version...", "Expand/Collapse sidebar" and "Breadcrumb" strings are translated in all 11 locales.

### Design decisions

- `<App />` keeps its `<Routes>` table under a single catch-all data route rather than converting every route to route objects: no route, lazy page or layout changed, and every existing `MemoryRouter`-based test still renders `<App />`.
- `useUnsavedChangesGuard` skips the blocker half when no data router is present (`UNSAFE_DataRouterContext`), so component tests with a bare `MemoryRouter` keep working; the data-router behaviour has its own tests.
- The dirty-page prompt reuses `UnsavedChangesDialog` with a "Stay" label.

### Deliberately skipped / follow-ups

- Server-side agent search relies on the descriptor listing's existing `filter` text match.
- The workforce shell keeps its own responsive handling; only its not-found route was added.

```decision-log
| 2026-10-04 | Manager uses a data router with one catch-all route wrapping `<App />` | `useBlocker` needs a data router; converting every route would churn the table and every `MemoryRouter` test | Route objects per page |
```

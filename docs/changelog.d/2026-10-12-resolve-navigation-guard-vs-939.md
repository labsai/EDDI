## Navigation guard: one app-level blocker wins over per-page blockers (2026-10-12)

**Repo/branch:** EDDI, `fix/manager-navigation-guard` (merge of `main`)

**What changed**
- #939 (on `main`) and this branch both added in-app unsaved-changes protection. `main` called `useBlocker` from every page through `useUnsavedChangesGuard` and rendered an `UnsavedChangesPrompt` per page; this branch registers dirty state in a store and holds navigation with ONE blocker in `NavigationGuardDialog`.
- The merge keeps the single blocker. `connection-detail`, `workflow-detail`, `config-editor-layout` and `agent-editor-sheet` now just call `useUnsavedChangesGuard(isDirty)`, use the module-level `allowNextNavigation()` for deliberate navigations, and no longer render a prompt. `UnsavedChangesPrompt` and `main`'s per-page navigation test were removed.
- `main`'s role-aware navigation (`useMayOpenScreen`) is kept in the rebuilt sidebar and command palette; both PRs' stable-id section collapse state collapsed into the route-registry ids.

**Design decision:** a router consults only the most recently registered blocker, so a per-page blocker lets any clean guard mounted later silently unprotect a dirty page. Regression tests in `use-unsaved-changes-guard.router.test.tsx` cover a clean sibling mounting after the dirty page and the browser Back button; they fail if each hook registers its own `useBlocker`.

## 💬 feat(ui): `@eddi/conversation` — the agent kind (2026-11-05)

**Repo:** EDDI (`feat/ui-eddi-conversation`, stacked on `feat/ui-eddi-ui`)

Phase 3b of the frontend unification plan (`planning/frontend-unification-plan.md` §1.3, §2.5, §3.1, §3.2, §7 phase 3, D9): the third shared package, [`@eddi/conversation`](../../ui/packages/conversation/README.md), with the `AgentConversation` controller.

### What changed and why

- **`AgentConversation`** ([`agent-conversation.ts`](../../ui/packages/conversation/src/controller/agent-conversation.ts)) — the 1:1 turn state machine, framework-free (`subscribe` / `getSnapshot`, read through `useSyncExternalStore`), on `@eddi/core`: streaming, a 409 that restores the draft, dropped vs paused turns, `inputField` on `done`, undo/redo, rerun, stop, cancel, the refresh after a pause, and `resume(conversationId)`, which restores the last output's quick replies. Its suite is the union of the 1:1 loops' turn rules, without React.
- **The shared `conversation.*` strings** in the eleven locales; the Manager's `i18n:check` reads a package's own locale files with the app's.
- **Gates.** CI's `UI Chat` job runs the package's type check, lint and tests; `sharedPackage({ routerFree: true })` and `boundaries.test.ts` keep it router-free, off `window.__EDDI_*__`, and unimported by `@eddi/core` and `@eddi/ui`; `AutoApproveCopilotPolicyTest` covers its config files.

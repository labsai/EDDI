## 📝 docs(planning): frontend unification and end-user UX plan (2026-10-29)

**Repo:** EDDI (`claude/amazing-thompson-jzrvz7`)

### What changed and why

A plan only; no code changes. [`planning/frontend-unification-plan.md`](../../planning/frontend-unification-plan.md) covers three goals: split Workforce out of the Manager bundle into its own app, build the Chat UI, the Manager and Workforce from one set of shared packages (`ui/packages/{core,tokens,ui,i18n,conversation,teams}` on npm workspaces), and make the end-user experience competitive with Open WebUI, LibreChat, ChatGPT and Claude.ai.

The survey behind it:

- Workforce is already its own shell and backend resource, but it shares the Manager's bundle, router and providers.
- The Manager's `ChatPanel` and the Chat UI's `ChatWidget` implement the same backend contract twice. Each has fixes and features the other lacks.
- The biggest end-user gap is on the backend. An `eddi-user` cannot list the agents or groups they may use, because `agentstore`, `deploymentstore` and `groupstore` are admin/editor only. Conversations also have no title, rename, pin or search.

### Design decisions

- Keep the `ui/manager` and `ui/chat` paths, and add `ui/workforce` and `ui/packages/*`, so `pom.xml`, `ci.yml` and the docs do not churn.
- Packages are consumed as source and stay router-free. The conversation state machine becomes a framework-free controller.
- The plan proposes Tailwind v4 and i18next for the Chat UI too, with a bundle budget in CI.
- Workforce becomes the role-aware end-user app, and `/chat` stays the embed surface. Seven open questions are listed in §8 of the plan.

### What's next

Answers to §8, then Phase 0 (baseline numbers and an executable feature matrix).

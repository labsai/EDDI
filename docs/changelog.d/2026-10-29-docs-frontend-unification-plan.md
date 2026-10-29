## 📝 docs(planning): frontend unification and end-user UX plan (2026-10-29)

**Repo:** EDDI (`claude/amazing-thompson-jzrvz7`)

### What changed and why

A plan only; no code changes. [`planning/frontend-unification-plan.md`](../../planning/frontend-unification-plan.md) covers three goals:

- split Workforce out of the Manager into its own app;
- build the Chat UI, the Manager and Workforce from shared packages (`ui/packages/{core,ui,conversation}` on npm workspaces);
- make the end-user experience competitive with Open WebUI, LibreChat, ChatGPT and Claude.ai.

Revision 2 came out of a review against the code, a real build, and eight perspectives: end user, agent designer, admin, embedder, security, performance, maintainers and product. The plan's §0 lists what changed. The main findings:

- **Five separate 1:1 turn loops**, not two. The Manager chat (`use-chat.ts`), the Platform Operator (`use-operator-chat.ts`), the Workforce member thread (`workforce-thread.tsx`) and the Chat UI widget each implement the same `/agents/{id}/stream` contract. There are also three group-transcript renderers and 14 `ReactMarkdown` call sites without one rendering policy. The shared layer is therefore designed around four kinds of conversation: agent, operator, team and read-only transcript.
- **Measured bundles.** `/workforce` loads exactly the files `/manage` loads: 714 KB gzipped before the first page. Of the 606 KB main stylesheet, 388 KB is `@font-face` rules for all eight Noto script families. The widget loads 217 KB gzipped, of which `rehype-raw`'s `parse5` and react-router are avoidable.
- **The "which agents may I use" logic already exists** for `GET /v1/models` (`AgentModelResolver`, `ResourceAccessGuard`). User-memory endpoints already check ownership.
- **Workforce fails for `eddi-user`.** It reads the admin/editor-only `/groupstore`, and nothing in it checks roles.

### Design decisions

- Vertical slices instead of refactor-first. Phase 0 ships the font and widget size wins and a CI bundle budget. The Workforce split comes after the shared packages, so it shares code instead of copying it.
- The catalog lists an agent only when it is opted in (default off), so an upgrade exposes nothing, the Platform Operator included.
- Which controls a conversation offers becomes per-agent configuration served through `/profile`. The widget's URL flags may only narrow it.
- One per-user event stream, because the browser allows only six connections per origin over HTTP/1.1.
- EDDI's own end-user app leads on what an OpenAI-protocol client such as Open WebUI cannot show: approvals, teams, structured input and memory transparency.

### What's next

Answers to the ten questions in §10 of the plan, then Phase 0.

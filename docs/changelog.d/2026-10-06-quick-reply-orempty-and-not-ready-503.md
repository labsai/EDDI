## Missing quick-reply array no longer fails the turn; managed calls answer 503 while no agent is ready (2026-10-06)

Repo: EDDI, branch `fix/quick-reply-orempty-and-not-ready-503`. Both found migrating a production 5.x deployment to 6.x.

- `PrePostUtils.renderPerIteration` now iterates `<pathToTargetArray>.orEmpty`, so a response without the array (e.g. an LLM reply with no `quickReplies`) yields zero quick replies / output items instead of `Iteration error - {...} not found` (5.x behaviour). Not appended when the path already ends in `.orEmpty` or uses `?:`, `??`, `or` or a range. This also covers `batchRequests` iteration, which shares the renderer. Tested against the real Qute engine with strict rendering off.
- `RestAgentManagement`: when `RestAgentEngine` refuses to start a conversation because no agent version is ready (404; or 503 while draining), the managed endpoints (`loadConversationMemory`, `sayWithinContext`) now answer 503 with `Retry-After: 5` and a clear message instead of an NPE on the missing user conversation and a 500. The concurrent-creation fallback also no longer dereferences a null conversation.
- Design: `RestAgentEngine` keeps its 404 for the start endpoint (public contract); the translation to 503 happens in the managed layer. Undo/redo/end endpoints only act on existing conversations and cannot hit this.
- Docs: `docs/httpcalls.md`, `docs/managed-agents.md`.

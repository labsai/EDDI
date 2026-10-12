## A2A streaming uses the conversation-scoped `sayStreaming` (2026-10-13)

**Repo:** EDDI · **Branch:** `fix/a2a-streaming-after-990`

**What changed:**
- **The break:** main stopped compiling after two PRs that were green on their own were merged in parallel.
  - #953 (A2A 1.0) called `IConversationService.sayStreaming(Environment, agentId, conversationId, …)`.
  - #990 removed that environment-qualified overload, which had no other callers.
- **The fix:** `A2ATaskHandler` now calls `sayStreaming(conversationId, …)`, which reads the agent and environment from the stored conversation record. `A2ATaskHandlerTest` stubs that overload.

**Behaviour:** unchanged. An A2A conversation is always started as `startConversation(Environment.production, agentId, …)`, so the record holds exactly the agent and environment the old call passed explicitly. The non-streaming path still uses the environment-qualified `say`, which #990 kept.

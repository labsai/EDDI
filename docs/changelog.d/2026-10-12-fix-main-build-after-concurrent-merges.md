## 🐛 fix(a2a): stream A2A turns through the agent-qualified `sayStreaming` again (2026-10-12)

**Repo:** EDDI (`fix/main-build-after-concurrent-merges`)

### What changed and why

#949 removed the environment-qualified `sayStreaming(Environment, agentId,
conversationId, …)` from `IConversationService` (no caller outside
`ConversationService` at the time) while #953 (A2A 1.0 / 0.3) added one in
`A2ATaskHandler`. Merged in parallel, `main` stopped compiling. #1008 made it
compile by moving the A2A call onto the conversation-id overload — which runs the
caller-facing checks, `requireConversationAccess` (the conversation-ownership
guard) among them.

A2A is driven by a peer agent, not by the signed-in owner of a stored
conversation, and A2A's non-streaming path still calls the qualified `say()` for
exactly that reason. After #1008 the two paths disagreed: a streaming turn ran an
ownership check the non-streaming turn skips. This restores the qualified
overload on the interface (public on the implementation) and points A2A
`message/stream` back at it, so both A2A paths go through the same entry point —
the behaviour #953 was written and reviewed against.

`A2ATaskHandlerTest`'s streaming tests stub the qualified overload again, so they
fail if the call drifts back to the conversation-id one.

**Files:** [`IConversationService.java`](../../src/main/java/ai/labs/eddi/engine/api/IConversationService.java),
[`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`A2ATaskHandler.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskHandler.java),
[`A2ATaskHandlerTest.java`](../../src/test/java/ai/labs/eddi/engine/a2a/A2ATaskHandlerTest.java)

```decision-log
| 2026-10-12 | A2A streaming uses the agent-qualified `sayStreaming`, like A2A's non-streaming `say()` | #1008 fixed the #949/#953 compile break by routing A2A streaming through the conversation-id overload and its ownership check | Keeping #1008's routing (A2A streaming and non-streaming would apply different access rules) |
```

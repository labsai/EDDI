## fix(llm): a model request never leaves without a user or assistant message (2026-11-15)

**Repo:** EDDI · **Branch:** `fix/llm-request-without-content`

Staging on 6.7.0 failed six times with Gemini's `400 INVALID_ARGUMENT: GenerateContentRequest.contents: contents is not specified`, always for the `greeting` task; the cascade escalated with the same request and the configured fallback was served.

### Root cause

The greeting fires at `CONVERSATION_START`, where step 0 has no user input. `ConversationHistoryBuilder` therefore produced no history and the request consisted of the system message alone. EDDI maps system messages to Gemini's `systemInstruction`, so `contents` was empty and Gemini refuses that. The plain, tool-loop, cascade and answer-tool paths all start from this one list, so all were affected; only a task with an explicit `prompt` (which becomes the user turn) was spared.

### What changed

- [`ConversationHistoryBuilder`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java): both the step-count and the token-aware builder append one user turn when the list holds no `UserMessage` or `AiMessage`. A list with content is untouched; a completely empty list still means "skip the call". The turn is request-only and never written to memory.
- New task parameter `emptyInputPrompt` (templated). Unset or blank: `(The conversation has just started. Greet the user.)` at the conversation opening, `(Continue the conversation.)` afterwards (for example `logSizeLimit: 0` without a prompt). It is a default, not a switch; the text is English, so multilingual agents should set it in their language or as `{context.language}`. Passed by `LlmTask` (first call, halved-window rebuild) and by `ToolLoopResumer` (degraded HITL rebuild); the tool loop, cascade steps, streaming and format re-asks all derive from the same list. Documented in [`langchain.md`](../langchain.md).

### Not changed

A cascade still escalates after a non-retryable 400: a later step may be another provider or model, so the retry is not provably identical. With the fix the malformed request no longer exists.

## Conversation search and tool_result review fixes (2026-11-16)

Repo: EDDI, branch `feat/conversation-search-tool-events`.

- `ToolTraceRedaction.summary` redacts again after normalization, so a credential split by a bidi character cannot be rejoined into the `tool_result` summary.
- `searchConversations` applies the exact-owner restriction only when authorization is enabled (`ConversationAccessGuard.authorizationEnabled()`), counts only rows with readable memory toward the page offset (an orphan ahead of a match no longer yields an empty page), and skips a descriptor whose resource URI does not parse instead of failing the page.
- `ToolLoopRunner.executeToolCall` emits no `tool_result` for a call aborted by a cancel or an interrupt.
- The app-kit MSW search handler orders matches newest first before paging.

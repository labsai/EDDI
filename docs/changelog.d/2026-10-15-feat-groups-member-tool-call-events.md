## feat(groups): `member_tool_call` SSE event for group members (2026-10-15)

**Repo:** EDDI (`feat/groups-member-tool-call-events`)

### What changed and why

A group member's turn is not streamed, so an observer saw nothing between
`speaker_start` and `speaker_complete`, however long the member's tool loop ran.
The 1:1 stream already emits `tool_call` (name only); the group stream now emits
`member_tool_call` `{agentId, displayName, tool}` with the same rule: the name
travels, the arguments never do.

- `IConversationMemory` gains a name-only `toolCallObserver` (default null),
  deliberately **separate from the event sink**: a non-null sink switches the LLM
  task into its streaming path, which a member turn must not trigger.
  `ToolLoopRunner` notifies it next to the sink (observer failures are swallowed).
- `ConversationResponseHandler.toolCallObserver()` (default null) lets a plain
  `say` caller supply it; `ConversationService` installs it per turn.
  `MemberTurnExecutor` supplies one only when a listener exists, and drops events
  from a cancelled speaker (the callback runs on a coordinator thread).
- `GroupDiscussionEventListener.onMemberToolCall` (default no-op), emitted by
  `RestGroupConversation`. `SlackGroupDiscussionListener` keeps the no-op: Slack
  posts finished contributions, and a message per tool call would be noise.
- Manager: the speaking member's typing indicator reads "using X..." instead of
  "responding..." (i18n in all locales). Docs: SSE table gains `member_tool_call`,
  `cost_updated`, `stance_updated`, and `token` is marked as never emitted.

### Design decisions

- Observer on memory rather than a new `say` overload: keeps the 9-argument
  `say` signature (and every existing mock of it) intact.
- Absent observer or listener changes nothing.

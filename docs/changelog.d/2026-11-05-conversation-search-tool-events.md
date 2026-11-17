## ✨ feat(conversations): search my conversations; tool_result on the stream (2026-11-05)

**Repo:** EDDI (`feat/conversation-search-tool-events`, stacked on `feat/conversation-operator-team`)

The backend half of phase 7 of the frontend unification plan (`planning/frontend-unification-plan.md`, rows B3 and B6; T1.3, T1.6 and T1.9 build on them).

### What changed and why

**B3: search my conversations.**

- `GET /conversationstore/conversations/search?q=&index=&limit=` returns the caller's own conversations whose **title** contains `q`, newest first, as the same descriptors the listing returns. It is paged like the listing: `index` is a page of results, and `index × limit` above 10,000 is a `400`. A page can come back short, because a match whose conversation memory is gone is left out.
- **Owner scope.** The caller's conversations only, for every caller, an administrator included. This is "my conversations"; an administrator who needs everybody's uses the listing's `filter`. The owner goes into the query as an exact `userId` match (`QueryFilter.exact`), so another user's descriptor is never read, and a row-level check repeats it. Because the owner is exact, a page from the store is a page of results, and the listing's owner-scan budget and back-fill are not needed. An anonymous caller with authorization on gets an empty list without a query. With authorization disabled every conversation is searched, as the listing then shows every conversation. A pre-v5.1.6 descriptor that records no owner is not searched; such descriptors predate titles.
- **Matching.** A new `IResourceFilter.ContainsIgnoreCase` is the case-insensitive twin of `Contains`: an escaped regex with the `i` option on MongoDB, an escaped `ILIKE … ESCAPE '\'` on PostgreSQL. Either way `q` is literal text. `q` is normalized the way titles are stored (`ConversationTitles.requireValidSearchQuery`: whitespace collapsed, control and bidi characters dropped) and must be 1 to 100 characters. No new index: the exact owner predicate, which uses the existing `userId` index, narrows the rows first.
- **Privacy.** Only titles are searched. A title never comes from a secret turn (B2 derives it after the turn-end scrub), it lives on the descriptor, and GDPR erasure deletes every descriptor of the user (`GdprComplianceServiceTest.deleteUserData_erasesConversationTitlesWithTheirDescriptors`). The search keeps no index of its own and drops a match without memory, so nothing it finds outlives an erasure. `RestConversationStoreSearchTest` pins title-only matching, the owner scope, the dropped foreign and orphan rows, normalization and paging.
- **Outputs are not searched (follow-up).** They live in the conversation memory documents, not the descriptors, so searching them needs its own index on both backends. They also hold model and tool text that the turn-end scrub does not cover: it removes secret input and secret context, not what a tool echoed. So searching them needs a redaction decision as well as an index, and that was not clearly safe to do here.
- Surfaces: `searchConversations` in the app-kit API layer, an MSW handler, the line in `openapi-operations.json` (added by hand, as for earlier endpoints: no instance was running to refresh it), and [`rest-api-reference.md`](../rest-api-reference.md#searching-my-conversations) and [`gdpr-compliance.md`](../gdpr-compliance.md).

**B6: `tool_result` on the SSE stream.**

- After every tool call it announced with `tool_call`, a streamed turn now sends `tool_result {tool, ok, summary}`. `ok` is true when the tool ran and `ToolOutcomeClassifier` reads its result as a success: an HTTP error status, an `error` field or a `NOT_EXECUTED`/`DENIED` status make it false, and so does a refusal (budget, quota, turn deadline). `summary` is the start of the result or of the refusal, at most 200 characters.
- **The same redaction as `toolTrace`.** The redaction `LifecycleManager` applied to the `task_complete` trace moved into `ToolTraceRedaction` (`redactTrace`, `redactValue`), and both channels use it. `ToolTraceRedaction.summary` redacts the **whole** text before cutting it, so a credential that straddles the cut cannot leak its first characters past the redactor's minimum length. A test pins this.
- The event is built in `ToolLoopRunner.executeToolCall`, which now wraps the old body (`runToolCall`), from the trace entry this call wrote. That covers every exit, the mock, budget and quota refusals included, and the resume path of an approved call. No event is sent for a call cut off by a cancellation or an approval pause. A failing sink never breaks the tool loop. The event is plumbed through `ConversationEventSink`, `StreamingResponseHandler`, both `ConversationService` bridges and `RestAgentEngineStreaming` as a typed `ToolResultEvent`.
- It is additive: older clients ignore unknown events. **UI:** `SSEEventType` lists `tool_result`, and `llm_retry`, which the backend already sent. A new contract test in `stream-events.review.test.ts` reads the event names from `RestAgentEngineStreaming.java` and fails when the union and the Java disagree. The parser is in a separate entry, `@eddi/core/tool-events` (`parseToolResult`, `toolResultListener`), which plugs into `AgentConversation`'s `onStreamEvent`. The widget does not import it and its controller is unchanged, so the widget bundle, at its 170 KB budget, does not grow. A widget test pins that nothing of the payload reaches the page.
- No meter: the per-tool counters (`eddi_tool_calls_total` and friends) already count these calls, and the event adds no new outcome.
- Group discussions: not done. A member's turn is not streamed, and only its tool *name* reaches the group stream (`member_tool_call`). A `member_tool_result` needs its own observer, so it is left as a follow-up.

**Platform Operator.** The prompt explains the search (the caller's own conversations only, an admin's too; titles only; not one of its tools) and `tool_result`. The allow-list is unchanged. The search answers only the caller's own conversations, so it would not help the operator inspect anyone else's, and the operator already holds the listing. Revision 55.

### Design decisions

- Titles only, now. Outputs need an index and a redaction rule of their own (see above).
- Owner-exact for administrators as well: the endpoint is the end user's search box, not an admin tool.
- A substring match (escaped regex / `ILIKE`), not a MongoDB text index or a PostgreSQL `tsvector`. Titles are short. Users expect a substring to match ("rom" finds "Rome"), which word-stemmed full-text search does not do. The exact owner predicate keeps the scan to one user's rows, and the existing descriptor query path and both backends' escaping are reused without new schema.

### What's next

- Frontend: the Workforce sidebar search box (T1.3), and tool cards (T1.9) and the "this turn ran tools" notice on edit and regenerate (T1.6) built on `@eddi/core/tool-events`.
- Searching outputs, after the privacy review above. `member_tool_result` for group discussions.

**Files:** [`IRestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/IRestConversationStore.java), [`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java), [`IResourceFilter.java`](../../src/main/java/ai/labs/eddi/datastore/IResourceFilter.java), [`ConversationTitles.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationTitles.java), [`ToolTraceRedaction.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/ToolTraceRedaction.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`RestAgentEngineStreaming.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java), [`tool-events.ts`](../../ui/packages/core/src/tool-events.ts); docs [`rest-api-reference.md`](../rest-api-reference.md), [`gdpr-compliance.md`](../gdpr-compliance.md), [`README.md`](../README.md).

```decision-log
| 2026-11-05 | Conversation search matches titles only, scoped to the caller by an exact userId match, administrators included | A title is never from a secret turn and is erased with the descriptor; "my conversations" is the end user's search, and the exact owner keeps paging simple and foreign rows unread | Searching outputs now (memory documents, unscrubbed tool text); letting admins search everyone's (the listing's filter does that) |
| 2026-11-05 | Search is a case-insensitive escaped substring (regex i / ILIKE), not a Mongo text index or a Postgres tsvector | Titles are short, users expect substring matches, the owner predicate bounds the scan, and both backends' escaping already exists | Full-text indexes (stemmed words, no substrings, new schema on both backends) |
| 2026-11-05 | tool_result's summary goes through ToolTraceRedaction, the redaction the task_complete toolTrace uses, on the whole text before truncation | One redactor for both outward channels; a cut before redaction can leave a key too short to recognise | A second redactor for the event; truncating first |
| 2026-11-05 | The tool_result parser lives in a separate @eddi/core/tool-events entry | The widget is at its bundle budget and shows no tool cards | Parsing in AgentConversation's switch (widget bytes) |
```

## 🐛 fix(groups): streaming continue binds the caller; MCP and templates enforce REST's checks (2026-11-06)

**Repo:** EDDI (`fix/group-rest-mcp-parity`)

### What changed and why

Three places where one surface of the group API did less than its sibling.

- **A streamed continuation ran without its caller.**
  `POST /groups/{groupId}/conversations/{id}/continue/stream` hands the round to
  `RestGroupConversation`'s own virtual-thread executor. Nothing carried the caller
  across, and on that thread the request scope is gone, so the member turns'
  `captureOrCurrent()` found nobody: every `${caller:userId}` and `${caller:token}`
  failed closed on this path alone. The caller is now captured on the request thread and bound around the
  work with `CallerIdentityContext.withIdentity`, the way `startAndDiscussAsync`
  already does for a streamed start. `CallerIdentityContext` is a new constructor
  parameter of `RestGroupConversation`.
- **The MCP discussion tools skipped the question ceilings.** REST re-checks by
  hand that a question is present and at most 50,000 characters, and that `userId`
  and `targetAgentId` are at most 256, because a question is fanned out to every
  member in every phase at the group owner's cost. An MCP tool calls the service
  in-process, where no Bean Validation constraint runs, so a caller with the
  CONVERSE role (`eddi-viewer` included) could send a multi-megabyte question, or
  start a discussion about nothing. The check now lives in `GroupQuestionLimits`,
  next to `GroupAttachmentLimits`, and `discuss_with_group`,
  `start_group_discussion`, `followup_with_member` and `continue_group_discussion`
  call it before any read, access check or service call, answering
  `{"error": …, "errorCode": "BAD_REQUEST"}`. REST calls the same class, so both
  surfaces refuse the same input with the same text. The constants stay on
  `IRestGroupConversation`.
- **Creating a group from a template turned an access refusal into a 500.**
  `POST /groupstore/templates/{id}/instantiate` saves through
  `IRestAgentGroupStore.createGroup`, which throws `ForbiddenException` for an
  assigned agent the caller may not use. The generic `catch (Exception)` logged it
  as an error with a stack trace and answered `500`. A `ForbiddenException` or a
  JAX-RS `WebApplicationException` is now rethrown, as `RestTeamHandoffs` and
  `RestGroupWorkspace` do, so the caller gets the `403` (or the store's own status).

The Javadoc on `budgetRefused` said the team-budget refusal "carries no
caller-supplied id". It does carry the group id: the stored one, already resolved
by the group read that precedes admission, and needed by the administrator `PUT`
path in the exhausted message. The comment now says that.

### Design decisions

- **Bind at the dispatch, not in the service.** Moving the async dispatch into
  `GroupConversationService`, or running the SSE method inline on a virtual
  thread, would also fix it, but would change when the SSE response is committed.
  Capturing before `submit` is the established pattern and a two-line change.
- **One check class, not a public static on the REST resource.** The MCP tools
  are in another package; making `RestGroupConversation.rejectionReason` public
  would have the MCP layer depend on a JAX-RS resource. `GroupQuestionLimits`
  reads the ceilings from `IRestGroupConversation`, so nothing is duplicated.
- **List limits not clamped.** The plan also suggested clamping `limit` in
  `list_groups` and `list_group_conversations`. REST takes the same unbounded
  `limit`, and both are bounded by the storage layer's `MAX_RESULT_LIMIT` (10,000),
  so this is not a check REST makes and MCP skips. Left for a change that bounds
  both surfaces together.

**Files:** [`RestGroupConversation.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java),
[`GroupQuestionLimits.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupQuestionLimits.java),
[`McpGroupTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpGroupTools.java),
[`RestGroupTemplates.java`](../../src/main/java/ai/labs/eddi/configs/groups/rest/RestGroupTemplates.java),
[`group-conversations.md`](../group-conversations.md); tests in `RestGroupConversationExtendedTest`,
`McpGroupToolsTest`, `RestGroupTemplatesTest`, and the constructor update in the other five
`RestGroupConversation*Test` classes.

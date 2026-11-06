## 🐛 fix(managed-conversations): one claim path for the user-conversation mapping (2026-11-06)

**Repo:** EDDI (`fix/managed-conversation-claims`)

### What changed and why

A managed conversation is one conversation per intent and user, held by a `UserConversation` mapping
with a unique (intent, userId) key. Two requests that both need a new conversation (none yet, or the
current one has ended) each start one and each try to store the mapping. The REST endpoints and the MCP
`chat_managed` tool handled that race differently, and both badly:

- **REST, ended conversation** (`RestAgentManagement`): the replace deleted the mapping unconditionally.
  A slower request deleted the fresh mapping a faster one had just stored, so the two clients went on
  talking to two different conversations; or its insert collided and the request failed with an opaque
  `500`.
- **REST, no mapping yet**: the loser re-read the winner, but left its own started conversation running
  with nothing pointing at it.
- **MCP `chat_managed`** (`McpConversationTools`): a lost insert fell into the generic catch and answered
  "Failed to chat via managed agent", again leaving the started conversation running. A store failure on
  the mapping read was swallowed as "no mapping", so a database hiccup started a second conversation and
  orphaned the user's real one. An agent that was not deployed yet answered a generic failure where REST
  answers `503` with `Retry-After`. And it always took the trigger's first deployment, where REST picks
  one at random.

Now there is one claim path. `IUserConversationStore.createOrAdopt` stores the candidate mapping or, on
a duplicate key, returns the mapping that won (a store error if that one is gone again by the time it is
read). Both callers use it, end the conversation they started whenever it is not the one returned
(best effort, logged on failure), and replace an ended mapping with `deleteUserConversationIfMatches`, so a
delete only removes the mapping while it still names the ended conversation. This is the pattern the
Slack, webhook and email channels already followed. MCP now lets a mapping-read failure surface, answers a
not-ready agent with `"errorCode": "UNAVAILABLE"`, and picks a random deployment like REST.

`RestAgentManagement` also takes `SecurityIdentity` and `OwnershipValidator` through its constructor.
The `{userId}` ownership check used to run only `if (ownershipValidator != null)`, a guard that turned
itself off when its dependency was missing, and the three test classes set both fields by reflection
(two of them never set the validator, so they ran with the check off). The check is now unconditional and
the tests pass both through the constructor.

**Behaviour changes:** a REST request that loses the replace race is served on the winner's conversation
instead of failing with `500`; `chat_managed` answers the same race with the winner's reply, a not-ready
agent with `UNAVAILABLE`, and spreads new conversations across a trigger's deployments. A conversation a
request started but could not claim is ended (MCP records `system:lost-create-race` as who ended it).

**Files:** [`IUserConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/triggermanagement/IUserConversationStore.java),
[`RestAgentManagement.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`McpConversationTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`managed-agents.md`](../managed-agents.md), [`mcp-server.md`](../mcp-server.md)

**Not changed:** `EmailInboundProcessor` and `OpenAiConversationBridge` keep their own copies of the
adopt-and-end logic; moving them onto `createOrAdopt` is a follow-up.

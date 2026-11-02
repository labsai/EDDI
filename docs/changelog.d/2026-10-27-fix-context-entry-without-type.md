## An untyped context entry is a 400, not a 500 (2026-10-27)

**Repo:** EDDI · **Branch:** `fix/context-entry-without-type`

**Problem.** `POST /agents/{agentId}/start` answered `500` with a `NullPointerException` ("`contextType` is null")
when a context entry had no `type`. The usual cause is a caller wrapping the map in `{"context": {…}}`: the start body
*is* the context map, so Jackson reads `context` as one entry with no type, and
`ConversationMemoryUtilities.prepareContext` switched on it. Found on a staging deployment while scripting an
end-to-end test; a `say` with an untyped entry went the same way.

**Change.**
- `RestAgentEngine.requireTypedContext` refuses a null or untyped entry with `400`
  `{"error": {"code": "INVALID_CONTEXT", …}}` naming the key and the valid types. It runs on `start`, on `say` and on
  the streaming `say`, before the turn is queued or the stream is opened, next to the existing input checks.
- `prepareContext` skips an untyped entry like an unknown type instead of throwing, so a producer other than these
  three endpoints cannot crash a turn with it either.
- [`rest-api-reference.md`](../rest-api-reference.md) documents the rule.

**Tests.** `RestAgentEngineClientContextTest` (start, say and streaming say: 400, nothing forwarded, no SSE frame) and
`ConversationMemoryUtilitiesTest` (an untyped entry is skipped, the others kept).

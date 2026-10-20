## 🐛 fix(hitl): a resume could not re-supply secret context, because loading a conversation dropped `secret` (2026-10-20)

**Repo:** EDDI (`fix/secret-context-survives-store-load`)

### What changed and why

Secret context on resume (#1114) refused every resume that re-supplied a secret with 400 — `context['userToken'] was not a secret context value in the paused turn` — although the paused step was stored with `"secret": true` and the placeholder. Both conversation stores rebuild a stored `context:*` entry as `new Context(type, value)` when they load a conversation, which dropped the flag, and `ConversationHitlService.validateResumeContext` and `Conversation.injectResumeSecretContext` both read it from that loaded memory. A resume always loads from the store, so the feature could not work in a deployment; the tests of #1114 and #1116 build the memory in process and never went through a load.

Both stores now keep the flag when they rebuild the entry. Nothing else changes: the stored value is still the placeholder, the re-supplied value lives for the resumed turn only, and that turn stores the placeholder again.

Files: [`ConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java) (`loadConversationMemorySnapshot`), [`PostgresConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java) (`fixContextTypes`).

### Tests

- `MongoConversationMemoryStoreTest`, `PostgresConversationMemoryStoreTest`: a secret entry keeps `secret: true` and the placeholder through a real store round trip; an ordinary entry stays unmarked.
- `ConversationMemoryStoreResilienceTest`: the same on the MongoDB load path with a mocked collection, so it also runs without Docker.
- Without the fix all three new tests fail (`expected: <true> but was: <null>`).
- End to end against an image built from this branch (MongoDB, an agent whose gated `POST` tool sends `Bearer {context.userToken}`): the resume that re-supplies the token now runs the approved call with it, where 6.6.0 answered 400; re-supplying a key that was not secret in the paused turn is still a 400 and leaves the pause; a resume without the token is still refused; neither token is stored in the conversation afterwards.

```regression-note
| 2026-10-20 | Every HITL resume that re-supplied secret context answered 400, so an approved call acting as the user could never run | Both conversation stores rebuilt context entries without the `secret` flag when loading a conversation; the resume check and the injection read it from that memory | Both loaders keep the flag; store round-trip tests for MongoDB and PostgreSQL | (this branch) |
```

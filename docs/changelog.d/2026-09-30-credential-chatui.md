## fix(migration): identifiers are no longer held back as credentials by the legacy-properties migration (2026-09-30)

**Repo:** EDDI (`fix/650-credential-chatui`)

### Why

On a 5.5.1 database, `PropertiesMigrationService` held back five `createdProgram` properties whose
value is `{courseId: <a 17-character alphanumeric id>}` and logged them as credentials. The rule
that fired is the entropy check in `SecretScrubber.scrubTextValue` (check 3): a key-like string of
at least 14 characters scoring over 3.5 bits per character is redacted unless its field is one of
the structural names. A random 17-character id scores about 4.1, and `courseId` is not structural.
The values stayed in `properties_migrated_v6`, but the users lost those memories.

### What changed

- [`PropertiesMigrationService`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/PropertiesMigrationService.java):
  the copy of the value handed to the scrubber (which already replaced BSON ObjectIds) now also
  replaces an **identifier-shaped** string (`[A-Za-z0-9_-]{1,128}`) under an **identifier-named**
  field — `id`, or a name whose last word is `id`/`ids` (`courseId`, `course_id`, `courseID`,
  `courseIds`) — with a placeholder. Names qualified by a credential word (`sessionId`,
  `accessKeyId`, `tokenId`, `apiId`) get no exemption.
- A new **known-format** check holds back any key whose value contains, at any depth, a JWT, a
  pasted `Bearer`/`Basic` value, or a provider key prefix (`sk-`, Stripe, Slack, GitHub, GitLab,
  AWS, Google, Hugging Face). It closes a gap the entropy rule never covered: `"Bearer …"` has a
  space, so the scrubber's whole-value pattern did not match it. An identifier-named field gets no
  exemption for these.
- The credential-name rules are unchanged, and the scrubber itself is untouched: the narrowing
  applies to this migration only, so export scrubbing is exactly as strict as before.

### Tests

`PropertiesMigrationServiceTest$IdentifiersAreNotCredentials`: the id migrates (with a
precondition test proving the scrubber alone still flags it); name variants migrate; `token`,
`apiKey`, a JWT, a `Bearer` value, an `sk-` key, a JWT under `courseId`, a high-entropy value under
a neutral key and under `sessionId` are all held back and logged by key name only; `userInfo` is
still skipped. Name- and format-rule cases use zero-entropy values so entropy cannot catch them
for the wrong reason. Mutation-checked.

### Not changed

The export scrubber has the same false positive: any identifier of 14+ random characters under a
non-structural field name is redacted on export. Loosening it globally is not shown to be safe
everywhere, so it is left as it is.

## fix(chat): a user holding only eddi-user sees the agent's name (2026-09-30)

**Repo:** EDDI (`fix/650-credential-chatui`)

### Why

The Chat UI read the agent's name from `GET /descriptorstore/descriptors/{id}/simple`, which is
`@RolesAllowed({"eddi-admin", "eddi-editor"})`. A chat user holding only `eddi-user` got a 403;
the failure was swallowed, and the header showed only the logo — never the name of the agent they
were talking to.

### What changed

- **The conversation read carries the name.** `SimpleConversationMemorySnapshot` gains a nullable
  `agentName`, which
  [`RestAgentEngine.readConversation`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java)
  sets after its ownership check, through the new
  [`AgentDisplayNameResolver`](../../src/main/java/ai/labs/eddi/engine/internal/AgentDisplayNameResolver.java).
  The managed-conversation load goes through the same method. No endpoint was added and no role
  was widened.
- **Only the name, only for a user of the agent.** The resolver checks the caller's `USE` access
  to the agent (the gate `POST /agents/{agentId}/start` applies) before reading the descriptor of
  the conversation's agent version, and returns the name alone — not the description, owner,
  grants or configuration. Any failure answers `null`; the read itself never fails over a name.
- **Chat UI** ([`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx)) takes the name from
  the snapshot and no longer calls the descriptor store; `fetchAgentDescriptor` is removed. With no
  name the header shows the logo, titled with the configured `title`.

### Tests

`AgentDisplayNameResolverTest` (name present for a `USE` caller; absent and no descriptor read
without `USE`; absent on a store failure or blank name; description never serialised; `eddi-user`
admitted by `readConversation` and by neither `IRestDocumentDescriptorStore` nor
`IRestAgentStore`). Chat UI: name from the snapshot with the descriptor store answering 403,
fallback to logo and title, blank name ignored. Mutation-checked.

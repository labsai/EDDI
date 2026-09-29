## 🐛 fix(engine): start managed conversations with a per-request copy of the trigger context (2026-09-27)

**Repo:** EDDI (`fix/managed-agent-auth-first`, second commit)

### What changed and why

When `RestAgentManagement.createNewConversation` started a managed conversation, it
wrote the caller's language straight into `agentDeployment.getInitialContext()`.
That deployment belongs to the `AgentTriggerConfiguration` returned by
`RestAgentTriggerStore.readAgentTrigger`, which is the instance held in the
shared `agentTriggers` cache, not a copy. Its `initialContext` is a plain
`HashMap`. As a result:

- concurrent requests raced on one map, so a conversation could start with
  another user's language;
- a request without a language replaced an earlier value with a null-valued
  `lang` context;
- the `HashMap` was written from several request threads without
  synchronization;
- the cached trigger drifted from the stored one until its cache entry was
  replaced.

Each request now copies the deployment's context
(`new HashMap<>(initialContext)`, or an empty map when the deployment has none),
puts `lang` into the copy, and passes the copy to
`startConversationWithContext`. The trigger in the cache is never written to.

**Null language:** kept as it was for the engine. With no language, the engine
still receives a `lang` entry of type `string` whose value is null. Leaving the
key out would change what a started conversation sees in its context. What was
wrong was writing that entry into the shared map, not sending it.

A deployment whose `initialContext` is null (possible for a stored trigger that
omits the field) used to throw an NPE on the `put`. It now starts the
conversation with a map that holds only `lang`.

### Tests

The new nested class "Trigger context is copied per request" in
`RestAgentManagementExtendedTest` covers five paths:

- `loadCreate`, `sayCreate` (language taken from the input context), and
  `loadReplaceEnded`;
- `nullLanguage`: the engine still receives a null-valued `lang`;
- `nullInitialContext`.

Each test asserts two things:

- the deployment's `initialContext` equals its value before the call and has no
  `lang` key;
- the map captured on `startConversationWithContext` is a different instance,
  still carries the designer-set entry, and has `lang` with the expected value.

**Mutation check:** restoring the in-place `put` fails all five tests. Four fail
on "the cached trigger's context was modified", and `nullInitialContext` fails
with the NPE.

**Files:** [`RestAgentManagement.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestAgentManagementExtendedTest.java`](../../src/test/java/ai/labs/eddi/engine/internal/RestAgentManagementExtendedTest.java)

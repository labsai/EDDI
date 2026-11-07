## 🐛 fix(setup): opaque 500 with a correlation id; every setup write goes through one writer (2026-11-07)

**Repo:** EDDI (`fix/setup-small-defects`)

### What changed and why

**The setup endpoints no longer echo an unexpected exception's message.**
`POST /administration/agents/setup`, `/setup-api` and `/{agentId}/setup-api/sync`
answered any failure their 4xx arms did not map with
`500 {"error": "Agent setup failed: " + e.getMessage()}`. Such a message is not
written for the caller: a MongoDB timeout names the replica it waited for
(`… waiting for a server: mongo-0.internal:27017`), a REST proxy failure the store
it could not reach. The three arms now share one helper, `opaqueServerError`, that
logs the full exception under a fresh UUID and answers
`500 {"error": "Agent setup failed (correlationId: <uuid>)"}` — the contract
`ResourceStoreExceptionMapper` and `RestAgentManagement` already follow. The
`error` key stays, because the Manager reads it. The 4xx arms (validation,
forbidden, not found, dangling references) still carry their author-facing text.

**`setupAgent` writes through the same `ResourceWriter` as `createApiAgent`.**
It created its parser, behaviour rules, LLM config, output set, workflow and agent
with seven hand-written copies of create, read `Location`, record for rollback,
patch the descriptor name; `createApiAgent` ran the same steps through the writer
but still inlined its agent write, and the MCP-calls loop existed twice (live and
dry run). There was no behavioural drift, but a step added later could miss the
rollback record or the descriptor name at one call site without any test noticing.
`ResourceWriter.createDescribed` now does create, record and describe in that order
in one place; every top-level document of both setup paths and each MCP-calls
config goes through it, the two MCP loops are one (`ResourceWriter.mcpCalls` is
gone), and `setupAgent` rolls back through `writer.rollback`. Behaviour, `resources`
keys and rollback order are unchanged.

### Design decisions

- **Correlation id in the `error` text, not a new field.** It matches the other
  opaque 500s (`… (correlationId: …)`) and needs no client change. A shared
  cross-class helper was rejected for now: the three existing copies answer in
  different shapes (JSON body, `text/plain`, SSE event).
- **No `AgentRef` record for the agent write.** The agent is created through
  `createDescribed` like the rest, and its id and version are read from the
  returned location — two lines, instead of a record and a method.
- **No `dryRun` on `SetupAgentRequest`.** That changes the REST and MCP contract
  and its docs; with `setupAgent` on the writer it is now a one-line
  `writerFor(request.dryRun())` follow-up.

### Not changed

`setupAgent` and `createApiAgent` still wrap a failure inside their own `try` as
`AgentSetupException("Failed to set up agent: " + e.getMessage())`, which the REST
layer answers as `400` with that text. That path is deliberate (the hardening tests
pin it, and a validation message raised there must reach the caller), so it is a
follow-up rather than part of this fix.

**Files:** [`RestAgentSetup.java`](../../src/main/java/ai/labs/eddi/engine/rest/RestAgentSetup.java),
[`AgentSetupService.java`](../../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java),
[`RestAgentSetupTest.java`](../../src/test/java/ai/labs/eddi/engine/rest/RestAgentSetupTest.java) (new),
[`AgentSetupServiceTest.java`](../../src/test/java/ai/labs/eddi/engine/setup/AgentSetupServiceTest.java)

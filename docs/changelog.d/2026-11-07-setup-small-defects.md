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
It created its parser, behaviour rules, LLM config, MCP-calls configs, output set,
workflow and agent with seven hand-written copies of create, read `Location`, record for rollback,
patch the descriptor name; `createApiAgent` ran the same steps through the writer
but still inlined its agent write, and the MCP-calls loop existed twice (live and
dry run). There was no behavioural drift, but a step added later could miss the
rollback record or the descriptor name at one call site without any test noticing.
`ResourceWriter.createDescribed` now does create, record and describe in that order
in one place; every document of both setup paths goes through it except
`createApiAgent`'s per-group httpcalls, which are recorded as one list before
their loop and named `<agent> - <group>`, and so keep `create` and `describe`.
The two MCP loops are one (`ResourceWriter.mcpCalls` is gone), and `setupAgent`
rolls back through `writer.rollback`. Behaviour, `resources` keys and rollback
order are unchanged.

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

**A failure the setup service wraps is a 500 now, not a 400 — caller-visible.**
`setupAgent` and `createApiAgent` wrapped anything their own `try` caught as
`AgentSetupException("Failed to set up agent: " + e.getMessage())`, the sync as
`"Sync failed: " + e.getMessage() + …`, and the named-vault-key paths appended the
provider's message (`Could not read vault key 'x': <provider text>`). The REST layer
answers `AgentSetupException` with `400`, so a store timeout or a vault backend error
was reported as the caller's fault, with the internal text attached. These sites now
throw `AgentSetupInternalException` (a subclass) through
`AgentSetupInternalException.internalFailure(safeMessage, cause)`: it logs the full
cause at ERROR under a fresh correlation id, keeps the cause attached, and its message
is the safe sentence plus `(correlationId: <uuid>)`, never the cause's text.
`RestAgentSetup` answers it with
`500 {"error": "Failed to set up agent (correlationId: <uuid>)"}` on all three
endpoints; the sync message keeps "Agent X was NOT changed (still version N)" and the
orphaned versions it wrote. The two vault-write failures that already withheld the
provider's text ("could not be stored in the secrets vault … Refusing to store it in
plaintext") are the same kind of failure and also answer 500 now. **Clients that
branched on `400` for these see `500`;** the Manager does not (the wizard shows
`error`, operator activation only checks 404/409). A validation `AgentSetupException`
raised inside the same `try` (an unparseable spec, a rejected `maxToolIterations`)
still passes through unchanged as `400` with its own message. The MCP setup tools and
`create_sub_agent` show `e.getMessage()` for any `AgentSetupException`, so they now
show the safe sentence and the id instead of the cause.

### Not changed

The MCP twins of these endpoints, `setup_agent` and `create_api_agent` in
`McpSetupTools`, answer an unmapped failure with
`errorJson("Failed to set up agent", e)`, which carries the cause's message. They
could use `McpToolUtils.toolFailure`, which describes a caller-facing failure and
logs anything else under a reference id; that is a separate change to a separate
surface.

**Files:** [`RestAgentSetup.java`](../../src/main/java/ai/labs/eddi/engine/rest/RestAgentSetup.java),
[`AgentSetupService.java`](../../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java),
[`ApiAgentSyncService.java`](../../src/main/java/ai/labs/eddi/engine/setup/ApiAgentSyncService.java),
[`AgentSetupHardeningTest.java`](../../src/test/java/ai/labs/eddi/engine/setup/AgentSetupHardeningTest.java),
[`AgentSetupVaultKeyReuseTest.java`](../../src/test/java/ai/labs/eddi/engine/setup/AgentSetupVaultKeyReuseTest.java),
[`RestAgentSetupTest.java`](../../src/test/java/ai/labs/eddi/engine/rest/RestAgentSetupTest.java) (new),
[`AgentSetupServiceTest.java`](../../src/test/java/ai/labs/eddi/engine/setup/AgentSetupServiceTest.java)

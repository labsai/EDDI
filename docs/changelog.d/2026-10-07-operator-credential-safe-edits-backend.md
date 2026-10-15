## feat(configs): PATCH (RFC 6902) on the LLM, HTTP-call and MCP-call stores (2026-10-07)

**Repo:** EDDI (`feat/operator-credential-safe-edits`)

### What changed and why

The Platform Operator could not edit an LLM configuration that has an API key — nearly all of them. It read the document (`"apiKey": "${vault:anthropic-api-key}"`), added a field, and sent the whole thing back with `PUT`; the call was refused because the body carried a vault reference its own configuration did not write (`ConfigReferenceGuard`). The refusal is correct and is unchanged. It also protects against something worse: had the guard allowed the echo, `ApiCallExecutor.buildRequest` resolves `${vault:…}` in the rendered body (`resolveSecrets`, immediately after the guard) and would have sent — and the store would have kept in a new, exportable version — the **plaintext** key.

The way through is to not send what does not change:

- `PATCH /llmstore/llms/{id}?version=N`, `PATCH /apicallstore/apicalls/{id}?version=N` and `PATCH /mcpcallsstore/mcpcalls/{id}?version=N`, with `Content-Type: application/json-patch+json`, apply an RFC 6902 patch to version `N`, validate the result exactly as `PUT` does and store it as a new version (returned in `Location`). `409` when `N` is not the current version, `400` for a failed `test` operation, a path that does not resolve, an unknown operation or a patched document the model rejects.
- A patch never mentions a credential it is not changing, so the stored reference is carried through untouched, and a patch that tries to *introduce* one is refused by `ConfigReferenceGuard` like any other call.
- `McpApiToolBuilder` only mapped `application/json` request bodies, so a PATCH declared as `application/json-patch+json` produced a tool with no body at all. It now maps `application/json-patch+json` too (plain JSON still wins when both are declared) and describes the parameter as an RFC 6902 patch.

### Design decisions

- **One implementation, three stores.** `RestVersionInfo.patch` (access check, version check, read, store write, `Location` — everything `update` does) plus `ConfigJsonPatcher` (parse, apply, strict re-parse). Each store contributes a three-line method and its own pre-write validation, the same function its `PUT` runs.
- **No new dependency.** `jakarta.json` (Parsson) is already on the classpath through `quarkus-rest`.
- **Own path/media type, no collision.** `output` and `dictionary` already have a `PATCH /{id}` with `application/json` and a different body (`PatchInstruction` SET/DELETE); neither holds credential references, so they are untouched. The three stores here had no PATCH, so the same path with `application/json-patch+json` cannot clash. OpenAPI allows one PATCH operation per path, so a store that already has the other PATCH would need a sub-path.
- **`PUT` stays.** It still works for documents without references and is what the Manager editors use.
- **Server-side approval guard.** `SelfUngatingGuard` (the check of an approved call before it executes, on the Slack, MCP and REST approval paths too) refused an llmstore write carrying `toolApprovals` as a key; a patch names the field in a path, which that test missed. It now also refuses a patch whose `path`/`from` names the field, whose `value` carries it, or that uses an operation other than add/replace/remove/test. The REST endpoint itself stays unrestricted: it cannot tell the operator from an admin with the same role.
- **Bounded.** A patch is limited to 256 KiB and 200 operations.

### Files

[`ConfigJsonPatcher.java`](../../src/main/java/ai/labs/eddi/configs/patch/ConfigJsonPatcher.java), [`RestVersionInfo.java`](../../src/main/java/ai/labs/eddi/configs/rest/RestVersionInfo.java), [`IRestLlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/IRestLlmStore.java), [`IRestApiCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/IRestApiCallsStore.java), [`IRestMcpCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/mcpcalls/IRestMcpCallsStore.java), [`McpApiToolBuilder.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java), [`secrets-vault.md`](../secrets-vault.md#editing-a-configuration-that-holds-a-reference-patch-not-echo)

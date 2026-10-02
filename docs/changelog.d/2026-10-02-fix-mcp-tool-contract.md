## 🐛 fix(mcp): honest tool contract — optional arguments, real defaults, isError, one role matrix with REST (2026-10-02)

**Repo:** EDDI (`fix/mcp-tool-contract`) — review 2026-10-02 §2 #4, #17, §3.4 (MCP rows), §4.5

### What changed and why

**Required-argument contract (High, live-verified).** quarkus-mcp-server 2.x makes
every `@ToolArg` required unless it says `required = false`, and refuses a call
that omits one (or passes `null`) with "Missing required argument". 75 of the 84
tools therefore declared *every* argument required — 235 of 244 arguments,
including 100 whose own descriptions said "optional" or "default: …". `list_agents {}`
failed, `chat_with_agent` could not be called without the `conversationId` it
promises to create, and `setup_agent` demanded all 14 arguments. Every optional
argument is now `required = false`, literal defaults are published as the schema
`default` (`environment` → `production`, limits, booleans, `style` → `ROUND_TABLE`),
and descriptions say what the code does. 122 arguments remain required — the
identifiers, messages, bodies and verdicts a tool cannot work without.

**Defaults that lied.** `get_agent` promised "default: latest" and read version 1;
it now resolves the current version, as do `read_workflow`, `read_resource` and
`list_agent_resources` (which said "default: 1"). `list_conversations` without
`agentVersion` lists every version and now says so. The duplicated
"'production' (default), 'production', or 'test'" is gone from every tool and from
`mcp-server.md`.

**Errors are tool errors.** Tools reported failures by *returning* `errorJson(…)`,
which the server sends as a successful result: "Access denied", "not found" and
validation failures all arrived with `isError: false`, and a missing role escaped
as a JSON-RPC internal error naming `io.quarkus.security.ForbiddenException`. A
new interceptor (`@McpErrorResults` → `McpErrorResultInterceptor`) rethrows an
error payload as `ToolCallException` — `isError: true`, same JSON body — and maps
a role refusal to `errorCode: FORBIDDEN`. The tools' return types, and the unit
tests that compare their strings, are unchanged.

**`McpGroupTools`.** `create_group` with no `memberAgentIds` threw an NPE that
reached the caller as `{"error":"null"}`, and an unknown `style` silently became
`ROUND_TABLE`; both are now `BAD_REQUEST` (the latter lists the valid styles). The
13 tools that returned `errorJson(e.getMessage())` go through one `failure(…)`:
caller mistakes (`IllegalArgumentException`, 4xx) are described, not-found is
`NOT_FOUND`, anything else is logged and answered with a fixed message and
`INTERNAL`.

**Role tiers (Medium, live-verified).** MCP and REST disagreed in three
directions: editors could run `setup_agent`/`create_api_agent` (REST setup is
admin-only), editors could *not* deploy (REST deploy admits them), and viewers
could read agent configurations REST refuses them. And because a role check is a
literal `hasRole`, an admin or editor without `eddi-viewer` was refused all 27
read tools. One matrix now lives in `McpRoles` — Docs, Converse
(admin/editor/user + viewer, ownership-guarded), Observe (admin + viewer, for
`read_audit_trail` and conversation-scoped `read_agent_logs`), Author
(admin/editor), Admin, plus the unchanged HITL guard — each tier enumerating its
roles, which gives `admin ⊇ editor ⊇ viewer` for the conversation tools. Observe
is not Converse because the REST audit and log endpoints are admin-only: an audit
entry carries the agent's system prompt and tool-call arguments, which `eddi-user`
cannot read over REST, so putting those two tools in the conversation tier would
have handed them to every user and editor. Deliberately narrower than REST and
documented: the schedule tools and the memory writes stay admin-only, because
they act on any user's data without the per-owner scoping REST applies.

**Breaking for MCP clients**, listed in the new
[Upgrading from 6.5](../upgrading-from-6.5.md#mcp-role-tiers): a viewer-only
account loses `list_agents`, `list_agent_configs`, `get_agent`,
`list_team_backlog` and `list_group_templates`; an editor-only account loses
`setup_agent` and `create_api_agent`; refusals arrive as `isError: true` instead
of a successful result or a JSON-RPC error.

**Docs resources and dead code.** The `eddi://docs/*` resources had no role check
(the docs tools and REST do); they now require the docs tier and refuse with
JSON-RPC `-32005`. Neither resources nor tools name the server's docs directory
any more, and `DocsService.docsDirectory()`, which existed only to name it, is
removed. In `McpGroupTools`, unparseable JSON in an argument (`roleAssignments`)
is described to the caller rather than answered as `INTERNAL`. `McpSetupTools`' test-only delegates (`buildPromptResponseJson`,
`isLocalLlmProvider`, `getService()`) are removed; the test calls
`AgentSetupService` directly.

**Platform Operator:** unchanged — it describes no MCP roles and reads
`mcp-server.md` through the docs endpoint, which carries the new matrix.

**Files:** [`McpRoles.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpRoles.java),
[`McpErrorResultInterceptor.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpErrorResultInterceptor.java),
[`McpErrorResults.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpErrorResults.java), every `Mcp*Tools` class,
[`McpDocResources.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpDocResources.java);
tests [`McpToolContractTest`](../../src/test/java/ai/labs/eddi/engine/mcp/McpToolContractTest.java),
[`McpRoleTierParityTest`](../../src/test/java/ai/labs/eddi/engine/mcp/McpRoleTierParityTest.java) (measures all 84
tools × 5 roles and compares each tier with the REST `@RolesAllowed` it mirrors),
[`McpErrorResultInterceptorTest`](../../src/test/java/ai/labs/eddi/engine/mcp/McpErrorResultInterceptorTest.java);
docs [`mcp-server.md`](../mcp-server.md) (argument contract, role mapping), [`security.md`](../security.md#role-tiers),
[`user-memory.md`](../user-memory.md), [`upgrading-from-6.5.md`](../upgrading-from-6.5.md) (new).

```decision-log
| 2026-10-02 | MCP role tiers mirror REST `@RolesAllowed` per tool; `eddi-viewer` is kept as an MCP-only read-and-converse role; audit trail and conversation logs stay admin + viewer (REST: admin); schedules and memory writes stay admin-only over MCP | Review §2 #17: MCP setup open to editors, deploy closed to them, config reads open to viewers, and no hierarchy for reads | A real role hierarchy in `requireRole` (would also widen every admin-only check implicitly); dropping `eddi-viewer` from MCP entirely (breaks every viewer-based MCP client) |
| 2026-10-02 | MCP tool failures become `isError: true` through an interceptor that rethrows `errorJson` results as `ToolCallException` | Every tool returns a `String`, and the unit tests compare those strings | Changing every tool's return type to `ToolResponse` |
```

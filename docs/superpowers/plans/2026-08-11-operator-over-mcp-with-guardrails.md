# Plan: let EDDI agents (esp. the Platform Operator) use MCP tools with full HITL guardrails

**Status:** proposed, not started.
**Audience:** a coding agent with no prior context on this thread. Everything needed is below or cited by exact file/symbol.
**Repos:** `EDDI` (Java/Quarkus backend) and `EDDI-Manager` (React admin UI). Both are checked out side by side in the same parent directory.
**Prerequisite:** EDDI PR #668 (`feat/agent-docs-and-hitl-strict`) merged, or branch from it. It adds `McpDocTools`, the MCP resource bridge, and `TaskToolApprovalsResolver`. Nothing here depends on those *semantically*, but Phase 2 touches adjacent code and will conflict textually.

---

## 1. Why this exists (read this before touching anything)

EDDI-Manager provisions a **Platform Operator** — an EDDI agent whose tools are generated from EDDI's own OpenAPI spec via `POST /administration/agents/setup-api` with an `endpoints` allow-list. It can read and (with approval on every write) operate the deployment.

EDDI *also* exposes ~76 tools over MCP at `/mcp` (`McpAdminTools`, `McpConversationTools`, `McpSetupTools`, `McpGroupTools`, `McpHitlTools`, `McpGdprTools`, `McpMemoryTools`, `McpDocTools`). Any EDDI agent can consume an MCP server by referencing an `mcpcalls` config from its workflow. **So an agent could technically point at EDDI's own MCP server today.** The Manager deliberately does not wire this, because three safety mechanisms that protect the operator's HTTP tools do **not** currently apply to MCP tools.

The goal of this plan is to close those three gaps so MCP becomes a first-class, safely gateable tool source — then optionally wire the operator to it for the composite verbs REST lacks (above all `apply_agent_changes`).

**Do not skip to Phase 4.** Wiring the operator to MCP before Phases 1–3 land would give it ungated write tools.

### The three gaps, precisely

| # | Gap | Where | Consequence today |
|---|-----|-------|-------------------|
| G1 | The gate cannot tell an MCP read from an MCP write | `ToolApprovalGate` classifies by pattern; HTTP tools carry `method:path` so `http.post:*` gates every write **fail-safe**. MCP tools are opaque names — `mcp:list_agents` and `mcp:delete_agent` look identical | To gate MCP writes you must enumerate names, and any tool added later is **ungated by default** — the exact fail-open the allow-list design refuses |
| G2 | Approval previews for MCP are thin | `ToolApprovalGateSupport.toPreview` builds `ResolvedRequestPreview` (method/uri/headers/body) only for http tools | An approver sees a tool name + redacted args, not a resolved request. **Integrity is fine** (see §2), but reviewability is worse |
| G3 | The Manager's two hard controls are blind to MCP | `EDDI-Manager/src/lib/operator/self-guard.ts` and `gate-guard.ts` both match on `requestPreview.uri` | An MCP `update_resource` aimed at the operator's own LLM config passes both guards |

### What is NOT a gap (verified — do not "fix" these)

- **Request pinning.** For HTTP, `ToolApprovalGateSupport` fingerprints the resolved request and re-checks before execution, because a pre-request resolution step could otherwise change what runs. **MCP has no such step**: a gated MCP call's arguments are frozen into `PendingToolCallBatch` at pause time and the approved call executes those exact arguments. There is nothing to drift. G2 is about preview *quality*, not integrity.
- **Capability.** EDDI's MCP admin tools are conveniences over the same REST stores. MCP adds no reachable capability except composite verbs (§Phase 4).

---

## 2. Key facts an implementer needs (all verified against the code)

**Gate classification** — `src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGate.java`:
```java
public GateResult classify(List<ToolExecutionRequest> batch,
                           Map<String,String> toolSources,     // name -> "http"|"mcp"|"builtin"|...
                           Map<String,String> toolEndpoints,   // name -> "post:/path"  (http ONLY)
                           ToolApprovalsConfig cfg,
                           Set<String> clearedCallIds)
```
Precedence, from its javadoc: **P1** exempt beats requireApproval · **P2** any pattern match suffices · **P3** empty/absent `requireApproval` = gate fully inactive. `addressesOf(name, toolSources, toolEndpoints)` derives the three addressable forms: `source.method:path` (http only), `source:name`, bare `name`.

**How `toolEndpoints` reaches the gate** — this is the template Phase 2 copies:
`ToolContribution` (record, `modules/llm/tools/spi/ToolContribution.java`) carries `toolSources` + `toolEndpoints` → each provider returns one → `AgentOrchestrator` merges them (~line 689) into `ToolSetup` → `ToolLoopRunner` (~line 337) passes `setup.toolEndpoints()` into `classify`. **Adding a parallel `toolReadOnly` map follows exactly this path.**

**Pattern validation** — `HitlConfigValidation` rejects a method qualifier on any source but `http` (`mcp.post:` is refused at save time) with a typo suggestion. Accepted sources: `builtin, http, mcp, a2a, dynamic, memory, recall`.

**Server side is ready.** quarkus-mcp-server **1.13.1** (see `pom.xml` property `quarkus-mcp-server.version`) supports:
```java
@Tool(name = "...", description = "...",
      annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                                      idempotentHint = true, openWorldHint = false))
```
Verified via `javap io.quarkiverse.mcp.server.Tool$Annotations` → `title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint`.

**Client side is the blocker.** langchain4j-mcp **1.18.1-beta28** drops annotations: `McpClient.listTools()` returns `List<ToolSpecification>` and no annotation type exists in the jar (`unzip -l` shows only `ToolSpecificationHelper`). `ToolSpecification` has a generic `metadata()` map, but the langchain4j MCP client does not populate it from `tools/list` annotations. **So a foreign MCP server's hints cannot be read today without an upstream contribution.** Phase 2 therefore uses a first-party map for EDDI's own server and treats foreign servers fail-safe.

**Manager guard shape** — `EDDI-Manager/src/lib/operator/blocked-calls.ts` combines `findSelfTargetedCalls` (self-guard) + `findGateCarryingCalls` (gate-guard) and is called from all three approval surfaces (`pages/operator.tsx`, `pages/approvals.tsx`, `pages/conversation-detail.tsx`). `PendingToolCallView` (`src/lib/api/hitl.ts`) has: `callId, toolName, source, arguments (redacted, may be truncated), argsTruncated, gateReason, requestPinned, requestPreview`. **Note `source` and `arguments` already exist — that is what the MCP branch keys on.**

---

## 3. Phases

Each phase is independently shippable and independently valuable. Do them in order.

### Phase 1 — Annotate EDDI's own MCP tools (backend, no behavior change)

**Goal:** every `@Tool` in `src/main/java/ai/labs/eddi/engine/mcp/` declares whether it mutates.

1. For each `@Tool` method in `McpAdminTools`, `McpConversationTools`, `McpSetupTools`, `McpGroupTools`, `McpHitlTools`, `McpGdprTools`, `McpMemoryTools`, `McpDocTools`, add `annotations = @Tool.Annotations(...)`.
2. `readOnlyHint = true` **only** for tools that cannot change state. Judge by what the tool *does*, not its name. Reads: `list_agents`, `get_agent`, `read_workflow`, `read_resource`, `list_docs`, `read_docs`, `get_deployment_status`, `read_conversation`, `list_conversations`, `read_audit_trail`, `read_agent_logs`, `discover_agents`, `list_schedules`, `read_schedule`, `list_groups`, `read_group`, `get_approval_status`, `list_pending_approvals`, … Writes (`readOnlyHint = false`): everything in `create_*`, `update_*`, `delete_*`, `deploy_*`, `undeploy_*`, `setup_*`, `apply_agent_changes`, `resume_conversation`, `approve_group_phase`, `cancel_*`, `fire_schedule_now`, `chat_*`/`talk_to_agent` (they create conversation state and can trigger tool calls).
   - **`destructiveHint = true`** for anything deleting or undeploying.
   - When in doubt, `readOnlyHint = false`. A needlessly gated read costs one approval click; a mis-declared write costs the gate.
3. **Add a test that fails when a new `@Tool` has no annotations.** Reflect over the MCP tool classes, assert every `@Tool`-annotated method declares `annotations`. Without this the set rots. Put it in `src/test/java/ai/labs/eddi/engine/mcp/McpToolAnnotationsCoverageTest.java`.
4. Update `docs/mcp-server.md`: note that tools carry MCP annotations and what `readOnlyHint` means for gating.

**Done when:** every MCP tool is annotated, the coverage test passes, `./mvnw.cmd test` green.

### Phase 2 — Teach the gate about MCP read/write (backend, the core of this plan)

**Goal:** `mcp:*` gating becomes fail-safe, i.e. "exempt known reads, gate everything else" — mirroring what `http.get:*` gives HTTP.

1. **Add `toolReadOnly` to the SPI.** In `ToolContribution`, add a `Map<String,Boolean> toolReadOnly` component (dispatch name → true when the tool is known read-only). Follow the existing defensive-copy pattern in the compact constructor; add it to the convenience constructors as `Map.of()`. Update the record javadoc the same way `toolEndpoints` is documented ("empty for every source that cannot determine it").
2. **Thread it through** exactly like `toolEndpoints`: `AgentOrchestrator` merge (~line 689) → `ToolSetup` → `ToolLoopRunner` (~lines 337, 398) → `ToolApprovalGate.classify(...)` gains a `Map<String,Boolean> toolReadOnly` parameter. **Keep the existing `classify` overloads** delegating with `Map.of()` so no caller or test breaks.
3. **Populate it for EDDI's own server.** In `McpToolProviderManager`, after `tools/list`, resolve read-only per tool:
   - langchain4j does not surface annotations (see §2), so introduce a small first-party source of truth: a `McpReadOnlyToolRegistry` listing EDDI's own read-only tool names, derived from the Phase 1 annotations. **Generate or test-pin it against the annotations so the two cannot drift** — e.g. a test that reflects over the `@Tool` methods and asserts the registry matches exactly.
   - Apply it only when the server is EDDI's own. Detect by probing `GET {baseUrl}/administration/docs` or a dedicated identity tool — **do not** infer from the URL string.
   - For foreign servers, leave the entry absent.
4. **Gate semantics.** In `ToolApprovalGate.classify`, add a fourth addressable form for MCP tools whose read-only status is *known true*: they may match an exemption like `mcp.readonly:*`. **Absent knowledge must never mean exempt** — an unknown tool stays gated whenever `mcp:*` is in `requireApproval`. Extend `HitlConfigValidation` to accept `mcp.readonly:` as a valid qualifier (it currently rejects any non-http qualifier) with the same typo-suggestion path.
5. **Tests.** Extend `ToolApprovalGateTest`: a known-read-only MCP tool is exempted by `mcp.readonly:*`; an unknown MCP tool with the same config is **gated**; a known-write MCP tool is gated; the absence of the map degrades to "everything gated" (fail-safe). Add a `HitlConfigValidationTest` case for the new qualifier.
6. Update `docs/hitl.md` §"Pattern forms" and the precedence table.

**Done when:** a config of `requireApproval: ["mcp:*"]` + `exempt: ["mcp.readonly:*"]` gates every EDDI MCP write and no EDDI MCP read, and an unknown/foreign MCP tool is gated.

**Fallback if step 3 proves too invasive:** ship Phase 2 with the registry only (skip foreign-server detection) and gate all foreign MCP tools unconditionally. Still a strict improvement.

### Phase 3 — Manager guards understand MCP calls (EDDI-Manager)

**Goal:** the two hard controls stop being blind to MCP.

1. **`self-guard.ts`** — `findSelfTargetedCalls` currently returns `[]` for any call without `requestPreview`. Add an MCP branch: when `call.source === "mcp"` and the method is not a known read, parse `call.arguments` (JSON) and refuse if the operator's own `agentId` appears in any string value. Reuse the existing `uriTargetsAgent` case-insensitive/percent-decoded comparison semantics for the id match. **Preserve the module's stated asymmetry**: a false positive costs one refused approval; a false negative costs the gate.
2. **`gate-guard.ts`** — `findGateCarryingCalls` matches `/llmstore/llms` in the URI. Add: when `source === "mcp"` and the tool is a resource-writing tool (`update_resource`, `create_resource`, `apply_agent_changes`), inspect `arguments` for a `toolApprovals` key at any depth (the existing `containsToolApprovalsKey` walker already does this — reuse it) and for `resourceType` naming the llm store. **`argsTruncated: true` must fail closed**, exactly as `bodyTruncated` does today.
3. **Tests** — mirror the existing `gate-guard.test.ts` / self-guard tests with MCP-shaped `PendingToolCallView` fixtures (`source: "mcp"`, `requestPreview: null`, `arguments` JSON).
4. Update the doc comments in both files — they currently justify themselves purely in URI terms.

**Done when:** an MCP call carrying `toolApprovals`, or aimed at the operator's own agent, disables Approve on all three Manager surfaces; `npm test` green.

### Phase 4 — Actually wire the operator to MCP (EDDI-Manager + small backend)

**Only after 1–3.** Two sub-steps, each optional.

1. **Backend: curated MCP tool subsets at provisioning time.** `POST /administration/agents/setup-api` accepts `mcpServerUrls` (comma-separated) and `AgentSetupService.createMcpCallsResources` creates one `mcpcalls` config per URL **with no whitelist** — so "give the operator only `apply_agent_changes` and `list_agent_resources`" is not expressible in one call. Add an optional per-URL tool whitelist to the setup request (e.g. `mcpToolsWhitelist`, applied to every created config, or a richer `url|tool1;tool2` form). Validate it the same way the existing fields are.
2. **Manager: opt-in MCP for the operator.** In `EDDI-Manager/src/lib/operator/tool-scopes.ts`, add a curated MCP tool allow-list beside `READ_ENDPOINTS`/`WRITE_ENDPOINTS`, pass `mcpServerUrls` + the whitelist through `provisionOperator` (`src/lib/api/operator.ts`), and extend `buildToolApprovals()` to add `mcp:*` to `requireApproval` and `mcp.readonly:*` to `exempt`. Update `system-prompt.ts` so the operator is told what the MCP tools do — in particular that `apply_agent_changes` replaces the four-step landing procedure the prompt currently teaches.
3. **Update the write canary** (`src/lib/operator/write-canary.ts`) if an MCP write is in scope — it currently provokes `PATCH /descriptorstore/descriptors/{id}` and asserts the pause. The equivalent MCP probe must be equally cheap and reversible.

**The prize:** `apply_agent_changes` batch-cascades URI changes through workflow → agent and optionally redeploys, in one gated, approvable call — replacing the config → workflow repoint → agent repoint → deploy chain the operator currently walks in four separate approvals, and which `system-prompt.ts` spends a paragraph explaining.

---

## 4. Guardrails for whoever implements this

- **Fail-safe or don't ship.** Every classification decision must default to "gated" on missing information. If you cannot determine that an MCP tool is read-only, it is a write.
- **No new bypass surface.** `HitlConfigValidation` refuses `mcp.post:` today *on purpose* (no MCP tool records a method). If you add `mcp.readonly:`, add it to the accepted set explicitly — do not relax the qualifier check generally.
- **Do not weaken `TaskToolApprovalsResolver`.** Strict mode (PR #668) is what stops a task-level `toolApprovals` from removing the gate. If MCP gating lands, the Manager's `gate-guard.ts` can eventually relax — that is a separate, deliberate decision, not a side effect.
- **Read `docs/project-philosophy.md`** (EDDI's supreme directive) and `AGENTS.md` §2 (workflow protocol: branch from `origin/main`, never commit to `main`, never force-push, **ask before pushing**).
- **Sandbox caveat:** tests binding loopback sockets (`A2AToolProviderManager*`, `Embedding*`) fail in sandboxed agent environments with `Unable to establish loopback connection`. That is environmental, not a regression — CI is the source of truth. Run `./mvnw.cmd test` (unit) locally; `./mvnw.cmd verify` needs Docker.
- **Windows:** use `.\mvnw.cmd`, not `./mvnw`.

## 5. Suggested branch/PR split

| Branch | Phase | Rough size |
|---|---|---|
| `feat/mcp-tool-annotations` | 1 | small, mechanical + one coverage test |
| `feat/mcp-readonly-gating` | 2 | medium; SPI + gate + validation + tests |
| `feat/manager-mcp-guards` (Manager repo) | 3 | small-medium; two guards + tests |
| `feat/operator-mcp-tools` (both repos) | 4 | medium; provisioning + scope + prompt + canary |

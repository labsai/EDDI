# MCP HITL Surface — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose EDDI's Human-in-the-Loop (HITL) approval operations over the MCP server so an external MCP client can list, read, resume/approve, and cancel human-approval gates for both regular (1:1) and group conversations — at full parity with the existing REST endpoints, without ever granting an LLM the authority to approve its own gate.

**Architecture:** Nine new thin `@Tool` methods in a new `McpHitlTools` class delegate **in-process** to `IConversationService` / `IGroupConversationService` (never through the REST layer). The security-critical HITL ownership check and the owner-scoped "pending-approvals" listing decision are extracted from `RestAgentEngine` / `RestGroupConversation` into a new shared `@ApplicationScoped HitlAccessGuard` used by both REST and MCP, so who-may-decide lives in exactly one place. Authority mirrors REST exactly (per-conversation owner/admin/approver via `OwnershipValidator`); a global kill-switch property can disable MCP mutations without touching the read-only tools.

**Tech Stack:** Java 25, Quarkus 3.34.x CDI, quarkiverse `quarkus-mcp-server-http` 1.13.0 (`@Tool`/`@ToolArg`), Micrometer, JUnit 5 + Mockito, Maven wrapper (`./mvnw`).

## Global Constraints

- **Human authority is preserved.** MCP is a transport, not a new authority. No tool lets an agent/LLM approve programmatically; every decision is attributed to the authenticated caller. Out of scope: SSE streaming over MCP, autonomous agent approver, any new realm role.
- **Delegate to services, not REST.** `McpHitlTools` calls `IConversationService` / `IGroupConversationService` directly. It must NOT call `RestAgentEngine` / `RestGroupConversation` (those return JAX-RS `Response`).
- **Mirror REST auth exactly.** Per-conversation ownership check is owner OR `eddi-admin` OR `eddi-approver` (via `OwnershipValidator`). No blanket realm-role gate on the tools. When `authorization.enabled=false` (dev default), `OwnershipValidator` no-ops — identical to REST today.
- **`decidedBy` / `cancelledBy` are set server-side**, never from a tool argument. MCP-originated decisions are attributed with an `mcp:` channel prefix (e.g. `mcp:alice`, or `mcp:anonymous` when unauthenticated), consistent with the existing `system:timeout` convention used by the timeout handler.
- **Tools never throw across the MCP boundary.** Every path returns a JSON string. Errors use the structured `errorJson(message, errorCode, details)` helper (Task 1).
- **New unit tests are plain JUnit 5 + Mockito — NOT `@QuarkusTest`.** They construct the classes with mocked dependencies and must run locally via `./mvnw test`. (Rationale: this environment cannot boot Quarkus / open loopback sockets; `@QuarkusTest` and HTTP-server tests are CI-only.)
- **Commits:** conventional-commit messages, scope `mcp` or `hitl`. Stage files individually (`git add <path>` — never `git add .` / `-A`). Do NOT add a `Co-Authored-By` trailer. Do NOT commit to `main` (work on the current `feat/hitl-framework` branch). Run `./mvnw compile` before every commit; never commit broken code.
- **Changelog:** update `docs/changelog.md` on this same branch, in the commit that contains the code it documents (Task 8).

---

## File Structure

**Create:**
- `src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java` — shared HITL authorization + owner-scoped listing coordinator (delegates role checks to `OwnershipValidator`, queries to the services).
- `src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java` — the 9 MCP `@Tool` methods.
- `src/test/java/ai/labs/eddi/engine/hitl/HitlAccessGuardTest.java` — Mockito unit test for the guard.
- `src/test/java/ai/labs/eddi/engine/mcp/McpHitlToolsTest.java` — Mockito unit test for the tools.

**Modify:**
- `src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java` — add the structured `errorJson(message, code, details)` overload.
- `src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java` — the `hitlOperation=true` branch of `validateConversationOwnership` and the body of `listPendingApprovals` delegate to the guard. (Non-HITL `hitlOperation=false` path is left untouched.)
- `src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java` — the `hitlOperation=true` group ownership branch and the group pending-approvals scoping delegate to the guard.
- `src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java` (or wherever `pausedForApprovalJson` is defined — confirm by grep) — add a `suggestNextTool` hint to the `PAUSED_FOR_APPROVAL` payload.
- `src/main/resources/application.properties` — register `eddi.mcp.hitl.mutations.enabled=true`.
- `docs/hitl.md`, `docs/mcp-server.md`, `docs/changelog.md` — document the new surface.

**Do NOT create** (this is an MCP tool class, not an `ILifecycleTask`): no `*Configuration` POJO, no `IResourceStore`, no MongoDB store, no JAX-RS interface, no `ExtensionDescriptor`. MCP tools are auto-discovered CDI beans; they need only `@ApplicationScoped` + `@Tool` methods + Mockito unit tests.

---

## Verified Reference Facts (do not re-derive)

All confirmed against the codebase — use these exact signatures.

**Branch-state delta (verified 2026-07-03, branch `feat/hitl-framework` @ `cf93e8036`).** Since this plan was drafted, the branch grew a **tool-level HITL** layer (`PendingToolCallBatch`, `ToolApprovalsConfig`, a pause-type discriminator). It does **not** change this plan's design because it reuses the same resume surface:
- There are now two HITL pause types on the regular surface, both using `ConversationState.AWAITING_HUMAN` and both resolved by the **same** `POST /agents/{id}/resume` + single `HitlDecision`: `hitlPauseType ∈ { null→treated as RULE, "RULE", "TOOL_CALL" }`. There is **no** separate tool-approval REST endpoint (`IRestAgentEngine` has only `resume` / `approval-status` / `pending-approvals` / `cancel`). ⇒ `resume_conversation`, `cancel_conversation`, and `list_pending_approvals` are **pause-type-agnostic** — they cover tool-call pauses with zero extra work.
- `ConversationMemorySnapshot` gained `String getHitlPauseType()` (null|"RULE"|"TOOL_CALL") and `PendingToolCallBatch getHitlPendingToolCalls()`. The pending tool-call batch is large (transcript + calls) and is exposed only via `get_approval_status?detail=full` (which serializes the whole snapshot). The summary view adds a lightweight `pauseType` field (Task 5).
- `PendingApprovalSummary` does **not** yet carry `pauseType`/`toolNames` (that surfacing task in `planning/hitl-tool-approval-plan.md` is not done). `list_pending_approvals` serializes the object as-is, so it stays correct and auto-surfaces those fields if/when they land.
- `pausedForApprovalJson` in `McpConversationTools` has **two** overloads (≈lines 602 and 629) and carries no `pauseType` yet; Task 7 adds `suggestNextTool` to both — a clean additive change.
- **Coordination:** an independent tool-approval plan is in progress on this branch. This surface is additive (new `McpHitlTools`, new `HitlAccessGuard`) and its only shared-file touches (`RestAgentEngine`, `RestGroupConversation`, `McpToolUtils`, `McpConversationTools`, `application.properties`, docs) do not overlap the parts that plan is actively editing.

**Services (delegation targets):**
- `void IConversationService.resumeConversation(String conversationId, HitlDecision decision, ConversationResponseHandler handler) throws ResourceStoreException, ResourceNotFoundException` — pass `null` for the handler.
- `CancelOutcome IConversationService.cancelConversation(String conversationId, ControlSignal mode, String cancelledBy) throws ResourceStoreException` — enum `CancelOutcome { CANCELLED, NOTHING_TO_CANCEL, NOT_FOUND }`.
- `ConversationMemorySnapshot IConversationService.getConversationMemorySnapshot(String conversationId) throws ResourceStoreException, ResourceNotFoundException`.
- `ConversationState IConversationService.getConversationState(String conversationId)`.
- `List<PendingApprovalSummary> IConversationService.listPendingApprovals(int limit) throws ResourceStoreException`.
- `List<PendingApprovalSummary> IConversationService.listPendingApprovals(String ownerUserId, int limit) throws ResourceStoreException`.
- `GroupConversation IGroupConversationService.resumeDiscussion(String groupConversationId, GroupApprovalRequest request, GroupDiscussionEventListener listener) throws GroupDiscussionException, ResourceStoreException, ResourceNotFoundException, ResourceModifiedException` — pass `null` for the listener (confirm null is accepted by reading the non-stream REST caller `RestGroupConversation.java:~209`; if not, pass a no-op listener).
- `boolean IGroupConversationService.cancelDiscussion(String conversationId, ControlSignal mode) throws ResourceStoreException, ResourceNotFoundException`.
- `List<PendingApprovalSummary> IGroupConversationService.listGroupPendingApprovals(String groupId, int limit) throws ResourceStoreException` — pass `groupId=null` for the cross-group inbox.

**Models:**
- `HitlDecision` (`ai.labs.eddi.engine.lifecycle.model.HitlDecision`) — **CONFIRMED unchanged**: default constructor + `setVerdict(HitlVerdict)`, `setNote(String)`, `setDecidedBy(String)` and `getVerdict()`, `getNote()`, `getDecidedBy()`; nested `enum HitlVerdict { APPROVED, REJECTED }` with case-insensitive `@JsonCreator HitlVerdict.fromString(String)` (returns null for null input, throws `IllegalArgumentException` for an unknown non-null value — so guard with try/catch OR check membership; the tool treats "not APPROVED/REJECTED" as `BAD_REQUEST`); `public static final int MAX_NOTE_LENGTH = 4096`. It has **no** tool-approval fields — a single verdict resolves the entire pending tool-call batch, exactly as REST does.
- `GroupApprovalRequest` (`ai.labs.eddi.engine.internal.GroupApprovalRequest`): fields `HitlDecision decision` and `Map<String,String> taskApprovals`. **Confirm constructor/setters by reading the file.**
- `ControlSignal` (`ai.labs.eddi.engine.lifecycle.model.ControlSignal`): `{ CONTINUE, CANCEL_GRACEFUL, CANCEL_IMMEDIATE }` — use `CANCEL_GRACEFUL` for cancels.
- `PendingApprovalSummary` (`ai.labs.eddi.engine.model.PendingApprovalSummary`) — returned as-is; serialize with `IJsonSerialization`.

**Infra:**
- `String IJsonSerialization.serialize(Object) throws IOException` — confirm the deserialize method name/signature in `IJsonSerialization.java` before using it in Task 6 (`taskApprovals` parsing).
- `authorization.enabled` — the config property the existing MCP tool classes inject: `@ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean`. `OwnershipValidator` short-circuits (allows) when it is `false`.
- `McpToolUtils.errorJson(String)` returns `{"error":"..."}` using manual escaping (`escapeJsonString`) — it never serializes, so it cannot throw. Reuse that manual-construction style for the new overload.
- Existing MCP tool classes (`McpMemoryTools`, `McpConversationTools`, `McpGroupTools`) are `@ApplicationScoped`, use **constructor injection**, inject `SecurityIdentity` (resolved per-request via the CDI normal-scoped proxy), and return `String`. Follow that exact pattern.

**REST source to extract from (read these before Tasks 2–3):**
- `RestAgentEngine.java`: `validateConversationOwnership(String, boolean)` at `428–466`; `listPendingApprovals(Integer)` at `397–419`; `getApprovalStatus(String, String)` at `354–394`; `resumeConversation` at `307–351`; `cancelConversation` at `277–301`.
- `RestGroupConversation.java`: `validateGroupConversationOwnership(String, String, boolean)` at `~363–412`; `getGroupApprovalStatus(String, String, String)` at `302–347`; group listing scoping at `512–548`.

---

### Task 1: Structured error helper in `McpToolUtils`

Gives every HITL tool a machine-readable error shape so a programmatic MCP client can distinguish not-found vs. wrong-state vs. forbidden vs. disabled. Additive — the existing single-arg `errorJson` is unchanged.

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java`
- Test: `src/test/java/ai/labs/eddi/engine/mcp/McpToolUtilsErrorJsonTest.java` (create)

**Interfaces:**
- Produces: `static String McpToolUtils.errorJson(String message, String errorCode, Map<String,String> details)` — returns `{"error":"...","errorCode":"...","details":{...}}`, always valid JSON, never throws.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/labs/eddi/engine/mcp/McpToolUtilsErrorJsonTest.java`:

```java
package ai.labs.eddi.engine.mcp;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class McpToolUtilsErrorJsonTest {

    @Test
    void errorJson_withCodeAndDetails_producesStructuredJson() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("currentState", "IN_PROGRESS");
        String json = McpToolUtils.errorJson("not resumable", "WRONG_STATE", details);
        assertTrue(json.contains("\"error\":\"not resumable\""));
        assertTrue(json.contains("\"errorCode\":\"WRONG_STATE\""));
        assertTrue(json.contains("\"currentState\":\"IN_PROGRESS\""));
    }

    @Test
    void errorJson_withNullDetails_omitsDetailsObject() {
        String json = McpToolUtils.errorJson("bad", "BAD_REQUEST", null);
        assertTrue(json.contains("\"errorCode\":\"BAD_REQUEST\""));
        assertFalse(json.contains("\"details\""));
    }

    @Test
    void errorJson_escapesQuotesInMessage() {
        String json = McpToolUtils.errorJson("he said \"hi\"", "X", null);
        assertTrue(json.contains("\\\"hi\\\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=McpToolUtilsErrorJsonTest`
Expected: FAIL (compilation error — `errorJson(String,String,Map)` not defined).

- [ ] **Step 3: Add the overload**

In `McpToolUtils.java`, add (keep the existing single-arg `errorJson` untouched; add `import java.util.Map;`):

```java
/**
 * Structured error JSON for tools whose callers need to branch on the failure kind.
 * Manual construction (like {@link #errorJson(String)}) so it can never throw during
 * an error path. {@code details} may be null/empty.
 */
static String errorJson(String message, String errorCode, java.util.Map<String, String> details) {
    var sb = new StringBuilder();
    sb.append("{\"error\":\"").append(escapeJsonString(message)).append("\"");
    if (errorCode != null && !errorCode.isBlank()) {
        sb.append(",\"errorCode\":\"").append(escapeJsonString(errorCode)).append("\"");
    }
    if (details != null && !details.isEmpty()) {
        sb.append(",\"details\":{");
        boolean first = true;
        for (var entry : details.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJsonString(entry.getKey())).append("\":\"")
              .append(escapeJsonString(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
    }
    sb.append("}");
    return sb.toString();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=McpToolUtilsErrorJsonTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java src/test/java/ai/labs/eddi/engine/mcp/McpToolUtilsErrorJsonTest.java
git commit -m "feat(mcp): add structured errorJson(message,code,details) helper"
```

---

### Task 2: `HitlAccessGuard` — regular (1:1) methods + `RestAgentEngine` refactor

Extract the HITL ownership composition and the owner-scoped pending-approvals decision so REST and MCP share one implementation. Only the `hitlOperation=true` path moves; the non-HITL path in `RestAgentEngine` is untouched (protects say/undo/redo/read at the 10 non-HITL call sites).

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java`
- Modify: `src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java` (methods at `397–419`, `438–466`)
- Test: `src/test/java/ai/labs/eddi/engine/hitl/HitlAccessGuardTest.java`

**Interfaces:**
- Consumes: `OwnershipValidator` (`requireOwnerAdminOrApprover(identity, ownerId, "conversation")`, `isAdmin(identity)`, `isApprover(identity)`, `isOwner(identity, ownerId)`), the conversation descriptor store (same field `RestAgentEngine` injects — read its type and `readDescriptor(conversationId, 0)` usage), `IConversationService`, `SecurityIdentity`.
- Produces:
  - `String requireConversationHitlAccess(String conversationId)` — returns owner userId (or `null` if the descriptor is absent and caller is admin/approver); throws `io.quarkus.security.ForbiddenException` on denial.
  - `List<PendingApprovalSummary> listScopedPendingApprovals(int limit) throws ResourceStoreException`.

- [ ] **Step 1: Read the source being extracted**

Read `RestAgentEngine.java:397–419` (`listPendingApprovals`) and `:438–466` (`validateConversationOwnership`) and note the exact injected field names/types for `ownershipValidator`, the descriptor store, `conversationService`, and `identity`. Reproduce those exact types in the guard.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/ai/labs/eddi/engine/hitl/HitlAccessGuardTest.java`. Use the descriptor-store type you found in Step 1 (shown here as `IConversationDescriptorStore` — replace with the actual type). Mock all deps:

```java
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HitlAccessGuardTest {

    OwnershipValidator ownershipValidator;
    IConversationService conversationService;
    SecurityIdentity identity;
    // TODO replace with the actual descriptor store type from RestAgentEngine:
    // IConversationDescriptorStore descriptorStore;
    HitlAccessGuard guard;

    @BeforeEach
    void setup() {
        ownershipValidator = mock(OwnershipValidator.class);
        conversationService = mock(IConversationService.class);
        identity = mock(SecurityIdentity.class);
        // descriptorStore = mock(IConversationDescriptorStore.class);
        // guard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService);
    }

    @Test
    void listScopedPendingApprovals_adminSeesAll() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(true);
        when(conversationService.listPendingApprovals(50)).thenReturn(List.of());
        List<PendingApprovalSummary> result = guard.listScopedPendingApprovals(50);
        assertNotNull(result);
        verify(conversationService).listPendingApprovals(50);          // unscoped overload
        verify(conversationService, never()).listPendingApprovals(anyString(), anyInt());
    }

    @Test
    void listScopedPendingApprovals_ownerSeesOnlyOwn() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        var principal = mock(java.security.Principal.class);
        when(principal.getName()).thenReturn("alice");
        when(identity.getPrincipal()).thenReturn(principal);
        when(conversationService.listPendingApprovals("alice", 50)).thenReturn(List.of());
        guard.listScopedPendingApprovals(50);
        verify(conversationService).listPendingApprovals("alice", 50);  // scoped overload
    }

    @Test
    void listScopedPendingApprovals_anonymousSeesNothing() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);
        List<PendingApprovalSummary> result = guard.listScopedPendingApprovals(50);
        assertTrue(result.isEmpty());
        verify(conversationService, never()).listPendingApprovals(anyString(), anyInt());
    }

    @Test
    void requireConversationHitlAccess_deniedThrowsForbidden() {
        // Arrange ownershipValidator.requireOwnerAdminOrApprover(...) to throw ForbiddenException
        // for a descriptor whose owner != caller. Assert:
        assertThrows(ForbiddenException.class, () -> guard.requireConversationHitlAccess("conv-1"));
    }
}
```

Fill in the descriptor-store mock and the `requireConversationHitlAccess` arrange-block using the exact descriptor read (`descriptorStore.readDescriptor("conv-1", 0)` returning a descriptor with a `getUserId()`), mirroring `RestAgentEngine.java:440–442`.

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=HitlAccessGuardTest`
Expected: FAIL (compilation error — `HitlAccessGuard` does not exist).

- [ ] **Step 4: Create the guard**

Create `src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java`. Port `RestAgentEngine.listPendingApprovals` (`397–419`) into `listScopedPendingApprovals`, and the `hitlOperation=true` body of `validateConversationOwnership` (`438–466`) into `requireConversationHitlAccess`. Replace `IConversationDescriptorStore`/field names with the actual types from Step 1:

```java
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.datastore.serialization.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Single source of truth for HITL authorization and owner-scoped pending-approval
 * listing, shared by the REST layer (RestAgentEngine / RestGroupConversation) and the
 * MCP layer (McpHitlTools). Delegates role decisions to {@link OwnershipValidator} and
 * queries to the conversation services — it composes, it does not duplicate.
 */
@ApplicationScoped
public class HitlAccessGuard {

    private static final Logger LOGGER = Logger.getLogger(HitlAccessGuard.class);

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    // TODO: replace with the exact descriptor store type used by RestAgentEngine
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationService conversationService;

    @Inject
    public HitlAccessGuard(SecurityIdentity identity,
                           OwnershipValidator ownershipValidator,
                           IConversationDescriptorStore conversationDescriptorStore,
                           IConversationService conversationService) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationService = conversationService;
    }

    /**
     * Strict HITL ownership check (owner OR admin OR approver). Fail-closed when the
     * descriptor is absent and the caller is neither admin nor approver.
     * @return the conversation owner's userId, or null if the descriptor was not found.
     */
    public String requireConversationHitlAccess(String conversationId) {
        try {
            var descriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
            ownershipValidator.requireOwnerAdminOrApprover(identity, descriptor.getUserId(), "conversation");
            return descriptor.getUserId();
        } catch (ForbiddenException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            if (!ownershipValidator.isAdmin(identity) && !ownershipValidator.isApprover(identity)) {
                LOGGER.warnf("HITL op on %s denied: no descriptor to verify ownership", conversationId);
                throw new ForbiddenException("Access denied: conversation ownership cannot be verified");
            }
            return null;
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load descriptor for HITL ownership check: %s", conversationId);
            throw new ForbiddenException("Access denied: unable to verify conversation ownership");
        }
    }

    /**
     * Owner-scoped pending-approval inbox: admins and approvers see all; other callers
     * see only their own; anonymous callers see nothing (fail-closed).
     */
    public List<PendingApprovalSummary> listScopedPendingApprovals(int limit) throws ResourceStoreException {
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isApprover(identity)) {
            return conversationService.listPendingApprovals(limit);
        }
        String callerId = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (callerId == null || callerId.isBlank()) {
            return List.of();
        }
        return conversationService.listPendingApprovals(callerId, limit);
    }
}
```

Match the actual descriptor store import/type and the `sanitize(...)`/logging idiom used in `RestAgentEngine` if you prefer.

- [ ] **Step 5: Refactor `RestAgentEngine` to delegate**

In `RestAgentEngine.java`: inject `HitlAccessGuard hitlAccessGuard` (add to the constructor). Change the top of `validateConversationOwnership` (`438`) so the HITL branch delegates:

```java
private String validateConversationOwnership(String conversationId, boolean hitlOperation) {
    if (hitlOperation) {
        return hitlAccessGuard.requireConversationHitlAccess(conversationId);
    }
    // ... existing non-HITL (requireOwnerOrAdmin) logic UNCHANGED ...
}
```

Change `listPendingApprovals` (`397`) body to:

```java
@Override
public List<PendingApprovalSummary> listPendingApprovals(Integer limit) {
    try {
        int effectiveLimit = limit != null ? limit : 200;
        return hitlAccessGuard.listScopedPendingApprovals(effectiveLimit);
    } catch (ResourceStoreException e) {
        throw new InternalServerErrorException(e.getMessage(), e);
    }
}
```

- [ ] **Step 6: Run guard test + REST regression**

Run: `./mvnw test -Dtest=HitlAccessGuardTest`
Expected: PASS.

Run the existing regular-surface HITL REST test unchanged (find its name: `grep -rl "resume" src/test/java/ai/labs/eddi/engine/internal | grep -i hitl` or look for `RestAgentEngineHitlTest`):
Run: `./mvnw test -Dtest=RestAgentEngineHitlTest`
Expected: PASS (behavior unchanged after extraction). If this test is a `@QuarkusTest` that cannot boot locally, note it and rely on CI (Task 9) — do not weaken it.

- [ ] **Step 7: Compile & commit**

Run: `./mvnw compile`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java src/test/java/ai/labs/eddi/engine/hitl/HitlAccessGuardTest.java src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java
git commit -m "refactor(hitl): extract regular HITL ownership + scoped listing into HitlAccessGuard"
```

---

### Task 3: `HitlAccessGuard` — group methods + `RestGroupConversation` refactor

Symmetric to Task 2 for the group surface. This closes the owner-scope leak risk on group listings by routing them through the same guard.

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java`
- Modify: `src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java` (methods at `~363–412`, `512–548`)
- Test: `src/test/java/ai/labs/eddi/engine/hitl/HitlAccessGuardTest.java` (extend)

**Interfaces:**
- Consumes: `IGroupConversationService.listGroupPendingApprovals(String groupId, int limit)`; the group descriptor read used by `RestGroupConversation.validateGroupConversationOwnership` (read the file for the exact store + `getUserId()` path); `OwnershipValidator`; `SecurityIdentity`.
- Produces:
  - `void requireGroupConversationHitlAccess(String groupId, String groupConversationId)` — throws `ForbiddenException` / `NotFoundException` as the REST method does.
  - `List<PendingApprovalSummary> listScopedGroupPendingApprovals(String groupId, int limit) throws ResourceStoreException` — `groupId=null` → cross-group inbox.

- [ ] **Step 1: Read the group source**

Read `RestGroupConversation.java:~363–412` (`validateGroupConversationOwnership`) and `512–548` (group listing scoping). Note the group descriptor store field/type and how it derives the owner userId, and confirm the scoping mirrors the regular one (admin/approver → all; else own; anonymous → none).

- [ ] **Step 2: Write the failing tests (extend `HitlAccessGuardTest`)**

Add to `HitlAccessGuardTest`:

```java
@Test
void listScopedGroupPendingApprovals_adminSeesAll() throws Exception {
    when(ownershipValidator.isAdmin(identity)).thenReturn(true);
    when(groupConversationService.listGroupPendingApprovals(null, 25)).thenReturn(java.util.List.of());
    guard.listScopedGroupPendingApprovals(null, 25);
    verify(groupConversationService).listGroupPendingApprovals(null, 25);
}

@Test
void listScopedGroupPendingApprovals_ownerFilteredToOwn() throws Exception {
    when(ownershipValidator.isAdmin(identity)).thenReturn(false);
    when(ownershipValidator.isApprover(identity)).thenReturn(false);
    var principal = mock(java.security.Principal.class);
    when(principal.getName()).thenReturn("bob");
    when(identity.getPrincipal()).thenReturn(principal);
    // Group scoping filters the returned list to entries owned by "bob".
    // Arrange listGroupPendingApprovals(...) to return a mix and assert only bob's remain,
    // mirroring RestGroupConversation.java:512-548.
}
```

Add `groupConversationService = mock(IGroupConversationService.class)` to `setup()` and pass it to the (now widened) guard constructor.

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw test -Dtest=HitlAccessGuardTest`
Expected: FAIL (compilation — group methods/constructor arg not present).

- [ ] **Step 4: Add the group methods to the guard**

Widen the `HitlAccessGuard` constructor to also inject `IGroupConversationService` and the group descriptor store. Add `requireGroupConversationHitlAccess` (port the `hitlOperation=true` branch of `validateGroupConversationOwnership`) and `listScopedGroupPendingApprovals` (port `RestGroupConversation.java:512–548`, calling `groupConversationService.listGroupPendingApprovals(groupId, limit)` then applying the same admin/approver-vs-own filter). Keep the regular methods from Task 2 unchanged.

- [ ] **Step 5: Refactor `RestGroupConversation` to delegate**

Inject `HitlAccessGuard`. Route the `hitlOperation=true` branch of `validateGroupConversationOwnership` to `hitlAccessGuard.requireGroupConversationHitlAccess(groupId, gcId)`, and route the group listing methods (`512–524`, `532–548`) through `hitlAccessGuard.listScopedGroupPendingApprovals(...)`. Leave any non-HITL group ownership path untouched.

- [ ] **Step 6: Run guard test + group regression**

Run: `./mvnw test -Dtest=HitlAccessGuardTest`
Expected: PASS.

Run the existing group HITL REST test unchanged (find it: `grep -rli "group" src/test/java/ai/labs/eddi/engine/internal | grep -i approv` — likely `RestGroupConversationHitlTest`):
Run: `./mvnw test -Dtest=RestGroupConversationHitlTest`
Expected: PASS (or CI-only if `@QuarkusTest`; note and defer to Task 9).

- [ ] **Step 7: Compile & commit**

Run: `./mvnw compile`

```bash
git add src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java src/test/java/ai/labs/eddi/engine/hitl/HitlAccessGuardTest.java src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java
git commit -m "refactor(hitl): extract group HITL ownership + scoped listing into HitlAccessGuard"
```

---

### Task 4: Register the MCP mutations kill-switch property

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the property**

Append (near other `eddi.*` / MCP properties):

```properties
# HITL over MCP: when false, the MCP resume/approve/cancel tools refuse mutations
# (read-only pending/status tools are unaffected). Ownership checks still apply when true.
eddi.mcp.hitl.mutations.enabled=true
```

- [ ] **Step 2: Compile**

Run: `./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "chore(mcp): register eddi.mcp.hitl.mutations.enabled kill-switch (default true)"
```

---

### Task 5: `McpHitlTools` — regular (1:1) tools

Four tools: `list_pending_approvals`, `get_approval_status`, `resume_conversation`, `cancel_conversation`.

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java`
- Test: `src/test/java/ai/labs/eddi/engine/mcp/McpHitlToolsTest.java`

**Interfaces:**
- Consumes: `HitlAccessGuard` (Tasks 2–3), `IConversationService`, `OwnershipValidator`, `IJsonSerialization`, `SecurityIdentity`, `MeterRegistry`, config `authorization.enabled` + `eddi.mcp.hitl.mutations.enabled`.
- Produces (tool method signatures later tasks/tests rely on):
  - `String listPendingApprovals(String limit)`
  - `String getApprovalStatus(String conversationId, String detail)`
  - `String resumeConversation(String conversationId, String verdict, String note)`
  - `String cancelConversation(String conversationId)`
  - helper `String principalWithMcpPrefix()` → `"mcp:" + name` or `"mcp:anonymous"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/labs/eddi/engine/mcp/McpHitlToolsTest.java`:

```java
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpHitlToolsTest {

    IConversationService conversationService;
    IGroupConversationService groupConversationService;
    HitlAccessGuard guard;
    OwnershipValidator ownershipValidator;
    IJsonSerialization json;
    SecurityIdentity identity;
    McpHitlTools tools;

    McpHitlTools build(boolean authEnabled, boolean mutationsEnabled) {
        return new McpHitlTools(conversationService, groupConversationService, guard,
                ownershipValidator, json, identity, new SimpleMeterRegistry(),
                authEnabled, mutationsEnabled);
    }

    @BeforeEach
    void setup() {
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        guard = mock(HitlAccessGuard.class);
        ownershipValidator = mock(OwnershipValidator.class);
        json = mock(IJsonSerialization.class);
        identity = mock(SecurityIdentity.class);
        var p = mock(Principal.class);
        when(p.getName()).thenReturn("alice");
        when(identity.getPrincipal()).thenReturn(p);
        tools = build(true, true);
    }

    @Test
    void resume_disabledByKillSwitch_returnsDisabledError() {
        tools = build(true, false);
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"DISABLED\""));
        verifyNoInteractions(conversationService);
    }

    @Test
    void resume_invalidVerdict_returnsBadRequest() {
        String out = tools.resumeConversation("c1", "maybe", null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""));
    }

    @Test
    void resume_noteTooLong_returnsBadRequest() {
        String longNote = "x".repeat(HitlDecision.MAX_NOTE_LENGTH + 1);
        String out = tools.resumeConversation("c1", "APPROVED", longNote);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""));
    }

    @Test
    void resume_happyPath_setsMcpDecidedByAndDelegates() throws Exception {
        String out = tools.resumeConversation("c1", "approved", "ok");   // lower-case verdict
        assertTrue(out.contains("RESUMED"));
        var captor = org.mockito.ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("c1"), captor.capture(), isNull());
        assertEquals("mcp:alice", captor.getValue().getDecidedBy());     // add getDecidedBy() if absent
        assertEquals(HitlDecision.HitlVerdict.APPROVED, captor.getValue().getVerdict());
    }

    @Test
    void resume_forbidden_returnsForbiddenError() {
        doThrow(new ForbiddenException("no")).when(guard).requireConversationHitlAccess("c1");
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""));
    }

    @Test
    void resume_wrongState_returnsWrongStateWithCurrentState() throws Exception {
        doThrow(new IllegalStateException("bad state"))
            .when(conversationService).resumeConversation(eq("c1"), any(), isNull());
        when(conversationService.getConversationState("c1"))
            .thenReturn(ai.labs.eddi.engine.memory.model.ConversationState.IN_PROGRESS);
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"WRONG_STATE\""));
        assertTrue(out.contains("IN_PROGRESS"));
    }

    @Test
    void resume_differentCaller_attributesCorrectPrincipal() throws Exception {
        var bob = mock(Principal.class);
        when(bob.getName()).thenReturn("bob");
        when(identity.getPrincipal()).thenReturn(bob);            // proves per-call identity resolution
        tools.resumeConversation("c1", "APPROVED", null);
        var captor = org.mockito.ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("c1"), captor.capture(), isNull());
        assertEquals("mcp:bob", captor.getValue().getDecidedBy());
    }

    @Test
    void listPendingApprovals_delegatesToGuard() throws Exception {
        when(guard.listScopedPendingApprovals(anyInt())).thenReturn(java.util.List.of());
        when(json.serialize(any())).thenReturn("[]");
        String out = tools.listPendingApprovals("50");
        assertEquals("[]", out);
        verify(guard).listScopedPendingApprovals(50);
    }
}
```

If `HitlDecision` lacks `getDecidedBy()`, add it (trivial getter) in Task 5 Step 2 so the test can assert attribution.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=McpHitlToolsTest`
Expected: FAIL (compilation — `McpHitlTools` not defined).

- [ ] **Step 3: Implement `McpHitlTools` (regular tools)**

Create `src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java`. Model annotations/imports on `McpConversationTools` (confirm the exact `@Tool`/`@ToolArg`/`@Blocking` import packages there). Use inline `meterRegistry.counter(...).increment()` (no `@PostConstruct` needed):

```java
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class McpHitlTools {

    private static final Logger LOGGER = Logger.getLogger(McpHitlTools.class);

    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final HitlAccessGuard hitlAccessGuard;
    private final OwnershipValidator ownershipValidator;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final MeterRegistry meterRegistry;
    private final boolean authEnabled;
    private final boolean mutationsEnabled;

    @Inject
    public McpHitlTools(IConversationService conversationService,
                        IGroupConversationService groupConversationService,
                        HitlAccessGuard hitlAccessGuard,
                        OwnershipValidator ownershipValidator,
                        IJsonSerialization jsonSerialization,
                        SecurityIdentity identity,
                        MeterRegistry meterRegistry,
                        @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
                        @ConfigProperty(name = "eddi.mcp.hitl.mutations.enabled", defaultValue = "true") boolean mutationsEnabled) {
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.hitlAccessGuard = hitlAccessGuard;
        this.ownershipValidator = ownershipValidator;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.meterRegistry = meterRegistry;
        this.authEnabled = authEnabled;
        this.mutationsEnabled = mutationsEnabled;
    }

    String principalWithMcpPrefix() {
        String name = identity != null && identity.getPrincipal() != null
                ? identity.getPrincipal().getName() : null;
        return "mcp:" + (name != null && !name.isBlank() ? name : "anonymous");
    }

    /** Case-insensitive verdict parse; returns null for null/blank/unrecognized — NEVER throws.
     *  (HitlVerdict.fromString throws IllegalArgumentException on an unknown non-null value.) */
    static HitlVerdict parseVerdictOrNull(String verdict) {
        if (verdict == null || verdict.isBlank()) return null;
        try {
            return HitlVerdict.fromString(verdict);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String disabledIfMutationsOff() {
        if (!mutationsEnabled) {
            return McpToolUtils.errorJson("HITL mutations are disabled over MCP", "DISABLED",
                    Map.of("property", "eddi.mcp.hitl.mutations.enabled"));
        }
        return null;
    }

    @Tool(name = "list_pending_approvals",
          description = "List regular (1:1) conversations awaiting human approval. Admins/approvers see all; "
                  + "other callers see only their own; unauthenticated callers see nothing.")
    @Blocking
    public String listPendingApprovals(
            @ToolArg(description = "Max entries to return (default 200, cap 1000)", required = false) String limit) {
        try {
            int effectiveLimit = McpToolUtils.parseIntOrDefault(limit, 200);
            var result = hitlAccessGuard.listScopedPendingApprovals(effectiveLimit);
            meterRegistry.counter("eddi.mcp.hitl.pending.listed", "surface", "regular").increment();
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.warn("list_pending_approvals failed", e);
            return McpToolUtils.errorJson("Failed to list pending approvals", "INTERNAL", null);
        }
    }

    @Tool(name = "get_approval_status",
          description = "Read the approval status of a paused regular conversation. detail=summary (default) "
                  + "returns pause metadata; detail=full returns the full memory snapshot (owner/admin, or "
                  + "approver only while the conversation is awaiting approval).")
    @Blocking
    public String getApprovalStatus(
            @ToolArg(description = "Conversation ID") String conversationId,
            @ToolArg(description = "summary (default) or full", required = false) String detail) {
        if (conversationId == null || conversationId.isBlank()) {
            return McpToolUtils.errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        try {
            String ownerId = hitlAccessGuard.requireConversationHitlAccess(conversationId);
            ConversationMemorySnapshot snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            boolean paused = snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
            if ("full".equals(detail)) {
                if (!paused && !ownershipValidator.isAdmin(identity) && !ownershipValidator.isOwner(identity, ownerId)) {
                    return McpToolUtils.errorJson(
                            "Full approval status is available to approvers only while awaiting approval — use summary",
                            "FORBIDDEN", null);
                }
                return jsonSerialization.serialize(snapshot);
            }
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("conversationId", conversationId);
            summary.put("state", snapshot.getConversationState().name());
            // pauseType distinguishes a RULE (PAUSE_CONVERSATION) pause from a TOOL_CALL pause
            // (null legacy snapshots => RULE). For TOOL_CALL, the pending tool-call batch is in
            // detail=full (whole snapshot, incl. getHitlPendingToolCalls()).
            summary.put("pauseType", paused && snapshot.getHitlPauseType() != null ? snapshot.getHitlPauseType() : (paused ? "RULE" : ""));
            summary.put("pausedAt", paused && snapshot.getHitlPausedAt() != null ? snapshot.getHitlPausedAt().toString() : "");
            summary.put("pauseReason", paused && snapshot.getHitlPauseReason() != null ? snapshot.getHitlPauseReason() : "");
            summary.put("timeoutPolicy", paused && snapshot.getHitlTimeoutPolicy() != null ? String.valueOf(snapshot.getHitlTimeoutPolicy()) : "");
            summary.put("approvalTimeout", paused && snapshot.getHitlApprovalTimeout() != null ? String.valueOf(snapshot.getHitlApprovalTimeout()) : "");
            return jsonSerialization.serialize(summary);
        } catch (ForbiddenException e) {
            return McpToolUtils.errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceNotFoundException e) {
            return McpToolUtils.errorJson("Conversation not found", "NOT_FOUND", null);
        } catch (Exception e) {
            LOGGER.warn("get_approval_status failed", e);
            return McpToolUtils.errorJson("Failed to read approval status", "INTERNAL", null);
        }
    }

    @Tool(name = "resume_conversation",
          description = "Resume a paused regular conversation with a human decision. verdict=APPROVED or REJECTED "
                  + "(case-insensitive). The decision is attributed to the authenticated caller.")
    @Blocking
    public String resumeConversation(
            @ToolArg(description = "Conversation ID awaiting approval") String conversationId,
            @ToolArg(description = "APPROVED or REJECTED (case-insensitive)") String verdict,
            @ToolArg(description = "Optional reviewer note (max 4096 chars)", required = false) String note) {
        String disabled = disabledIfMutationsOff();
        if (disabled != null) return disabled;
        if (conversationId == null || conversationId.isBlank()) {
            return McpToolUtils.errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        HitlVerdict parsed = parseVerdictOrNull(verdict);
        if (parsed == null) {
            return McpToolUtils.errorJson("Invalid verdict; use APPROVED or REJECTED", "BAD_REQUEST", null);
        }
        if (note != null && note.length() > HitlDecision.MAX_NOTE_LENGTH) {
            return McpToolUtils.errorJson("Note exceeds max length " + HitlDecision.MAX_NOTE_LENGTH, "BAD_REQUEST", null);
        }
        try {
            hitlAccessGuard.requireConversationHitlAccess(conversationId);
            HitlDecision decision = new HitlDecision();   // adapt to actual constructor/setters
            decision.setVerdict(parsed);
            decision.setNote(note);
            decision.setDecidedBy(principalWithMcpPrefix());
            conversationService.resumeConversation(conversationId, decision, null);
            meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "regular", "verdict", parsed.name()).increment();
            return "{\"status\":\"RESUMED\",\"conversationId\":\"" + McpToolUtils.escapeJsonString(conversationId)
                    + "\",\"verdict\":\"" + parsed.name() + "\"}";
        } catch (ForbiddenException e) {
            return McpToolUtils.errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceNotFoundException e) {
            return McpToolUtils.errorJson("Conversation not found", "NOT_FOUND", null);
        } catch (IllegalStateException e) {
            String state;
            try { state = conversationService.getConversationState(conversationId).name(); }
            catch (RuntimeException ex) { state = "unknown"; }
            return McpToolUtils.errorJson("Conversation is not in a resumable state", "WRONG_STATE",
                    Map.of("currentState", state));
        } catch (ResourceStoreException e) {
            LOGGER.warn("resume_conversation failed", e);
            return McpToolUtils.errorJson("Failed to resume conversation", "INTERNAL", null);
        }
    }

    @Tool(name = "cancel_conversation",
          description = "Cancel a paused or running regular conversation. Attributed to the authenticated caller.")
    @Blocking
    public String cancelConversation(
            @ToolArg(description = "Conversation ID") String conversationId) {
        String disabled = disabledIfMutationsOff();
        if (disabled != null) return disabled;
        if (conversationId == null || conversationId.isBlank()) {
            return McpToolUtils.errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        try {
            hitlAccessGuard.requireConversationHitlAccess(conversationId);
            var outcome = conversationService.cancelConversation(conversationId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL, principalWithMcpPrefix());
            meterRegistry.counter("eddi.mcp.hitl.cancelled", "surface", "regular").increment();
            return switch (outcome) {
                case CANCELLED -> "{\"status\":\"CANCELLED\"}";
                case NOT_FOUND -> McpToolUtils.errorJson("Conversation not found", "NOT_FOUND", null);
                case NOTHING_TO_CANCEL -> McpToolUtils.errorJson(
                        "Nothing to cancel: conversation is neither awaiting approval nor executing",
                        "WRONG_STATE", null);
            };
        } catch (ForbiddenException e) {
            return McpToolUtils.errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceStoreException e) {
            LOGGER.warn("cancel_conversation failed", e);
            return McpToolUtils.errorJson("Failed to cancel conversation", "INTERNAL", null);
        }
    }
}
```

Adapt `new HitlDecision(); setVerdict/setNote` to `HitlDecision`'s actual API, and the `snapshot.getHitl*` getters to their real names (read `ConversationMemorySnapshot` + `RestAgentEngine.getApprovalStatus:378–384`). Ensure `HitlDecision` exposes `getDecidedBy()`/`getVerdict()` for the test.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=McpHitlToolsTest`
Expected: PASS (all regular-tool tests).

- [ ] **Step 5: Compile & commit**

Run: `./mvnw compile`

```bash
git add src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java src/test/java/ai/labs/eddi/engine/mcp/McpHitlToolsTest.java src/main/java/ai/labs/eddi/engine/lifecycle/model/HitlDecision.java
git commit -m "feat(mcp): add regular HITL tools (list/status/resume/cancel) to McpHitlTools"
```

(Only stage `HitlDecision.java` if you added a getter to it.)

---

### Task 6: `McpHitlTools` — group tools

Five tools: `list_group_pending_approvals`, `list_all_group_pending_approvals`, `get_group_approval_status`, `approve_group_phase`, `cancel_group_discussion`.

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java`
- Test: `src/test/java/ai/labs/eddi/engine/mcp/McpHitlToolsTest.java` (extend)

**Interfaces:**
- Consumes: `HitlAccessGuard.requireGroupConversationHitlAccess`, `HitlAccessGuard.listScopedGroupPendingApprovals`, `IGroupConversationService.resumeDiscussion/cancelDiscussion`, `GroupApprovalRequest`, `IJsonSerialization.deserialize` (confirm the method name).
- Produces: `String listGroupPendingApprovals(String groupId, String limit)`, `String listAllGroupPendingApprovals(String limit)`, `String getGroupApprovalStatus(String groupId, String conversationId, String detail)`, `String approveGroupPhase(String groupId, String conversationId, String verdict, String note, String taskApprovalsJson)`, `String cancelGroupDiscussion(String groupId, String conversationId)`.

- [ ] **Step 1: Write the failing tests (extend `McpHitlToolsTest`)**

```java
@Test
void approveGroup_disabledByKillSwitch_returnsDisabled() {
    tools = build(true, false);
    String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
    assertTrue(out.contains("\"errorCode\":\"DISABLED\""));
    verifyNoInteractions(groupConversationService);
}

@Test
void approveGroup_malformedTaskApprovals_returnsBadRequest() throws Exception {
    when(json.deserialize(eq("{bad"), eq(java.util.Map.class))).thenThrow(new RuntimeException("parse"));
    String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, "{bad");
    assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""));
}

@Test
void approveGroup_happyPath_delegatesWithMcpDecidedBy() throws Exception {
    when(json.serialize(any())).thenReturn("{\"ok\":true}");
    String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", "note", null);
    verify(groupConversationService).resumeDiscussion(eq("gc1"), any(), isNull());
    assertTrue(out.contains("ok"));
}

@Test
void listAllGroupPendingApprovals_delegatesToGuardWithNullGroup() throws Exception {
    when(guard.listScopedGroupPendingApprovals(isNull(), anyInt())).thenReturn(java.util.List.of());
    when(json.serialize(any())).thenReturn("[]");
    String out = tools.listAllGroupPendingApprovals("100");
    verify(guard).listScopedGroupPendingApprovals(null, 100);
    assertEquals("[]", out);
}

@Test
void cancelGroup_happyPath_delegates() throws Exception {
    when(groupConversationService.cancelDiscussion(eq("gc1"), any())).thenReturn(true);
    String out = tools.cancelGroupDiscussion("g1", "gc1");
    assertTrue(out.contains("CANCELLED"));
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=McpHitlToolsTest`
Expected: FAIL (group tool methods not defined).

- [ ] **Step 3: Implement the group tools**

Add to `McpHitlTools`. `approve_group_phase` parses `taskApprovals` from a JSON-object string, mirrors the regular resume validation, delegates to `resumeDiscussion(conversationId, request, null)`, and serializes the returned `GroupConversation`:

```java
@Tool(name = "list_group_pending_approvals",
      description = "List a group's conversations awaiting human approval (owner-scoped).")
@Blocking
public String listGroupPendingApprovals(
        @ToolArg(description = "Group ID") String groupId,
        @ToolArg(description = "Max entries (default 100)", required = false) String limit) {
    if (groupId == null || groupId.isBlank()) {
        return McpToolUtils.errorJson("groupId is required", "BAD_REQUEST", null);
    }
    try {
        var result = hitlAccessGuard.listScopedGroupPendingApprovals(groupId,
                McpToolUtils.parseIntOrDefault(limit, 100));
        meterRegistry.counter("eddi.mcp.hitl.pending.listed", "surface", "group").increment();
        return jsonSerialization.serialize(result);
    } catch (Exception e) {
        LOGGER.warn("list_group_pending_approvals failed", e);
        return McpToolUtils.errorJson("Failed to list group pending approvals", "INTERNAL", null);
    }
}

@Tool(name = "list_all_group_pending_approvals",
      description = "Cross-group HITL inbox: all group conversations awaiting approval (owner-scoped).")
@Blocking
public String listAllGroupPendingApprovals(
        @ToolArg(description = "Max entries (default 100)", required = false) String limit) {
    try {
        var result = hitlAccessGuard.listScopedGroupPendingApprovals(null,
                McpToolUtils.parseIntOrDefault(limit, 100));
        meterRegistry.counter("eddi.mcp.hitl.pending.listed", "surface", "group-all").increment();
        return jsonSerialization.serialize(result);
    } catch (Exception e) {
        LOGGER.warn("list_all_group_pending_approvals failed", e);
        return McpToolUtils.errorJson("Failed to list group pending approvals", "INTERNAL", null);
    }
}

@Tool(name = "get_group_approval_status",
      description = "Read the approval status (summary) of a paused group discussion.")
@Blocking
public String getGroupApprovalStatus(
        @ToolArg(description = "Group ID") String groupId,
        @ToolArg(description = "Group conversation ID") String conversationId,
        @ToolArg(description = "summary (default) or full", required = false) String detail) {
    if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
        return McpToolUtils.errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
    }
    try {
        hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
        // Mirror RestGroupConversation.getGroupApprovalStatus (302-347): build and return the
        // SUMMARY map only (MCP cannot return polymorphic shapes). For detail=full, apply the
        // same owner/admin/approver-while-paused gate as REST and return the full object; the
        // full transcript is otherwise available via existing read tools.
        // Read RestGroupConversation.java:302-347 for the exact snapshot getters and gate.
        return jsonSerialization.serialize(/* summary map built per REST source */ new LinkedHashMap<String,String>());
    } catch (ForbiddenException e) {
        return McpToolUtils.errorJson("Access denied", "FORBIDDEN", null);
    } catch (Exception e) {
        LOGGER.warn("get_group_approval_status failed", e);
        return McpToolUtils.errorJson("Failed to read group approval status", "INTERNAL", null);
    }
}

@Tool(name = "approve_group_phase",
      description = "Approve or reject a paused group discussion phase (or specific tasks for TASK-granularity). "
              + "verdict=APPROVED|REJECTED. taskApprovals is an optional JSON object mapping task-id to APPROVED|REJECTED.")
@Blocking
public String approveGroupPhase(
        @ToolArg(description = "Group ID") String groupId,
        @ToolArg(description = "Group conversation ID") String conversationId,
        @ToolArg(description = "APPROVED or REJECTED (case-insensitive)") String verdict,
        @ToolArg(description = "Optional reviewer note (max 4096 chars)", required = false) String note,
        @ToolArg(description = "Optional JSON object {\"task-id\":\"APPROVED|REJECTED\"}", required = false) String taskApprovalsJson) {
    String disabled = disabledIfMutationsOff();
    if (disabled != null) return disabled;
    if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
        return McpToolUtils.errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
    }
    HitlVerdict parsed = parseVerdictOrNull(verdict);
    if (parsed == null) {
        return McpToolUtils.errorJson("Invalid verdict; use APPROVED or REJECTED", "BAD_REQUEST", null);
    }
    if (note != null && note.length() > HitlDecision.MAX_NOTE_LENGTH) {
        return McpToolUtils.errorJson("Note exceeds max length " + HitlDecision.MAX_NOTE_LENGTH, "BAD_REQUEST", null);
    }
    Map<String, String> taskApprovals = null;
    if (taskApprovalsJson != null && !taskApprovalsJson.isBlank()) {
        try {
            taskApprovals = jsonSerialization.deserialize(taskApprovalsJson, Map.class);  // confirm method name
        } catch (Exception e) {
            return McpToolUtils.errorJson("Malformed taskApprovals JSON", "BAD_REQUEST", null);
        }
    }
    try {
        hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
        HitlDecision decision = new HitlDecision();
        decision.setVerdict(parsed);
        decision.setNote(note);
        decision.setDecidedBy(principalWithMcpPrefix());
        var request = new ai.labs.eddi.engine.internal.GroupApprovalRequest();  // adapt constructor/setters
        request.setDecision(decision);
        request.setTaskApprovals(taskApprovals);
        var result = groupConversationService.resumeDiscussion(conversationId, request, null);
        meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "group", "verdict", parsed.name()).increment();
        return jsonSerialization.serialize(result);
    } catch (ForbiddenException e) {
        return McpToolUtils.errorJson("Access denied", "FORBIDDEN", null);
    } catch (ResourceNotFoundException e) {
        return McpToolUtils.errorJson("Group conversation not found", "NOT_FOUND", null);
    } catch (IllegalArgumentException e) {
        return McpToolUtils.errorJson("Invalid task approvals: " + e.getMessage(), "BAD_REQUEST", null);
    } catch (IllegalStateException e) {
        return McpToolUtils.errorJson("Discussion is not in an approvable state", "WRONG_STATE", null);
    } catch (Exception e) {
        LOGGER.warn("approve_group_phase failed", e);
        return McpToolUtils.errorJson("Failed to approve group phase", "INTERNAL", null);
    }
}

@Tool(name = "cancel_group_discussion",
      description = "Cancel an in-progress or paused group discussion.")
@Blocking
public String cancelGroupDiscussion(
        @ToolArg(description = "Group ID") String groupId,
        @ToolArg(description = "Group conversation ID") String conversationId) {
    String disabled = disabledIfMutationsOff();
    if (disabled != null) return disabled;
    if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
        return McpToolUtils.errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
    }
    try {
        hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
        boolean cancelled = groupConversationService.cancelDiscussion(conversationId,
                ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);
        meterRegistry.counter("eddi.mcp.hitl.cancelled", "surface", "group").increment();
        return cancelled ? "{\"status\":\"CANCELLED\"}"
                : McpToolUtils.errorJson("Nothing to cancel", "WRONG_STATE", null);
    } catch (ForbiddenException e) {
        return McpToolUtils.errorJson("Access denied", "FORBIDDEN", null);
    } catch (ResourceNotFoundException e) {
        return McpToolUtils.errorJson("Group conversation not found", "NOT_FOUND", null);
    } catch (Exception e) {
        LOGGER.warn("cancel_group_discussion failed", e);
        return McpToolUtils.errorJson("Failed to cancel group discussion", "INTERNAL", null);
    }
}
```

Fill in the `get_group_approval_status` summary map from the exact getters in `RestGroupConversation.java:302–347`. Confirm `GroupApprovalRequest`'s constructor/setters and `IJsonSerialization.deserialize`'s signature.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=McpHitlToolsTest`
Expected: PASS (regular + group tests).

- [ ] **Step 5: Compile & commit**

Run: `./mvnw compile`

```bash
git add src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java src/test/java/ai/labs/eddi/engine/mcp/McpHitlToolsTest.java
git commit -m "feat(mcp): add group HITL tools (list/status/approve/cancel) to McpHitlTools"
```

---

### Task 7: Discoverability — name the resume tool in the `PAUSED_FOR_APPROVAL` payload

So an LLM MCP client that receives a paused signal knows which tool to call to close the loop.

**Files:**
- Modify: the method that builds the `PAUSED_FOR_APPROVAL` JSON (`pausedForApprovalJson`, likely in `McpConversationTools.java` or `McpToolUtils.java` — locate with grep).
- Test: the existing test covering that payload (e.g. `McpConversationToolsHitlTest`).

- [ ] **Step 1: Locate the payload builder**

Run: `grep -rn "PAUSED_FOR_APPROVAL\|pausedForApproval" src/main/java`
Note the method and the JSON fields it emits (state, conversationId, agentId, reason).

- [ ] **Step 2: Update the existing test to expect the hint**

In the test that asserts the paused payload, add an assertion that the JSON now contains `"suggestNextTool":"resume_conversation"`. Run it to confirm it FAILS first:
Run: `./mvnw test -Dtest=McpConversationToolsHitlTest`
Expected: FAIL on the new assertion. (If this is a `@QuarkusTest` not runnable locally, add the assertion and defer verification to CI — Task 9.)

- [ ] **Step 3: Add the field**

`McpConversationTools` has **two** `pausedForApprovalJson` overloads (≈lines 602 and 629); both build a `Map`/`result` and `put("status", "PAUSED_FOR_APPROVAL")`. Add to **both**:

```java
result.put("suggestNextTool", "resume_conversation");
```

Neither overload currently emits `pauseType` (the tool-approval plan will add that separately) — so only add the `suggestNextTool` key; do not touch or assume any other field. `resume_conversation` is the correct next tool for both RULE and TOOL_CALL pauses (both resolve via the single-verdict resume).

- [ ] **Step 4: Run the test**

Run: `./mvnw test -Dtest=McpConversationToolsHitlTest`
Expected: PASS (or CI-only).

- [ ] **Step 5: Commit**

```bash
git add <the payload-builder file> <the test file>
git commit -m "feat(mcp): name resume_conversation in PAUSED_FOR_APPROVAL payload for client chaining"
```

---

### Task 8: Documentation + changelog

**Files:**
- Modify: `docs/hitl.md`, `docs/mcp-server.md`, `docs/changelog.md`
- Optional: `AGENTS.md` (add an "MCP Tool Class Checklist" note)

- [ ] **Step 1: `docs/hitl.md` — add an "MCP Surface" section**

Document the 9 tools (name + one-line description), that they mirror the REST endpoints, that mutating tools require the same owner/admin/approver authority and honor `eddi.mcp.hitl.mutations.enabled`, that MCP decisions are attributed `mcp:<principal>`, that there is no streaming variant (`approve_group_phase` blocks and returns the resumed discussion), and the structured error codes (`NOT_FOUND | WRONG_STATE | FORBIDDEN | DISABLED | BAD_REQUEST`). Note that the regular tools resolve **both** `RULE` and `TOOL_CALL` pauses (both are `AWAITING_HUMAN`, both resolved by a single verdict); `get_approval_status` reports `pauseType`, and for a `TOOL_CALL` pause the pending tool-call batch is visible via `detail=full`.

- [ ] **Step 2: `docs/mcp-server.md` — add a "HITL Tools (9)" category**

List all 9 tools with descriptions matching `docs/hitl.md`, so they are discoverable in the tool catalog.

- [ ] **Step 3: `docs/changelog.md` — add an entry**

Add a dated entry (repo: EDDI, branch: `feat/hitl-framework`) summarizing: new `McpHitlTools` (9 tools), the shared `HitlAccessGuard` extraction (regular + group), the `eddi.mcp.hitl.mutations.enabled` kill-switch, `mcp:`-prefixed decision attribution, and the `PAUSED_FOR_APPROVAL` `suggestNextTool` hint. Note design decisions: human authority preserved; delegate to services not REST; mirror REST auth + kill-switch; no streaming/agent-approver.

- [ ] **Step 4: (Optional) `AGENTS.md` — MCP tool class checklist**

Under §4.3, add a short note that an MCP tool class needs only `@ApplicationScoped` + constructor-injected deps + `@Tool`/`@ToolArg` methods returning JSON strings + `@Blocking` on I/O + Mockito unit tests — NOT the `ILifecycleTask` artifacts (Configuration POJO, Store, REST, ExtensionDescriptor). Reference `McpConversationTools`/`McpHitlTools` as examples.

- [ ] **Step 5: Commit**

```bash
git add docs/hitl.md docs/mcp-server.md docs/changelog.md AGENTS.md
git commit -m "docs(mcp): document MCP HITL surface (tools, kill-switch, auth, error codes)"
```

(Drop `AGENTS.md` from the stage if you skipped Step 4.)

---

### Task 9: Full build + test verification

**Files:** none (verification only).

- [ ] **Step 1: Full compile**

Run: `./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Run the full locally-runnable test suite (Mockito unit tests)**

Run: `./mvnw test -Dtest='McpToolUtilsErrorJsonTest,HitlAccessGuardTest,McpHitlToolsTest'`
Expected: PASS, 0 failures.

- [ ] **Step 3: Run the broader HITL/MCP regression (whatever runs locally)**

Run: `./mvnw test -Dtest='*Hitl*,*McpHitl*'`
Expected: PASS for all non-`@QuarkusTest` tests. Any `@QuarkusTest` / integration tests that cannot boot locally are expected to be verified in CI — record which were skipped locally; do not delete or weaken them.

- [ ] **Step 4: Push and let CI run the full suite**

CI (`.github/workflows/ci.yml`) runs the complete suite including `@QuarkusTest` and integration tests. Push the branch and confirm CI is green before requesting review. (Get explicit approval before pushing.)

```bash
git status          # confirm only intended files are committed across Tasks 1-8
git log --oneline -9
```

- [ ] **Step 5: Final self-check against the design**

Confirm: 9 tools present; all mutating tools honor the kill-switch; `decidedBy`/`cancelledBy` are `mcp:`-prefixed and never taken from args; group listings are owner-scoped via the guard; REST HITL regression tests unchanged and green in CI; docs + changelog landed on `feat/hitl-framework`.

---

## Self-Review (author's check against the design)

- **Spec coverage:** all 9 tools (Tasks 5–6); shared guard regular+group (Tasks 2–3); kill-switch registered (Task 4) and enforced (Tasks 5–6); structured errors (Task 1, used throughout); `mcp:` attribution (Task 5 helper); owner-scoping for group + cross-group (Task 3 + Task 6 tests); `@Blocking` on all I/O tools; verdict/note validation; summary-only group status; metrics (inline counters); discoverability hint (Task 7); docs + changelog (Task 8); full verification (Task 9). ✓
- **Rejected-by-design (documented, not built):** agent-level `HitlConfig.enabled`; dev-mode fail-closed mutations; by-intent resume variant; SSE over MCP; autonomous approver; rate limiting (idempotency covered by the `AWAITING_HUMAN` state check + `compareAndSetState`). ✓
- **Type consistency:** service signatures, `HitlVerdict.fromString`, `GroupApprovalRequest`, `ControlSignal.CANCEL_GRACEFUL`, `CancelOutcome`, `errorJson(msg,code,details)` used consistently across tasks. ✓
- **Known confirm-on-read points (flagged inline, not placeholders):** `HitlDecision` constructor/setters + `getDecidedBy`; `GroupApprovalRequest` constructor/setters; the conversation/group descriptor store types; `IJsonSerialization.deserialize` signature; `ConversationMemorySnapshot.getHitl*` getters; `resumeDiscussion` null-listener acceptance; the `pausedForApprovalJson` location. Each task names the exact file/lines to read.

# Group Conversations — Implementation Plan

> **Status:** Approved for implementation  
> **Phase:** 10 (replaces Phase 10a + 10b items 43, 44, 46)  
> **Estimated SP:** 13  
> **Prerequisites:** None — builds on existing `ConversationService`, `IConversationCoordinator` (NATS/in-memory)

---

## 1. Overview

Group Conversations enable multiple agents to discuss a question posed by a user. Agents participate in structured debate rounds, giving each other feedback, and produce a collective opinion through moderator synthesis.

**Architecture:** Group is a first-class entity (config, store, REST, MCP tools). Each participant agent runs its own normal pipeline via `ConversationService.say()` — agents are group-unaware by default. The `GroupConversationService` orchestrates the debate on top of the existing `IConversationCoordinator` (NATS-backed for durability/ordering).

```
User → POST /groups/{env}/{groupId}/conversations
         │
         ▼
    GroupConversationService
         │
         ├── Round 1: submit agent calls via IConversationCoordinator
         │     ├── ConversationService.say(agentA, privateConvA, input)
         │     ├── ConversationService.say(agentB, privateConvB, input)
         │     └── ConversationService.say(agentC, privateConvC, input)
         │
         ├── Round 2: inject Round 1 transcript into inputs
         │     ├── ConversationService.say(agentA, privateConvA, input+context)
         │     ├── ConversationService.say(agentB, privateConvB, input+context)
         │     └── ...
         │
         └── Synthesis: ConversationService.say(moderator, modConv, fullTranscript)
               │
               ▼
         GroupConversation (transcript + synthesized answer) → User
```

---

## 2. Data Models

### 2.1 AgentGroupConfiguration

Persisted, versioned config entity following the existing store pattern (`AbstractResourceStore`).

```java
package ai.labs.eddi.configs.groups.model;

public class AgentGroupConfiguration {
    private String name;
    private String description;
    private List<GroupMember> members;
    private String moderatorAgentId;        // optional — if null, last round response is final
    private ProtocolConfig protocol;
    private ContextConfig contextConfig;

    // --- Member definition ---

    public record GroupMember(
        String agentId,                    // deployed agent ID
        String displayName,               // "Architecture Expert" — used in transcript
        Integer speakingOrder,             // for sequential ordering, nullable for parallel
        boolean allowGroupDiscussion,      // can this member invoke group tools? (depth control)
        boolean allowAgentToAgentCalls     // can this member call other agents?
    ) {}

    // --- Protocol definition ---

    public record ProtocolConfig(
        ProtocolType type,                 // SEQUENTIAL or PARALLEL
        int maxRounds,                     // discussion rounds (default: 2)
        int agentTimeoutSeconds,           // per-agent timeout (default: 60)
        MemberFailurePolicy onAgentFailure,  // SKIP, RETRY, ABORT (default: SKIP)
        int maxRetries,                    // if RETRY (default: 2)
        MemberUnavailablePolicy onMemberUnavailable  // SKIP or FAIL (default: SKIP)
    ) {
        public enum ProtocolType { SEQUENTIAL, PARALLEL }
        public enum MemberFailurePolicy { SKIP, RETRY, ABORT }
        public enum MemberUnavailablePolicy { SKIP, FAIL }
    }

    // --- Context/input construction ---

    public record ContextConfig(
        HistoryStrategy historyStrategy,     // FULL, LAST_ROUND, WINDOW (default: FULL)
        Integer windowSize,                  // for WINDOW strategy
        boolean summarizeBetweenRounds,      // not implemented in v1
        String inputTemplateRound1,          // Thymeleaf template (optional, has default)
        String inputTemplateRoundN,          // Thymeleaf template (optional, has default)
        String inputTemplateSynthesis        // Thymeleaf template (optional, has default)
    ) {
        public enum HistoryStrategy { FULL, LAST_ROUND, WINDOW }
    }
}
```

### 2.2 GroupConversation

The transcript record, persisted in its own collection/table.

```java
package ai.labs.eddi.configs.groups.model;

public class GroupConversation {
    private String id;
    private String groupId;
    private String userId;
    private GroupConversationState state;
    private String originalQuestion;
    private List<TranscriptEntry> transcript;
    private Map<String, String> memberConversationIds;  // agentId → private conversationId
    private int currentRound;
    private String synthesizedAnswer;
    private int depth;                    // recursion depth (0 = top-level)
    private Instant created;
    private Instant lastModified;

    public record TranscriptEntry(
        String speakerAgentId,            // agentId or "user"
        String speakerDisplayName,
        String content,
        int round,                        // 0 = user question, 1+ = rounds, -1 = synthesis
        TranscriptEntryType type,
        Instant timestamp,
        String errorReason                // populated for ERROR/SKIPPED entries
    ) {}

    public enum TranscriptEntryType {
        QUESTION, OPINION, SYNTHESIS, ERROR, SKIPPED
    }

    public enum GroupConversationState {
        CREATED, IN_PROGRESS, SYNTHESIZING, COMPLETED, FAILED
    }
}
```

---

## 3. How Input Reaches the LLM

Two complementary mechanisms (both always active):

**1. Input construction (default, group-unaware agents):**
The `GroupConversationService` constructs a natural language `input` string for each member per round. This becomes the user message in the agent's conversation.

**2. Context injection (opt-in, group-aware agents):**
The `GroupConversationService` always sets `context.put("groupTranscript", transcriptData)`. If an agent's system prompt references `[[${context.groupTranscript}]]`, it gets the structured data. Otherwise it's ignored.

### Default Input Templates

**Round 1:**
```
A panel of experts is discussing the following question:
"{{question}}"

As {{displayName}}, please share your professional perspective.
```

**Round 2+:**
```
The discussion continues (Round {{round}}).

Previous responses:
{{#each previousResponses}}
— {{speaker}} (Round {{round}}): "{{content}}"
{{/each}}

As {{displayName}}, please respond to the others' perspectives.
```

**Synthesis:**
```
The panel discussed this question for {{totalRounds}} rounds:
"{{question}}"

Full transcript:
{{#each transcript}}
[Round {{round}}] {{speaker}}: "{{content}}"
{{/each}}

Synthesize a balanced conclusion with clear recommendation.
```

Templates are processed by the existing Thymeleaf `ITemplatingEngine`. Custom templates via `ContextConfig.inputTemplate*` override defaults.

---

## 4. NATS-Backed Orchestration

The `GroupConversationService` uses `IConversationCoordinator` (NATS or in-memory) for all agent calls. This gives durability, ordering, dead-letter, and metrics from day one.

### State Machine

```
CREATED → IN_PROGRESS → SYNTHESIZING → COMPLETED
                ↓              ↓
              FAILED         FAILED
```

### Orchestration Flow

```java
// Pseudocode — GroupConversationService.discuss()
GroupConversation gc = createGroupConversation(groupId, question, userId, depth);
gc.state = IN_PROGRESS;

for (int round = 1; round <= config.protocol.maxRounds; round++) {
    gc.currentRound = round;
    String contextText = buildContext(gc.transcript, config.contextConfig, round);

    if (config.protocol.type == SEQUENTIAL) {
        for (GroupMember member : orderedMembers) {
            String input = buildInput(config, member, question, contextText, round);
            // Submit through coordinator for ordering + durability
            String response = executeAgentTurn(member, gc, input);
            gc.transcript.add(new TranscriptEntry(member, response, round));
            contextText = rebuildContext(...); // sequential sees previous in same round
        }
    } else { // PARALLEL
        List<CompletableFuture<TranscriptEntry>> futures = orderedMembers.stream()
            .map(member -> CompletableFuture.supplyAsync(() -> {
                String input = buildInput(config, member, question, contextText, round);
                String response = executeAgentTurn(member, gc, input);
                return new TranscriptEntry(member, response, round);
            }))
            .toList();
        // Wait for all, collect results, handle timeouts/failures
        gc.transcript.addAll(collectResults(futures, config.protocol));
    }
    save(gc);
}

// Synthesis
if (config.moderatorAgentId != null) {
    gc.state = SYNTHESIZING;
    gc.synthesizedAnswer = executeSynthesis(gc, config);
}
gc.state = COMPLETED;
save(gc);
```

### executeAgentTurn()

```java
private String executeAgentTurn(GroupMember member, GroupConversation gc, String input) {
    // 1. Check if agent is deployed
    if (!isAgentDeployed(member.agentId)) {
        if (config.protocol.onMemberUnavailable == FAIL) throw ...;
        return null; // SKIPPED entry
    }

    // 2. Get or create private conversation for this member in this group
    String privateConvId = gc.memberConversationIds
        .computeIfAbsent(member.agentId, id ->
            conversationService.startConversation(env, id, gc.userId, groupContext).conversationId()
        );

    // 3. Build InputData with group context
    InputData inputData = new InputData(input, Map.of(
        "groupTranscript", new Context(ContextType.object, gc.transcript),
        "groupConversationId", new Context(ContextType.string, gc.id),
        "groupDepth", new Context(ContextType.string, String.valueOf(gc.depth))
    ));

    // 4. Call through ConversationService (which uses IConversationCoordinator for ordering)
    CompletableFuture<String> responseFuture = new CompletableFuture<>();
    conversationService.say(env, member.agentId, privateConvId,
        false, true, null, inputData, false,
        snapshot -> responseFuture.complete(extractResponse(snapshot))
    );

    // 5. Wait with timeout
    try {
        return responseFuture.get(config.protocol.agentTimeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        handleTimeout(member, config.protocol);
        return null; // SKIPPED or retry
    }
}
```

---

## 5. Recursion Depth Control

Prevents infinite loops when agents can trigger group discussions.

### System-Wide Config

```properties
# application.properties
eddi.groups.max-depth=3
```

### How It Works

1. When a user starts a group conversation: `depth=0`
2. The `depth` is stored in the `GroupConversation` and passed as context to all member agents
3. When a member agent calls `discuss_with_group` tool: service checks `currentDepth + 1 <= maxDepth`
4. If exceeded: tool returns error message instead of executing
5. Per-member `allowGroupDiscussion=false` blocks group calls entirely for that member

### Depth Tracking

```java
// In GroupConversationService
public GroupConversation discuss(String groupId, String question, String userId, int depth) {
    if (depth > maxGroupDepth) {
        throw new GroupDepthExceededException(
            "Maximum group discussion depth (%d) exceeded".formatted(maxGroupDepth));
    }
    // ... depth is stored in GroupConversation and passed to member contexts
}

// In MCP tool or built-in tool
@Tool("Discuss a question with a group of agents")
public String discussWithGroup(String groupId, String question) {
    // Extract current depth from conversation context
    int currentDepth = getCurrentGroupDepth(memory);
    return groupConversationService.discuss(groupId, question, userId, currentDepth + 1);
}
```

---

## 6. Private Conversations

Each member agent gets **one persistent conversation per group conversation**. This means:

- Round 1: first turn in the agent's private conversation
- Round 2: second turn — agent naturally has conversation history from round 1
- The LLM sees its own previous responses, plus new group context in the input

### Lifecycle

- **Created:** when group conversation starts (`ConversationService.startConversation()`)
- **Tagged:** with property `groupConversationId` for filtering/cascade
- **Hidden:** filtered from user-facing conversation list endpoints
- **Deleted:** cascade-deleted when group conversation is deleted

---

## 7. SSE Streaming

Live debate streaming via SSE for watching discussions unfold in real-time.

### Endpoint

```
POST /groups/{env}/{groupId}/conversations/{convId}/stream
Accept: text/event-stream
```

### Events

```
event: group_start
data: {"groupId":"...", "question":"...", "members":[...], "maxRounds":2}

event: round_start
data: {"round":1, "speakers":["architectExpert","devopsExpert"]}

event: speaker_start
data: {"speaker":"architectExpert", "displayName":"Architecture Expert", "round":1}

event: token
data: {"speaker":"architectExpert", "token":"Yes"}

event: speaker_complete
data: {"speaker":"architectExpert", "round":1, "response":"Yes, I believe..."}

event: round_complete
data: {"round":1}

event: synthesis_start
data: {"moderator":"moderatorAgent"}

event: synthesis_complete
data: {"response":"Based on all perspectives..."}

event: group_complete
data: {"groupConversationId":"...", "synthesizedAnswer":"...", "totalRounds":2}
```

**Implementation:** `GroupConversationService` calls `ConversationService.sayStreaming()` for each member. Per-agent `StreamingResponseHandler` events are wrapped with speaker/round metadata and forwarded to the group-level SSE sink (`GroupConversationEventSink`).

---

## 8. REST API

### Group Configuration CRUD

```
POST   /groupstore/groups                              → create group config
GET    /groupstore/groups                              → list groups (with pagination)
GET    /groupstore/groups/{id}                         → read group config
PUT    /groupstore/groups/{id}                         → update group config
DELETE /groupstore/groups/{id}?permanent=true           → delete group config + cascade
```

### Group Conversations

```
POST   /groups/{env}/{groupId}/conversations           → start group discussion
GET    /groups/{env}/{groupId}/conversations/{id}       → read group conversation (transcript)
POST   /groups/{env}/{groupId}/conversations/{id}/stream → SSE stream of live debate
DELETE /groups/{env}/{groupId}/conversations/{id}       → delete group conversation + cascade
```

### MCP Tools

| Tool | Description |
|---|---|
| `create_group` | Create group config (name, members, moderator, protocol) |
| `list_groups` | List all group configs |
| `read_group` | Read group config |
| `update_group` | Update group config |
| `delete_group` | Delete group config |
| `discuss_with_group` | Start group discussion, return transcript + synthesis |
| `read_group_conversation` | Read group conversation transcript |

---

## 9. Error Handling Summary

| Scenario | Config | Behavior |
|---|---|---|
| Member agent not deployed | `onMemberUnavailable: SKIP\|FAIL` | Skip with SKIPPED entry, or fail group conversation |
| Agent timeout during round | `agentTimeoutSeconds` | Cancel, record SKIPPED or retry |
| Agent returns error | `onAgentFailure: SKIP\|RETRY\|ABORT` | Skip, retry up to `maxRetries`, or abort group |
| Recursion depth exceeded | `eddi.groups.max-depth` | Tool returns error, agent handles gracefully |
| NATS unavailable (if configured) | Falls back to in-memory coordinator | Via existing `IConversationCoordinator` abstraction |

---

## 10. Files to Create/Modify

### New Files

| File | Purpose |
|---|---|
| `configs/groups/model/AgentGroupConfiguration.java` | Config POJO with `GroupMember`, `ProtocolConfig`, `ContextConfig` |
| `configs/groups/model/GroupConversation.java` | Transcript model with `TranscriptEntry`, state enum |
| `configs/groups/IAgentGroupStore.java` | Store interface (extends `IResourceStore`) |
| `configs/groups/IRestAgentGroupStore.java` | JAX-RS interface for group config CRUD |
| `configs/groups/rest/RestAgentGroupStore.java` | JAX-RS implementation |
| `configs/groups/mongo/AgentGroupStore.java` | MongoDB store (extends `AbstractResourceStore`) |
| `configs/groups/IGroupConversationStore.java` | Store interface for group conversations |
| `configs/groups/mongo/MongoGroupConversationStore.java` | MongoDB persistence for group conversations |
| `engine/api/IGroupConversationService.java` | Service interface (no JAX-RS deps) |
| `engine/internal/GroupConversationService.java` | Core orchestrator — rounds, context, NATS |
| `engine/api/IRestGroupConversation.java` | JAX-RS interface for group conversation endpoints |
| `engine/internal/RestGroupConversation.java` | JAX-RS implementation |
| `engine/internal/RestGroupConversationStreaming.java` | SSE streaming endpoint |
| `engine/lifecycle/GroupConversationEventSink.java` | SSE event definitions for group streaming |
| `engine/mcp/McpGroupTools.java` | MCP tools for group CRUD + discussion |

### Modified Files

| File | Change |
|---|---|
| `application.properties` | Add `eddi.groups.max-depth=3` |
| `engine/mcp/McpToolFilter.java` | Add group MCP tools to whitelist |
| `engine/memory/descriptor/IConversationDescriptorStore.java` | Add filtering for group-internal conversations |

### Tests

| File | Tests |
|---|---|
| `configs/groups/rest/RestAgentGroupStoreTest.java` | CRUD tests for group config |
| `engine/internal/GroupConversationServiceTest.java` | Orchestration: rounds, sequential/parallel, timeouts, depth control, error handling |
| `engine/internal/RestGroupConversationTest.java` | REST endpoint tests |
| `engine/mcp/McpGroupToolsTest.java` | MCP tool tests |

---

## 11. Implementation Order

### Phase 10.1: Group Config + Store (3 SP)

1. `AgentGroupConfiguration` + `GroupMember` + `ProtocolConfig` + `ContextConfig` — data models
2. `IAgentGroupStore` + `AgentGroupStore` (MongoDB, extends `AbstractResourceStore`)
3. `IRestAgentGroupStore` + `RestAgentGroupStore` — CRUD endpoints
4. `RestAgentGroupStoreTest` — unit tests
5. Verify: `./mvnw test`

### Phase 10.2: Group Conversation + Orchestration (5 SP)

1. `GroupConversation` + `TranscriptEntry` — data models
2. `IGroupConversationStore` + `MongoGroupConversationStore`
3. `IGroupConversationService` — service interface
4. `GroupConversationService` — core orchestrator:
   - Round management (sequential + parallel)
   - Input construction with Thymeleaf templates
   - Context injection
   - Private conversation lifecycle
   - Timeout + error handling
   - Depth tracking
   - NATS-backed via `IConversationCoordinator`
5. `GroupConversationServiceTest` — comprehensive unit tests
6. Verify: `./mvnw test`

### Phase 10.3: REST + SSE + MCP (3 SP)

1. `IRestGroupConversation` + `RestGroupConversation` — start/read/delete group conversations
2. `GroupConversationEventSink` — streaming event definitions
3. `RestGroupConversationStreaming` — SSE endpoint
4. `McpGroupTools` — MCP tools (create_group, discuss_with_group, etc.)
5. Update `McpToolFilter` whitelist
6. `RestGroupConversationTest` + `McpGroupToolsTest`
7. Verify: `./mvnw test`

### Phase 10.4: Integration Testing (2 SP)

1. Integration test: create group → discuss → verify transcript
2. Integration test: agent failure → skip → verify partial transcript
3. Integration test: depth control → verify recursion blocked
4. SSE streaming test: verify event sequence
5. Full test suite green: `./mvnw verify`

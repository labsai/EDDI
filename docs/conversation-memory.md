# Conversation Memory and State Management

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

## Overview

**Conversation Memory** (`IConversationMemory`) is the heart of EDDI's stateful architecture. It's a Java object that represents the complete state of a conversation, including history, user data, context, and intermediate processing results. This object is passed through the entire Lifecycle Pipeline, with each task reading from and writing to it.

## What is Conversation Memory?

Think of Conversation Memory as a **living document** that captures everything about a conversation:

- **Who**: User ID and agent ID
- **What**: All messages exchanged (both user inputs and agent outputs)
- **When**: Timestamp of each interaction
- **Context**: Data passed from external systems (user profile, session info, etc.)
- **State**: Current processing stage (READY, IN_PROGRESS, ENDED, etc.)
- **Properties**: Extracted and stored data (user preferences, entities, variables)
- **History**: Complete record of all previous conversation steps

## Key Concepts

### 1. Conversation Steps

A conversation is divided into **steps**, where each step represents one complete interaction cycle:

```
Step 1: User says "Hello" → Agent responds "Hi, how can I help?"
Step 2: User says "What's the weather?" → Agent responds "The weather is sunny, 75°F"
Step 3: ...
```

Each step contains:

- **Input**: What the user said
- **Actions**: Actions triggered by behavior rules
- **Data**: Results from lifecycle tasks (parsed expressions, API responses, LLM outputs)
- **Output**: Agent's response

### 2. Current Step vs Previous Steps

```java
IWritableConversationStep getCurrentStep();  // The step being processed right now
IConversationStepStack getPreviousSteps();    // All completed steps (history)
```

- **Current Step**: Writable, being built during lifecycle execution
- **Previous Steps**: Read-only, provides conversation history

### 3. Memory Scopes

EDDI supports different scopes for storing data:

| Scope          | Lifetime             | Use Case                                                     |
| -------------- | -------------------- | ------------------------------------------------------------ |
| `step`         | Single interaction   | Temporary data needed only for this response. Cleared at the end of the turn, however it ends — except a HITL pause, whose turn is not over |
| `conversation` | Entire conversation  | User preferences, extracted entities (persists across steps) |
| `longTerm`     | Across conversations | User profile data that should persist between sessions. Written to the user memory store when a turn completes |
| `secret`       | Entire conversation  | API keys, tokens and other credentials. The plaintext goes into the [Secrets Vault](secrets-vault.md) the moment the instruction runs, and the property holds only a `${vault:...}` reference. Needs `EDDI_VAULT_MASTER_KEY`; without it the turn fails rather than storing plaintext. See [Properties](properties.md) |

These are the values of the `Property.Scope` enum (`step`, `conversation`, `longTerm`, `secret`).

### 4. Undo/Redo Support

Conversation Memory supports undo/redo operations:

```java
void undoLastStep();       // Go back to previous step
boolean isUndoAvailable(); // Check if undo is possible
void redoLastStep();       // Re-apply undone step
boolean isRedoAvailable(); // Check if redo is possible
```

This enables scenarios like:

- User makes a mistake and wants to go back
- Testing different conversation paths
- Debugging agent behavior

Undo and redo carry the **conversation properties** with the step. Each completed turn records which
properties it changed (step data `properties:changes`, uncommitted and non-public, so the LLM never
sees it); undo restores their values from before the turn — including removing a property the turn
created — and redo re-applies them. For `longTerm` properties the change is carried into the user
memory store as well, but only where the store still holds exactly the value the undone turn wrote: a
value another conversation or agent has written since is left alone. A new turn clears the redo stack,
because the undone steps now belong to an abandoned branch. A turn completed through a HITL resume
records no property changes; undoing it leaves its properties as they are.

## Conversation Memory Structure

### Core Properties

```java
public interface IConversationMemory {
    // Identity
    String getConversationId();
    String getAgentId();
    Integer getAgentVersion();
    String getUserId();

    // State
    ConversationState getConversationState();
    void setConversationState(ConversationState state);

    // Steps
    IWritableConversationStep getCurrentStep();
    IConversationStepStack getPreviousSteps();
    IConversationStepStack getAllSteps();
    int size();  // Total number of steps

    // Properties
    IConversationProperties getConversationProperties();

    // Output
    List<ConversationOutput> getConversationOutputs();

    // History management
    void undoLastStep();
    void redoLastStep();
    Stack<IConversationStep> getRedoCache();
}
```

### Conversation States

```java
public enum ConversationState {
    READY,           // Agent is ready to process next input
    IN_PROGRESS,     // Currently processing a message
    EXECUTION_INTERRUPTED,  // Processing was interrupted
    ERROR,           // An error occurred
    ENDED,           // Conversation has ended
    AWAITING_HUMAN   // Paused awaiting human approval (HITL) — see hitl.md
}
```

## How Lifecycle Tasks Use Memory

Each lifecycle task follows this pattern:

```java
@Override
public void execute(IConversationMemory memory, Object component) {
    // 1. Read from memory
    String userInput = memory.getCurrentStep().getLatestData("input").getResult();

    // 2. Perform task logic
    String processed = process(userInput);

    // 3. Write results back to memory
    IData<String> data = dataFactory.createData("output", processed);
    memory.getCurrentStep().storeData(data);
}
```

### Example: Behavior Rules Task

```java
// Reads conversation memory to check conditions
IData<List<String>> expressionsData =
    memory.getCurrentStep().getLatestData("expressions");

// If conditions match, stores actions in memory
memory.getCurrentStep().storeData(
    dataFactory.createData("actions", List.of("welcome_action"))
);
```

### Example: LangChain Task

```java
// Reads conversation history
List<IConversationStep> history = memory.getPreviousSteps().getAllSteps();

// Calls LLM with history
String llmResponse = langChainService.chat(history, currentInput);

// Stores LLM response in memory
memory.getCurrentStep().storeData(
    dataFactory.createData("llmResponse", llmResponse)
);
```

### Example: HTTP Calls Task

```java
// Reads a context entry the client sent with this turn. Each entry is stored
// as step data under "context:<name>", holding the Context ({type, value})
IData<Context> userIdContext = memory.getCurrentStep().getLatestData("context:userId");
if (userIdContext == null || userIdContext.getResult() == null || userIdContext.getResult().getValue() == null) {
    return; // no userId sent with this turn: skip the lookup rather than call /users/null
}
String userId = String.valueOf(userIdContext.getResult().getValue());

// Makes API call
JsonObject response = httpClient.get("/users/" + userId);

// Stores response for use in output templates
memory.getCurrentStep().storeData(
    dataFactory.createData("userProfile", response)
);
```

## Accessing Memory in Configurations

### In Output Templates (Qute)

```html
<!-- Access current input -->
You said: {memory.current.input}

<!-- Access previous step data -->
Previously, you mentioned: {memory.last.userPreference}

<!-- Access context data -->
Welcome, {context.userName}!

<!-- Access HTTP call response -->
The weather is: {memory.current.httpCalls.weatherResponse.temperature}

<!-- Access LLM response -->
AI says: {memory.current.output}
```

### In HTTP Call Body Templates

```json
{
  "userId": "{context.userId}",
  "message": "{memory.current.input}",
  "conversationId": "{conversationInfo.conversationId}"
}
```

### In Behavior Rule Conditions

```json
{
  "type": "contextmatcher",
  "configs": {
    "contextKey": "userName",
    "contextType": "string"
  }
}
```

## Memory Persistence

### Storage Mechanism

1. **During a turn**: the memory is a Java object on the heap, read and written by the pipeline's tasks
2. **After the turn**: it is converted to a `ConversationMemorySnapshot` and saved to the datastore — MongoDB or PostgreSQL, whichever the deployment uses. A turn that only added a step is appended; undo, redo, rerun and HITL pause/resume rewrite the document. Writes are guarded by an optimistic revision (`_rev`), so two writers cannot silently overwrite each other
3. **On the next turn**: the snapshot is loaded from the datastore again and converted back into a live memory object

### What Is Cached

**Conversation memory is not cached.** Every turn loads it from the datastore and saves it back:

```
Request → Load snapshot from datastore → Execute lifecycle → Save snapshot to datastore
```

What *is* cached is the conversation **state** (`READY`, `IN_PROGRESS`, …): `ConversationService` keeps it in a Caffeine cache named `conversationState` with a 30-second TTL, so clients polling `GET /agents/{conversationId}/status` during a running turn do not hit the database on every poll. The cache is local to each instance, and the TTL bounds how stale an entry written elsewhere can be.

### Stored Document Structure

In MongoDB the snapshot is a document in the `conversationmemories` collection; in PostgreSQL the same JSON is the `data` column (JSONB) of the `conversation_memories` table. Abridged, with the HITL bookkeeping fields omitted:

```javascript
{
  "_id": "<conversationId>",
  "_rev": 4,                     // optimistic-concurrency revision
  "_histRev": 1,                 // revision of the last write that rewrote history
  "schemaVersion": 1,
  "agentId": "agent-123",
  "agentVersion": 1,
  "userId": "user-456",
  "environment": "production",
  "conversationState": "READY",
  "conversationSteps": [
    {
      "workflows": [             // one entry per workflow of the agent
        {
          "lifecycleTasks": [    // the step's data, in the order it was stored
            { "key": "input:initial", "result": "Hello", "timestamp": "...",
              "originWorkflowId": "<workflowId>", "public": true, "committed": true },
            { "key": "expressions:parsed", "result": "greeting(hello)", "public": false, "committed": true },
            { "key": "actions", "result": ["welcome_action"], "public": true, "committed": true },
            { "key": "output:text:welcome_action", "result": { "type": "text", "text": "Hi! How can I help you?" } }
          ]
        }
      ]
    }
  ],
  "conversationOutputs": [       // one per step: what the client and the LLM history see
    { "input": "Hello", "actions": ["welcome_action"],
      "output": [ { "type": "text", "text": "Hi! How can I help you?" } ] }
  ],
  "conversationProperties": {    // Property objects, not raw values
    "userName": { "name": "userName", "valueString": "John", "scope": "conversation" }
  },
  "pendingLongTermWrites": [],
  "redoCache": []                // steps removed by undo, available for redo
}
```

Two things the shape makes visible: a step's data lives under `workflows[].lifecycleTasks[]`, keyed by memory key (prefixed keys such as `input:initial` and `output:text:<action>`), not as a flat list; and the rendered outputs are stored separately in `conversationOutputs`, which is what the conversation log and the LLM's history are built from.

## Best Practices

### 1. Use Appropriate Scopes

```java
// ❌ Don't store temporary data in conversation scope
propertyInstruction.setScope("conversation");  // This persists!

// ✅ Use step scope for temporary data
propertyInstruction.setScope("step");  // Cleaned after this step
```

### 2. Clean Up Large Data

If you store large API responses, consider cleaning them after use:

```json
{
  "postResponse": {
    "propertyInstructions": [
      {
        "name": "temperature",
        "fromObjectPath": "weatherResponse.current.temperature",
        "scope": "conversation"
      }
    ]
  }
}
```

Extract only what you need instead of storing the entire response.

### 3. Leverage History for Context

When calling LLMs, you can control how much history is sent:

```json
{
  "parameters": {
    "includeFirstAgentMessage": "true",
    "logSizeLimit": "10"
  }
}
```

### 4. Use Context for External Data

Pass data from your application via context instead of hardcoding:

```javascript
// API Request — the conversationId comes from POST /agents/{agentId}/start
POST /agents/conversation123
{
  "input": "What's my order status?",
  "context": {
    "userId": { "type": "string", "value": "user-789" },
    "sessionId": { "type": "string", "value": "session-xyz" }
  }
}
```

Then access in agent logic:

```
{context.userId}
```

## Memory Flow Example

Let's trace how memory flows through a complete conversation step:

### 1. User Request

```http
POST /agents/conv-123
{
  "input": "What's the weather in Paris?",
  "context": {
    "userId": { "type": "string", "value": "john-doe" }
  }
}
```

### 2. Memory Load and Step Preparation

The conversation already exists (`POST /agents/{agentId}/start` created it and ran its first, `CONVERSATION_START` step). For this turn:

```java
// ConversationService: load the stored snapshot, convert it to live memory
IConversationMemory memory = loadConversationMemory("conv-123");

// Conversation.say(): state READY → IN_PROGRESS, start a new step, then store
// the turn's input and context as step data
//   "input:initial"  → "What's the weather in Paris?"
//   "context:userId" → Context{type=string, value="john-doe"}
```

Context entries are **step data** (`context:<name>`), not conversation properties: they belong to the turn they arrive with. Templates read them as `{context.userId}`.

### 3. Parser Task Execution

```java
// Reads input
String input = memory.getCurrentStep().getLatestData("input").getResult();

// Parses input
List<String> expressions = parse(input);
// Result: ["question(what)", "entity(weather)", "location(paris)"]

// Stores in memory
memory.getCurrentStep().storeData(
    dataFactory.createData("expressions", expressions)
);
```

### 4. Behavior Rules Execution

```java
// Reads expressions
List<String> expressions = memory.getCurrentStep()
    .getLatestData("expressions").getResult();

// Evaluates: if expressions contains "entity(weather)" → trigger "fetch_weather"
if (matchesRule(expressions, "entity(weather)")) {
    memory.getCurrentStep().storeData(
        dataFactory.createData("actions", List.of("fetch_weather"))
    );
}
```

### 5. HTTP Call Execution

```java
// Reads action
List<String> actions = memory.getCurrentStep()
    .getLatestData("actions").getResult();

if (actions.contains("fetch_weather")) {
    // Extract location from expressions
    String location = extractLocation(expressions);  // "paris"

    // Make API call
    JsonObject weather = weatherApi.get(location);

    // Store response
    memory.getCurrentStep().storeData(
        dataFactory.createData("weatherData", weather)
    );
}
```

### 6. Output Generation

```java
// Reads weather data
JsonObject weather = memory.getCurrentStep()
    .getLatestData("weatherData").getResult();

// Applies template
String output = applyTemplate(
    "The weather in {weatherData.location} is {weatherData.description}",
    memory
);
// Result: "The weather in Paris is sunny with 22°C"

// Stores output
memory.getCurrentStep().storeData(
    dataFactory.createData("output", List.of(output))
);
```

### 7. Memory Persistence

```java
// Conversation: step-scoped properties dropped, changed longTerm properties
// upserted to the user memory store, state IN_PROGRESS → READY

// ConversationService: snapshot saved to the datastore (MongoDB or PostgreSQL)
conversationMemoryStore.storeConversationMemorySnapshot(snapshot);

// ...and the state cached for 30 seconds
cacheConversationState("conv-123", ConversationState.READY);
```

### 8. Response to User

```json
{
  "conversationState": "READY",
  "conversationOutputs": [
    {
      "input": "What's the weather in Paris?",
      "actions": ["fetch_weather"],
      "output": [
        { "type": "text", "text": "The weather in Paris is sunny with 22°C", "delay": 0 }
      ]
    }
  ]
}
```

## Advanced Topics

### Accessing Nested Data

```java
// In Java
IData<JsonObject> httpData = memory.getCurrentStep()
    .getLatestData("httpCalls.userProfile");
String userName = httpData.getResult().getString("name");

// In Qute
{memory.current.httpCalls.userProfile.name}
```

### Iterating Over History

```java
IConversationStepStack previousSteps = memory.getPreviousSteps();
for (IConversationStep step : previousSteps) {
    IData<String> inputData = step.getLatestData("input");
    if (inputData != null) {
        String pastInput = inputData.getResult();
        // Process historical input
    }
}
```

### Conditional Memory Access

```
{#if memory.current.weatherData}
  Temperature: {memory.current.weatherData.temperature}
{#else}
  N/A
{/if}
```

---

## Template Variable Reference

When tasks process templates (system prompts, HTTP call bodies, property instructions, output templates), `MemoryItemConverter.convert(memory)` produces a map with these top-level keys:

| Key | Type | Source | Example Access |
|---|---|---|---|
| `context` | `Map<String, Object>` | Input context variables set per turn | `{context.language}` |
| `properties` | `Map<String, Object>` (**raw values**) | **All conversation properties** — includes both session-scoped and `longTerm` properties loaded from persistent storage | `{properties.preferred_language}` |
| `memory` | `Map` with `current`, `last`, `past` | Conversation step data from the pipeline | `{memory.current.output}`, `{memory.last.input}` |
| `snippets` | `Map<String, Object>` | Prompt Snippets — auto-injected from `PromptSnippetService` | `{snippets.cautious_mode}` |
| `vars` | `Map<String, Object>` | Global Variables — deployment-wide config from `GlobalVariableResolver` | `{vars.default-model}` |
| `userInfo` | `Map` with `userId` | Authenticated user identity | `{userInfo.userId}` |
| `conversationInfo` | `Map` with `conversationId`, `agentId`, etc. | Conversation metadata | `{conversationInfo.agentId}` |
| `conversationLog` | `String` | Formatted conversation history | `{conversationLog}` |

> **Key insight**: `longTerm` properties are loaded into `conversationProperties` at conversation init and are immediately available via `{properties.key}` in any template. You do NOT need a separate template namespace for persistent data — properties IS the namespace.

> ⚠️ **`properties` holds raw values, not `Property` objects.** `MemoryItemConverter.convert()` inserts `ConversationProperties.toMap()`, and `toMap()` returns the unwrapped Java value (`String`, `Integer`, `Boolean`, `List`, `Map`) that was stored — the `Property` wrapper is gone by the time a template sees it. Write `{properties.preferred_language}`; `{properties.preferred_language.valueString}` resolves against a `String` and fails at render time. See [Agent Config Authoring](agent-config-authoring.md#template-syntax) for the authoritative template data model.

### When to Use Which

| Need | Use | Why |
|---|---|---|
| Data from your application | `{context.X}` | Per-request, set by caller |
| Persistent user preferences | `{properties.X}` | Survives across conversations (scope=longTerm) |
| Current turn's input/output | `{memory.current.X}` | Step-level data from the pipeline |
| Previous turn's data | `{memory.last.X}` | One step back |
| Who the user is | `{userInfo.userId}` | Authenticated identity |
| Which agent/conversation | `{conversationInfo.agentId}` | Conversation metadata |
| Full conversation history | `{conversationLog}` | Formatted string of all turns |

---

## Conversation Lifecycle: Init and Teardown

Understanding what happens at conversation boundaries is critical for features that manage persistent state.

### Initialization (`Conversation.init()`)

`init()` runs **once**, when the conversation is created by `POST /agents/{agentId}/start` (`Agent.startConversation`). It does not load anything from the conversation store — the memory is new:

```
Agent.startConversation(userId, context)
  ├─→ new ConversationMemory(agentId, agentVersion, userId)
  └─→ Conversation.init(context)
        ├─→ state = READY
        ├─→ current step gets the CONVERSATION_START action
        ├─→ loadUserProperties()
        │     └─→ IUserMemoryStore.getVisibleEntries(userId, agentId, groupIds, recallOrder, maxEntries)
        │     └─→ self + group + global entries; the most specific scope wins per key
        │     └─→ put into conversationProperties with scope=longTerm
        │     └─→ available as {properties.key} in all templates
        └─→ runs the pipeline for this first step (context included)
```

Recalled `longTerm` properties then travel inside the conversation document; later turns do not reload them from the user memory store.

### Each Turn (`Conversation.say()`)

```
ConversationService
  ├─→ load snapshot from the datastore → live memory
  └─→ Agent.continueConversation(memory) → Conversation.say(message, context)
        ├─→ EXECUTION_INTERRUPTED is auto-recovered to READY;
        │   IN_PROGRESS or AWAITING_HUMAN refuse the turn
        ├─→ state = IN_PROGRESS, start a new step
        ├─→ store input:initial and context:<name> step data
        └─→ run every workflow's lifecycle
```

### Pipeline Execution

The `LifecycleManager` of each workflow runs its configured tasks in sequence, for example:

```
LifecycleManager.executeLifecycle(memory)
  ├─→ Input Parser
  ├─→ Behavior Rules → emit actions
  ├─→ PropertySetterTask → set properties based on actions
  ├─→ ApiCallsTask → execute API calls based on actions
  ├─→ LlmTask → call LLM based on actions
  └─→ OutputGenerationTask → format response
```

### Teardown

After the pipeline, still inside `Conversation`:

```
on every exit of the turn
  ├─→ secret context values scrubbed
  ├─→ step-scoped properties dropped (unless the turn paused for HITL)
  └─→ changed longTerm keys recorded as owed (pendingLongTermWrites)

if the turn completed (not paused, cancelled or abandoned):
  postConversationLifecycleTasks()
    ├─→ record the turn's property changes (for undo/redo)
    └─→ storePropertiesPermanently()
          └─→ only longTerm properties that changed (or are owed) are upserted
              to IUserMemoryStore, with visibility applied at this boundary

state: IN_PROGRESS → READY (ENDED on CONVERSATION_END, ERROR on failure)
```

`ConversationService` then saves the snapshot to the datastore and updates the cached state. `secret` properties are not handled here: they were vaulted when their property instruction ran.

> **Key insight**: Persistent state is a **session concern** handled in `Conversation.java` init/teardown — NOT a pipeline task. If a feature needs to load/save cross-conversation state, it extends the Conversation init/teardown logic. The pipeline processes data for a single turn; session boundaries manage what persists between turns.

## Related Documentation

- [Architecture Overview](architecture.md) - Understanding the big picture
- [Properties](properties.md) - Property system, scopes, and persistence
- [Behavior Rules](behavior-rules.md) - Using memory in conditions
- [Output Templating](output-templating.md) - Accessing memory in outputs
- [HTTP Calls](httpcalls.md) - Storing API responses in memory
- [LLM Integration](langchain.md) - Using conversation history with LLMs
- [Passing Context Information](passing-context-information.md) - Injecting external data

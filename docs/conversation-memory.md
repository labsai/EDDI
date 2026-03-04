# Conversation Memory and State Management

**Version: ≥5.5.x**

## Overview

**Conversation Memory** (`IConversationMemory`) is the heart of EDDI's stateful architecture. It's a Java object that represents the complete state of a conversation, including history, user data, context, and intermediate processing results. This object is passed through the entire Lifecycle Pipeline, with each task reading from and writing to it.

## What is Conversation Memory?

Think of Conversation Memory as a **living document** that captures everything about a conversation:

- **Who**: User ID and bot ID
- **What**: All messages exchanged (both user inputs and bot outputs)
- **When**: Timestamp of each interaction
- **Context**: Data passed from external systems (user profile, session info, etc.)
- **State**: Current processing stage (READY, IN_PROGRESS, ENDED, etc.)
- **Properties**: Extracted and stored data (user preferences, entities, variables)
- **History**: Complete record of all previous conversation steps

## Key Concepts

### 1. Conversation Steps

A conversation is divided into **steps**, where each step represents one complete interaction cycle:

```
Step 1: User says "Hello" → Bot responds "Hi, how can I help?"
Step 2: User says "What's the weather?" → Bot responds "The weather is sunny, 75°F"
Step 3: ...
```

Each step contains:
- **Input**: What the user said
- **Actions**: Actions triggered by behavior rules
- **Data**: Results from lifecycle tasks (parsed expressions, API responses, LLM outputs)
- **Output**: Bot's response

### 2. Current Step vs Previous Steps

```java
IWritableConversationStep getCurrentStep();  // The step being processed right now
IConversationStepStack getPreviousSteps();    // All completed steps (history)
```

- **Current Step**: Writable, being built during lifecycle execution
- **Previous Steps**: Read-only, provides conversation history

### 3. Memory Scopes

EDDI supports different scopes for storing data:

| Scope | Lifetime | Use Case |
|-------|----------|----------|
| `step` | Single interaction | Temporary data needed only for this response |
| `conversation` | Entire conversation | User preferences, extracted entities (persists across steps) |
| `longTerm` | Across conversations | User profile data that should persist between sessions |

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
- Debugging bot behavior

## Conversation Memory Structure

### Core Properties

```java
public interface IConversationMemory {
    // Identity
    String getConversationId();
    String getBotId();
    Integer getBotVersion();
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
    READY,           // Bot is ready to process next input
    IN_PROGRESS,     // Currently processing a message
    EXECUTION_INTERRUPTED,  // Processing was interrupted
    ERROR,           // An error occurred
    ENDED            // Conversation has ended
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
// Reads context from memory for request
String userId = memory.getConversationProperties()
    .get("context.userId");

// Makes API call
JsonObject response = httpClient.get("/users/" + userId);

// Stores response for use in output templates
memory.getCurrentStep().storeData(
    dataFactory.createData("userProfile", response)
);
```

## Accessing Memory in Configurations

### In Output Templates (Thymeleaf)

```html
<!-- Access current input -->
You said: [[${memory.current.input}]]

<!-- Access previous step data -->
Previously, you mentioned: [[${memory.previous.userPreference}]]

<!-- Access context data -->
Welcome, [[${memory.current.context.userName}]]!

<!-- Access HTTP call response -->
The weather is: [[${memory.current.httpCalls.weatherResponse.temperature}]]

<!-- Access LLM response -->
AI says: [[${memory.current.llmResponse}]]
```

### In HTTP Call Body Templates

```json
{
  "userId": "[[${memory.current.context.userId}]]",
  "message": "[[${memory.current.input}]]",
  "conversationId": "[[${memory.conversationId}]]"
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

1. **During Processing**: Memory resides in Java heap (fast access)
2. **After Each Step**: Memory is serialized and saved to MongoDB
3. **On Next Request**: Memory is loaded from MongoDB and cached

### Caching Strategy

```
Request → Check Cache → If Miss: Load from MongoDB → Execute Lifecycle → Save to MongoDB + Update Cache
```

EDDI uses **Infinispan** for distributed caching:
- Fast retrieval of frequently accessed conversations
- Reduced MongoDB load
- Supports horizontal scaling across multiple EDDI instances

### MongoDB Structure

```javascript
{
  "_id": "conversationId",
  "botId": "bot-123",
  "botVersion": 1,
  "userId": "user-456",
  "conversationState": "READY",
  "conversationSteps": [
    {
      "timestamp": 1699824000000,
      "data": [
        {"key": "input", "value": "Hello"},
        {"key": "expressions", "value": ["greeting(hello)"]},
        {"key": "actions", "value": ["welcome_action"]},
        {"key": "output", "value": ["Hi! How can I help you?"]}
      ]
    },
    // ... more steps
  ],
  "conversationProperties": {
    "userName": "John",
    "userPreference": "concise"
  },
  "redoCache": []
}
```

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
    "sendConversation": "true",
    "includeFirstBotMessage": "true",
    "logSizeLimit": "10"  // Only last 10 messages
  }
}
```

### 4. Use Context for External Data

Pass data from your application via context instead of hardcoding:

```javascript
// API Request
POST /bots/prod/mybot/conversation123
{
  "input": "What's my order status?",
  "context": {
    "userId": "user-789",
    "sessionId": "session-xyz"
  }
}
```

Then access in bot logic:
```
${memory.current.context.userId}
```

## Memory Flow Example

Let's trace how memory flows through a complete conversation step:

### 1. User Request
```json
POST /bots/prod/weatherbot/conv-123
{
  "input": "What's the weather in Paris?",
  "context": {
    "userId": "john-doe"
  }
}
```

### 2. Memory Initialization
```java
IConversationMemory memory = loadOrCreateMemory("conv-123");
memory.getCurrentStep().storeData(
    dataFactory.createData("input", "What's the weather in Paris?")
);
memory.getConversationProperties().put(
    "context.userId", "john-doe"
);
```

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
    "The weather in [[${weatherData.location}]] is [[${weatherData.description}]]",
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
// Save to MongoDB
conversationMemoryStore.save(memory);

// Update cache
cache.put("conv-123", memory.getConversationState());
```

### 8. Response to User
```json
{
  "conversationState": "READY",
  "conversationOutputs": [
    {
      "output": ["The weather in Paris is sunny with 22°C"],
      "actions": ["fetch_weather"]
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

// In Thymeleaf
[[${memory.current.httpCalls.userProfile.name}]]
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

```javascript
// In Thymeleaf, check if data exists
[(${memory.current.weatherData != null ? memory.current.weatherData.temperature : 'N/A'})]
```

## Related Documentation

- [Architecture Overview](architecture.md) - Understanding the big picture
- [Behavior Rules](behavior-rules.md) - Using memory in conditions
- [Output Templating](output-templating.md) - Accessing memory in outputs
- [HTTP Calls](httpcalls.md) - Storing API responses in memory
- [LangChain Integration](langchain.md) - Using conversation history with LLMs


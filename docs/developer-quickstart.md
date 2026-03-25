# Developer Quickstart Guide

**Version: ≥5.5.x**

This guide helps developers quickly understand EDDI's architecture and start building agents.

## Understanding EDDI in 5 Minutes

### What EDDI Is

EDDI is **middleware for conversational AI**—it sits between your app and AI services (OpenAI, Claude, etc.), providing:

- **Orchestration**: Control when and how LLMs are called
- **Business Logic**: IF-THEN rules for decision-making
- **State Management**: Maintain conversation history and context
- **API Integration**: Call external REST APIs from agent logic

### Key Concept: The Lifecycle Pipeline

Every user message goes through a **pipeline of tasks**:

```
Input → Parser → Rules → API/LLM → Output
```

Each task transforms the **Conversation Memory** (a state object containing everything about the conversation).

### Agent Composition

Agents aren't code—they're **JSON configurations**:

```
Agent (list of packages)
  └─ Workflow (list of extensions)
      ├─ Behavior Rules (.behavior.json)
      ├─ HTTP Calls (.httpcalls.json)
      ├─ LangChain (.langchain.json)
      └─ Output Templates (.output.json)
```

## Quick Setup

### Prerequisites

- Java 25
- Maven 3.8.4
- MongoDB 6.0+
- Docker (optional, recommended)

### Run with Docker (Easiest)

```bash
# Clone repo
git clone https://github.com/labsai/EDDI.git
cd EDDI

# Start EDDI + MongoDB
docker-compose up

# Access dashboard
open http://localhost:7070
```

### Run from Source

```bash
# Clone repo
git clone https://github.com/labsai/EDDI.git
cd EDDI

# Start MongoDB (or use Docker)
# On Mac: brew services start mongodb-community
# On Linux: sudo systemctl start mongod

# Run EDDI in dev mode
./mvnw compile quarkus:dev

# Access dashboard
open http://localhost:7070
```

### Configuring AI Tools

If you plan to use the **Web Search** or **Weather** tools in your agents, you need to set up API keys in your environment or `application.properties`.

**Web Search (Google):**

- `eddi.tools.websearch.provider=google`
- `eddi.tools.websearch.google.api-key=...`
- `eddi.tools.websearch.google.cx=...`

**Weather (OpenWeatherMap):**

- `eddi.tools.weather.openweathermap.api-key=...`

See [LangChain Documentation](langchain.md#tool-configuration-server-side) for details.

## Your First Agent (via API)

### 1. Create a Dictionary

Dictionaries define what users can say:

```bash
curl -X POST http://localhost:7070/regulardictionarystore/regulardictionaries \
  -H "Content-Type: application/json" \
  -d '{
    "words": [
      {
        "word": "hello",
        "expressions": "greeting(hello)",
        "frequency": 0
      },
      {
        "word": "hi",
        "expressions": "greeting(hi)",
        "frequency": 0
      }
    ],
    "phrases": []
  }'
```

**Response**: Dictionary ID (e.g., `eddi://ai.labs.parser.dictionaries.regular/regulardictionarystore/regulardictionaries/abc123?version=1`)

### 2. Create Behavior Rules

Rules define what the agent does:

```bash
curl -X POST http://localhost:7070/behaviorstore/behaviorsets \
  -H "Content-Type: application/json" \
  -d '{
    "behaviorGroups": [
      {
        "name": "Greetings",
        "behaviorRules": [
          {
            "name": "Welcome",
            "conditions": [
              {
                "type": "inputmatcher",
                "configs": {
                  "expressions": "greeting(*)",
                  "occurrence": "currentStep"
                }
              }
            ],
            "actions": ["welcome_action"]
          }
        ]
      }
    ]
  }'
```

**Response**: Behavior set ID

### 3. Create Output Templates

```bash
curl -X POST http://localhost:7070/outputstore/outputsets \
  -H "Content-Type: application/json" \
  -d '{
    "outputSet": [
      {
        "action": "welcome_action",
        "timesOccurred": 0,
        "outputs": [
          {
            "valueAlternatives": [
              "Hello! How can I help you today?"
            ]
          }
        ]
      }
    ]
  }'
```

**Response**: Output set ID

### 4. Create a Workflow

Workflows bundle extensions together:

```bash
curl -X POST http://localhost:7070/packagestore/packages \
  -H "Content-Type: application/json" \
  -d '{
    "packageExtensions": [
      {
        "type": "eddi://ai.labs.parser.dictionaries.regular",
        "extensions": {
          "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/abc123?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.behavior",
        "extensions": {
          "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/def456?version=1"
        },
        "config": {
          "appendActions": true
        }
      },
      {
        "type": "eddi://ai.labs.output",
        "extensions": {
          "uri": "eddi://ai.labs.output/outputstore/outputsets/ghi789?version=1"
        }
      }
    ]
  }'
```

**Response**: Workflow ID

### 5. Create a Agent

```bash
curl -X POST http://localhost:7070/agentstore/agents \
  -H "Content-Type: application/json" \
  -d '{
    "packages": [
      "eddi://ai.labs.package/packagestore/packages/xyz123?version=1"
    ]
  }'
```

**Response**: Agent ID (e.g., `agent-abc-123`)

### 6. Deploy the Agent

```bash
curl -X POST "http://localhost:7070/administration/production/deploy/agent-abc-123?version=1"
```

### 7. Chat with Your Agent

```bash
# Start conversation
curl -X POST http://localhost:7070/agents/agent-abc-123/start \
  -H "Content-Type: application/json" \
  -d '{"input": "hello"}'

# Response includes conversationId
# {
#   "conversationId": "conv-123",
#   "conversationState": "READY",
#   "conversationOutputs": [
#     {"output": ["Hello! How can I help you today?"]}
#   ]
# }

# Continue conversation
curl -X POST http://localhost:7070/agents/conv-123 \
  -H "Content-Type: application/json" \
  -d '{"input": "hi"}'
```

## Adding an LLM (OpenAI Example)

### 1. Create LangChain Configuration

```bash
curl -X POST http://localhost:7070/langchainstore/langchains \
  -H "Content-Type: application/json" \
  -d '{
    "tasks": [
      {
        "actions": ["send_to_ai"],
        "id": "openai_chat",
        "type": "openai",
        "description": "OpenAI ChatGPT integration",
        "parameters": {
          "apiKey": "your-openai-api-key",
          "modelName": "gpt-4o",
          "temperature": "0.7",
          "systemMessage": "You are a helpful assistant",
          "sendConversation": "true",
          "addToOutput": "true"
        }
      }
    ]
  }'
```

### 2. Add LangChain to Workflow

Add this extension to your package:

```json
{
  "type": "eddi://ai.labs.llm",
  "extensions": {
    "uri": "eddi://ai.labs.llm/langchainstore/langchains/langchain-id?version=1"
  }
}
```

### 3. Create Behavior Rule to Trigger LLM

```json
{
  "name": "Ask AI",
  "conditions": [
    {
      "type": "inputmatcher",
      "configs": {
        "expressions": "question(*)",
        "occurrence": "currentStep"
      }
    }
  ],
  "actions": ["send_to_ai"]
}
```

Now when users ask questions, the LLM is automatically called!

## Understanding the Flow

Let's trace what happens when a user says "hello":

### 1. API Request

```json
POST /agents/agent-abc-123/start
{"input": "hello"}
```

### 2. RestAgentEngine

- Validates agent ID
- Creates/loads conversation memory
- Submits to ConversationCoordinator

### 3. ConversationCoordinator

- Ensures sequential processing (no race conditions)
- Queues message for this conversation

### 4. LifecycleManager Executes Pipeline

**Parser Task**:

```
Input: "hello"
→ Parses using dictionary
→ Output: expressions = ["greeting(hello)"]
→ Stores in memory
```

**Behavior Rules Task**:

```
Reads: expressions = ["greeting(hello)"]
→ Evaluates rules
→ Rule matches: "if greeting(*) then welcome_action"
→ Output: actions = ["welcome_action"]
→ Stores in memory
```

**Output Task**:

```
Reads: actions = ["welcome_action"]
→ Looks up output template for "welcome_action"
→ Output: "Hello! How can I help you today?"
→ Stores in memory
```

### 5. Save & Return

- Memory saved to MongoDB
- Response returned to user

## Key Architectural Components

### IConversationMemory

The state object passed through the pipeline:

```java
IConversationMemory memory = ...;

// Read user input
String input = memory.getCurrentStep().getLatestData("input").getResult();

// Store parsed data
memory.getCurrentStep().storeData(
    dataFactory.createData("expressions", expressions)
);

// Access conversation properties
String userName = memory.getConversationProperties().get("userName");
```

### ILifecycleTask

Interface all tasks implement:

```java
public class MyTask implements ILifecycleTask {
    @Override
    public void execute(IConversationMemory memory, Object component) {
        // 1. Read from memory
        String input = memory.getCurrentStep().getLatestData("input").getResult();

        // 2. Process
        String result = process(input);

        // 3. Write to memory
        memory.getCurrentStep().storeData(
            dataFactory.createData("myResult", result)
        );
    }
}
```

### ConversationCoordinator

Ensures messages are processed in order:

```java
// Messages for same conversation execute sequentially
coordinator.submitInOrder(conversationId, () -> {
    processMessage(memory, input);
    return null;
});
```

## Common Patterns

### Pattern 1: Conditional LLM Invocation

Only call LLM for complex queries:

```json
{
  "behaviorRules": [
    {
      "name": "Simple Greeting",
      "conditions": [
        { "type": "inputmatcher", "configs": { "expressions": "greeting(*)" } }
      ],
      "actions": ["simple_greeting"]
    },
    {
      "name": "Complex Question",
      "conditions": [
        { "type": "inputmatcher", "configs": { "expressions": "question(*)" } }
      ],
      "actions": ["send_to_ai"]
    }
  ]
}
```

### Pattern 2: API Call Before LLM

Fetch data, then ask LLM to format it:

```json
{
  "behaviorRules": [
    {
      "name": "Weather Query",
      "conditions": [
        {
          "type": "inputmatcher",
          "configs": { "expressions": "entity(weather)" }
        }
      ],
      "actions": ["httpcall(weather-api)", "send_to_ai"]
    }
  ]
}
```

The LLM receives the API response in memory and can format it naturally.

### Pattern 3: Context-Aware Responses

Use context passed from your app:

```bash
curl -X POST http://localhost:7070/agents/agent-abc-123/start \
  -d '{
    "input": "What is my name?",
    "context": {
      "userName": "John",
      "userId": "user-123"
    }
  }'
```

Access in output template:

```
Hello [[${memory.current.context.userName}]]!
```

## Next Steps

### Learn More

- **[Architecture Overview](architecture.md)** - Deep dive into design
- **[Behavior Rules](behavior-rules.md)** - Master decision logic
- **[HTTP Calls](httpcalls.md)** - Integrate external APIs
- **[LangChain Integration](langchain.md)** - Configure LLMs
- **[Agent Father Deep Dive](agent-father-deep-dive.md)** - Real-world example

### Use the Dashboard

Visit `http://localhost:7070` to:

- Create agents visually
- Test conversations interactively
- Browse configurations
- Monitor deployments

### Explore Examples

Check the `examples/` folder for:

- Weather agent (API integration)
- Support agent (multi-turn conversations)
- E-commerce agent (context management)

### Build Your Own Task

Create a custom lifecycle task:

```java
@ApplicationScoped
public class MyCustomTask implements ILifecycleTask {
    @Override
    public String getId() {
        return "ai.labs.mycompany.customtask";
    }

    @Override
    public String getType() {
        return "custom_processing";
    }

    @Override
    public void execute(IConversationMemory memory, Object component) {
        // Your logic here
    }
}
```

Register it in CDI and it becomes available as an extension!

## Troubleshooting

### Agent doesn't respond

1. Check deployment status: `GET /administration/deploy/{agentId}`
2. Check conversation state: `GET /conversationstore/conversations/{conversationId}`
3. Check logs for errors

### Rules not matching

- Verify dictionary expressions match your input
- Check rule conditions are correct
- Use `occurrence: "anyStep"` to match across conversation

### LLM not being called

- Ensure behavior rule triggers the LLM action
- Check LangChain configuration is in the package
- Verify API key is correct

### Memory not persisting

- Ensure MongoDB is running
- Check connection string in config
- Use correct scope (`conversation` not `step`)

## Getting Help

- **Documentation**: https://docs.labs.ai
- **GitHub**: https://github.com/labsai/EDDI
- **Issues**: https://github.com/labsai/EDDI/issues

## Summary

EDDI's power comes from its **configurable pipeline architecture**:

- Agents are JSON configurations, not code
- Everything flows through Conversation Memory
- Tasks are pluggable and reusable
- LLMs are orchestrated, not just proxied

Start simple, then add complexity as needed. The architecture scales from basic chatagents to sophisticated multi-API workflows.

# Developer Quickstart Guide

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

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
Agent (list of workflows)
  └─ Workflow (ordered list of pipeline steps)
      ├─ Parser          — input → expressions
      ├─ Behavior Rules  — expressions → actions
      ├─ API Calls / MCP — actions → outbound calls
      ├─ LLM             — actions → model turn
      ├─ Output          — actions → user-visible messages
      └─ Templating      — resolves {…} in the output
```

Each step points at a stored configuration document by `eddi://` URI, so the
same rule set or output set can be reused across workflows and agents.

## Quick Setup

### Prerequisites

- **Java 25** (the version in `pom.xml` is authoritative)
- **Maven** — not needed separately; the repo ships the `./mvnw` wrapper
  (`.\mvnw.cmd` on Windows)
- **MongoDB 7** (or PostgreSQL — see [Architecture](architecture.md))
- **Docker** — optional for running from source, required for the quick start
  below

### Run with Docker (Easiest)

```bash
# Clone repo
git clone https://github.com/labsai/EDDI.git
cd EDDI

# Start EDDI + MongoDB (pin a version, or omit to get :latest)
EDDI_VERSION=6.3.0 docker compose up -d

# Access dashboard
open http://localhost:7070/manage
```

Optional overlays stack on top of the base file — for example, a local LLM on
the same Docker network:

```bash
docker compose -f docker-compose.yml -f docker-compose.ollama.yml up -d
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

> **💡 Secrets Vault:** If you plan to store API keys through the Manager UI or use `${vault:...}` references, set the vault master key first:
>
> ```bash
> export EDDI_VAULT_MASTER_KEY=my-dev-passphrase   # Linux/macOS
> $env:EDDI_VAULT_MASTER_KEY = "my-dev-passphrase"  # Windows PowerShell
> ```
>
> Without this, the vault is disabled and secret endpoints return HTTP 503. Any passphrase works for local dev. See [Secrets Vault](secrets-vault.md) for full details.

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

The walkthrough below is a complete, copy-pasteable session against a stock
`docker compose up -d` instance on `http://localhost:7070`. Every request and
every response shape here is the one the running server actually produces.

Three things to know before you start, because they surprise everybody once:

1. **A successful create returns `201` with an empty body.** The id is in the
   `Location` (and `X-Resource-URI`) header, as an `eddi://` URI — not an HTTP
   URL you can fetch. Take the last path segment as the id:
   `eddi://ai.labs.dictionary/dictionarystore/dictionaries/68a1…?version=1`
   → id `68a1…`, version `1`.
2. **Every store has a `descriptors` listing** — `GET /<store>/<resource>/descriptors`.
   That is how you find what you created. There is also a cross-type listing,
   `GET /descriptorstore/descriptors?type=<type>`, which is handy when you want
   everything of one kind at once.
3. **Unknown fields are rejected, not ignored.** A `400` naming the offending
   key means the payload used an older field name — the message lists the legal
   ones. This is deliberate: a silently dropped key looks like a successful save
   and changes nothing.

`jq` is used below only to pretty-print; it is not required.

```bash
EDDI=http://localhost:7070
```

### 1. Create a Dictionary

Dictionaries map what users type to **expressions**, which behavior rules then
match on:

```bash
curl -i -X POST $EDDI/dictionarystore/dictionaries \
  -H "Content-Type: application/json" \
  -d '{
    "words": [
      { "word": "hello", "expressions": "greeting(hello)", "frequency": 0 },
      { "word": "hi",    "expressions": "greeting(hi)",    "frequency": 0 }
    ],
    "phrases": []
  }'
```

**Response**: `201 Created`, with

```text
Location: eddi://ai.labs.dictionary/dictionarystore/dictionaries/<DICTIONARY_ID>?version=1
```

Keep `<DICTIONARY_ID>`. To list every dictionary, or read one back:

```bash
curl -s "$EDDI/dictionarystore/dictionaries/descriptors?limit=100" | jq
curl -s "$EDDI/dictionarystore/dictionaries/<DICTIONARY_ID>?version=1" | jq
```

> **Naming a resource.** A resource created over the API has an empty name, so
> the Manager UI lists it as "Unnamed …". Names live on the *descriptor*, not on
> the configuration document — set one with a `PATCH`, and the same call works
> for every resource type in this guide:
>
> ```bash
> curl -X PATCH "$EDDI/descriptorstore/descriptors/<ANY_RESOURCE_ID>?version=1" \
>   -H "Content-Type: application/json" \
>   -d '{ "operation": "SET",
>         "document": { "name": "Greeting dictionary", "description": "hello / hi" } }'
> ```

### 2. Create Behavior Rules

Rules decide which **actions** a turn emits:

```bash
curl -i -X POST $EDDI/rulestore/rulesets \
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

**Response**: `201`, `Location: eddi://ai.labs.rules/rulestore/rulesets/<RULESET_ID>?version=1`

```bash
curl -s "$EDDI/rulestore/rulesets/descriptors?limit=100" | jq
```

> `behaviorRules` is the canonical name: it is what a read returns, and what the
> shipped reference config and the ZIP fixtures use. `rules` is still accepted on
> write for older clients. Before 6.4.0 a read answered `rules` regardless of what
> you posted, which is why a rule set created over the API showed up empty in the
> Manager.

### 3. Create Output Templates

Output maps an action to what the user sees. **`valueAlternatives` is a list of
typed output items, not a list of strings** — a bare string is rejected:

```bash
curl -i -X POST $EDDI/outputstore/outputsets \
  -H "Content-Type: application/json" \
  -d '{
    "outputSet": [
      {
        "action": "welcome_action",
        "timesOccurred": 0,
        "outputs": [
          {
            "valueAlternatives": [
              { "type": "text", "text": "Hello! How can I help you today?" }
            ]
          }
        ]
      }
    ]
  }'
```

**Response**: `201`, `Location: eddi://ai.labs.output/outputstore/outputsets/<OUTPUT_ID>?version=1`

Besides `text`, an output item may be `image`, `quickReply`, `inputField`,
`applicationLink`, `button`, `agentFace` or `other`. See
[Output Configuration](output-configuration.md).

### 4. Create a Workflow

A workflow is the ordered list of pipeline steps — the **`workflowSteps`** array.
Each step names a lifecycle task by `eddi://` URI; a step that needs a
configuration document points at it through `config.uri`, and the parser takes
its dictionaries through `extensions` instead:

```bash
curl -i -X POST $EDDI/workflowstore/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "workflowSteps": [
      {
        "type": "eddi://ai.labs.parser",
        "config": {},
        "extensions": {
          "dictionaries": [
            {
              "type": "eddi://ai.labs.parser.dictionaries.regular",
              "config": {
                "uri": "eddi://ai.labs.dictionary/dictionarystore/dictionaries/<DICTIONARY_ID>?version=1"
              }
            }
          ],
          "corrections": []
        }
      },
      {
        "type": "eddi://ai.labs.rules",
        "config": {
          "uri": "eddi://ai.labs.rules/rulestore/rulesets/<RULESET_ID>?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.output",
        "config": {
          "uri": "eddi://ai.labs.output/outputstore/outputsets/<OUTPUT_ID>?version=1"
        }
      },
      { "type": "eddi://ai.labs.templating", "config": {} }
    ]
  }'
```

**Response**: `201`, `Location: eddi://ai.labs.workflow/workflowstore/workflows/<WORKFLOW_ID>?version=1`

Step order is execution order. The available step types:

| Step `type`                | Purpose                        | Configuration            |
| -------------------------- | ------------------------------ | ------------------------ |
| `eddi://ai.labs.parser`    | Input → expressions            | dictionaries/corrections via `extensions` |
| `eddi://ai.labs.rules`     | Behavior rules → actions       | `config.uri` → rule set  |
| `eddi://ai.labs.property`  | Slot-filling / properties      | `config.uri` → property setter |
| `eddi://ai.labs.apicalls`  | Outbound HTTP calls            | `config.uri` → API calls |
| `eddi://ai.labs.mcpcalls`  | MCP tool calls                 | `config.uri` → MCP calls |
| `eddi://ai.labs.llm`       | LLM interaction                | `config.uri` → LLM config |
| `eddi://ai.labs.output`    | Actions → user-visible output  | `config.uri` → output set |
| `eddi://ai.labs.templating`| Resolves `{…}` in the output   | none                     |

> **Add `eddi://ai.labs.templating` last** whenever an output or system prompt
> contains `{properties.x}`-style placeholders. Without it the expressions are
> never resolved. It is harmless when there is nothing to resolve, so the
> examples keep it.
>
> `eddi://ai.labs.behavior` and `eddi://ai.labs.httpcalls` are accepted as
> aliases of `ai.labs.rules` and `ai.labs.apicalls`; new configurations should
> use the names in the table.

### 5. Create an Agent

An agent is a list of workflows — the field is **`workflows`**:

```bash
curl -i -X POST $EDDI/agentstore/agents \
  -H "Content-Type: application/json" \
  -d '{
    "workflows": [
      "eddi://ai.labs.workflow/workflowstore/workflows/<WORKFLOW_ID>?version=1"
    ]
  }'
```

**Response**: `201`, `Location: eddi://ai.labs.agent/agentstore/agents/<AGENT_ID>?version=1`

Give it a name, or the Manager will list it as "Unnamed Agent":

```bash
curl -X PATCH "$EDDI/descriptorstore/descriptors/<AGENT_ID>?version=1" \
  -H "Content-Type: application/json" \
  -d '{ "operation": "SET",
        "document": { "name": "Greeter", "description": "Says hello" } }'
```

> `packages` is still accepted as an alias for `workflows`, so a payload using
> the old name is stored rather than rejected. It is deprecated — write
> `workflows`.

### 6. Deploy the Agent

An agent must be deployed to an environment before it can hold a conversation.
Pass `waitForCompletion=true` and the call returns the *final* status instead of
a bare `202 Accepted`:

```bash
curl -s -X POST \
  "$EDDI/administration/production/deploy/<AGENT_ID>?version=1&waitForCompletion=true" | jq
```

```json
{
  "status": "READY",
  "agentId": "<AGENT_ID>",
  "version": 1,
  "environment": "production"
}
```

Any status other than `READY` — typically `ERROR` — means the agent's workflow
references something that does not resolve. Check the server log, then
re-deploy. The status can be re-read at any time:

```bash
curl -s "$EDDI/administration/production/deploymentstatus/<AGENT_ID>?version=1" | jq
```

### 7. Chat with Your Agent

Starting a conversation and sending a message are **two separate calls**. The
start call takes no message body; it creates the conversation and returns its id
in the `Location` header:

```bash
curl -i -X POST \
  "$EDDI/agents/<AGENT_ID>/start?environment=production&userId=test-user"
```

```text
HTTP/1.1 201 Created
Location: eddi://ai.labs.conversation/conversationstore/conversations/<CONVERSATION_ID>
```

Then talk to the **conversation**, not the agent. Plain text is the simplest
form:

```bash
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>" \
  -H "Content-Type: text/plain" \
  --data 'hello' | jq
```

```json
{
  "conversationId": "<CONVERSATION_ID>",
  "agentId": "<AGENT_ID>",
  "agentVersion": 1,
  "userId": "test-user",
  "environment": "production",
  "conversationState": "READY",
  "conversationOutputs": [
    {
      "actions": ["welcome_action"],
      "output": [
        { "type": "text", "text": "Hello! How can I help you today?", "delay": 0 }
      ]
    }
  ],
  "conversationProperties": {},
  "conversationSteps": []
}
```

The same endpoint accepts JSON when you want to pass context alongside the
message:

```bash
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>" \
  -H "Content-Type: application/json" \
  -d '{ "input": "hello",
        "context": { "language": { "type": "string", "value": "en" } } }' | jq
```

To start a conversation *with* an initial context, POST that same context map —
and only a context map — to the start endpoint:

```bash
curl -i -X POST "$EDDI/agents/<AGENT_ID>/start?environment=production&userId=test-user" \
  -H "Content-Type: application/json" \
  -d '{ "userName": { "type": "string", "value": "Ada" } }'
```

## Adding an LLM (Ollama Example)

Ollama is used here because it needs no API key. Everything below is identical
for the other providers except `type` and the credential — see
[LLM Integration](langchain.md) for the full list.

If EDDI runs in Docker and Ollama runs on your host, `localhost` inside the
container is the container. Either use `http://host.docker.internal:11434`, or
run Ollama as a compose service with
`docker compose -f docker-compose.yml -f docker-compose.ollama.yml up -d`, which
puts it on the same network as `http://ollama:11434`.

### 1. Create the LLM Configuration

```bash
curl -i -X POST $EDDI/llmstore/llms \
  -H "Content-Type: application/json" \
  -d '{
    "tasks": [
      {
        "id": "assistant",
        "type": "ollama",
        "description": "Local Ollama chat",
        "actions": ["send_to_ai"],
        "parameters": {
          "baseUrl": "http://host.docker.internal:11434",
          "model": "llama3.2:3b",
          "temperature": "0.7",
          "systemMessage": "You are a helpful assistant.",
          "addToOutput": "true"
        }
      }
    ]
  }'
```

**Response**: `201`, `Location: eddi://ai.labs.llm/llmstore/llms/<LLM_ID>?version=1`

> `parameters` is a free-form map, but a key no provider reads is logged as a
> warning at build time rather than applied. `systemMessage` and `addToOutput`
> are read by the pipeline; `model`, `baseUrl`, `temperature`, `maxTokens`,
> `topP` and `topK` are read by the Ollama builder.

For a hosted provider, put the credential in the vault rather than in the
configuration document:

```json
"parameters": { "apiKey": "${vault:openai-key}", "modelName": "gpt-4o" }
```

### 2. Add the LLM Step to the Workflow

Insert an `eddi://ai.labs.llm` step into `workflowSteps`, **before**
`eddi://ai.labs.output`:

```json
{
  "type": "eddi://ai.labs.llm",
  "config": {
    "uri": "eddi://ai.labs.llm/llmstore/llms/<LLM_ID>?version=1"
  }
}
```

Update the workflow in place (this creates version 2):

```bash
curl -i -X PUT "$EDDI/workflowstore/workflows/<WORKFLOW_ID>?version=1" \
  -H "Content-Type: application/json" \
  -d '{ "workflowSteps": [ /* … the full list, with the llm step added … */ ] }'
```

Then point the agent at the new workflow version and deploy again — a deployed
agent version is immutable, so a config change always means a re-deploy.

### 3. Trigger the LLM from a Behavior Rule

The LLM task runs when an action it listens for is emitted. Add a rule whose
`actions` contains `send_to_ai`:

```json
{
  "name": "Ask AI",
  "conditions": [
    {
      "type": "inputmatcher",
      "configs": { "expressions": "unknown(*)", "occurrence": "currentStep" }
    }
  ],
  "actions": ["send_to_ai"]
}
```

Now anything the dictionary does not recognise is handed to the model, while
`greeting(*)` still takes the cheap rule-based path.

## Understanding the Flow

Let's trace what happens when a user says "hello":

### 1. API Request

```text
POST /agents/{agentId}/start?environment=production   → 201, conversation id in Location
POST /agents/{conversationId}   (text/plain)  "hello" → the turn below
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

The start endpoint's JSON body **is** the context map — there is no `input`
field on it, because starting a conversation and sending a message are separate
calls:

```bash
# 1. Start with initial context
curl -i -X POST "http://localhost:7070/agents/<AGENT_ID>/start?environment=production" \
  -H "Content-Type: application/json" \
  -d '{
    "userName": { "type": "string", "value": "John" },
    "userId":   { "type": "string", "value": "user-123" }
  }'

# 2. Send the message (context may also be supplied per turn)
curl -s -X POST "http://localhost:7070/agents/<CONVERSATION_ID>" \
  -H "Content-Type: application/json" \
  -d '{ "input": "What is my name?" }'
```

Access in an output template — and remember the `eddi://ai.labs.templating`
step, or the placeholder is never resolved:

```text
Hello {context.userName}!
```

## Next Steps

### Learn More

- **[Architecture Overview](architecture.md)** - Deep dive into design
- **[Behavior Rules](behavior-rules.md)** - Master decision logic
- **[HTTP Calls](httpcalls.md)** - Integrate external APIs
- **[LLM Integration](langchain.md)** - Configure LLMs (12 providers)
- **[Output Configuration](output-configuration.md)** - Message and quick-reply types
- **[Human-in-the-Loop](hitl.md)** - Gate an agent's writes on human approval

### Use the Dashboard

Visit `http://localhost:7070/manage` to:

- Create agents visually
- Test conversations interactively
- Browse configurations
- Monitor deployments

### Explore a Worked Configuration

`docs/agent-configs/rule-based-reference/` is a complete, working rule-based
agent — a conversational wizard that provisions another agent over EDDI's own
REST API. It is the canonical reference for behavior-rule patterns, property
setters capturing free text, HTTP call templates and quick replies. Two unit
tests sweep it, so the config documents it supplies keep parsing and saving —
note that descriptors and unmapped filenames are counted as skipped rather than
checked, so a green sweep is not a claim about every file in the directory.

### Build Your Own Task

Create a custom lifecycle task:

```java
@ApplicationScoped
public class MyCustomTask implements ILifecycleTask {
    @Override
    public TaskId getId() {
        return new TaskId("ai.labs.mycompany.customtask");
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

### A request failed and I have no idea why

- `400` with a message naming a field — the payload used a key the model does
  not declare, or put the wrong kind of value in one that it does. The message
  names the JSON path and the legal fields.
- `404` on deploy — the agent id or version does not exist.
- `201` but nothing appears in the Manager — the resource was created with an
  empty name. Set one via `PATCH /descriptorstore/descriptors/{id}?version=1`.

### Agent doesn't respond

1. Deployment status:
   `GET /administration/production/deploymentstatus/{agentId}?version=1`
   — anything other than `READY` means the workflow did not load.
2. Conversation state: `GET /agents/{conversationId}/status`
3. Full memory snapshot (what each pipeline step actually produced):
   `GET /agents/{conversationId}?returnDetailed=true`
4. Server log: `docker compose logs -f eddi`, or `GET /administration/logs`

### Rules not matching

- Confirm the parser produced the expression you are matching on — the
  `expressions:parsed` entry of the detailed snapshot above shows exactly what
  it emitted, and `behavior_rules:success` / `behavior_rules:fail` show which
  rules matched.
- The dictionary has to be wired into the **parser step's** `extensions`, not
  referenced as a step of its own.
- `actionmatcher` with a comma-separated list means AND (a contiguous sublist),
  not OR. Use a `connector` with `"operator": "OR"` for alternatives.
- `occurrence: "anyStep"` matches anywhere in the conversation.

### LLM not being called

- The behavior rule has to emit the action the LLM task lists in its `actions`.
- The `eddi://ai.labs.llm` step has to be in the **workflow the deployed agent
  version points at** — adding it to a newer workflow version does nothing until
  the agent is re-pointed and re-deployed.
- Check the credential resolves. A `${vault:...}` reference needs
  `EDDI_VAULT_MASTER_KEY` set, or the vault is disabled.

### Ollama-backed agent times out or answers nothing

- From inside the `eddi` container, `localhost` is the container. Use
  `http://host.docker.internal:11434`, or the `docker-compose.ollama.yml`
  overlay and `http://ollama:11434`.
- Reasoning models (gemma3n, deepseek-r1, qwen3 …) think before answering, and
  the reasoning is not part of the streamed content — the window stays silent
  for as long as that takes. Set `"think": "false"` in the task's `parameters`
  to turn it off, or `"returnThinking": "true"` to surface it.

### Memory not persisting

- Ensure MongoDB is running and the connection string is right.
- `scope: "step"` is cleared at the end of the turn, `conversation` lives for
  the session, `longTerm` survives across conversations.

## Getting Help

- **Documentation**: https://github.com/labsai/EDDI/tree/main/docs
- **GitHub**: https://github.com/labsai/EDDI
- **Issues**: https://github.com/labsai/EDDI/issues

## Summary

EDDI's power comes from its **configurable pipeline architecture**:

- Agents are JSON configurations, not code
- Everything flows through Conversation Memory
- Tasks are pluggable and reusable
- LLMs are orchestrated, not just proxied

Start simple, then add complexity as needed. The architecture scales from basic agents to sophisticated multi-API workflows.

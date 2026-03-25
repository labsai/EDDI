# The Agent Father: A Deep Dive

**Version: ≥5.5.x**

## Overview

The **Agent Father** is EDDI's meta-agent—an agent that creates other agents. It's the perfect example of EDDI's architecture in action, demonstrating how conversation flow, behavior rules, property extraction, and HTTP calls work together to build sophisticated workflows.

More importantly, it shows EDDI's unique capability: **the same architecture that powers simple agents can orchestrate complex, multi-step processes**, even self-modifying the system itself.

## What Makes Agent Father Special?

### It's Not Special Code

Agent Father is **not** a special feature or custom module. It's a **regular EDDI agent** built using the standard components:

- Behavior Rules (to control conversation flow)
- Property Extraction (to gather user input)
- HTTP Calls (to invoke EDDI's own API)
- Output Templates (to guide users)

### It Demonstrates Self-Modification

Agent Father uses EDDI's REST API to create new agents, packages, dictionaries, and configurations. This is possible because EDDI's API is designed to be **programmable**—you can automate agent creation just like any other API integration.

### It's a Conversational Wizard

Instead of requiring users to understand JSON configurations or API calls, Agent Father provides a **conversational interface** that:

1. Asks questions in natural language
2. Validates and stores answers
3. Builds complete agent configurations
4. Creates the agent via API
5. Returns the agent ID for deployment

## Architecture of Agent Father

### Agent Composition

Agent Father is composed of multiple packages:

```
Agent Father (.agent.json)
  ├─ Workflow 1: Core Conversation Flow
  │   ├─ Behavior Rules: Question sequencing
  │   ├─ Output Templates: Questions and responses
  │   └─ Properties: Store user answers
  │
  ├─ Workflow 2: Agent Creation Logic
  │   ├─ Behavior Rules: Trigger API calls when data is ready
  │   ├─ HTTP Calls: POST to /agentstore/agents
  │   └─ Properties: Extract agent ID from response
  │
  ├─ Workflow 3: Workflow Creation Logic
  │   ├─ HTTP Calls: POST to /packagestore/packages
  │   └─ Properties: Store package references
  │
  ├─ Workflow 4: Dictionary Creation Logic
  │   └─ HTTP Calls: POST to /regulardictionarystore/regulardictionaries
  │
  └─ Workflow 5: LangChain Configuration
      └─ HTTP Calls: POST to /langchainstore/langchains
```

## Step-by-Step Flow

Let's walk through how Agent Father creates a new agent:

### Step 1: Conversation Start

**User**: Starts conversation with Agent Father

**Agent Father**: (via Output Template)

```
"Welcome! I'll help you create a new agent. What would you like to call your agent?"
```

**Behavior Rule**:

```json
{
  "name": "Greeting",
  "conditions": [
    {
      "type": "occurrence",
      "configs": {
        "maxTimesOccurred": "0",
        "behaviorRuleName": "Greeting"
      }
    }
  ],
  "actions": ["greet_user"]
}
```

_(Triggers only on first step)_

### Step 2: Capture Agent Name

**User**: "My Weather Agent"

**Property Setter**: (from property extension)

```json
{
  "name": "agentName",
  "valueExtraction": "input",
  "scope": "conversation"
}
```

**Result**: Stores "My Weather Agent" in conversation memory:

```java
memory.getConversationProperties().put("context.agentName", "My Weather Agent");
```

**Agent Father**: "Great! What should your agent do? Describe its purpose."

### Step 3: Capture Agent Description

**User**: "It should tell users the current weather"

**Property Setter**:

```json
{
  "name": "agentDescription",
  "valueExtraction": "input",
  "scope": "conversation"
}
```

**Agent Father**: "Which AI provider would you like to use? (OpenAI, Claude, Gemini, or None)"

### Step 4: Capture LLM Choice

**User**: "OpenAI"

**Property Setter**:

```json
{
  "name": "llmProvider",
  "valueExtraction": "input",
  "scope": "conversation"
}
```

**Agent Father**: "Please provide your OpenAI API key."

### Step 5: Capture API Key

**User**: "sk-..."

**Property Setter**:

```json
{
  "name": "apiKey",
  "valueExtraction": "input",
  "scope": "conversation"
}
```

### Step 6: Trigger Agent Creation

Now all required data is collected. A Behavior Rule monitors the memory:

```json
{
  "name": "Create Agent When Ready",
  "conditions": [
    {
      "type": "contextmatcher",
      "configs": {
        "contextKey": "agentName",
        "contextType": "string"
      }
    },
    {
      "type": "contextmatcher",
      "configs": {
        "contextKey": "agentDescription",
        "contextType": "string"
      }
    },
    {
      "type": "contextmatcher",
      "configs": {
        "contextKey": "llmProvider",
        "contextType": "string"
      }
    },
    {
      "type": "contextmatcher",
      "configs": {
        "contextKey": "apiKey",
        "contextType": "string"
      }
    }
  ],
  "actions": ["httpcall(create-agent)"]
}
```

**Explanation**:

- This rule checks if all required data exists in memory
- When all conditions are met, it triggers the `httpcall(create-agent)` action
- This demonstrates **conditional API execution** based on conversation state

### Step 7: Execute HTTP Call to Create Agent

The `create-agent` HTTP call is defined in an HTTP Calls extension:

```json
{
  "targetServerUrl": "http://localhost:7070",
  "httpCalls": [
    {
      "name": "create-agent",
      "saveResponse": true,
      "responseObjectName": "newAgentResponse",
      "actions": ["httpcall(create-agent)"],
      "request": {
        "method": "POST",
        "path": "/agentstore/agents",
        "headers": {
          "Content-Type": "application/json"
        },
        "body": "{\"packages\": []}"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "newAgentId",
            "fromObjectPath": "newAgentResponse.id",
            "scope": "conversation"
          }
        ]
      }
    }
  ]
}
```

**What Happens**:

1. **Request**: POST to `http://localhost:7070/agentstore/agents`
2. **Body**: Empty agent configuration (packages added later)
3. **Response**:
   ```json
   {
     "id": "673f1a2b4c5d6e7f8a9b0c1d",
     "version": 1,
     "packages": []
   }
   ```
4. **Property Extraction**: Saves agent ID to `context.newAgentId`

### Step 8: Create Workflow with LangChain Configuration

Another HTTP call creates a package:

```json
{
  "name": "create-package",
  "actions": ["httpcall(create-package)"],
  "request": {
    "method": "POST",
    "path": "/packagestore/packages",
    "body": "{\"packageExtensions\": [{\"type\": \"eddi://ai.labs.llm\", \"extensions\": {\"uri\": \"[[${memory.current.httpCalls.langchainConfigUri}]]\"}}]}"
  },
  "postResponse": {
    "propertyInstructions": [
      {
        "name": "workflowId",
        "fromObjectPath": "packageResponse.id",
        "scope": "conversation"
      }
    ]
  }
}
```

### Step 9: Create LangChain Configuration

```json
{
  "name": "create-langchain-config",
  "actions": ["httpcall(create-langchain-config)"],
  "request": {
    "method": "POST",
    "path": "/langchainstore/langchains",
    "body": "{\"tasks\": [{\"actions\": [\"send_message\"], \"type\": \"[[${context.llmProvider.toLowerCase()}]]\", \"parameters\": {\"apiKey\": \"[[${context.apiKey}]]\", \"modelName\": \"gpt-4o\", \"systemMessage\": \"[[${context.agentDescription}]]\", \"addToOutput\": \"true\"}}]}"
  }
}
```

**Note**: The body uses **Thymeleaf templating** to inject conversation memory values:

- `${context.llmProvider}` → "openai"
- `${context.apiKey}` → "sk-..."
- `${context.agentDescription}` → "It should tell users the current weather"

### Step 10: Link Workflow to Agent

```json
{
  "name": "update-agent-with-package",
  "actions": ["httpcall(update-agent)"],
  "request": {
    "method": "PUT",
    "path": "/agentstore/agents/[[${context.newAgentId}]]",
    "body": "{\"packages\": [\"eddi://ai.labs.package/packagestore/packages/[[${context.workflowId}]]?version=1\"]}"
  }
}
```

### Step 11: Deploy Agent

```json
{
  "name": "deploy-agent",
  "actions": ["httpcall(deploy-agent)"],
  "request": {
    "method": "POST",
    "path": "/administration/production/deploy/[[${context.newAgentId}]]",
    "queryParams": {
      "version": "1"
    }
  }
}
```

### Step 12: Confirmation

**Agent Father**: (via Output Template)

```
"Your agent has been created successfully!
Agent ID: [[${context.newAgentId}]]
You can start chatting with it at:
http://localhost:7070/chat/production/[[${context.newAgentId}]]"
```

## Key Architectural Insights

### 1. Conversation-Driven Workflows

Agent Father demonstrates that EDDI can orchestrate **any** multi-step process, not just conversations:

- Data collection (via conversation)
- Validation (via behavior rules)
- API orchestration (via HTTP calls)
- Response formatting (via output templates)

### 2. Conditional Execution

The behavior rule that triggers agent creation shows **conditional API execution**:

```
IF (all required data collected) THEN (create agent)
```

This is more sophisticated than simple API proxies—it's **business logic orchestration**.

### 3. Memory as State Machine

Conversation memory acts as a **state machine**:

- Initial state: No data collected
- Transition: User provides information → Property setters update state
- Trigger: All data present → Behavior rule fires
- Action: HTTP call executes

### 4. Template-Based Configuration

HTTP call bodies use Thymeleaf templates, allowing **dynamic configuration**:

```json
{
  "apiKey": "[[${context.apiKey}]]",
  "systemMessage": "[[${context.agentDescription}]]"
}
```

This means the same HTTP call definition can create different configurations based on conversation data.

### 5. Self-Modification

Agent Father calls EDDI's own API, demonstrating:

- **Programmable infrastructure**: Agents can modify the system
- **API-first design**: Everything is accessible via REST
- **Composability**: Agents are data, not code—they can be created programmatically

## Real-World Applications

The Agent Father pattern can be applied to many scenarios:

### 1. Customer Onboarding Wizard

```
Agent collects: Name, email, company, preferences
→ Creates CRM record via API
→ Sends welcome email via SendGrid API
→ Creates Slack channel via Slack API
```

### 2. Order Processing System

```
Agent collects: Product, quantity, shipping address
→ Validates inventory via ERP API
→ Processes payment via Stripe API
→ Creates shipping label via FedEx API
→ Sends confirmation via Twilio SMS API
```

### 3. Support Ticket Creation

```
Agent collects: Issue description, severity, attachments
→ Creates Jira ticket via Jira API
→ Notifies team via Slack API
→ Sends confirmation email via SendGrid API
```

### 4. Dynamic Agent Configuration

```
Agent collects: Customer requirements, industry, use case
→ Selects appropriate LLM (OpenAI for creative, Claude for analytical)
→ Configures behavior rules based on industry
→ Sets up integrations based on use case
→ Deploys customized agent
```

## Code Deep Dive

Let's look at the actual Java components that make Agent Father work:

### Behavior Rules Task (executes rules)

```java
public class BehaviorRulesTask implements ILifecycleTask {
    @Override
    public void execute(IConversationMemory memory, Object component) {
        // Load behavior rules from component
        BehaviorConfiguration config = (BehaviorConfiguration) component;

        // Evaluate each rule
        for (BehaviorRule rule : config.getBehaviorRules()) {
            boolean allConditionsMet = evaluateConditions(rule.getConditions(), memory);

            if (allConditionsMet) {
                // Store actions in memory for next task
                memory.getCurrentStep().storeData(
                    dataFactory.createData("actions", rule.getActions())
                );
                break;  // First match wins
            }
        }
    }
}
```

### HTTP Calls Task (executes API calls)

```java
public class HttpCallsTask implements ILifecycleTask {
    @Override
    public void execute(IConversationMemory memory, Object component) {
        HttpCallsConfiguration config = (HttpCallsConfiguration) component;

        // Get actions from previous task (behavior rules)
        List<String> actions = memory.getCurrentStep()
            .getLatestData("actions").getResult();

        for (HttpCallDefinition httpCall : config.getHttpCalls()) {
            if (actions.contains("httpcall(" + httpCall.getName() + ")")) {
                // Execute HTTP call
                String url = config.getTargetServerUrl() + httpCall.getRequest().getPath();
                String body = applyTemplate(httpCall.getRequest().getBody(), memory);

                Response response = httpClient.post(url, body);

                // Store response in memory
                if (httpCall.isSaveResponse()) {
                    memory.getCurrentStep().storeData(
                        dataFactory.createData(
                            "httpCalls." + httpCall.getResponseObjectName(),
                            response.getBody()
                        )
                    );
                }

                // Extract properties from response
                for (PropertyInstruction instruction : httpCall.getPostResponse().getPropertyInstructions()) {
                    Object value = extractFromJsonPath(response.getBody(), instruction.getFromObjectPath());
                    memory.getConversationProperties().put(
                        "context." + instruction.getName(),
                        value
                    );
                }
            }
        }
    }
}
```

### Property Extraction Task

```java
public class PropertyExtractorTask implements ILifecycleTask {
    @Override
    public void execute(IConversationMemory memory, Object component) {
        PropertyConfiguration config = (PropertyConfiguration) component;

        for (PropertyInstruction instruction : config.getInstructions()) {
            if (instruction.getValueExtraction().equals("input")) {
                // Extract from user input
                String input = memory.getCurrentStep()
                    .getLatestData("input").getResult();

                // Store in appropriate scope
                if (instruction.getScope().equals("conversation")) {
                    memory.getConversationProperties().put(
                        "context." + instruction.getName(),
                        input
                    );
                }
            }
        }
    }
}
```

## Configuration Files

### Agent Father Agent Configuration

**File**: `agentfather.agent.json`

```json
{
  "packages": [
    "eddi://ai.labs.package/packagestore/packages/6740832b2b0f614abcaee7c8?version=1",
    "eddi://ai.labs.package/packagestore/packages/6740832a2b0f614abcaee79e?version=1",
    "eddi://ai.labs.package/packagestore/packages/6740832a2b0f614abcaee7a3?version=1",
    "eddi://ai.labs.package/packagestore/packages/6740832a2b0f614abcaee7a8?version=1",
    "eddi://ai.labs.package/packagestore/packages/6740832a2b0f614abcaee7ad?version=1"
  ]
}
```

### Workflow Configuration Example

**File**: `package-conversation-flow.package.json`

```json
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.behavior",
      "extensions": {
        "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/6740832a2b0f614abcaee79f?version=1"
      },
      "config": {
        "appendActions": true
      }
    },
    {
      "type": "eddi://ai.labs.output",
      "extensions": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/6740832a2b0f614abcaee7a1?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.property",
      "extensions": {
        "uri": "eddi://ai.labs.property/propertysetterstore/propertysetters/6740832a2b0f614abcaee7a2?version=1"
      }
    }
  ]
}
```

### Behavior Rules Example

**File**: `behavior-agent-creation.behavior.json`

```json
{
  "behaviorGroups": [
    {
      "name": "Agent Creation",
      "behaviorRules": [
        {
          "name": "Create Agent When Ready",
          "conditions": [
            {
              "type": "contextmatcher",
              "configs": {
                "contextKey": "agentName",
                "contextType": "string"
              }
            },
            {
              "type": "contextmatcher",
              "configs": {
                "contextKey": "agentDescription",
                "contextType": "string"
              }
            },
            {
              "type": "contextmatcher",
              "configs": {
                "contextKey": "llmProvider",
                "contextType": "string"
              }
            },
            {
              "type": "contextmatcher",
              "configs": {
                "contextKey": "apiKey",
                "contextType": "string"
              }
            }
          ],
          "actions": ["httpcall(create-agent)", "show_success_message"]
        }
      ]
    }
  ]
}
```

## Testing Agent Father

### Using the REST API

```bash
# 1. Start conversation with Agent Father
curl -X POST "http://localhost:7070/agents/agentfather/start" \
  -H "Content-Type: application/json" \
  -d '{"input": "I want to create an agent"}'

# Response includes conversationId
# {
#   "conversationId": "conv-123",
#   "conversationState": "READY",
#   "conversationOutputs": [
#     {"output": ["Welcome! What would you like to call your agent?"]}
#   ]
# }

# 2. Provide agent name
curl -X POST "http://localhost:7070/agents/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "Weather Agent"}'

# 3. Provide description
curl -X POST "http://localhost:7070/agents/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "Tells users the current weather"}'

# 4. Provide LLM choice
curl -X POST "http://localhost:7070/agents/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "OpenAI"}'

# 5. Provide API key
curl -X POST "http://localhost:7070/agents/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "sk-..."}'

# Agent Father will create the agent and return the agent ID
```

## Lessons from Agent Father

### 1. Configuration Over Code

Agent Father proves that complex workflows can be **configured**, not coded. No Java needed—just JSON.

### 2. Composability is Powerful

By combining simple components (rules, HTTP calls, templates), you can build sophisticated systems.

### 3. Conversations Are Workflows

Any multi-step process can be modeled as a conversation, making it user-friendly and intuitive.

### 4. EDDI is Infrastructure

EDDI isn't just for agents—it's infrastructure for **orchestrating any API-driven workflow** with conversational interfaces.

### 5. Self-Modification is Safe

Because agents are data (JSON), creating/modifying them via API is safe and version-controlled.

## Summary

The Agent Father demonstrates EDDI's core philosophy:

> **Sophisticated AI orchestration should be configuration, not code.**

By combining:

- **Behavior Rules** (decision logic)
- **Property Extraction** (state management)
- **HTTP Calls** (API orchestration)
- **Output Templates** (user interaction)

You can build systems that:

- Guide users through complex processes
- Collect and validate data conversationally
- Orchestrate multiple API calls conditionally
- Generate dynamic configurations
- Self-modify and adapt

This is the power of EDDI's architecture—and Agent Father is the proof.

## Related Documentation

- [Architecture Overview](architecture.md) - Understanding EDDI's design
- [Conversation Memory](conversation-memory.md) - How state is managed
- [Behavior Rules](behavior-rules.md) - Conditional logic
- [HTTP Calls](httpcalls.md) - API integration
- [Output Templating](output-templating.md) - Dynamic responses

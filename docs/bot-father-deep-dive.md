# The Bot Father: A Deep Dive

**Version: ≥5.5.x**

## Overview

The **Bot Father** is EDDI's meta-bot—a bot that creates other bots. It's the perfect example of EDDI's architecture in action, demonstrating how conversation flow, behavior rules, property extraction, and HTTP calls work together to build sophisticated workflows.

More importantly, it shows EDDI's unique capability: **the same architecture that powers simple chatbots can orchestrate complex, multi-step processes**, even self-modifying the system itself.

## What Makes Bot Father Special?

### It's Not Special Code
Bot Father is **not** a special feature or custom module. It's a **regular EDDI bot** built using the standard components:
- Behavior Rules (to control conversation flow)
- Property Extraction (to gather user input)
- HTTP Calls (to invoke EDDI's own API)
- Output Templates (to guide users)

### It Demonstrates Self-Modification
Bot Father uses EDDI's REST API to create new bots, packages, dictionaries, and configurations. This is possible because EDDI's API is designed to be **programmable**—you can automate bot creation just like any other API integration.

### It's a Conversational Wizard
Instead of requiring users to understand JSON configurations or API calls, Bot Father provides a **conversational interface** that:
1. Asks questions in natural language
2. Validates and stores answers
3. Builds complete bot configurations
4. Creates the bot via API
5. Returns the bot ID for deployment

## Architecture of Bot Father

### Bot Composition

Bot Father is composed of multiple packages:

```
Bot Father (.bot.json)
  ├─ Package 1: Core Conversation Flow
  │   ├─ Behavior Rules: Question sequencing
  │   ├─ Output Templates: Questions and responses
  │   └─ Properties: Store user answers
  │
  ├─ Package 2: Bot Creation Logic
  │   ├─ Behavior Rules: Trigger API calls when data is ready
  │   ├─ HTTP Calls: POST to /botstore/bots
  │   └─ Properties: Extract bot ID from response
  │
  ├─ Package 3: Package Creation Logic
  │   ├─ HTTP Calls: POST to /packagestore/packages
  │   └─ Properties: Store package references
  │
  ├─ Package 4: Dictionary Creation Logic
  │   └─ HTTP Calls: POST to /regulardictionarystore/regulardictionaries
  │
  └─ Package 5: LangChain Configuration
      └─ HTTP Calls: POST to /langchainstore/langchains
```

## Step-by-Step Flow

Let's walk through how Bot Father creates a new bot:

### Step 1: Conversation Start

**User**: Starts conversation with Bot Father

**Bot Father**: (via Output Template)
```
"Welcome! I'll help you create a new bot. What would you like to call your bot?"
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
*(Triggers only on first step)*

### Step 2: Capture Bot Name

**User**: "My Weather Bot"

**Property Setter**: (from property extension)
```json
{
  "name": "botName",
  "valueExtraction": "input",
  "scope": "conversation"
}
```

**Result**: Stores "My Weather Bot" in conversation memory:
```java
memory.getConversationProperties().put("context.botName", "My Weather Bot");
```

**Bot Father**: "Great! What should your bot do? Describe its purpose."

### Step 3: Capture Bot Description

**User**: "It should tell users the current weather"

**Property Setter**:
```json
{
  "name": "botDescription",
  "valueExtraction": "input",
  "scope": "conversation"
}
```

**Bot Father**: "Which AI provider would you like to use? (OpenAI, Claude, Gemini, or None)"

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

**Bot Father**: "Please provide your OpenAI API key."

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

### Step 6: Trigger Bot Creation

Now all required data is collected. A Behavior Rule monitors the memory:

```json
{
  "name": "Create Bot When Ready",
  "conditions": [
    {
      "type": "contextmatcher",
      "configs": {
        "contextKey": "botName",
        "contextType": "string"
      }
    },
    {
      "type": "contextmatcher",
      "configs": {
        "contextKey": "botDescription",
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
  "actions": ["httpcall(create-bot)"]
}
```

**Explanation**: 
- This rule checks if all required data exists in memory
- When all conditions are met, it triggers the `httpcall(create-bot)` action
- This demonstrates **conditional API execution** based on conversation state

### Step 7: Execute HTTP Call to Create Bot

The `create-bot` HTTP call is defined in an HTTP Calls extension:

```json
{
  "targetServerUrl": "http://localhost:7070",
  "httpCalls": [
    {
      "name": "create-bot",
      "saveResponse": true,
      "responseObjectName": "newBotResponse",
      "actions": ["httpcall(create-bot)"],
      "request": {
        "method": "POST",
        "path": "/botstore/bots",
        "headers": {
          "Content-Type": "application/json"
        },
        "body": "{\"packages\": []}"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "newBotId",
            "fromObjectPath": "newBotResponse.id",
            "scope": "conversation"
          }
        ]
      }
    }
  ]
}
```

**What Happens**:
1. **Request**: POST to `http://localhost:7070/botstore/bots`
2. **Body**: Empty bot configuration (packages added later)
3. **Response**: 
   ```json
   {
     "id": "673f1a2b4c5d6e7f8a9b0c1d",
     "version": 1,
     "packages": []
   }
   ```
4. **Property Extraction**: Saves bot ID to `context.newBotId`

### Step 8: Create Package with LangChain Configuration

Another HTTP call creates a package:

```json
{
  "name": "create-package",
  "actions": ["httpcall(create-package)"],
  "request": {
    "method": "POST",
    "path": "/packagestore/packages",
    "body": "{\"packageExtensions\": [{\"type\": \"eddi://ai.labs.langchain\", \"extensions\": {\"uri\": \"[[${memory.current.httpCalls.langchainConfigUri}]]\"}}]}"
  },
  "postResponse": {
    "propertyInstructions": [
      {
        "name": "packageId",
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
    "body": "{\"tasks\": [{\"actions\": [\"send_message\"], \"type\": \"[[${context.llmProvider.toLowerCase()}]]\", \"parameters\": {\"apiKey\": \"[[${context.apiKey}]]\", \"modelName\": \"gpt-4o\", \"systemMessage\": \"[[${context.botDescription}]]\", \"addToOutput\": \"true\"}}]}"
  }
}
```

**Note**: The body uses **Thymeleaf templating** to inject conversation memory values:
- `${context.llmProvider}` → "openai"
- `${context.apiKey}` → "sk-..."
- `${context.botDescription}` → "It should tell users the current weather"

### Step 10: Link Package to Bot

```json
{
  "name": "update-bot-with-package",
  "actions": ["httpcall(update-bot)"],
  "request": {
    "method": "PUT",
    "path": "/botstore/bots/[[${context.newBotId}]]",
    "body": "{\"packages\": [\"eddi://ai.labs.package/packagestore/packages/[[${context.packageId}]]?version=1\"]}"
  }
}
```

### Step 11: Deploy Bot

```json
{
  "name": "deploy-bot",
  "actions": ["httpcall(deploy-bot)"],
  "request": {
    "method": "POST",
    "path": "/administration/unrestricted/deploy/[[${context.newBotId}]]",
    "queryParams": {
      "version": "1"
    }
  }
}
```

### Step 12: Confirmation

**Bot Father**: (via Output Template)
```
"Your bot has been created successfully! 
Bot ID: [[${context.newBotId}]]
You can start chatting with it at: 
http://localhost:7070/chat/unrestricted/[[${context.newBotId}]]"
```

## Key Architectural Insights

### 1. Conversation-Driven Workflows

Bot Father demonstrates that EDDI can orchestrate **any** multi-step process, not just conversations:
- Data collection (via conversation)
- Validation (via behavior rules)
- API orchestration (via HTTP calls)
- Response formatting (via output templates)

### 2. Conditional Execution

The behavior rule that triggers bot creation shows **conditional API execution**:
```
IF (all required data collected) THEN (create bot)
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
  "systemMessage": "[[${context.botDescription}]]"
}
```

This means the same HTTP call definition can create different configurations based on conversation data.

### 5. Self-Modification

Bot Father calls EDDI's own API, demonstrating:
- **Programmable infrastructure**: Bots can modify the system
- **API-first design**: Everything is accessible via REST
- **Composability**: Bots are data, not code—they can be created programmatically

## Real-World Applications

The Bot Father pattern can be applied to many scenarios:

### 1. Customer Onboarding Wizard
```
Bot collects: Name, email, company, preferences
→ Creates CRM record via API
→ Sends welcome email via SendGrid API
→ Creates Slack channel via Slack API
```

### 2. Order Processing System
```
Bot collects: Product, quantity, shipping address
→ Validates inventory via ERP API
→ Processes payment via Stripe API
→ Creates shipping label via FedEx API
→ Sends confirmation via Twilio SMS API
```

### 3. Support Ticket Creation
```
Bot collects: Issue description, severity, attachments
→ Creates Jira ticket via Jira API
→ Notifies team via Slack API
→ Sends confirmation email via SendGrid API
```

### 4. Dynamic Bot Configuration
```
Bot collects: Customer requirements, industry, use case
→ Selects appropriate LLM (OpenAI for creative, Claude for analytical)
→ Configures behavior rules based on industry
→ Sets up integrations based on use case
→ Deploys customized bot
```

## Code Deep Dive

Let's look at the actual Java components that make Bot Father work:

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

### Bot Father Bot Configuration

**File**: `botfather.bot.json`
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

### Package Configuration Example

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

**File**: `behavior-bot-creation.behavior.json`
```json
{
  "behaviorGroups": [
    {
      "name": "Bot Creation",
      "behaviorRules": [
        {
          "name": "Create Bot When Ready",
          "conditions": [
            {
              "type": "contextmatcher",
              "configs": {
                "contextKey": "botName",
                "contextType": "string"
              }
            },
            {
              "type": "contextmatcher",
              "configs": {
                "contextKey": "botDescription",
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
          "actions": [
            "httpcall(create-bot)",
            "show_success_message"
          ]
        }
      ]
    }
  ]
}
```

## Testing Bot Father

### Using the REST API

```bash
# 1. Start conversation with Bot Father
curl -X POST "http://localhost:7070/bots/unrestricted/botfather" \
  -H "Content-Type: application/json" \
  -d '{"input": "I want to create a bot"}'

# Response includes conversationId
# {
#   "conversationId": "conv-123",
#   "conversationState": "READY",
#   "conversationOutputs": [
#     {"output": ["Welcome! What would you like to call your bot?"]}
#   ]
# }

# 2. Provide bot name
curl -X POST "http://localhost:7070/bots/unrestricted/botfather/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "Weather Bot"}'

# 3. Provide description
curl -X POST "http://localhost:7070/bots/unrestricted/botfather/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "Tells users the current weather"}'

# 4. Provide LLM choice
curl -X POST "http://localhost:7070/bots/unrestricted/botfather/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "OpenAI"}'

# 5. Provide API key
curl -X POST "http://localhost:7070/bots/unrestricted/botfather/conv-123" \
  -H "Content-Type: application/json" \
  -d '{"input": "sk-..."}'

# Bot Father will create the bot and return the bot ID
```

## Lessons from Bot Father

### 1. Configuration Over Code
Bot Father proves that complex workflows can be **configured**, not coded. No Java needed—just JSON.

### 2. Composability is Powerful
By combining simple components (rules, HTTP calls, templates), you can build sophisticated systems.

### 3. Conversations Are Workflows
Any multi-step process can be modeled as a conversation, making it user-friendly and intuitive.

### 4. EDDI is Infrastructure
EDDI isn't just for chatbots—it's infrastructure for **orchestrating any API-driven workflow** with conversational interfaces.

### 5. Self-Modification is Safe
Because bots are data (JSON), creating/modifying them via API is safe and version-controlled.

## Summary

The Bot Father demonstrates EDDI's core philosophy:

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

This is the power of EDDI's architecture—and Bot Father is the proof.

## Related Documentation

- [Architecture Overview](architecture.md) - Understanding EDDI's design
- [Conversation Memory](conversation-memory.md) - How state is managed
- [Behavior Rules](behavior-rules.md) - Conditional logic
- [HTTP Calls](httpcalls.md) - API integration
- [Output Templating](output-templating.md) - Dynamic responses


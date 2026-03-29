# LLM Integration

**Version: 6.0.0**

## Overview

The **LLM Lifecycle Task** (formerly "Langchain") is EDDI's unified integration point for Large Language Models (LLMs).

By default, it provides **simple chat** with any LLM provider. Optionally, you can enable **agent mode** to give your LLM access to built-in tools (calculator, web search, weather, etc.).

EDDI supports **12 LLM providers** out of the box: OpenAI, Anthropic, Google Gemini, Mistral AI, Azure OpenAI, Amazon Bedrock, Oracle GenAI, Ollama, Hugging Face, and Jlama — plus any OpenAI-compatible endpoint (DeepSeek, Cohere, etc.) via the `baseUrl` parameter.

The task automatically detects which mode to use based on your configuration—no manual switching required.

---

## EDDI's Value Proposition for LLMs

EDDI doesn't just forward messages to LLMs—it **orchestrates** them:

1. **Conditional LLM Invocation**: Use Behavior Rules to decide whether to call an LLM based on user input, context, or conversation state
2. **Pre-processing**: Parse, normalize, and enrich user input before sending to the LLM
3. **Context Management**: Control exactly what conversation history and context data is sent to the LLM
4. **Multi-LLM Support**: Switch between different LLMs (OpenAI, Claude, Gemini, Ollama, Hugging Face, Jlama) based on rules or user preferences
5. **Post-processing**: Transform, validate, or augment LLM responses before sending to users
6. **Hybrid Workflows**: Combine LLM calls with traditional APIs (e.g., LLM generates query → API fetches data → LLM formats result)
7. **State Persistence**: All LLM interactions are logged in conversation memory for analytics and debugging
8. **Tool Calling**: Enable LLMs to use built-in tools or custom HTTP call tools to access external capabilities

---

## Role in the Lifecycle

The Langchain task is a lifecycle task that executes when triggered by Behavior Rules:

```
User Input → Parser → Behavior Rules → [LangChain Task] → Output Generation
                           ↓
                    Action: "send_to_llm"
```

---

## Supported LLM Providers

The Langchain task integrates with multiple LLM providers via the langchain4j library:

- **OpenAI** (ChatGPT, GPT-4, GPT-4o) — also supports **DeepSeek** and **Cohere** via `baseUrl`
- **Anthropic** (Claude)
- **Google Gemini** (`gemini` - AI Studio API, `gemini-vertex` - Vertex AI)
- **Mistral AI** (Mistral Large, Codestral, Pixtral)
- **Azure OpenAI** (GPT-4o via Azure-hosted endpoints)
- **Amazon Bedrock** (Claude, Llama, Titan via AWS credential chain)
- **Oracle GenAI** (Cohere Command R+ via OCI authentication)
- **Ollama** (Local models)
- **Hugging Face** (Various models)
- **Jlama** (Local Java-based inference)

**Note**: Use the "Agent Father" agent to streamline setup and configuration of the Langchain task with guided assistance.

---

## Configuration Modes

### Default: Simple Chat

By default, the Langchain task provides straightforward LLM chat functionality. Just configure your LLM provider and start chatting.

### Optional: Agent Mode with Tools

To give your LLM access to tools (calculator, web search, weather, etc.), set `enableBuiltInTools: true` in your configuration.

The task automatically switches to agent mode when tools are enabled.

**Note**: Custom HTTP call tools (via the `tools` parameter) are also supported. You can provide a list of EDDI HTTP call URIs to give the agent access to your own APIs.

---

## Simple Chat Configuration

This is the standard way to use the Langchain task - just connect to an LLM and start chatting.

### Basic Example

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "simpleChat",
      "type": "openai",
      "description": "Simple chat interaction",
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant",
        "prompt": "",
        "logSizeLimit": "-1",
        "includeFirstAgentMessage": "true",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

### Configuration Parameters

| Parameter                  | Type    | Description                                           | Default           |
| -------------------------- | ------- | ----------------------------------------------------- | ----------------- |
| **Core Parameters**        |         |                                                       |                   |
| `apiKey`                   | string  | API key for the LLM provider                          | Required          |
| `modelName`                | string  | Model identifier (e.g., "gpt-4o", "Claude")           | Provider-specific |
| `systemMessage`            | string  | System message for LLM context                        | ""                |
| `prompt`                   | string  | Override user input (if not set, uses actual input)   | ""                |
| **Context Control**        |         |                                                       |                   |
| `logSizeLimit`             | int     | Conversation history limit                            | -1 (unlimited)    |
| `includeFirstAgentMessage` | boolean | Include first agent message in context                | true              |
| **Output Control**         |         |                                                       |                   |
| `convertToObject`          | boolean | Parse response as JSON. Enables three-layer enforcement: system prompt reinforcement, native API JSON mode (OpenAI, Gemini, Mistral), and pre-parse validation | false             |
| `responseSchema`           | string  | JSON schema for structured output. When set with `convertToObject=true`, the exact schema is injected into the system prompt so the LLM knows the expected format | ""                |
| `addToOutput`              | boolean | Add response to conversation output                   | false             |
| **Logging**                |         |                                                       |                   |
| `logRequests`              | boolean | Log API requests                                      | false             |
| `logResponses`             | boolean | Log API responses                                     | false             |
| **API Configuration**      |         |                                                       |                   |
| `temperature`              | string  | Model temperature (0-1)                               | Provider-specific |
| `timeout`                  | string  | Request timeout (milliseconds)                        | Provider-specific |

### Provider-Specific Examples

#### OpenAI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "openaiChat",
      "type": "openai",
      "description": "OpenAI GPT-4o chat",
      "parameters": {
        "apiKey": "your-openai-api-key",
        "modelName": "gpt-4o",
        "temperature": "0.7",
        "timeout": "15000",
        "logRequests": "true",
        "logResponses": "true",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Anthropic Claude

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "claudeChat",
      "type": "anthropic",
      "description": "Anthropic Claude chat",
      "parameters": {
        "apiKey": "your-anthropic-api-key",
        "modelName": "claude-3-opus-20240229",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "includeFirstAgentMessage": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Anthropic doesn't allow the first message to be from the agent, so `includeFirstAgentMessage` should be set to `false`.

#### Google Gemini (Vertex AI)

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "geminiChat",
      "type": "gemini",
      "description": "Google Gemini chat",
      "parameters": {
        "publisher": "vertex-ai",
        "projectId": "your-project-id",
        "modelId": "gemini-pro",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Ollama (Local Models)

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "ollamaChat",
      "type": "ollama",
      "description": "Ollama local model chat",
      "parameters": {
        "model": "llama3",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Hugging Face

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "huggingfaceChat",
      "type": "huggingface",
      "description": "Hugging Face model chat",
      "parameters": {
        "accessToken": "your-huggingface-access-token",
        "modelId": "llama3",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Jlama (Local Java Inference)

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "jlamaChat",
      "type": "jlama",
      "description": "Jlama local model chat",
      "parameters": {
        "modelName": "tjake/Llama-3.2-1B-Instruct-JQ4",
        "temperature": "0.7",
        "timeout": "30000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Jlama runs models locally in Java without requiring external services like Ollama.

#### Mistral AI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "mistralChat",
      "type": "mistral",
      "description": "Mistral AI chat",
      "parameters": {
        "apiKey": "your-mistral-api-key",
        "modelName": "mistral-large-latest",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Azure OpenAI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "azureChat",
      "type": "azure-openai",
      "description": "Azure OpenAI chat",
      "parameters": {
        "apiKey": "your-azure-api-key",
        "deploymentName": "gpt-4o",
        "endpoint": "https://your-instance.openai.azure.com",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Azure OpenAI uses `deploymentName` (not `modelName`) and requires an `endpoint` URL for your Azure instance.

#### Amazon Bedrock

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "bedrockChat",
      "type": "bedrock",
      "description": "Amazon Bedrock chat",
      "parameters": {
        "modelId": "anthropic.claude-v2",
        "region": "us-east-1",
        "temperature": "0.7",
        "maxTokens": "1024",
        "timeout": "30000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Bedrock uses `modelId` (not `modelName`) and does not require an `apiKey`. Authentication is via the AWS SDK default credential chain (environment variables, IAM roles, `~/.aws/credentials`).

#### Oracle GenAI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "oracleChat",
      "type": "oracle-genai",
      "description": "Oracle GenAI chat",
      "parameters": {
        "modelName": "cohere.command-r-plus",
        "compartmentId": "ocid1.compartment.oc1..your-compartment-id",
        "configProfile": "DEFAULT",
        "temperature": "0.7",
        "maxTokens": "1024",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Oracle GenAI does not require an `apiKey`. Authentication is via OCI SDK (`~/.oci/config`). The `configProfile` parameter selects which OCI profile to use (defaults to `"DEFAULT"`).

#### DeepSeek / Cohere (via OpenAI-Compatible Endpoints)

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "deepseekChat",
      "type": "openai",
      "description": "DeepSeek via OpenAI-compatible endpoint",
      "parameters": {
        "apiKey": "your-deepseek-api-key",
        "modelName": "deepseek-chat",
        "baseUrl": "https://api.deepseek.com",
        "temperature": "0.7",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Any OpenAI-compatible provider (DeepSeek, Cohere, etc.) can be used by setting the `baseUrl` parameter on the `openai` type. No additional dependencies are required.

---

## Agent Mode (Enhanced Features)

### AI Agent with Built-in Tools

```json
{
  "tasks": [
    {
      "actions": ["help"],
      "id": "aiAgent",
      "type": "openai",
      "description": "AI agent with calculator and web search",
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant with access to tools."
      },
      "enableBuiltInTools": true,
      "builtInToolsWhitelist": ["calculator", "datetime", "websearch"],
      "conversationHistoryLimit": 10
    }
  ]
}
```

### Agent Mode Parameters

| Parameter                  | Type     | Description                                      | Default                |
| -------------------------- | -------- | ------------------------------------------------ | ---------------------- |
| **Tool Configuration**     |          |                                                  |                        |
| `enableBuiltInTools`       | boolean  | Enable built-in tools                            | false                  |
| `builtInToolsWhitelist`    | string[] | Specific tools to enable                         | (all if not specified) |
| `tools`                    | string[] | Custom HTTP call tool URIs to enable             | (none)                 |
| **Context Control**        |          |                                                  |                        |
| `conversationHistoryLimit` | int      | Max conversation turns in context                | 10                     |
| **Cost & Performance**     |          |                                                  |                        |
| `maxBudgetPerConversation` | number   | Limit total tool/LLM usage cost per conversation | (unlimited)            |
| `enableToolCaching`        | boolean  | Cache tool results to reduce API calls           | true                   |
| `enableRateLimiting`       | boolean  | Limit tool/LLM usage rate                        | true                   |

---

## Built-in Tools

When `enableBuiltInTools: true`, you can use these tools:

| Tool Name           | Description                                     | Whitelist Value  |
| ------------------- | ----------------------------------------------- | ---------------- |
| **Calculator**      | Safe math expressions (sandboxed parser)        | `calculator`     |
| **Date/Time**       | Get current date, time, timezone info           | `datetime`       |
| **Web Search**      | Search the web (includes Wikipedia & News)      | `websearch`      |
| **Data Formatter**  | Format JSON, CSV, XML data                      | `dataformatter`  |
| **Web Scraper**     | Extract content from web pages (SSRF-protected) | `webscraper`     |
| **Text Summarizer** | Summarize long text                             | `textsummarizer` |
| **PDF Reader**      | Extract text from PDF URLs (SSRF-protected)     | `pdfreader`      |
| **Weather**         | Get weather information                         | `weather`        |

### Tool Configuration (Server-Side)

Some tools require API keys or external configuration to function. These are configured via **Environment Variables** or `application.properties` on the EDDI server.

#### Web Search Tool

By default, the tool uses **DuckDuckGo** (HTML scraping), which requires no configuration.

To use **Google Custom Search** (more reliable/structured), configure these properties:

```properties
# In application.properties
eddi.tools.websearch.provider=google
eddi.tools.websearch.google.api-key=YOUR_GOOGLE_API_KEY
eddi.tools.websearch.google.cx=YOUR_CUSTOM_SEARCH_ENGINE_ID
```

**Docker Environment Variables:**

- `EDDI_TOOLS_WEBSEARCH_PROVIDER=google`
- `EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=...`
- `EDDI_TOOLS_WEBSEARCH_GOOGLE_CX=...`

#### Weather Tool

The weather tool uses **OpenWeatherMap**. You must provide an API key:

```properties
# In application.properties
eddi.tools.weather.openweathermap.api-key=YOUR_OWM_API_KEY
```

**Docker Environment Variables:**

- `EDDI_TOOLS_WEATHER_OPENWEATHERMAP_API_KEY=...`

### Example: Selective Tool Enablement

```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["calculator", "datetime", "websearch"]
}
```

This enables **only** calculator, datetime, and websearch tools.

### Example: Enable All Tools

```json
{
  "enableBuiltInTools": true
}
```

Omitting `builtInToolsWhitelist` enables all available built-in tools.

---

## Custom HTTP Tools

In addition to built-in tools, you can give your agent access to any configured EDDI HTTP call. This allows the agent to interact with your own APIs or third-party services.

### Configuration

To enable custom tools, add the `tools` property to your task configuration with a list of HTTP call URIs.

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "type": "openai",
      "parameters": {
        "apiKey": "...",
        "modelName": "gpt-4o"
      },
      "enableBuiltInTools": true,
      "tools": [
        "eddi://ai.labs.httpcalls/get_stock_price?version=1",
        "eddi://ai.labs.httpcalls/create_jira_ticket?version=1"
      ]
    }
  ]
}
```

### How it Works

1.  **Configuration**: You provide the URIs of the HTTP calls you want the agent to use.
2.  **Discovery**: The agent is automatically informed about these tools and how to use them.
3.  **Execution**: When the agent decides to use a tool, it calls the `executeHttpCall` function with the tool's URI and necessary arguments.
4.  **Security**: The agent can **only** execute the HTTP calls explicitly listed in the `tools` array. It cannot make arbitrary HTTP requests to the internet.

---

## Extended Configuration Options

The Langchain task supports advanced pre-request and post-response processing for fine-tuned control over task behavior.

### Complete Configuration Example

```json
{
  "tasks": [
    {
      "id": "advancedTask",
      "type": "openai",
      "description": "Task with pre/post processing",
      "actions": ["process_input"],
      "preRequest": {
        "propertyInstructions": [
          {
            "name": "userContext",
            "valueString": "premium_user",
            "scope": "conversation"
          }
        ]
      },
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "false"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "lastResponseTime",
            "valueString": "{{currentTimestamp}}",
            "scope": "conversation"
          }
        ],
        "outputBuildInstructions": [
          {
            "pathToTargetArray": "response.suggestions",
            "iterationObjectName": "item",
            "outputType": "text",
            "outputValue": "{{item.text}}"
          }
        ],
        "qrBuildInstructions": [
          {
            "pathToTargetArray": "response.quickReplies",
            "iterationObjectName": "reply",
            "quickReplyValue": "{{reply.text}}",
            "quickReplyExpressions": "{{reply.action}}"
          }
        ]
      }
    }
  ]
}
```

### Configuration Parameters Explained

#### Pre-Request Configuration

- **preRequest.propertyInstructions**: Defines properties to be set before making the request to the LLM API
  - **name**: The property name
  - **valueString**: The value to be assigned (supports templating)
  - **scope**: The scope of the property (`step`, `conversation`, `longTerm`)

#### Post-Response Configuration

- **postResponse.propertyInstructions**: Defines properties to be set based on the LLM response
  - **name**: The property name
  - **valueString**: The value to be assigned (supports templating)
  - **scope**: The scope of the property

- **postResponse.outputBuildInstructions**: Configures how the response should be transformed into output (alternative to `addToOutput`)
  - **pathToTargetArray**: The path to the array in the response
  - **iterationObjectName**: The name of the object for iterating
  - **outputType**: The type of output to generate
  - **outputValue**: The value to be used for output (supports templating)

- **postResponse.qrBuildInstructions**: Configures quick replies based on the response
  - **pathToTargetArray**: The path to the quick replies array
  - **iterationObjectName**: The name of the object for iterating
  - **quickReplyValue**: The value for the quick reply (supports templating)
  - **quickReplyExpressions**: The expressions for the quick reply

#### Response Metadata

- **responseObjectName**: Name for storing the full response object in memory
- **responseMetadataObjectName**: Name for storing response metadata (token usage, finish reason) in memory

---

## API Endpoints

The Langchain task configurations can be managed via REST API endpoints.

### Endpoints Overview

1. **Read JSON Schema**
   - **Endpoint:** `GET /langchainstore/langchains/jsonSchema`
   - **Description:** Retrieves the JSON schema for validating Langchain configurations

2. **List Langchain Descriptors**
   - **Endpoint:** `GET /langchainstore/langchains/descriptors`
   - **Description:** Returns a list of all Langchain configurations with optional filters

3. **Read Langchain Configuration**
   - **Endpoint:** `GET /langchainstore/langchains/{id}`
   - **Description:** Fetches a specific Langchain configuration by its ID

4. **Update Langchain Configuration**
   - **Endpoint:** `PUT /langchainstore/langchains/{id}`
   - **Description:** Updates an existing Langchain configuration

5. **Create Langchain Configuration**
   - **Endpoint:** `POST /langchainstore/langchains`
   - **Description:** Creates a new Langchain configuration

6. **Duplicate Langchain Configuration**
   - **Endpoint:** `POST /langchainstore/langchains/{id}`
   - **Description:** Duplicates an existing Langchain configuration

7. **Delete Langchain Configuration**
   - **Endpoint:** `DELETE /langchainstore/langchains/{id}`
   - **Description:** Deletes a specific Langchain configuration

---

## Tool Execution Pipeline

All tool invocations—both built-in tools and custom HTTP call tools—are routed through a unified **Tool Execution Service** that applies enterprise-grade controls:

```
Tool Call ──▶ Rate Limiter ──▶ Cache Check ──▶ Execute Tool ──▶ Cost Tracker ──▶ Result
```

### Controls

| Feature           | Description                                                            | Config Key                                                 |
| ----------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------- |
| **Rate Limiting** | Token-bucket per tool, configurable limits                             | `enableRateLimiting`, `defaultRateLimit`, `toolRateLimits` |
| **Smart Caching** | Deduplicates identical tool calls by arguments hash                    | `enableToolCaching`                                        |
| **Cost Tracking** | Per-conversation budget enforcement with automatic stale-data eviction | `enableCostTracking`, `maxBudgetPerConversation`           |

### Configuration Example

```json
{
  "tasks": [
    {
      "actions": ["help"],
      "type": "openai",
      "enableBuiltInTools": true,
      "enableRateLimiting": true,
      "defaultRateLimit": 100,
      "toolRateLimits": { "websearch": 30, "weather": 50 },
      "enableToolCaching": true,
      "enableCostTracking": true,
      "maxBudgetPerConversation": 5.0,
      "parameters": { "apiKey": "...", "modelName": "gpt-4o" }
    }
  ]
}
```

### Security Hardening

Tools that accept URLs from LLM-generated arguments are protected against **Server-Side Request Forgery (SSRF)**:

- Only `http` and `https` schemes are allowed
- Private/internal IP ranges are blocked (loopback, site-local, link-local)
- Cloud metadata endpoints are blocked (`169.254.169.254`, `metadata.google.internal`)
- Internal hostnames (`.local`, `.internal`, `localhost`) are rejected

The **Calculator** tool uses a sandboxed recursive-descent math parser (`SafeMathParser`) instead of a script engine, eliminating any possibility of code injection.

See the [Security documentation](security.md) for full details.

---

## Monitoring & Observability

EDDI provides built-in metrics for monitoring agent performance:

- Tool execution success/failure rates
- Response latency (P50, P95, P99)
- Cache hit rates
- Cost tracking
- Rate limit violations

See the [Metrics Documentation](metrics.md) for details on configuring Prometheus/Grafana monitoring.

---

## Complete Example: Multi-Capability Agent

```json
{
  "tasks": [
    {
      "actions": ["*"],
      "id": "universalAssistant",
      "type": "openai",
      "description": "Universal AI assistant with multiple capabilities",
      "parameters": {
        "apiKey": "your-openai-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful AI assistant with access to calculator, web search, and weather tools.",
        "temperature": "0.7",
        "timeout": "30000"
      },
      "enableBuiltInTools": true,
      "builtInToolsWhitelist": [
        "calculator",
        "datetime",
        "websearch",
        "weather"
      ],
      "conversationHistoryLimit": 10
    }
  ]
}
```

This agent can:

- ✅ Perform calculations
- ✅ Get date/time info
- ✅ Search the web
- ✅ Check weather
- ✅ Maintain 10 turns of conversation history

---

## Integration with Behavior Rules

To trigger the Langchain task, configure Behavior Rules to emit the appropriate action:

```json
{
  "name": "Send to LLM",
  "rules": [
    {
      "name": "User asks question",
      "conditions": [
        {
          "type": "occurrence",
          "occurrence": "currentstep",
          "value": "input:initial"
        }
      ],
      "actions": ["send_message"]
    }
  ]
}
```

Then reference this action in your Langchain task:

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "myChat",
      "type": "openai",
      "parameters": {
        "apiKey": "your-api-key",
        "addToOutput": "true"
      }
    }
  ]
}
```

---

## Structured Output (JSON Mode)

When you need the LLM to return a specific JSON structure (e.g., for property extraction, API response formatting, or quick reply generation), use the `convertToObject` parameter with an optional `responseSchema`.

### Three-Layer Enforcement

EDDI uses three complementary mechanisms to ensure reliable JSON output:

| Layer | Mechanism | Coverage |
|---|---|---|
| **1. System Prompt** | Appends `## RESPONSE FORMAT (MANDATORY)` section with schema to every request | All providers |
| **2. Native API** | Sets `ResponseFormatType.JSON` on `ChatRequest` | OpenAI, Gemini, Mistral, Azure OpenAI |
| **3. Validation** | Pre-parse `startsWith("{")` check before deserialization | All providers |

If a provider doesn't support native JSON mode (e.g., Anthropic), EDDI gracefully falls back to prompt-only enforcement.

### Basic JSON Mode

```json
{
  "parameters": {
    "convertToObject": "true",
    "addToOutput": "false",
    "systemMessage": "You are a customer support classifier."
  }
}
```

### With Response Schema

For maximum reliability, specify the exact JSON structure you expect:

```json
{
  "parameters": {
    "convertToObject": "true",
    "addToOutput": "false",
    "responseSchema": "{\"htmlResponseText\": \"string — the formatted response\", \"quickReplies\": [\"string — suggested follow-up options\"], \"sentiment\": \"positive|negative|neutral\"}",
    "systemMessage": "You are a customer support agent. Analyze the user's message and respond."
  }
}
```

The schema is injected into the system prompt as a JSON code block so the LLM sees the exact expected format.

### Using with Output Configuration

When `convertToObject=true`, the LLM's JSON response is stored in conversation memory as a parsed object. You can then reference its fields in the Output Configuration:

```json
{
  "outputBuildInstructions": [{
    "outputType": "text",
    "outputValue": "{properties.aiOutputObject.htmlResponseText}"
  }],
  "qrBuildInstructions": [{
    "pathToTargetArray": "properties.aiOutputObject.quickReplies",
    "iterationObjectName": "quickReply",
    "quickReplyValue": "{quickReply}",
    "quickReplyExpressions": "trigger(quick_reply)"
  }]
}
```

### Debugging

When `convertToObject=true`, the raw LLM response is **always** persisted in conversation memory (key: `langchain:data`) even if JSON parsing fails. This ensures you can inspect what the LLM actually returned via the conversation log.

### Tips

- **Streaming**: Not recommended with JSON mode — the UI would show raw JSON building up
- **Provider compatibility**: OpenAI, Gemini, and Mistral support native JSON mode. Other providers rely on prompt-based enforcement
- **Schema specificity**: The more specific your `responseSchema`, the more reliable the output. Use type hints (`"string"`, `"number"`, `"boolean"`) and descriptions

---

## Common Issues and Troubleshooting

### API Key Issues

- **Problem**: "Invalid API key" errors
- **Solution**: Ensure API keys are valid and have not expired. Renew them before expiry.

### Model Misconfiguration

- **Problem**: "Model not found" errors
- **Solution**: Verify model names match those supported by the provider (e.g., "gpt-4o" for OpenAI, not "gpt4")

### Timeout Issues

- **Problem**: Requests timing out
- **Solution**: Increase the `timeout` parameter value (in milliseconds). Default is often 15000 (15 seconds).

### Anthropic First Message Error

- **Problem**: Anthropic API rejects conversations starting with agent message
- **Solution**: Set `includeFirstAgentMessage: "false"` for Anthropic tasks

### Tool Not Working

- **Problem**: Agent not using expected tools
- **Solution**:
  - Verify `enableBuiltInTools: true` is set
  - Check `builtInToolsWhitelist` includes the desired tool
  - Ensure the model supports tool calling (e.g., gpt-4o, not gpt-3.5-turbo)

### Response Not Added to Output

- **Problem**: LLM response not visible to user
- **Solution**: Set `addToOutput: "true"` in parameters, or configure `postResponse.outputBuildInstructions`

---

## Tool Execution Context

Understanding how tools execute is critical for designing new built-in tools and avoiding common pitfalls.

### Execution Path

All LLM tools execute **inside a conversation pipeline**. The full execution path is:

```
LlmTask.execute(memory)
  └─→ AgentOrchestrator.buildToolList(memory, config)
      └─→ Constructs tool instances with conversation context
  └─→ LLM invokes tool
  └─→ ToolExecutionService.executeToolWrapped()
      └─→ Rate Limiter → Cache Check → Execute → Cost Tracker → Result
```

### Implicit Context

`IConversationMemory` is **always available** when tools execute. Tools that need conversation state (e.g., `userId`, `agentId`, `groupIds`) receive it via constructor injection from `AgentOrchestrator`, which has the memory object at tool-list build time.

This means:
- **No ThreadLocal** or request-scoped beans needed
- **No `userId` parameter** on LLM tools — the conversation always knows who the user is
- Only external interfaces (MCP, REST) that operate **outside** a conversation need explicit user identification

### LLM Tools vs MCP Tools

| Aspect | LLM Tools (built-in) | MCP Tools |
|---|---|---|
| Execution context | Inside conversation pipeline | Outside conversation |
| User identification | Implicit from `IConversationMemory` | Explicit `userId` parameter |
| Registration | `builtInToolsWhitelist` in langchain config | `McpMemoryTools.java` |
| Audience | The LLM agent itself | External AI agents or admin tooling |

---

## See Also

- [Behavior Rules](behavior-rules.md) - Triggering LLM tasks conditionally
- [HTTP Calls](httpcalls.md) - Creating custom HTTP call tools for agents
- [Security](security.md) - SSRF protection, sandboxed evaluation, tool hardening
- [Output Configuration](output-configuration.md) - Formatting agent responses
- [Conversation Memory](conversation-memory.md) - Understanding conversation state
- [Metrics](metrics.md) - Monitoring LLM performance

---

## Summary

The LLM Lifecycle Task provides a flexible, unified interface for integrating LLMs into EDDI agents:

1. ✅ **Simple by Default** - Start with basic chat, add tools when needed
2. ✅ **12 Provider Support** - OpenAI, Anthropic, Google, Mistral, Azure, Bedrock, Oracle, Ollama, Hugging Face, Jlama + OpenAI-compatible (DeepSeek, Cohere)
3. ✅ **Built-in Tools** - 8 tools available when you enable agent mode
4. ✅ **Tool Execution Pipeline** - Rate limiting, caching, cost tracking for every tool call
5. ✅ **Security Hardened** - SSRF protection, sandboxed math evaluation, input validation
6. ✅ **Fine-Grained Control** - Pre/post processing, context management, templating
7. ✅ **Orchestration Layer** - Conditional invocation, hybrid workflows, state persistence
8. ✅ **Easy Configuration** - Use Agent Father for guided setup

Whether you need simple chat or advanced agent capabilities, the Langchain task provides the foundation for intelligent conversational experiences in EDDI.

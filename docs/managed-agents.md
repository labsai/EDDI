# Managed Agents

## Overview

**Managed Agents** is an EDDI feature that provides automatic conversation management, allowing you to trigger agents based on **intents** without manually creating and managing conversation IDs. EDDI handles the conversation lifecycle for you.

### The Problem It Solves

**Without Managed Agents** (manual approach):

1. Your app creates a conversation: `POST /agents/agent123/start`
2. EDDI returns conversation ID: `conv-456`
3. Your app stores this ID
4. Your app sends messages: `POST /agents/conv-456`
5. Your app manages conversation lifecycle

**With Managed Agents** (automatic approach):

1. You define an agent trigger with an intent keyword
2. Your app sends: `POST /agents/managed/weather_help/user123`
3. EDDI automatically:
   - Creates conversation (if none exists for this user/intent)
   - Routes to correct agent
   - Manages conversation state
   - Reuses existing conversation on subsequent calls

### Use Cases

- **Multi-Agent Applications**: Route users to different agents based on intent without tracking conversation IDs
- **Microservices Architecture**: Each service triggers agents by intent, EDDI handles coordination
- **Simplified Integration**: Client apps don't need conversation management logic
- **User-Centric Sessions**: One conversation per user per intent, automatically managed
- **A/B Testing**: Define multiple agents for same intent; EDDI picks one randomly

### Key Concepts

**Intent**: A keyword or phrase that maps to one or more agent deployments

- Example: `"weather_help"` → Weather Agent
- Example: `"order_status"` → Order Tracking Agent
- Example: `"support_technical"` → Technical Support Agent

**Agent Trigger**: Configuration that links an intent to specific agents

**User ID**: Identifies the user; EDDI maintains one conversation per user per intent

### How It Works

```
1. Define Agent Trigger:
   Intent: "weather_help" → Agent: weather-agent-v2 (production)

2. User Requests:
   POST /agents/managed/weather_help/user-123
   {"input": "What's the weather?"}

3. EDDI Logic:
   - Checks if user-123 has active conversation for "weather_help"
   - If NO: Creates new conversation with weather-agent-v2
   - If YES: Continues existing conversation
   - Processes message through agent's lifecycle
   - Returns response

4. Subsequent Requests:
   POST /agents/managed/weather_help/user-123
   {"input": "What about tomorrow?"}
   → Continues same conversation
```

### Benefits

- **Simplified Client Logic**: No conversation ID management needed
- **Intent-Based Routing**: Natural way to organize multi-agent systems
- **Automatic Session Management**: EDDI handles conversation lifecycle
- **Initial Context Support**: Pass context at conversation start
- **Random Agent Selection**: A/B testing or load distribution built-in

## Managed Agents Configuration

This feature allows you to take advantage of **EDDI**'s automatic management of agents. It is possible to avoid creating conversations and managing them yourself—let EDDI handle it.

This acts as a shortcut to directly start a conversation with an agent that covers a specific **intent**.

First, you need to set up a `AgentTrigger`.

## AgentTrigger

### The request model

```javascript
{
  "intent": "string",
  "agentDeployments": [
    {
      "environment": "environment",
      "agentId": "string",
      "initialContext": {
        "additionalProp1": {
          "type": "string",
          "value": ""
        },
        "additionalProp2": {
          "type": "string",
          "value": ""
        },
        "additionalProp3": {
          "type": "string",
          "value": ""
        }
      }
    }
  ]
}
```

### Description of the request model

| Element          | Description                                                                                                                                                                                                                                                                                                    |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| intent           | (`String`) keyword or phrase (camel case or with '-') that will be used in managed agents to trigger the agents defined in this model                                                                                                                                                                          |
| agentDeployments | (`Array:`<`AgentDeployment`>) array of `AgentDeployment`. If multiple `agentDeployments` are defined, one will be picked randomly.                                                                                                                                                                             |
| environment      | (`String`) the environment: `production` (default) or `test`. Legacy values `unrestricted` and `restricted` are accepted and mapped to `production`.                                                                                                                                              |
| agentId          | (`String`) the id of the agent that you want to create the agentTrigger for it.                                                                                                                                                                                                                |
| initialContext   | (Object) Context handed to the agent at conversation start. Keys map to `Context` objects with `type` and `value` fields. Only applied when the conversation is first created.                                                                                                |

### AgentTrigger API endpoints

| HTTP Method | API Endpoint                                | Request Body         | Response             |
| ----------- | ------------------------------------------- | -------------------- | -------------------- |
| DELETE      | `/agenttriggerstore/agenttriggers/{intent}` | N/A                  | N/A                  |
| GET         | `/agenttriggerstore/agenttriggers/{intent}` | N/A                  | Agent Triggers-model |
| PUT         | `/agenttriggerstore/agenttriggers/{intent}` | Agent Triggers-model | N/A                  |
| POST        | `/agenttriggerstore/agenttriggers`          | Agent Triggers-model | N/A                  |

## Triggering a ManagedAgent

To trigger a managed agent you will have to call the following API endpoints.

### API Methods

| HTTP Method | API Endpoint                                          | Request Body | Response           |
| ----------- | ----------------------------------------------------- | ------------ | ------------------ |
| GET         | `/agents/managed/{intent}/{userId}`                   | N/A          | Conversation model |
| POST        | `/agents/managed/{intent}/{userId}`                   | Input model  | N/A                |
| POST        | `/agents/managed/{intent}/{userId}/endConversation`   | N/A          | N/A                |
| GET         | `/agents/managed/{intent}/{userId}/undo`              | N/A          | Boolean            |
| POST        | `/agents/managed/{intent}/{userId}/undo`              | N/A          | N/A                |
| GET         | `/agents/managed/{intent}/{userId}/redo`              | N/A          | Boolean            |
| POST        | `/agents/managed/{intent}/{userId}/redo`              | N/A          | N/A                |

### Description API endpoint required path parameters

| Element   | Description                                                                |
| --------- | -------------------------------------------------------------------------- |
| {intent}​ | (`String`) the label/keyword used originally to point to this AgentTrigger |
| {userId}​ | (`String`) used to specify the user who triggered the conversation         |

### Example _:_

#### 1/Create an AgentTrigger

_Request URL:_

`POST` `http://localhost:7070/agenttriggerstore/agenttriggers`

_Request Body_

```javascript
{
  "intent": "weather_trigger",
  "agentDeployments": [
    {
      "environment": "production",
      "agentId": "5bf5418c46e0fb000b7636d0",
      "initialContext": {}
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`200`

#### 2/Trigger the ManagedAgent

_Request URL:_

`POST` `http://localhost:7070/agents/managed/weather_trigger/myUserId`

_Request Body_

```javascript
{
  "input": "Hello managed agent!",
  "context": {}
}
```

_Response Body_

```javascript
{
  "agentId": "5bf5418c46e0fb000b7636d0",
  "agentVersion": 10,
  "userId": "myUserId",
  "environment": "production",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationOutputs": [
    {
      "input": "Hello managed agent!",
      "expressions": "unknown(Hello), unknown(managed), unknown(agent!)",
      "intents": [
        "unknown",
        "unknown",
        "unknown"
      ]
    }
  ],
  "conversationProperties": {},
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "Hello managed agent!"
        }
      ],
      "timestamp": 1552869578596
    }
  ]
}
```

_Response Code_

`200`

### MCP Integration

The same managed conversation functionality is available via the MCP `chat_managed` tool:

```
chat_managed(intent: "weather_trigger", userId: "myUserId", message: "Hello managed agent!")
```

See [MCP Server](mcp-server.md) for full tool documentation.

# Conversations

## Overview

**Conversations** are the primary interaction mechanism in EDDI. Each conversation represents a stateful dialog session between a user and a agent, maintaining complete history, context, and state throughout the interaction.

### Key Concepts

- **Stateful Sessions**: Each conversation maintains its own state (conversation memory) that persists across multiple interactions
- **Conversation ID**: Unique identifier that references a specific conversation session
- **Lifecycle States**: Conversations transition through states: `READY`, `IN_PROGRESS`, `ENDED`, `ERROR`
- **History Management**: Full conversation history is maintained, with support for undo/redo operations
- **Context Passing**: External context can be injected into conversations at any step

### How Conversations Work in EDDI

When you create a conversation:

1. EDDI creates a new `IConversationMemory` object
2. Assigns a unique conversation ID
3. Links it to a specific agent and user
4. Initializes the first conversation step
5. Returns the conversation ID for subsequent interactions

Each message sent to a conversation:

1. Loads the conversation memory from MongoDB/cache
2. Executes the agent's lifecycle pipeline
3. Updates the conversation memory with results
4. Saves the updated memory
5. Returns the agent's response

> **Time Travel Feature**: EDDI has a powerful feature for conversations—the ability to go back in time using the `/undo` and `/redo` API endpoints!

## Working with Conversations

In this section we will explain how to **send/receive messages** from a Chatagent. The first step is creating a `conversation`. Once you have the `conversation` `Id`, you can **send** messages via **`POST`** requests and **receive** responses via **`GET`** requests, while having the capacity to send context information through the body of the **POST** request.

## Creating/initiating a conversation :

### &#x20;Create a Conversation with a Chatagent REST API Endpoint

| Element           | Tags                                                                                             |
| ----------------- | ------------------------------------------------------------------------------------------------ |
| **HTTP Method**   | `POST`                                                                                           |
| **API endpoint**  | `/agents/{environment}/{agentId}`                                                                |
| **{environment}** | (`Path` **parameter**):`String` Deployment environment (e.g: `restricted`,`unrestricted`,`test`) |
| {**agentId**}     | (`Path` **parameter**):`String Id` of the agent that you wish to **start conversation with**.    |

### Response Model

```javascript
{
  "agentId": "string",
  "agentVersion": Integer,
  "userId": "string",
  "environment": "string",
  "conversationState": "string",
  "redoCacheSize": 0,
  "conversationOutputs": [
                "input"    :    "string",
                "expressions"    :    <arrayOfString>,
                "intents" :        <arrayOfString>,
                "actions"    :    <arrayOfString>,
                "httpCalls"    :    {JsonObject},
                "properties" :    <arrayOfString>,
                "output" : "string"
    ],
  "conversationProperties": {
        "<nameOfProperty>" : {
      "name" : "string",
      "value" : "string" | {JsonObject},
      "scope" : "string"
    }},
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "string",
          "value": {}
        }
      ],
      "timestamp": "dateTime"
    }
  ]
}
```

### Description of the Conversation response model

| Element                        | Tags                                                                                                                                                                                                                                                                               |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **agentId**                    | (`String`) The id of the agent that sent the reply.                                                                                                                                                                                                                                |
| agentVersion                   | (`integer`) The version of the agent that sent the reply.                                                                                                                                                                                                                          |
| userId                         | (`String`) The id of the user who interacted with the agent.                                                                                                                                                                                                                       |
| environment                    | (`String`) the name of the environment where the agent is deployed                                                                                                                                                                                                                 |
| conversationState              | <p>(<code>String</code>) The state of the current conversation, could be:</p><p><code>READY</code>, </p><p><code>IN_PROGRESS</code>, </p><p><code>ENDED</code>, </p><p><code>EXECUTION_INTERRUPTED</code>, </p><p><code>ERROR</code></p>                                           |
| redoCount                      | (`integer`) if undo has been performed, this number indicates how many times redo can be done (=times undo has been triggered)                                                                                                                                                     |
| conversationOutputs            | (`Array`: <`conversationOutput`>) Array of `conversationOutput`                                                                                                                                                                                                                    |
| conversationOutput.input       | (`String`) The user's input.                                                                                                                                                                                                                                                       |
| conversationOutput.expressions | (`Array`: <`String`>) an array of the `expressions` involved in the creation of this reply (output).                                                                                                                                                                               |
| conversationOutput.intents     | (`Array`: <`String`>) an array of the `intents` involved in the creation of this reply (output).                                                                                                                                                                                   |
| conversationOutput.actions     | (`Array`: <`String`>) an array of the `actions` involved in the creation of this reply (output).                                                                                                                                                                                   |
| conversationOutput.httpCalls   | (`Array`: <`JsonObject`>) an array of the `httpCalls` objects involved in the creation of this reply (output).                                                                                                                                                                     |
| conversationOutput.properties  | (`Array`: <`JsonObject`>) the list of available properties in the current conversation.                                                                                                                                                                                            |
| conversationOutput.output      | (`String`) The final agent's output                                                                                                                                                                                                                                                |
| conversationProperties         | (`Array`: <>) Array of `conversationProperty`, <`nameOfProperty`> is a dynamic value that represents the name of the property                                                                                                                                                      |
| \<nameOfProperty>.name         | (`String`) name of the property.                                                                                                                                                                                                                                                   |
| \<nameOfProperty>.value        | (`String`\|`JsonObject`) value of the property.                                                                                                                                                                                                                                    |
| \<nameOfProperty>.scope        | <p>(<code>String</code>) scope can be </p><p><code>step</code> (=valid one interaction [user input to user output]), </p><p><code>conversation</code> (=valid for the entire conversation), </p><p><code>longTerm</code> (=valid across conversations [based on given userId])</p> |
| conversationSteps              | (`Array`: <`conversationStep`>) Array of `conversationStep`.                                                                                                                                                                                                                       |
| conversationStep.key           | (`String`) the element key in the conversationStep e.g key : input:initial, actions                                                                                                                                                                                                |
| conversationStep.value         | (`String`) the element value of the conversationStep e.g in case of `actionq` as `key` it could be an array string `[ "current_weather_in_city" ]`.                                                                                                                                |
| timestamp.timestamp            | (`dateTime`) the timestamp in (ISO 8601) format                                                                                                                                                                                                                                    |

> **Note** `conversationProperties` can also be used in output templating e.g: `[[${properties.city}]].`

### Sample Response

```javascript
{
  "agentId": "5bf5418c46e0fb000b7636d0",
  "agentVersion": 10,
  "userId": "anonymous-zj1p1GDtM5",
  "environment": "unrestricted",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationOutputs": [
    {
      "input": "madrid",
      "expressions": "unknown(madrid)",
      "intents": [
        "unknown"
      ],
      "actions": [
        "current_weather_in_city"
      ],
      "httpCalls": {
        "currentWeather": {
          "coord": {
            "lon": -3.7,
            "lat": 40.42
          },
          "weather": [
            {
              "id": 800,
              "main": "Clear",
              "description": "clear sky",
              "icon": "01n"
            }
          ],
          "base": "stations",
          "main": {
            "temp": 10.86,
            "pressure": 1019,
            "humidity": 66,
            "temp_min": 8.33,
            "temp_max": 13.33
          },
          "visibility": 10000,
          "wind": {
            "speed": 5.7,
            "deg": 240
          },
          "clouds": {
            "all": 0
          },
          "dt": 1551735805,
          "sys": {
            "type": 1,
            "id": 6443,
            "message": 0.0049,
            "country": "ES",
            "sunrise": 1551681788,
            "sunset": 1551723011
          },
          "id": 3117735,
          "name": "Madrid",
          "cod": 200
        }
      },
      "properties": {
        "currentWeather": {
          "coord": {
            "lon": -3.7,
            "lat": 40.42
          },
          "weather": [
            {
              "id": 800,
              "main": "Clear",
              "description": "clear sky",
              "icon": "01n"
            }
          ],
          "base": "stations",
          "main": {
            "temp": 10.86,
            "pressure": 1019,
            "humidity": 66,
            "temp_min": 8.33,
            "temp_max": 13.33
          },
          "visibility": 10000,
          "wind": {
            "speed": 5.7,
            "deg": 240
          },
          "clouds": {
            "all": 0
          },
          "dt": 1551735805,
          "sys": {
            "type": 1,
            "id": 6443,
            "message": 0.0049,
            "country": "ES",
            "sunrise": 1551681788,
            "sunset": 1551723011
          },
          "id": 3117735,
          "name": "Madrid",
          "cod": 200
        },
        "city": "madrid"
      },
      "output": [
        "The current weather situation of madrid is clear sky at 10.86 °C"
      ]
    }
  ],
  "conversationProperties": {
    "city": {
      "name": "city",
      "value": "madrid",
      "scope": "conversation"
    }
  },
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "madrid"
        },
        {
          "key": "actions",
          "value": [
            "current_weather_in_city"
          ]
        },
        {
          "key": "output:text:current_weather_in_city",
          "value": "The current weather situation of madrid is clear sky at 10.86 °C"
        }
      ],
      "timestamp": 1551736024776
    }
  ]
}
```

The `conversationId` will be provided through the **`location`** **HTTP Header** of the response, you will use that later to submit messages to the Chatagent to maintain a conversation.

### Example _:_

_Request URL:_

`http://localhost:7070/agents/unrestricted/5ad2ab182de29719b44a792a`

_Response Body_

`no content`

_Response Code_

`201`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 15 Apr 2018 01:45:09 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.conversation/conversationstore/conversations/5ad2aea52de29719b44a792c",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

## Send/receive messages

### Send a message

### Send message in a conversation with a Chatagent REST API Endpoint

| Element               | Tags                                                                                                                                                                                                                                                                   |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| HTTP Method           | `POST`                                                                                                                                                                                                                                                                 |
| API endpoint          | `/agents/{environment}/{agentId}/{conversationId}`                                                                                                                                                                                                                     |
| {environment}         | (`Path` **parameter**):`String` Deployment environment (e.g: `restricted`,`unrestricted`,`test`)                                                                                                                                                                       |
| agentId               | (`Path` **parameter**):`String Id` of the agent that you wish to **continue a conversation with.**                                                                                                                                                                     |
| conversationId        | (`Path` **parameter**): `String Id` of the **conversation** that you wish to **send** the message to.                                                                                                                                                                  |
| returnDetailed        | (`Query` **parameter**):`Boolean` - **Default** : `false`                                                                                                                                                                                                              |
| returnCurrentStepOnly | (`Query` **parameter**):`Boolean` - **Default** : `true`                                                                                                                                                                                                               |
| Request Body          | <p>JSON Object , example : <code>{ "input": "the message", "context": {} }</code></p><p>The <code>context</code> here is where you pass context variables that can be evaluated by EDDI, we will be explaining this in more details in Passing Context Information</p> |

### Example :

_Request URL_

`http://localhost:7070/agents/restricted/5aaf90e29f7dd421ac3c7dd4/5add1fe8a081a228a0588d1c?returnDetailed=false&returnCurrentStepOnly=true`

_Request Body_

```javascript
{
  "input": "Hi!",
  "context": {}
}
```

Response Code

`200`

Response Body

```javascript
{
  "agentId": "5aaf90e29f7dd421ac3c7dd4",
  "agentVersion": 1,
  "environment": "restricted",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "Hi!"
        }
      ],
      "timestamp": 1524441253098
    }
  ]
}
```

Response Headers

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 22 Apr 2018 23:54:12 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "321",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "content-type": "application/json;resteasy-server-has-produces=true"
}
```

### Receive a message

### Receive message in a conversation with a Chatagent REST API Endpoint

| Element          | Tags                                                                                                         |
| ---------------- | ------------------------------------------------------------------------------------------------------------ |
| HTTP Method      | `GET`                                                                                                        |
| API endpoint     | `/agents/{environment}/{agentId}/{conversationId}`                                                           |
| {environment}    | (`Path` **parameter**):`String` Deployment environment (e.g: `restricted,unrestricted,test`)                 |
| {agentId}        | (`Path` **parameter**):`String Id` of the agent that you wish to **continue a conversation** **with**.       |
| {conversationId} | (`Path` **parameter**): `String Id` of the **conversation** that you wish to **receive** a the message from. |
| returnDetailed   | (`Query` **parameter**):`Boolean` - **Default** : `false`                                                    |
|                  |                                                                                                              |

### Example

_Request URL:_

`http://localhost:7070/agents/unrestricted/5aaf90e29f7dd421ac3c7dd4/5add1fe8a081a228a0588d1c?returnDetailed=false`

_Response Body_

```javascript
{
  "agentId": "5aaf90e29f7dd421ac3c7dd4",
  "agentVersion": 1,
  "environment": "restricted",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "actions",
          "value": [
            "global_menu"
          ]
        },
        {
          "key": "output:text:global_menu",
          "value": "What do you want to do?"
        },
        {
          "key": "quickReplies:global_menu",
          "value": [
            {
              "value": "Show me your skillz",
              "expressions": "confirmation(show_skills)",
              "default": false
            },
            {
              "value": "Tell me a joke",
              "expressions": "trigger(tell_a_joke)",
              "default": false
            }
          ]
        }
      ],
      "timestamp": 1524441064450
    },
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "Hi!"
        }
      ],
      "timestamp": 1524441253098
    }
  ]
}
```

Response Code

`200`

Response Headers

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Mon, 23 Apr 2018 00:07:25 GMT",
  "cache-control": "no-cache",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "891",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "content-type": "application/json"
}
```

## Time Travel: Undo and Redo

One of EDDI's most powerful features is the ability to **go back in time** within a conversation. The undo/redo functionality allows you to step backward and forward through conversation history, perfect for:

- **User Correction**: User made a mistake and wants to retry
- **Testing**: Developers testing different conversation paths
- **Debugging**: Analyzing agent behavior at specific steps
- **User Experience**: Allowing users to explore different options

### How It Works

EDDI maintains a **redo cache** of undone conversation steps. When you undo a step, it's moved to this cache. You can then either:

- Continue the conversation (clears redo cache)
- Redo the step (restores it from cache)

```
Step 1 → Step 2 → Step 3 (current)
         undo ↓
Step 1 → Step 2 (current) | [Step 3 in redo cache]
         redo ↓
Step 1 → Step 2 → Step 3 (current)
```

### Undo API

#### Check if Undo is Available

| Element      | Value                                                   |
| ------------ | ------------------------------------------------------- |
| HTTP Method  | `GET`                                                   |
| API Endpoint | `/agents/{environment}/{agentId}/undo/{conversationId}` |
| Response     | `true` if undo is available, `false` otherwise          |

**Example:**

```bash
curl -X GET "http://localhost:7070/agents/unrestricted/AGENT_ID/undo/CONV_ID"
```

**Response:**

```json
true
```

#### Perform Undo

| Element      | Value                                                   |
| ------------ | ------------------------------------------------------- |
| HTTP Method  | `POST`                                                  |
| API Endpoint | `/agents/{environment}/{agentId}/undo/{conversationId}` |
| Response     | HTTP 200 (no content)                                   |

**Example:**

```bash
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID/undo/CONV_ID"
```

**Response:** HTTP 200 (No Content)

**Effect**: The last conversation step is removed from the conversation history and stored in the redo cache.

### Redo API

#### Check if Redo is Available

| Element      | Value                                                   |
| ------------ | ------------------------------------------------------- |
| HTTP Method  | `GET`                                                   |
| API Endpoint | `/agents/{environment}/{agentId}/redo/{conversationId}` |
| Response     | `true` if redo is available, `false` otherwise          |

**Example:**

```bash
curl -X GET "http://localhost:7070/agents/unrestricted/AGENT_ID/redo/CONV_ID"
```

**Response:**

```json
true
```

#### Perform Redo

| Element      | Value                                                   |
| ------------ | ------------------------------------------------------- |
| HTTP Method  | `POST`                                                  |
| API Endpoint | `/agents/{environment}/{agentId}/redo/{conversationId}` |
| Response     | HTTP 200 (no content)                                   |

**Example:**

```bash
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID/redo/CONV_ID"
```

**Response:** HTTP 200 (No Content)

**Effect**: The last undone step is restored from the redo cache and added back to the conversation history.

### Complete Example Flow

```bash
# 1. Start conversation
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID" -d '{}'
# Returns: {"conversationId": "CONV_ID"}

# 2. Send message
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID/CONV_ID" \
  -H "Content-Type: application/json" \
  -d '{"input": "Hello"}'
# Agent responds: "Hi! How can I help?"

# 3. Send another message
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID/CONV_ID" \
  -H "Content-Type: application/json" \
  -d '{"input": "Book a flight"}'
# Agent responds: "Where would you like to go?"

# 4. Oops, user meant hotel not flight! Undo last step
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID/undo/CONV_ID"
# Now back to: "Hi! How can I help?"

# 5. Try again with correct input
curl -X POST "http://localhost:7070/agents/unrestricted/AGENT_ID/CONV_ID" \
  -H "Content-Type: application/json" \
  -d '{"input": "Book a hotel"}'
# Agent responds: "Which city?"

# 6. Wait, maybe flight was right. Check if redo is available
curl -X GET "http://localhost:7070/agents/unrestricted/AGENT_ID/redo/CONV_ID"
# Returns: false (because we sent a new message, clearing redo cache)
```

### Redo Cache Behavior

**Important**: The redo cache is cleared when you send a new message after an undo. This prevents inconsistent conversation states.

```
Normal flow:
Step 1 → Step 2 → Step 3

After undo:
Step 1 → Step 2 | [Step 3 cached]
         ↓ can redo

After new message:
Step 1 → Step 2 → Step 4
         ↓ redo cache cleared (Step 3 lost)
```

### Checking Redo Cache Size

The conversation response includes `redoCacheSize` field:

```json
{
  "conversationId": "CONV_ID",
  "redoCacheSize": 0,
  "conversationState": "READY"
}
```

- `redoCacheSize: 0` - No undo has been performed, redo not available
- `redoCacheSize: 1` - One undo performed, one redo available
- `redoCacheSize: 2` - Two undos performed, two redos available

### Use Cases

**1. User Correction**

```
User: "Book me a table at 7pm"
Agent: "For how many people?"
User: "Wait, I meant 8pm"
→ Undo and retry
```

**2. Exploring Options**

```
User: "Show me flights to Paris"
Agent: [Shows flights]
User: "Actually, let me see hotels instead"
→ Undo and try different path
```

**3. Testing Agent Behavior**

```
Developer tests:
1. Input A → Response X
2. Undo
3. Input B → Response Y
4. Undo, redo → Back to Response X
```

### Limitations

- Undo/redo only affects conversation **history** and **memory**
- External API calls made during undone steps are **not reversed**
  - Example: If a payment API was called, undoing won't refund the payment
- Redo cache has a **size limit** (configurable)
- Redo cache is **session-specific** (cleared on conversation end)

### Best Practices

1. **Always check availability** before calling undo/redo to avoid errors
2. **Inform users** when undo clears redo cache (UX consideration)
3. **Be careful with side effects** - undo doesn't reverse external API calls
4. **Use for user convenience** - great for conversational UX
5. **Log undo/redo** - helps with analytics and debugging

## Related API Endpoints

- `POST /agents/{environment}/{agentId}` - Start conversation
- `POST /agents/{environment}/{agentId}/{conversationId}` - Send message
- `GET /agents/{environment}/{agentId}/{conversationId}` - Get conversation state
- `POST /agents/{environment}/{agentId}/undo/{conversationId}` - Undo last step
- `POST /agents/{environment}/{agentId}/redo/{conversationId}` - Redo last step
- `GET /agents/{environment}/{agentId}/undo/{conversationId}` - Check undo availability
- `GET /agents/{environment}/{agentId}/redo/{conversationId}` - Check redo availability

## Sample Agent

{% file src=".gitbook/assets/weather_agent_v2.zip" %}
Weather-agent-v2.zip
{% endfile %}

# Managed Bots

## Overview

**Managed Bots** is an EDDI feature that provides automatic conversation management, allowing you to trigger bots based on **intents** without manually creating and managing conversation IDs. EDDI handles the conversation lifecycle for you.

### The Problem It Solves

**Without Managed Bots** (manual approach):
1. Your app creates a conversation: `POST /bots/unrestricted/bot123`
2. EDDI returns conversation ID: `conv-456`
3. Your app stores this ID
4. Your app sends messages: `POST /bots/unrestricted/bot123/conv-456`
5. Your app manages conversation lifecycle

**With Managed Bots** (automatic approach):
1. You define a bot trigger with an intent keyword
2. Your app sends: `POST /managedbots/weather_help/user123`
3. EDDI automatically:
   - Creates conversation (if none exists for this user/intent)
   - Routes to correct bot
   - Manages conversation state
   - Reuses existing conversation on subsequent calls

### Use Cases

- **Multi-Bot Applications**: Route users to different bots based on intent without tracking conversation IDs
- **Microservices Architecture**: Each service triggers bots by intent, EDDI handles coordination
- **Simplified Integration**: Client apps don't need conversation management logic
- **User-Centric Sessions**: One conversation per user per intent, automatically managed
- **A/B Testing**: Define multiple bots for same intent; EDDI picks one randomly

### Key Concepts

**Intent**: A keyword or phrase that maps to one or more bot deployments
- Example: `"weather_help"` → Weather Bot
- Example: `"order_status"` → Order Tracking Bot
- Example: `"support_technical"` → Technical Support Bot

**Bot Trigger**: Configuration that links an intent to specific bots

**User ID**: Identifies the user; EDDI maintains one conversation per user per intent

### How It Works

```
1. Define Bot Trigger:
   Intent: "weather_help" → Bot: weather-bot-v2 (unrestricted)

2. User Requests:
   POST /managedbots/weather_help/user-123
   {"input": "What's the weather?"}

3. EDDI Logic:
   - Checks if user-123 has active conversation for "weather_help"
   - If NO: Creates new conversation with weather-bot-v2
   - If YES: Continues existing conversation
   - Processes message through bot's lifecycle
   - Returns response

4. Subsequent Requests:
   POST /managedbots/weather_help/user-123
   {"input": "What about tomorrow?"}
   → Continues same conversation
```

### Benefits

- **Simplified Client Logic**: No conversation ID management needed
- **Intent-Based Routing**: Natural way to organize multi-bot systems
- **Automatic Session Management**: EDDI handles conversation lifecycle
- **Initial Context Support**: Pass context at conversation start
- **Random Bot Selection**: A/B testing or load distribution built-in

## Managed Bots Configuration

This feature allows you to take advantage of **EDDI**'s automatic management of bots. It is possible to avoid creating conversations and managing them yourself—let EDDI handle it.

This acts as a shortcut to directly start a conversation with a bot that covers a specific **intent**.

First, you need to set up a `BotTrigger`.

## BotTrigger

### The request model

```javascript
{
  "intent": "string",
  "botDeployments": [
    {
      "environment": "environment",
      "botId": "string",
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

| Element        | Description                                                                                                                                                                                                                                                                                            |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| intent         | (`String`) keyword or phrase (camel case or with '-') that will be used in managed bots to trigger the bots defined in this model                                                                                                                                                                      |
| botDeployments | (`Array:`<`BotDeployment`>) array of `BotDeployment`. If multiple `botDeployments` are defined, one will be picked randomly.                                                                                                                                                                           |
| environment    | (`String`) the environment that you would like (restricted, unrestricted, test)                                                                                                                                                                                                                        |
| botId          | (`String`) the id of the bot that you want to create the botTrigger for it.                                                                                                                                                                                                                            |
| initialContext | (Array <`Object`> ) As context can be handed over on each request to the bot, `initialContext` allows the definition of context the bot should get at the very first conversation step when a conversation with the bot is started (only way to get context to the bot in the first conversation step) |

### BotTrigger API endpoints

| HTTP Method | API Endpoint                            | Request Body       | Response           |
| ----------- | --------------------------------------- | ------------------ | ------------------ |
| DELETE      | `/bottriggerstore/bottriggers/{intent}` | N/A                | N/A                |
| GET         | `/bottriggerstore/bottriggers/{intent}` | N/A                | Bot Triggers-model |
| PUT         | `/bottriggerstore/bottriggers/{intent}` | Bot Triggers-model | N/A                |
| POST        | `/bottriggerstore/bottriggers`          | Bot Triggers-model | N/A                |

## Triggering a ManagedBot

To trigger a managed bot you will have to call the following API endpoints.

### API Methods

| HTTP Method | API Endpoint                                        | Request Body | Response           |
| ----------- | --------------------------------------------------- | ------------ | ------------------ |
| GET         | `/managedbots​/{intent}​/{userId}`                  | N/A          | Conversation model |
| POST        | `/managedbots​/{intent}​/{userId}`                  | Input model  | N/A                |
| POST        | `/managedbots​/{intent}​/{userId}​/endConversation` | Input model  | N/A                |

### Description API endpoint required path parameters

| Element   | Description                                                               |
| --------- | ------------------------------------------------------------------------- |
| {intent}​ |  (`String`) the label/keyword used originally to point to this BotTrigger |
| {userId}​ | (`String`) used to specify the user who triggered the conversation        |

### Example _:_

#### 1/Create a BotTrigger

_Request URL:_

`POST` `http://localhost:7070//bottriggerstore/bottriggers`

_Request Body_

```javascript
{
  "intent": "weather_trigger",
  "botDeployments": [
    {
      "environment": "unrestricted",
      "botId": "5bf5418c46e0fb000b7636d0",
      "initialContext": {}
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`200`

_Response Headers_

```javascript
access-control-allow-headers: authorization, Content-Type 
access-control-allow-methods: GET, PUT, POST, DELETE, PATCH, OPTIONS 
access-control-allow-origin: * 
access-control-expose-headers: location 
connection: Keep-Alive 
content-length: 0 
date: Mon, 18 Mar 2019 00:31:07 GMT 
keep-alive: timeout=5, max=100 
server: Apache/2.4.29 (Ubuntu)
```

#### 2/Trigger the ManagedBot

_Request URL:_

`POST` `http://localhost:7070/managedbots/weather_trigger/myUserId`

_Request Body_

```javascript
{
  "input": "Hello managed bot!",
  "context": {}
}
```

_Response Body_

```javascript
{
  "botId": "5bf5418c46e0fb000b7636d0",
  "botVersion": 10,
  "userId": "myUserId",
  "environment": "unrestricted",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationOutputs": [
    {
      "input": "Hello managed bot!",
      "expressions": "unknown(Hello), unknown(managed), unknown(bot!)",
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
          "value": "Hello managed bot!"
        }
      ],
      "timestamp": 1552869578596
    }
  ]
}
```

_Response Code_

`200`

_Response Headers_

```javascript
access-control-allow-headers: authorization, Content-Type 
access-control-allow-methods: GET, PUT, POST, DELETE, PATCH, OPTIONS 
access-control-allow-origin: * 
access-control-expose-headers: location 
connection: Keep-Alive 
content-length: 0 
date: Mon, 18 Mar 2019 00:31:07 GMT 
keep-alive: timeout=5, max=100 
server: Apache/2.4.29 (Ubuntu)
```

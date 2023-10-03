# Managed Bots

## Managed Bots

This feature will allow you to take advantage of **EDDI**'s automatic management of bots, it is possible to avoid creating conversations and managing them yourself, but let them be managed by **EDDI**.

This will act as a shortcut to start directly a conversation with a bot that covers a specific **intent**.

But first you will have to set up a `BotTrigger`.

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

# Conversations

## Conversations

In this section we will talk about how to send/receive messages from a Chatbot, the first step is the creation of the `conversation`, once you have the `conversation` `Id` you will be able to **send** a message to the Chabot through a **`POST`** and to **receive** the message through A **`GET`**, while having a the capacity to send context information through the body of the **POST** request as well.

> **Important** EDDI has a great feature for conversations with chat bots, it's the possibility to go back in time by using the two API endpoints : `/undo` and `/redo` !

## Creating/initiating a conversation :

###  Create a Conversation with a Chatbot REST API Endpoint

| Element | Tags |
| :--- | :--- |
| **HTTP Method** | `POST` |
| **Api endpoint** | `/bots/{environment}/{botId}` |
| **{environment}** | \(`Path` **parameter**\):`String` Deployment environment \(e.g: `restricted`,`unrestricted`,`test`\) |
| {**botId**} | \(`Path` **parameter**\):`String Id` of the bot that you wish to **start conversation with**. |

### Response Model

```javascript
{
  "botId": "string",
  "botVersion": Integer,
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

<table>
  <thead>
    <tr>
      <th style="text-align:left">Element</th>
      <th style="text-align:left">Tags</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left"><b>botId</b>
      </td>
      <td style="text-align:left">(<code>String</code>) The id of the bot that sent the reply.</td>
    </tr>
    <tr>
      <td style="text-align:left">botVersion</td>
      <td style="text-align:left">(<code>integer</code>) The version of the bot that sent the reply.</td>
    </tr>
    <tr>
      <td style="text-align:left">userId</td>
      <td style="text-align:left">(<code>String</code>) The id of the user who interacted with the bot.</td>
    </tr>
    <tr>
      <td style="text-align:left">environment</td>
      <td style="text-align:left">(<code>String</code>) the name of the environment where the bot is deployed</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationState</td>
      <td style="text-align:left">
        <p>(<code>String</code>) The state of the current conversation, could be <code>READY</code>,</p>
        <p><code>IN_PROGRESS</code>,</p>
        <p><code>ENDED</code>,</p>
        <p><code>EXECUTION_INTERRUPTED</code>,</p>
        <p><code>ERROR</code>
        </p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">redoCount</td>
      <td style="text-align:left">(<code>integer</code>) if undo has been performed, this number indicates
        how many times redo can be done (=times undo has been triggered)</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutputs</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>conversationOutput</code>&gt;) Array of <code>conversationOutput</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.input</td>
      <td style="text-align:left">(<code>String</code>) The user&apos;s input.</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.expressions</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>String</code>&gt;) an array of the <code>expressions </code>involved
        in the creation of this reply (output).</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.intents</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>String</code>&gt;) an array of the <code>intents </code>involved
        in the creation of this reply (output).</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.actions</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>String</code>&gt;) an array of the <code>actions </code>involved
        in the creation of this reply (output).</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.httpCalls</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>JsonObject</code>&gt;) an array of the <code>httpCalls </code>objects
        involved in the creation of this reply (output).</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.properties</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>JsonObject</code>&gt;) the list of available
        properties in the current conversation.</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationOutput.output</td>
      <td style="text-align:left">(<code>String</code>) The final bot&apos;s output</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationProperties</td>
      <td style="text-align:left">(<code>Array</code>: &lt;&gt;) Array of <code>conversationProperty</code>,
        &lt;<code>nameOfProperty</code>&gt; is a dynamic value that represents
        the name of the property</td>
    </tr>
    <tr>
      <td style="text-align:left">&lt;nameOfProperty&gt;.name</td>
      <td style="text-align:left">(<code>String</code>) name of the property.</td>
    </tr>
    <tr>
      <td style="text-align:left">&lt;nameOfProperty&gt;.value</td>
      <td style="text-align:left">(<code>String</code>|<code>JsonObject</code>) value of the property.</td>
    </tr>
    <tr>
      <td style="text-align:left">&lt;nameOfProperty&gt;.scope</td>
      <td style="text-align:left">
        <p>(<code>String</code>) scope can be</p>
        <p><code>step </code>(=valid one interaction [user input to user output]),</p>
        <p><code>conversation </code>(=valid for the entire conversation),</p>
        <p><code>longTerm </code>(=valid across conversations [based on given userId])</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">conversationSteps</td>
      <td style="text-align:left">(<code>Array</code>: &lt;<code>conversationStep</code>&gt;) Array of <code>conversationStep</code>.</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationStep.key</td>
      <td style="text-align:left">(<code>String</code>) the element key in the conversationStep e.g key
        : input:initial, actions</td>
    </tr>
    <tr>
      <td style="text-align:left">conversationStep.value</td>
      <td style="text-align:left">(<code>String</code>) the element value of the conversationStep e.g in
        case of <code>actionq </code>as <code>key </code>it could be an array string <code>[ &quot;current_weather_in_city&quot; ]</code>.</td>
    </tr>
    <tr>
      <td style="text-align:left">timestamp.timestamp</td>
      <td style="text-align:left">(<code>dateTime</code>) the timestamp in (ISO 8601) format</td>
    </tr>
  </tbody>
</table>> **Note** `conversationProperties` can also be used in output templating e.g: `[[${properties.city}]].`

### Sample Response

```javascript
{
  "botId": "5bf5418c46e0fb000b7636d0",
  "botVersion": 10,
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

The `conversationId` will be provided through the **`location`** **HTTP Header** of the response, you will use that later to submit messages to the Chabot to maintain a conversation.

### Example _:_

_Request URL:_

`http://localhost:7070/bots/unrestricted/5ad2ab182de29719b44a792a`

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

### Send message in a conversation with a Chatbot REST API Endpoint

<table>
  <thead>
    <tr>
      <th style="text-align:left">Element</th>
      <th style="text-align:left">Tags</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left">HTTP Method</td>
      <td style="text-align:left"><code>POST</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Api endpoint</td>
      <td style="text-align:left"><code>/bots/{environment}/{botId}/{conversationId}</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">{environment}</td>
      <td style="text-align:left">(<code>Path </code><b>parameter</b>):<code>String </code>Deployment environment
        (e.g: <code>restricted</code>,<code>unrestricted</code>,<code>test</code>)</td>
    </tr>
    <tr>
      <td style="text-align:left">botId</td>
      <td style="text-align:left">(<code>Path </code><b>parameter</b>):<code>String Id </code>of the bot
        that you wish to <b>continue a conversation with.</b>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">conversationId</td>
      <td style="text-align:left">(<code>Path </code><b>parameter</b>): <code>String Id </code>of the <b>conversation </b>that
        you wish to <b>send </b>the message to.</td>
    </tr>
    <tr>
      <td style="text-align:left">returnDetailed</td>
      <td style="text-align:left">(<code>Query </code><b>parameter</b>):<code>Boolean </code>- <b>Default </b>: <code>false</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">returnCurrentStepOnly</td>
      <td style="text-align:left">(<code>Query </code><b>parameter</b>):<code>Boolean </code>- <b>Default </b>: <code>true</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Request Body</td>
      <td style="text-align:left">
        <p>JSON Object , example : <code>{ &quot;input&quot;: &quot;the message&quot;, &quot;context&quot;: {} }</code>
        </p>
        <p>The <code>context </code>here is where you pass context variables that
          can be evaluated by EDDI, we will be explaining this in more details in
          Passing Context Information</p>
      </td>
    </tr>
  </tbody>
</table>### Example :

_Request URL_

`http://localhost:7070/bots/restricted/5aaf90e29f7dd421ac3c7dd4/5add1fe8a081a228a0588d1c?returnDetailed=false&returnCurrentStepOnly=true`

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
  "botId": "5aaf90e29f7dd421ac3c7dd4",
  "botVersion": 1,
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

### Receive message in a conversation with a Chatbot REST API Endpoint

| Element | Tags |
| :--- | :--- |
| HTTP Method | `GET` |
| Api endpoint | `/bots/{environment}/{botId}/{conversationId}` |
| {environment} | \(`Path` **parameter**\):`String` Deployment environment \(e.g: `restricted,unrestricted,test`\) |
| {botId} | \(`Path` **parameter**\):`String Id` of the bot that you wish to **continue a conversation** **with**. |
| {conversationId} | \(`Path` **parameter**\): `String Id` of the **conversation** that you wish to **receive** a the message from. |
| returnDetailed | \(`Query` **parameter**\):`Boolean` - **Default** : `false` |
|  |  |

### Example

_Request URL:_

`http://localhost:7070/bots/unrestricted/5aaf90e29f7dd421ac3c7dd4/5add1fe8a081a228a0588d1c?returnDetailed=false`

_Response Body_

```javascript
{
  "botId": "5aaf90e29f7dd421ac3c7dd4",
  "botVersion": 1,
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

## Undo and redo :

The undo and redo methods basically allow you to return a **step back** in a conversation, this is done by sending a **`POST`** along with bot and conversation ids to `/bots/{environment}/`**`{botId}`**`/redo/`**`{conversationId}`** endpoint**,** the `GET` call of the same endpoint with the same parameters will allow you to see if the last submitted **undo**/**redo** was successful by receiving a `true` or `false` in the **response body.**

### Undo and redo in a conversation REST API Endpoint

| Element | Tags |
| :--- | :--- |
| HTTP Method | `POST` |
| Api endpoint | `/bots/{environment}/{botId}/[undo/redo]/{conversationId}` |
| {environment} | \(`Path` **parameter**\):`String` Deployment environment \(e.g: `restricted,unrestricted,test`\) |
| {botId} | \(`Path` parameter\):`String Id` of the bot that you wish to **continue a conversation with**. |
| {conversationId} | \(`Path` **parameter**\): `String Id` of the **conversation** that you wish to **undo/redo** the last conversation step. |

### Example \(undo\)

_Request URL:_

`http://localhost:7070/bots/restricted/5aaf98e19f7dd421ac3c7de9/undo/5ade58dda081a23418503d6f`

_Response Body_

`no content`

_Response Code_

`200`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Mon, 23 Apr 2018 22:20:57 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "content-type": null
}
```

### Sample bot:

{% file src=".gitbook/assets/weather\_bot\_v2.zip" caption="Weather-bot-v2.zip" %}


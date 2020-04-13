# HttpCalls

## HttpCalls

In this article we will talk about EDDI's **`httpCalls`** **feature** \(calling other `JSON` APIS\).

The **`httpCalls`** feature allows a **Chatbot** to consume **3rd** party APIs and use the `JSON` response in another **`httpCall`** \(for **authentication** or requesting a token for instance\) or directly print the results in Chatbot's `Output,` this means, for example, you can call a weather API and use the `JSON` response in your Chatbot's output if the user asks about today's weather or the week's forecast!

We will emphasize the `httpCall` model and go through an example step by step, you can also download the example in **Postman** collection format and run the steps.

## Model and API endpoint

```javascript
{
  "targetServer": "string",
  "httpCalls": [
    {
      "name": "string",
      "saveResponse": boolean,
      "fireAndForget": boolean,
      "responseObjectName": "string",
      "actions": [
        "string"
      ],
      "preRequest": {
        "batchRequests": {
          "pathToTargetArray": "string",
          "iterationObjectName": "string"
        }
      },
      "request": {
        "path": "string",
        "headers": {},
        "queryParams": {},
        "method": "string",
        "contentType": "string",
        "body": "string"
      },
      "postResponse": {
        "qrBuildInstruction": {
          "pathToTargetArray": "String",
          "iterationObjectName": "String",
          "quickReplyValue": "String",
          "quickReplyExpressions": "String"
        },
        "propertyInstructions": [
          {
            "name": "string",
            "value": "string",
            "scope": "string",
            "fromObjectPath": "savedObjName.something.something",
            "override": boolean,
            "httpCodeValidator": {
              "runOnHttpCode": [
                <array of Integers>
              ],
              "skipOnHttpCode": [
                <array of Integers>
              ]
            }
          }
        ]
      }
    }
  ]
}
```

### Description

An `httpCall` is mainly composed from the `targetServer` `array` of `httpCalls`, the latter will have request where you put all details about your actual **http request** \(`method`,`path`,`headers`, etc..\) and postResponse where you can define what happens after the `httpCall` has been executed and a `response` has been received; such as quick replies by using `qrBuildInstruction`.

You can _**`${memory.current.httpCalls.<responseObjectName>}`**_ to access your `JSON` object, so you can use it in `output templating` or in another `httpCall`, for example an `httpCall` will get the `oAuth` `token` and another `httpCall` will use in the `http` `headers` to authenticate to an API.

### Description of the model

<table>
  <thead>
    <tr>
      <th style="text-align:left">Element</th>
      <th style="text-align:left">Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left">targetServer</td>
      <td style="text-align:left">(<code>String</code>) <code>root/context</code> path of the<code> httpCall</code> (e.g <code>http://example.com/api)</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.saveResponse</td>
      <td style="text-align:left">(<code>Boolean</code>) whether to save the <code>JSON </code>response into<code> ${memory.current.httpCalls}</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.fireAndForget</td>
      <td style="text-align:left">(<code>Boolean</code>) whether to execute the request without waiting
        for a response to be returned, (Useful for <code>POST</code>)</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.responseObjectName</td>
      <td style="text-align:left">(<code>String</code>) name of the <code>JSON </code>object so it can be
        accessed from other <code>httpCalls </code>or <code>outputsets</code>.</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.actions</td>
      <td style="text-align:left">(<code>String</code>) name of the <code>output</code>/<code>behavior </code>set
        mapped to this http call.</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.preRequest.batchRequests.pathToTargetArray</td>
      <td style="text-align:left">(<code>String</code>) json path to the target array to be used as body
        of requests e.g: &quot;<code>memory.current.output</code>&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.preRequest.batchRequests.iterationObjectName</td>
      <td style="text-align:left">(<code>String</code>) name of the variable to be used for each element
        of array found in <code>pathToTargetArray</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.request.path</td>
      <td style="text-align:left">(<code>String</code>) path in the <code>targetServer </code>of the <code>httpCall </code>(e.g
        /<code>books</code>)</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.request.headers</td>
      <td style="text-align:left">(<code>Array</code>:&lt;key,value&gt; ) for each <code>httpCall HTTP header</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.request.queryParams</td>
      <td style="text-align:left">(<code>Array</code>: &lt;key,value&gt;) for each <code>httpCall </code>query
        parameter</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.request.method</td>
      <td style="text-align:left">(<code>String</code>) <code>HTTP </code>Method of the <code>httpCall </code>(e.g <code>GET</code>,<code>POST</code>,etc...)</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.request.contentType</td>
      <td style="text-align:left">(<code>String</code>) value of the <code>contentType HTTP header </code>of
        the <code>httpCall</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.request.body</td>
      <td style="text-align:left">(<code>String</code>) an escaped <code>JSON </code>object that goes in
        the <code>HTTP Request </code>body if needed.</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.qrBuildInstruction.pathToTargetArray</td>
      <td style="text-align:left">(<code>String</code>) path to the array in your <code>JSON </code><b>response data.</b>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.qrBuildInstruction.iterationObjectName</td>
      <td style="text-align:left">(<code>String</code>) a variable name that will point to the <code>TargetArray.</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.qrBuildInstruction.quickReplyValue</td>
      <td style="text-align:left">(<code>String</code>) <code>thymeleaf expression </code>to use as a <code>quickReply </code>value.</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.qrBuildInstruction.quickReplyExpressions</td>
      <td
      style="text-align:left">(<code>String</code>) <code>expression </code>to retrieve a property from <code>iterationObjectName</code>.</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.name</td>
      <td style="text-align:left">(<code>String</code>) name of property to be used in templating</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.value</td>
      <td style="text-align:left">(<code>String</code>) a static value can be set here if <code>fromObjectPath</code> is
        not defined.</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.scope</td>
      <td style="text-align:left">
        <p>(<code>String</code>) Can be either :</p>
        <p><code>step </code>used for only for one user interaction</p>
        <p><code>conversation </code>for entire conversation and</p>
        <p><code>longTerm </code>for between conversations</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.fromObjectPath</td>
      <td style="text-align:left">(<code>String</code>) JSON path to the saved object e.g <code>savedObjName.something.something</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.override</td>
      <td style="text-align:left">(<code>Boolean</code>) flag for override</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.httpCodeValidator.runOnHttpCode</td>
      <td
      style="text-align:left">(<code>Array</code>:<b> </b>&lt;Integer&gt; ) a list of http code that
        enables this property instruction e.g [<code>200</code>]</td>
    </tr>
    <tr>
      <td style="text-align:left">httpCall.postResponse.propertyInstructions.httpCodeValidator.skipOnHttpCode</td>
      <td
      style="text-align:left">(<code>Array</code>: &lt;Integer&gt;) list of http code that enables this
        property instruction e.g [<code>500,501,400</code>]</td>
    </tr>
  </tbody>
</table>### HttpCall API endpoints

| HTTP Method | API Endpoint | Request Body | Response |
| :--- | :--- | :--- | :--- |
| POST | `/httpcallsstore/httpcalls` | http-call-model | N/A |
| GET | `/httpcallsstore/httpcalls/descriptors` | N/A | list of references to http-call-model |
| DELETE | `/httpcallsstore/httpcalls/{id}` | N/A | N/A |
| GET | `/httpcallsstore/httpcalls/{id}` | N/A | http-call-model |
| PUT | `/httpcallsstore/httpcalls/{id}` | http-call-model | N/A |
| GET | `/httpcallsstore/httpcalls/{id}/currentversion` | N/A | http-call-model |
| POST | `/httpcallsstore/httpcalls/{id}/currentversion` | http-call-model | N/A |

### httpCall Sample

```javascript
{
  "targetServer": "https://api.bot-metrics.com/v1/messages",
  "httpCalls": [
    {
      "name": "sendUserMessageToAnalytics",
      "actions": [
        "send_input_to_analytics"
      ],
      "saveResponse": false,
      "fireAndForget": true,
      "request": {
        "method": "post",
        "queryParams": {
          "token": "<token>"
        },
        "contentType": "application/json",
        "body": "{\"text\": \"[[${memory.current.input}]]\",\"message_type\": \"incoming\",\"user_id\": \"[[${memory.current.userInfo.userId}]]\",\"platform\": \"eddi\"}"
      }
    },
    {
      "name": "sendBotMessageToAnalytics",
      "actions": [
        "send_output_to_analytics"
      ],
      "saveResponse": false,
      "fireAndForget": true,
      "preRequest": {
        "batchRequests": {
          "pathToTargetArray": "memory.current.output",
          "iterationObjectName": "output"
        }
      },
      "request": {
        "method": "post",
        "queryParams": {
          "token": "<token>"
        },
        "contentType": "application/json",
        "body": "{\"text\": \"[[${output}]]\",\"message_type\": \"outgoing\",\"user_id\": \"[[${memory.current.userInfo.userId}]]\",\"platform\": \"eddi\"}"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "nameOfPropertyToBeUsedInTemplating",
            "value": "StaticValueHereIfFromObjectPathIsNotDefined",
            "scope": "step",
            "fromObjectPath": "savedObjName.something.something",
            "override": true,
            "httpCodeValidator": {
              "runOnHttpCode": [
                200
              ],
              "skipOnHttpCode": [
                0,
                400,
                401,
                402,
                403,
                404,
                409,
                410,
                500,
                501,
                502
              ]
            },
            "qrBuildInstruction": {
              "pathToTargetArray": "savedObjName.data.topics",
              "iterationObjectName": "topic",
              "templateFilterExpression": "${topic.subType} != 'specialSubType'",
              "quickReplyValue": "[(${topic.name})]",
              "quickReplyExpressions": "property(topic_id([(${topic.id})]))"
            }
          }
        ]
      }
    }
  ]
}
```

## Step by step example

We will do a step by step example from scratch \(**Chatbot** creation to a simple conversation that uses `httpCall`\)

For the sake of simplicity we will use a free weather API to fetch weather of cities by their names \([api.openweathermap.org](http://api.openweathermap.org/)\).

### 1 - Create regularDictionnary

> More about regular dictionaries can be found [here](creating-your-first-chatbot.md#1-creating-a-regular-dictionary).

_Request URL_

`POST` `http://localhost:7070/regulardictionarystore/regulardictionaries`

_Request Body_

```javascript
{
  "words": [
    {
      "word": "weather",
      "exp": "trigger(current_weather)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "what is the weather",
      "exp": "trigger(current_weather)"
    },
    {
      "phrase": "whats the weather",
      "exp": "trigger(current_weather)"
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`201`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 16:40:58 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/5af86a9aba31c023bcb9ef2b?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 2 - Create the behaviorSet

> More about behaviorSets can be found in [Behavior Rules](behavior-rules.md)

_Request URL_

`POST` `http://localhost:7070/behaviorstore/behaviorsets`

_Response Body_

`no content`

_Response Code_

`201`

_Request Body_

```javascript
{
  "behaviorGroups": [
    {
      "name": "",
      "behaviorRules": [
        {
          "name": "Ask for City",
          "actions": [
            "ask_for_city"
          ],
          "children": [
            {
              "type": "inputmatcher",
              "values": {
                "expressions": "trigger(current_weather)"
              },
              "children": []
            }
          ]
        },
        {
          "name": "Current Weather in City",
          "actions": [
            "current_weather_in_city"
          ],
          "children": [
            {
              "type": "inputmatcher",
              "values": {
                "expressions": "trigger(current_weather)",
                "occurrence": "lastStep"
              },
              "children": []
            }
          ]
        }
      ]
    }
  ]
}
```

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 16:45:52 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/5af86bc0ba31c023bcb9ef2c?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 3 - Create the **httpCall**

Note that we can pass user input to the http call using _**`[[${memory.current.input}]]`**_

_Request URL_

`POST` `http://localhost:7070/httpcallsstore/httpcalls`

_Request Body_

```javascript
{
  "targetServer": "https://api.openweathermap.org/data/2.5/weather",
  "httpCalls": [
    {
      "name": "currentWeather",
      "saveResponse": true,
      "responseObjectName": "currentWeather",
      "actions": [
        "current_weather_in_city"
      ],
      "request": {
        "path": "",
        "headers": {},
        "queryParams": {
          "APPID": "c3366d78c7c0f76d63eb4cdf1384ddbf",
          "units": "metric",
          "q": "[[${memory.current.input}]]"
        },
        "method": "get",
        "contentType": "",
        "body": ""
      }
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`201`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 19:13:52 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/5af88e70ba31c023bcb9ef2e?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 4 - Create the outputSet

> More about outputSet can be found [Output Configuration](output-configuration.md).
>
> Note When you set `"saveResponse" : true` in `httpCall` then you can use `[[${memory.current.httpCalls.<responseObjectName>}]]` to access the response data and use `thymeleaf`\( `th:each` \) to iterate over `JSON` `arrays` if you have them in your `JSON` response.

_Request URL_

`POST` `http://localhost:7070/outputstore/outputsets`

_Request Body_

```javascript
{
  "outputSet": [
    {
      "action": "ask_for_city",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Which City would you like to know the weather of?"
          ]
        }
      ],
      "quickReplies": []
    },
    {
      "action": "current_weather_in_city",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "The current weather situation of [[${memory.current.input}]] is [[${memory.current.httpCalls.currentWeather.weather[0].description}]] at [[${memory.current.httpCalls.currentWeather.main.temp}]] °C"
          ]
        }
      ],
      "quickReplies": []
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`201`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 16:48:37 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.output/outputstore/outputsets/5af86c65ba31c023bcb9ef2d?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 5 - Creating the package

> More about packages can be found [here](creating-your-first-chatbot.md#4-creating-the-package).
>
> Important Package note
>
> * `ai.labs.httpcalls` & `ai.labs.output` must come after `ai.labs.behavior` in order of the package definition
> * `ai.labs.templating` has to be after `ai.labs.output`

_Request URL_

`POST` `http://localhost:7070/packagestore/packages`

_Request Body_

```javascript
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.parser",
      "extensions": {
        "dictionaries": [
          {
            "type": "eddi://ai.labs.parser.dictionaries.integer"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.decimal"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.punctuation"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.email"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.time"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.ordinalNumber"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.regular",
            "config": {
              "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/{{dictionary_id}}?version=1"
            }
          }
        ],
        "corrections": [
          {
            "type": "eddi://ai.labs.parser.corrections.stemming",
            "config": {
              "language": "english",
              "lookupIfKnown": "false"
            }
          },
          {
            "type": "eddi://ai.labs.parser.corrections.levenshtein",
            "config": {
              "distance": "2"
            }
          },
          {
            "type": "eddi://ai.labs.parser.corrections.mergedTerms"
          }
        ]
      },
      "config": {}
    },
    {
      "type": "eddi://ai.labs.behavior",
      "config": {
        "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/{{behaviourset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.httpcalls",
      "config": {
        "uri": "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/{{httpcall_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/{{outputset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.templating",
      "extensions": {},
      "config": {}
    }
  ]
}
```

Response Body

`no content`

Response Code

`201`

Response Headers

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 19:26:36 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.package/packagestore/packages/5af8916cba31c023bcb9ef2f?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 6 - Creating the bot

_Request URL_

`POST` `http://localhost:7070/botstore/bots`

_Request Body_

```javascript
{
  "packages": [
    "eddi://ai.labs.package/packagestore/packages/{{package_id}}?version=1"
  ],
  "channels": []
}
```

_Response Body_

`no content`

_Response Code_

`201`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 21:18:16 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.bot/botstore/bots/5af8ab98ba31c023bcb9ef32?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 7 - Deploy the bot

_Request URL_

`POST` `http://localhost:7070/administration/restricted/deploy/**<bot_id>**?version=1&autoDeploy=true`

_Response Body_

`no content`

_Response Code_

`202`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 21:21:54 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 8 - Create the conversation

_Request URL_

`POST` `http://localhost:7070/bots/**<env>**/**<bot_id>**`

_Response Body_

`no content`

_Response Code_

`201`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 21:30:45 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.conversation/conversationstore/conversations/5af8ae85ba31c023bcb9ef35",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```

### 9 - Say weather

_Request URL_

`POST` `http://localhost:7070/bots/<env>/<bot_id>/<conversation_id>?returnDetailed=false&returnCurrentStepOnly=true`

_Request Body_

```javascript
{
  "input": "weather"
}
```

_Response Body_

```javascript
{
  "botId": "5af8b075ba31c023bcb9ef3b",
  "botVersion": 1,
  "environment": "unrestricted",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "weather"
        },
        {
          "key": "actions",
          "value": [
            "ask_for_city"
          ]
        },
        {
          "key": "output:text:ask_for_city",
          "value": "Which City would you like to know the weather of?"
        }
      ],
      "timestamp": 1526247548410
    }
  ]
}
```

_Response Code_

`200`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 21:35:15 GMT",
  "content-type": "application/json;resteasy-server-has-produces=true",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "325",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location"
}
```

### 10 - Say "Vienna"

_Request URL_

`POST` `http://localhost:7070/bots/<env>/<bot_id>/<conversation_id>?returnDetailed=false&returnCurrentStepOnly=true`

_Request Body_

```javascript
{
  "botId": "5af8b075ba31c023bcb9ef3b",
  "botVersion": 1,
  "environment": "unrestricted",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "Vienna"
        },
        {
          "key": "actions",
          "value": [
            "current_weather_in_city"
          ]
        },
        {
          "key": "output:text:current_weather_in_city",
          "value": "The current weather situation of Vienna is clear sky at 17.68 °C"
        }
      ],
      "timestamp": 1526247618080
    }
  ]
}
```

_Response Code_

`200`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sun, 13 May 2018 21:35:15 GMT",
  "content-type": "application/json;resteasy-server-has-produces=true",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "325",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location"
}
```

## Full example

If you would like to run the full example through postman , you can download and import the collection below.

{% file src=".gitbook/assets/eddi-weather-bot.postman\_collection.json" caption="EDDI - Weather bot.postman\_collection.json" %}




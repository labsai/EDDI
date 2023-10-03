# HttpCalls

## HttpCalls

In this article we will talk about EDDI's **`httpCalls`** **feature** (calling other `JSON` APIs).

The **`httpCalls`** feature allows a **Chatbot** to consume **3rd** party APIs and use the `JSON` response in another **`httpCall`** (for **authentication** or requesting a token for instance) or directly print the results in Chatbot's `Output,` this means, for example, you can call a weather API and use the `JSON` response in your Chatbot's output if the user asks about today's weather or the week's forecast!

We will emphasize the `httpCall` model and go through an example step by step, you can also download the example in **Postman** collection format and run the steps.

## Model and API endpoint

```javascript
{
  "targetServerUrl": "string",
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

An `httpCall` is mainly composed from the `targetServer` `array` of `httpCalls`, the latter will have request where you put all details about your actual **http request** (`method`,`path`,`headers`, etc..) and postResponse where you can define what happens after the `httpCall` has been executed and a `response` has been received; such as quick replies by using `qrBuildInstruction`.

You can use _**`${memory.current.httpCalls.<responseObjectName>}`**_ to access your `JSON` object, so you can use it in `output templating` or in another `httpCall`, for example an `httpCall` will get the `oAuth` `token` and another `httpCall` will use in the `http` `headers` to authenticate to an API.

### Description of the model

| Element                                                                     | Description                                                                                                                                                                                                                      |
| --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| targetServerUrl                                                             | (`String`) `root/context` path of the `httpCall` (e.g `http://example.com/api)`                                                                                                                                                  |
| httpCall.saveResponse                                                       | (`Boolean`) whether to save the `JSON` response into `${memory.current.httpCalls}`                                                                                                                                               |
| httpCall.fireAndForget                                                      | (`Boolean`) whether to execute the request without waiting for a response to be returned, (useful for `POST`)                                                                                                                    |
| httpCall.responseObjectName                                                 | (`String`) name of the `JSON` object so it can be accessed from other `httpCalls` or `outputsets`.                                                                                                                               |
| httpCall.actions                                                            | (`String`) name of the `output`/`behavior` set mapped to this http call.                                                                                                                                                         |
| httpCall.preRequest.batchRequests.pathToTargetArray                         | (`String`) `JSON` path to the target array to be used as body of requests e.g: "`memory.current.output`"                                                                                                                         |
| httpCall.preRequest.batchRequests.iterationObjectName                       | (`String`) name of the variable to be used for each element of array found in `pathToTargetArray`                                                                                                                                |
| httpCall.request.path                                                       | (`String`) path in the `targetServer` of the `httpCall` (e.g /`books`)                                                                                                                                                           |
| httpCall.request.headers                                                    | (`Array`:\<key, value> ) for each `httpCall HTTP header`                                                                                                                                                                         |
| httpCall.request.queryParams                                                | (`Array`: \<key, value>) for each `httpCall` query parameter                                                                                                                                                                     |
| httpCall.request.method                                                     | (`String`) `HTTP` Method of the `httpCall` (e.g `GET`,`POST`,etc...)                                                                                                                                                             |
| httpCall.request.contentType                                                | (`String`) value of the `contentType HTTP header` of the `httpCall`                                                                                                                                                              |
| httpCall.request.body                                                       | (`String`) an escaped `JSON` object that goes in the `HTTP Request` body if needed.                                                                                                                                              |
| httpCall.postResponse.qrBuildInstruction.pathToTargetArray                  | (`String`) path to the array in your `JSON` **response data.**                                                                                                                                                                   |
| httpCall.postResponse.qrBuildInstruction.iterationObjectName                | (`String`) a variable name that will point to the `TargetArray.`                                                                                                                                                                 |
| httpCall.postResponse.qrBuildInstruction.quickReplyValue                    | (`String`) `thymeleaf expression` to use as a `quickReply` value.                                                                                                                                                                |
| httpCall.postResponse.qrBuildInstruction.quickReplyExpressions              | (`String`) `expression` to retrieve a property from `iterationObjectName`.                                                                                                                                                       |
| httpCall.postResponse.propertyInstructions.name                             | (`String`) name of property to be used in templating                                                                                                                                                                             |
| httpCall.postResponse.propertyInstructions.value                            | (`String`) a static value can be set here if `fromObjectPath` is not defined.                                                                                                                                                    |
| httpCall.postResponse.propertyInstructions.scope                            | <p>(<code>String</code>) Can be either : </p><p><code>step</code> used for only for one user interaction </p><p><code>conversation</code> for entire conversation and </p><p><code>longTerm</code> for between conversations</p> |
| httpCall.postResponse.propertyInstructions.fromObjectPath                   | (`String`) JSON path to the saved object e.g `savedObjName.something.something`                                                                                                                                                  |
| httpCall.postResponse.propertyInstructions.override                         | (`Boolean`) flag for override                                                                                                                                                                                                    |
| httpCall.postResponse.propertyInstructions.httpCodeValidator.runOnHttpCode  | (`Array`: \<Integer> ) a list of http code that enables this property instruction e.g \[`200`]                                                                                                                                   |
| httpCall.postResponse.propertyInstructions.httpCodeValidator.skipOnHttpCode | (`Array`: \<Integer>) list of http code that enables this property instruction e.g \[`500,501,400`]                                                                                                                              |

### HttpCall API endpoints

| HTTP Method | API Endpoint                                    | Request Body    | Response                              |
| ----------- | ----------------------------------------------- | --------------- | ------------------------------------- |
| POST        | `/httpcallsstore/httpcalls`                     | http-call-model | N/A                                   |
| GET         | `/httpcallsstore/httpcalls/descriptors`         | N/A             | list of references to http-call-model |
| DELETE      | `/httpcallsstore/httpcalls/{id}`                | N/A             | N/A                                   |
| GET         | `/httpcallsstore/httpcalls/{id}`                | N/A             | http-call-model                       |
| PUT         | `/httpcallsstore/httpcalls/{id}`                | http-call-model | N/A                                   |
| GET         | `/httpcallsstore/httpcalls/{id}/currentversion` | N/A             | http-call-model                       |
| POST        | `/httpcallsstore/httpcalls/{id}/currentversion` | http-call-model | N/A                                   |

### httpCall Sample

```javascript
{
  "targetServerUrl": "https://api.bot-metrics.com/v1/messages",
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

We will do a step by step example from scratch (**Chatbot** creation to a simple conversation that uses `httpCall`)

For the sake of simplicity we will use a free weather API to fetch weather of cities by their names ([api.openweathermap.org](http://api.openweathermap.org/)).

### 1 - Create regularDictionnary

> More about regular dictionaries can be found [here](creating-your-first-chatbot/#1-creating-a-regular-dictionary).

_Request URL_

`POST` `http://localhost:7070/regulardictionarystore/regulardictionaries`

_Request Body_

```javascript
{
  "words": [
    {
      "word": "weather",
      "expressions": "trigger(current_weather)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "what is the weather",
      "expressions": "trigger(current_weather)"
    },
    {
      "phrase": "whats the weather",
      "expressions": "trigger(current_weather)"
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
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "trigger(current_weather)"
              }
            }
          ]
        },
        {
          "name": "Current Weather in City",
          "actions": [
            "current_weather_in_city"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "trigger(current_weather)",
                "occurrence": "lastStep"
              }
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
  "targetServerUrl": "https://api.openweathermap.org/data/2.5/weather",
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
> Note When you set `"saveResponse" : true` in `httpCall` then you can use `[[${memory.current.httpCalls.<responseObjectName>}]]` to access the response data and use `thymeleaf`( `th:each` ) to iterate over `JSON` `arrays` if you have them in your `JSON` response.

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
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Which City would you like to know the weather of?"
            }
          ]
        }
      ]
    },
    {
      "action": "current_weather_in_city",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "The current weather situation of [[${memory.current.input}]] is [[${memory.current.httpCalls.currentWeather.weather[0].description}]] at [[${memory.current.httpCalls.currentWeather.main.temp}]] °C"
            }
          ]
        }
      ]
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

> More about packages can be found [here](creating-your-first-chatbot/#4-creating-the-package).
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

If you would like to run the full example through postman, you can download and import the collection below.

{% file src=".gitbook/assets/EDDI - Weather bot.postman_collection.json" %}
EDDI - Weather bot.postman\_collection.json
{% endfile %}


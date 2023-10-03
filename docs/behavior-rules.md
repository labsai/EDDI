# Behavior Rules

## Behavior Rules

`Behavior Rules` are very flexible in structure to cover most use cases that you will come across. `Behavior Rules` are clustered in `Groups`. `Behavior Rules` are executed sequential within each `Group`. As soon as one `Behavior Rule` succeeds, all remaining `Behavior Rules` in this `Group` will be skipped.

## **Groups**

```javascript
{
  "behaviorGroups": [
    {
      "name": "GroupName",
      "behaviorRules": [
        {
          "name": "RuleName",
          "actions": [
            "action-to-be-triggered"
          ],
          "conditions": [
            <CONDITIONS>
          ]
        },
        {
          "name": "DifferentRule",
          "actions": [
            "another-action-to-be-triggered"
          ],
          "conditions": [
            <CONDITIONS>
          ]
        },
        <MORE_RULES>
      ]
    }
  ]
}
```

## Type of Conditions

Each `Behavior Rule` has a list of `conditions`, that, depending on the `condition` , might have a list of `sub-conditions`.&#x20;

> **If all conditions are true, then the Behavior Rule is successful and it will trigger predefined actions**.

### List of available conditions:

* [Input Matcher](behavior-rules.md#input-matcher)
* [Context Matcher](behavior-rules.md#context-matcher)
* [Connector](behavior-rules.md#connector)
* [Negation](behavior-rules.md#negation)
* [Occurrence](behavior-rules.md#occurrence)
* [Dependency](behavior-rules.md#dependency)
* [Action Matcher](behavior-rules.md#action-matcher)
* [Dynamic Value Matcher](behavior-rules.md#dynamic-value-matcher)

### General Structure

`conditions` are always children of either a `Behavior Rule` or another `condition`. It will always follows that same structure.

### Description of condition structure



### Input Matcher

The `inputmatcher` is used to match **user inputs**. Not directly the real input of the user, but the meaning of it, represented by `expressions` that are **resolved** from by the `parser`.

### Description

| Element | Value          | Description                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| type    | `inputmatcher` |                                                                                                                                                                                                                                                                                                                                                                                                                                |
| configs | `expressions`  | <p>comma separated list of</p><p><code>expressions</code> such as:</p><p><code>expression(value),expression2(value2),</code></p><p><code>yetAnotherExpressions(anotherValue(withASubValue))</code></p>                                                                                                                                                                                                                         |
|         | `occurrence`   | <p><code>currentStep</code> - used in case if the user said it in this <code>conversationStep</code></p><p><code>lastStep</code> - used in case if the user said it in the previous <code>conversationStep</code></p><p><code>anyStep</code> - used in case if the user said it in any step if this whole conversation</p><p><code>never</code> - used in case if the user has never said that, including the current step</p> |

If the **user** would type "hello", and the parser resolves this as expressions "`greeting(hello)`" _\[assuming it has been defined in one of the dictionaries]_, then a `condition` could look as following in order to match this user input meaning:

```javascript
(...)
  "conditions": [
    {
      "type": "inputmatcher",
      "configs": {
        "expressions": "greeting(*)",
        "occurrence": "currentStep"
      }
    }
  ]
(...)
```

This `inputmatcher` `condition` will match any `expression` of type greeting, may that be "`greeting(hello)`", "`greeting(hi)`" or anything else. Of course, if you would want to match `greeting(hello)` explicitly, you would put "`greeting(hello)`" as value for the "`expressions`" field.

### Context Matcher

The `contextmatcher` is used to match `context` data that has been handed over to **EDDI** alongside the user input. This is great to check certain `conditions` that come from another system, such as the day time or to check the existence of user data.

### Description

| Element | Value                                                                                    | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------- | ---------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| type    | `contextmatcher`                                                                         |                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| configs | `contextKey`                                                                             | The key for this context (defined when handing over context to **EDDI**)                                                                                                                                                                                                                                                                                                                                                                                         |
|         | `contextType`                                                                            | <p><code>expressions</code> </p><p><code>object</code> </p><p><code>string</code></p>                                                                                                                                                                                                                                                                                                                                                                            |
|         | `expressions` (if `contextType=expressions`)                                             | A `list` of comma separated `expressions`                                                                                                                                                                                                                                                                                                                                                                                                                        |
|         | <p><code>objectKeyPath</code> (if contextType=object)</p><p><code>objectValue</code></p> | <p> Allows match via <code>Jsonpath</code>, such as "<code>profile.username</code>" (see: <a href="https://github.com/rest-assured/rest-assured/wiki/Usage"><code>https://github.com/rest-assured/rest-assured/wiki/Usage</code></a><code>)</code> </p><p>Exp: <code>contextKey</code>: <code>userInfo</code> , <code>contextValue</code>: <code>{"profile":{"username":"John"}}</code> The value to be match with the extracted <code>JsonPath</code> value</p> |
|         | string                                                                                   | `string` matching (`equals`)                                                                                                                                                                                                                                                                                                                                                                                                                                     |

### Examples

```javascript
(...)
  "conditions": [
    {
      "type": "contextmatcher",
      "configs": {
        "contextType": "expressions",
        "contextKey": "someContextName",
        "expressions": "contextDataExpression(*)"
      }
    }
  ]
(...)

(...)
  "conditions": [
    {
      "type": "contextmatcher",
      "configs": {
        "contextType": "object",
        "contextKey": "userInfo",
        "objectKeyPath": "profile.username",
        "objectValue": "John"
      }
    }
  ]
(...)

(...)
  "conditions": [
    {
      "type": "contextmatcher",
      "configs": {
        "contextType": "string",
        "contextKey": "daytime",
        "string": "night"
      }
    }
  ]
(...)
```

### Connector

The `connector` is there to all logical `OR` conditions within rules. By default all conditions are `AND` `conditions`, but in some cases it might be suitable to connect conditions with a logical `OR`.

### Description

| Element | Value                             |
| ------- | --------------------------------- |
| type    | `connector`                       |
| values  | `operator` (either `AND` or `OR`) |

### **Examples**

```javascript
(...)
  "conditions": [
    {
      "type": "connector",
      "configs": {
        "operator": "OR"
      },
      "conditions": [
        <any other conditions>
      ]
    }
  ]
(...)
```

### Negation

Inverts the overall outcome of the children conditions

In some cases it is more relevant if a `condition` is `false` than if it is `true`, this is where the `negation` `condition` comes into play. The logical result of all children together (`AND` connected), will be _**inverted**_.

### Example:

```bash
Child 1 - true
Child 2 - true
→ Negation = false
Child 1 - false
Child 2 - true
→ Negation = true

(...)
  "conditions": [
    {
      "type": "negation",
      "conditions": [
        <any other conditions>
      ]
    }
  ]
(...)
```

### Occurrence

Defines the occurrence/frequency of an action in a `Behavior Rule`.

```javascript
(...)
{
  "type": "occurrence",
  "configs": {
    "maxTimesOccurred": "0",
    "minTimesOccurred": "0",
    "behaviorRuleName": "Welcome"
  }
}
(...)
```

### Dependency

Check if another `Behavior Rule` has met it's condition or not in the same `conversationStep`. Sometimes you need to know if a rule has succeeded , `dependency` will take that rule that hasn't been executed yet in a sandbox environment as a `reference` for an other behavior rule.

```javascript
(...)
{
  "type": "dependency",
  "configs": {
    "reference": "<name-of-another-behavior-rule>"
  }
}
(...)
```

### Action Matcher

As `inputMatcher` doesn't look at expressions but it looks for actions instead, imagine a `Behavior Rule` has been triggered and you want to check if that action has been triggered before.

```javascript
(...)
{
  "type": "actionmatcher",
  "configs": {
    "actions": "show_available_products",
    "occurrence": "lastStep"
  }
}
(...)
```

### Dynamic Value Matcher

This will allow you to compile a condition based on any http request/properties or any sort of variables available in EDDI's context.

```javascript
(...)
  {
  "type": "dynamicvaluematcher",
  "configs": {
    "valuePath": "memory.current.httpCalls.someObj.errors",
    "contains": "partly matching",
    "equals": "needs to be equals"
  }
}
(...)
```

## The Behavior Rule API Endpoints

The API Endpoints below will allow you to manage the `Behavior Rule`s in your EDDI instance.

The **`{id}`** is a path parameters that indicate which behavior rule you want to alter.

### API Methods

| HTTP Method | API Endpoint                                      | Request Body          | Response              |
| ----------- | ------------------------------------------------- | --------------------- | --------------------- |
| **DELETE**  | `/behaviorstore/behaviorsets/{id}`                | N/A                   | N/A                   |
| **GET**     | `/behaviorstore/behaviorsets/{id}`                | N/A                   | **BehaviorSet model** |
| **PUT**     | `/behaviorstore/behaviorsets/{id}`                | **BehaviorSet model** | N/A                   |
| **GET**     | `/behaviorstore/behaviorsets/descriptors`         | N/A                   | **BehaviorSet model** |
| **POST**    | `/behaviorstore/behaviorsets`                     | **BehaviorSet model** | N/A                   |
| **GET**     | `/behaviorstore/behaviorsets/{id}/currentversion` | N/A                   | **BehaviorSet model** |
| **POST**    | `/behaviorstore/behaviorsets/{id}/currentversion` | **BehaviorSet model** | N/A                   |

### Example

We will demonstrate here the creation of a `BehaviorSet`

_Request URL_

`POST http://localhost:7070/behaviorstore/behaviorsets`

_Request Body_

```javascript
{
  "behaviorGroups": [
    {
      "name": "Smalltalk",
      "behaviorRules": [
        {
          "name": "Welcome",
          "actions": [
            "welcome"
          ],
          "conditions": [
            {
              "type": "negation",
              "conditions": [
                {
                  "type": "occurrence",
                  "configs": {
                    "maxTimesOccurred": "1",
                    "behaviorRuleName": "Welcome"
                  }
                }
              ]
            }
          ]
        },
        {
          "name": "Greeting",
          "actions": [
            "greet"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "greeting(*)",
                "occurrence": "currentStep"
              }
            }
          ]
        },
        {
          "name": "Goodbye",
          "actions": [
            "say_goodbye",
            "CONVERSATION_END"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "goodbye(*)"
              }
            }
          ]
        },
        {
          "name": "Thank",
          "actions": [
            "thank"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "thank(*)"
              }
            }
          ]
        },
        {
          "name": "how are you",
          "actions": [
            "how_are_you"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "how_are_you"
              }
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
  "date": "Thu, 21 Jun 2018 01:00:02 GMT",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "0",
  "location": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/5b2af892ee5ee72440ee1b4b?version=1",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location",
  "content-type": null
}
```


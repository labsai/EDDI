# Behavior Rules

## Overview

**Behavior Rules** are the decision-making engine in EDDI's Lifecycle Pipeline. They are IF-THEN rules that evaluate conversation state and trigger actions based on conditions. This is where you define **when** to call an LLM, **when** to invoke an API, and **how** your agent responds to user inputs.

### Role in the Lifecycle

In EDDI's processing pipeline, Behavior Rules sit between input parsing and action execution:

```
User Input → Parser → Behavior Rules → API/LLM Calls → Output Generation
```

Behavior Rules examine the conversation memory (including parsed input, context data, and conversation history) and decide:

- Which actions to trigger
- Whether to call an LLM or skip it
- Whether to make external API calls
- What output to generate

### Key Concepts

- **Rules are IF-THEN logic**: If all conditions match, execute the specified actions
- **Rules are grouped**: Multiple rules can be organized into groups for better structure
- **Sequential execution**: Rules within a group execute in order until one succeeds
- **First match wins**: Once a rule in a group succeeds, remaining rules in that group are skipped
- **Actions trigger other lifecycle tasks**: Actions like `httpcall(weather-api)` or `send_to_llm` activate other parts of the pipeline

## Behavior Rules Structure

`Behavior Rules` are very flexible in structure to cover most use cases that you will come across. `Behavior Rules` are clustered in `Groups`. `Behavior Rules` are executed sequentially within each `Group`. As soon as one `Behavior Rule` succeeds, all remaining `Behavior Rules` in this `Group` will be skipped.

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

- [Input Matcher](behavior-rules.md#input-matcher)
- [Context Matcher](behavior-rules.md#context-matcher)
- [Connector](behavior-rules.md#connector)
- [Negation](behavior-rules.md#negation)
- [Occurrence](behavior-rules.md#occurrence)
- [Dependency](behavior-rules.md#dependency)
- [Action Matcher](behavior-rules.md#action-matcher)
- [Dynamic Value Matcher](behavior-rules.md#dynamic-value-matcher)

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

### Limitations

- The runtime `context` you hand over to EDDI may declare `"type": "array"`, but `contextmatcher` only understands `expressions`, `object` and `string`. **An array context can never match any `contextmatcher`** — the condition always fails and the engine logs a warning naming the context key. Send the data as an `object` (and match with `objectKeyPath`) if you need to match into it.
- The configured `contextType` must equal the runtime type of the context. A `contextmatcher` configured for `string` never matches an `expressions` context, and vice versa; the mismatch is logged at DEBUG level.

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

Two edge cases are worth knowing (they mirror the `negation` rules below):

- A `connector` **must** declare at least one nested condition. An empty `connector` is rejected at configuration validation time — an `AND` over zero conditions would succeed unconditionally and make the surrounding rule fire on every turn.
- If a child reports `NOT_EXECUTED` (e.g. a `sizematcher` where every bound is `-1`), the `AND` branch treats it as a failure, exactly like a condition placed directly on the rule. A misconfigured condition therefore decides a rule the same way whether or not it is wrapped in a `connector`.

### Negation

Inverts the overall outcome of the children conditions

In some cases it is more relevant if a `condition` is `false` than if it is `true`, this is where the `negation` `condition` comes into play. The logical result of all children together (`AND` connected), will be _**inverted**_.

This is exactly what the engine does: every child is evaluated in order, the first child that _fails_ makes the negation succeed (the `AND` is already false), and the negation only fails when _every_ child succeeded. Multi-child negations therefore behave as documented — earlier EDDI versions only looked at the first child, which made a negation with more than one child effectively always true.

Two edge cases are worth knowing:

- A `negation` **must** declare at least one nested condition. An empty `negation` is rejected at configuration validation time.
- If a child reports `ERROR` or `NOT_EXECUTED` (e.g. a `sizematcher` where every bound is `-1`), there is nothing meaningful to invert — that state is propagated unchanged instead of being flipped.

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

`behaviorRuleName` and at least one of `minTimesOccurred` / `maxTimesOccurred` are **required** — both are validated when the ruleset is loaded. Without a rule name there is nothing to count, and without a bound the condition matches as soon as any behavior rule has ever succeeded, which is almost never what was intended.

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

### Size Matcher

This condition type checks the size of arrays or collections in the conversation memory.

```json
(...)
  {
  "type": "sizematcher",
  "configs": {
    "valuePath": "memory.current.httpCalls.results",
    "min": "1",
    "max": "10",
    "equal": "-1"
  }
}
(...)
```

The example above matches whenever the API call stored between 1 and 10 result elements. Collections, maps and arrays report their **element count** — earlier EDDI versions ran the resolved value through `Integer.parseInt`, so a real collection silently degraded to size `0` and a rule like this one could never match.

| Config      | Type   | Description                              |
| ----------- | ------ | ---------------------------------------- |
| `valuePath` | string | Path to the array/collection to check    |
| `min`       | int    | Minimum size required (-1 to skip check) |
| `max`       | int    | Maximum size allowed (-1 to skip check)  |
| `equal`     | int    | Exact size required (-1 to skip check)   |

**How the size is determined** — the value the `valuePath` resolves to decides the rule:

| Resolved value            | Size used                                                              |
| ------------------------- | ---------------------------------------------------------------------- |
| `null` / path not found   | `0`                                                                    |
| Collection, Map, or array | Its element count                                                      |
| Number                    | The number itself (the value _is_ the size)                            |
| Numeric string            | The parsed number (kept for backwards compatibility)                   |
| Any other value           | The length of its textual representation                               |

If `min`, `max` and `equal` are all `-1`, the condition reports `NOT_EXECUTED` — it neither succeeds nor fails, and a wrapping `negation` propagates that state instead of inverting it.

## The Behavior Rule API Endpoints

The API Endpoints below will allow you to manage the `Behavior Rule`s in your EDDI instance.

The **`{id}`** is a path parameters that indicate which behavior rule you want to alter.

### API Methods

| HTTP Method | API Endpoint                                      | Request Body          | Response              |
| ----------- | ------------------------------------------------- | --------------------- | --------------------- |
| **DELETE**  | `/rulestore/rulesets/{id}`                | N/A                   | N/A                   |
| **GET**     | `/rulestore/rulesets/{id}`                | N/A                   | **BehaviorSet model** |
| **PUT**     | `/rulestore/rulesets/{id}`                | **BehaviorSet model** | N/A                   |
| **GET**     | `/rulestore/rulesets/descriptors`         | N/A                   | **BehaviorSet model** |
| **POST**    | `/rulestore/rulesets`                     | **BehaviorSet model** | N/A                   |
| **GET**     | `/rulestore/rulesets/{id}/currentversion` | N/A                   | **BehaviorSet model** |
| **POST**    | `/rulestore/rulesets/{id}/currentversion` | **BehaviorSet model** | N/A                   |

### Example

We will demonstrate here the creation of a `BehaviorSet`

_Request URL_

`POST http://localhost:7070/rulestore/rulesets`

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

The `Location` response header contains the URI of the newly created resource:

```
Location: eddi://ai.labs.rules/rulestore/rulesets/{id}?version=1
```

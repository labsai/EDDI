# Output Templating

## Overview

**Output Templating** is one of EDDI's most powerful features—it allows you to create **dynamic, data-driven responses** that pull information from conversation memory, API responses, or context data. Instead of static text, your bot can generate personalized, contextual replies.

### Why Output Templating Matters

Without templating:
```
"The weather is available"
```

With templating:
```
"The weather in [[${context.city}]] is [[${memory.current.httpCalls.weatherData.condition}]] with [[${memory.current.httpCalls.weatherData.temperature}]]°F"
```

Result:
```
"The weather in Paris is sunny with 75°F"
```

### Powered by Thymeleaf

The **output templating** is evaluated by the **Thymeleaf templating engine**, which means you can use the majority of Thymeleaf tags and expression language to define dynamic outputs.

### Common Use Cases

- **Personalization**: Greet users by name: `"Hello [[${context.userName}]]!"`
- **API Response Formatting**: Display data from HTTP calls: `"Your order #[[${httpCalls.orderData.orderId}]] is on the way"`
- **Conditional Outputs**: `"[# th:if="${user.isPremium}"]Exclusive offer for you![/]"`
- **Iteration**: Loop through arrays: `"Available options: [# th:each="opt : ${options}"][[${opt}]][/]"`
- **Calculations**: `"Total: $[[${price * quantity}]]"`

### What You Can Access

In templates, you have access to:
- **`memory.current.*`** - Current step data (input, httpCalls, properties)
- **`memory.previous.*`** - Previous step data
- **`context.*`** - Context passed from your application
- **`properties.*`** - Conversation properties (stored data)
- **`httpCalls.*`** - Responses from external APIs

## Configuration

One of the coolest features of **EDDI** is it will allow you dynamically template your output based on data that you would receive from `httpCalls` or `context information`, making **EDDI's** replies rich and dynamic.

## Enabling the feature:

Basically while creating the bot you must include `eddi://ai.labs.output` to one of the `packages` that will be part of the bot.

> **Important:** The templating feature will not work if it is included before `eddi://ai.labs.output` extension, **it must be included after**.

## Example

Here is how the output templating should be specified **inside of a package.**&#x20;

```javascript
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/{{outputset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.templating"
    }
  ]
}
```

Make sure the templating is defined after the output, not before.

## _**Additional Information :**_

[Thymeleaf documentation.](https://www.thymeleaf.org/)

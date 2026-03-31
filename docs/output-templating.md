# Output Templating

## Overview

**Output Templating** is one of EDDI's most powerful features—it allows you to create **dynamic, data-driven responses** that pull information from conversation memory, API responses, or context data. Instead of static text, your agent can generate personalized, contextual replies.

### Why Output Templating Matters

Without templating:

```
"The weather is available"
```

With templating:

```
"The weather in {context.city} is {memory.current.httpCalls.weatherData.condition} with {memory.current.httpCalls.weatherData.temperature}°F"
```

Result:

```
"The weather in Paris is sunny with 75°F"
```

### Powered by Qute

The **output templating** is evaluated by the **Quarkus Qute templating engine**, which provides native image compatibility and a clean, expressive syntax.

> **Note:** EDDI v6 migrated from Thymeleaf to Qute. Existing templates are automatically migrated via `V6QuteMigration` (startup) and the import pipeline.

### Common Use Cases

- **Personalization**: Greet users by name: `"Hello {context.userName}!"`
- **API Response Formatting**: Display data from HTTP calls: `"Your order #{httpCalls.orderData.orderId} is on the way"`
- **Conditional Outputs**: `"{#if user.isPremium}Exclusive offer for you!{/if}"`
- **Iteration**: Loop through arrays: `"Available options: {#for opt in options}{opt}{#if opt_hasNext}, {/if}{/for}"`

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

While creating the agent you must include `eddi://ai.labs.output` to one of the `workflows` that will be part of the agent.

> **Important:** The templating feature will not work if it is included before `eddi://ai.labs.output` extension, **it must be included after**.

## Example

Here is how the output templating should be specified **inside of a workflow.**&#x20;

```javascript
{
  "workflowExtensions": [
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

## Template Syntax Reference

### Variable Output

```
{variableName}
{object.nested.property}
```

### Conditionals

```
{#if condition}
  Content shown when true
{#else}
  Content shown when false
{/if}
```

### Iteration

```
{#for item in items}
  {item.name}
  {#if item_hasNext}, {/if}
{/for}
```

**Iteration metadata** available inside `{#for}`:

| Variable | Description |
| --- | --- |
| `item_index` | 0-based index |
| `item_indexParity` | `odd` or `even` |
| `item_hasNext` | `true` if not the last item |
| `item_count` | Total items in the collection |
| `item_isFirst` | `true` if first item |
| `item_isLast` | `true` if last item |

### String Methods

All standard String methods are available as natural method calls on string variables:

| Expression | Description |
| --- | --- |
| `{str.toLowerCase()}` | Convert to lowercase |
| `{str.toUpperCase()}` | Convert to uppercase |
| `{str.replace('old', 'new')}` | Replace substring |
| `{str.substring(5)}` | Substring from index |
| `{str.substring(0, 5)}` | Substring range |
| `{str.indexOf('x')}` | Find character index |
| `{str.contains('sub')}` | Check if contains |
| `{str.startsWith('pre')}` | Check prefix |
| `{str.endsWith('suf')}` | Check suffix |
| `{str.trim()}` | Trim whitespace |
| `{str.length()}` | String length |
| `{str.isEmpty()}` | Empty check |

**Chaining** is supported: `{name.replace(' ', '-').toLowerCase()}`

## Custom Expression Utilities

EDDI provides custom namespace extensions for use in templates (output, httpcalls, property setters).

### `uuidUtils` — ID & URI Utilities

| Expression | Description |
| --- | --- |
| `{uuidUtils:generateUUID()}` | Generates a random UUID string |
| `{uuidUtils:extractId(locationUri)}` | Extracts the resource ID from an EDDI location URI |
| `{uuidUtils:extractVersion(locationUri)}` | Extracts the version number from an EDDI location URI |

**`extractId` and `extractVersion`** work with both MongoDB ObjectIds (24-char hex) and PostgreSQL UUIDs (36-char with dashes):

```
// Input: "http://localhost:7070/behaviorstore/behaviorsets/6740832a2b0f614abcaee7ab?version=1"
{uuidUtils:extractId(properties.location)}     → "6740832a2b0f614abcaee7ab"
{uuidUtils:extractVersion(properties.location)} → "1"
```

> **Important:** Avoid using `.substring()` with hardcoded offsets to extract IDs from URIs—use `{uuidUtils:extractId(...)}` instead. Hardcoded offsets break when switching between MongoDB (24-char ObjectIds) and PostgreSQL (36-char UUIDs).

### Other Custom Extensions

| Namespace | Expression | Purpose |
| --- | --- | --- |
| `json` | `{json:serialize(obj)}` | JSON manipulation utilities |
| `encoder` | `{encoder:base64(data)}` | Text encoding utilities |

## Migration from Thymeleaf (v5 → v6)

If you are upgrading from EDDI v5, template syntax is automatically migrated:

| v5 (Thymeleaf) | v6 (Qute) |
| --- | --- |
| `[[${variable}]]` | `{variable}` |
| `[(${variable})]` | `{variable}` |
| `[# th:each="x : ${list}"]...[/]` | `{#for x in list}...{/for}` |
| `[# th:if="${condition}"]...[/]` | `{#if condition}...{/if}` |
| `#strings.toLowerCase(var)` | `{var.toLowerCase()}` |
| `#strings.substring(var, 37)` | `{var.substring(37)}` |
| `#uuidUtils.method()` | `{uuidUtils:method()}` |
| `#json.method()` | `{json:method()}` |
| `a + '/' + b` | `{a}/{b}` |

## _**Additional Information:**_

[Quarkus Qute documentation.](https://quarkus.io/guides/qute-reference)

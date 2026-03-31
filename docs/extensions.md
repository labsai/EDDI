# Extensions

## Overview

**Extensions** are the building blocks of EDDI agents. In EDDI's composable architecture, agents are not monolithic applications but rather **assemblies of extensions**, each providing a specific capability. Extensions are referenced by packages, and packages are combined to form complete agents.

### The Agent Composition Hierarchy

```
Agent (.agent.json)
  └─ Workflow 1 (.package.json)
      ├─ Extension 1: Behavior Rules (eddi://ai.labs.behavior)
      ├─ Extension 2: HTTP Calls (eddi://ai.labs.httpcalls)
      └─ Extension 3: Output Sets (eddi://ai.labs.output)
  └─ Workflow 2 (.package.json)
      ├─ Extension 1: Dictionary (eddi://ai.labs.parser.dictionaries.regular)
      └─ Extension 2: LangChain (eddi://ai.labs.llm)
```

### What Extensions Do

Each extension type corresponds to a **lifecycle task** or **resource** that the agent can use:

| Extension Type                  | Purpose                          | Lifecycle Role                                        |
| ------------------------------- | -------------------------------- | ----------------------------------------------------- |
| `ai.labs.parser`                | Input parsing and normalization  | Transforms raw user input into structured expressions |
| `ai.labs.parser.dictionaries.*` | Define vocabularies and entities | Used by parser to recognize intents and entities      |
| `ai.labs.behavior`              | Define IF-THEN rules             | Decides what actions to take based on conditions      |
| `ai.labs.httpcalls`             | Configure external API calls     | Executes HTTP requests to external services           |
| `ai.labs.llm`                   | Configure LLM integrations       | Sends requests to LLM APIs (OpenAI, Claude, etc.)     |
| `ai.labs.output`                | Define output templates          | Formats responses using conversation data             |
| `ai.labs.property`              | Extract and store data           | Manages conversation memory properties                |

### EDDI Resource URIs

All EDDI's resources start with `eddi://`, which is used to distinguish EDDI-specific extensions from other resources. This URI scheme allows:

- **Version control**: Each extension can have multiple versions
- **Reusability**: The same extension can be used by multiple packages/agents
- **Clear references**: Explicit URIs make configuration transparent

Example URI:

```
eddi://ai.labs.behavior/behaviorstore/behaviorsets/673abc123?version=1
```

## Extension Discovery

In this article we will talk about **EDDI**'s **`extensions`**.

**EDDI's `extensions`** are the features that your current instance of EDDI is supporting, the latter are used in the process of configuring/developing an Agent.

The list of `extensions` will allow you to have an overview of what is enabled in your current instance of **EDDI**, the list can be retrieved by calling the API endpoint below.

### &#x20;Extensions REST API Endpoint

| Element      | Value                        |
| ------------ | ---------------------------- |
| HTTP Method  | `GET`                        |
| API Endpoint | `/extensionstore/extensions` |

## Model

```javascript
[
  {
    type: "string",
    displayName: "string",
    configs: {},
    extensions: {},
  },
];
```

### Description of the model

| Element     | Value                                     |
| ----------- | ----------------------------------------- |
| type        | (`String`) The type of the extension      |
| displayName | (`String`) A given name to the extension  |
| configs     | (`Object`) Configuration of the extension |
| extensions  | (`Object`) Extensions of the extension    |

## Example

> More about regular dictionaries can be found [here](creating-your-first-agent/#1-creating-a-regular-dictionary).

_Request URL_

`GET http://localhost:7070/extensionstore/extensions`

_Response Body_

```javascript
[
  {
    type: "ai.labs.parser",
    displayName: "Input Parser",
    configs: {
      includeUnknown: {
        displayName: "Include Unknown Expressions",
        fieldType: "BOOLEAN",
        defaultValue: true,
        optional: true,
      },
      includeUnused: {
        displayName: "Include Unused Expressions",
        fieldType: "BOOLEAN",
        defaultValue: true,
        optional: true,
      },
      appendExpressions: {
        displayName: "Append Expressions",
        fieldType: "BOOLEAN",
        defaultValue: true,
        optional: true,
      },
    },
    extensions: {
      corrections: [
        {
          type: "ai.labs.parser.corrections.levenshtein",
          displayName: "Damerau Levenshtein Correction",
          configs: {
            distance: {
              displayName: "Distance",
              fieldType: "INT",
              defaultValue: 2,
              optional: true,
            },
          },
          extensions: {},
        },
        {
          type: "ai.labs.parser.corrections.stemming",
          displayName: "Grammar Stemming Correction",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.corrections.phonetic",
          displayName: "Phonetic Matching Correction",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.corrections.mergedTerms",
          displayName: "Merged Terms Correction",
          configs: {},
          extensions: {},
        },
      ],
      normalizer: [
        {
          type: "ai.labs.parser.normalizers.punctuation",
          displayName: "Punctuation Normalizer",
          configs: {
            removePunctuation: {
              displayName: "Remove Punctuation",
              fieldType: "BOOLEAN",
              defaultValue: false,
              optional: true,
            },
            punctuationRegexPattern: {
              displayName: "Punctuation RegEx Pattern",
              fieldType: "STRING",
              defaultValue: "!?:.,;",
              optional: true,
            },
          },
          extensions: {},
        },
        {
          type: "ai.labs.parser.normalizers.specialCharacter",
          displayName: "Convert Special Character Normalizer",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.normalizers.contractedWords",
          displayName: "Contracted Word Normalizer",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.normalizers.allowedCharacter",
          displayName: "Remove Undefined Character Normalizer",
          configs: {},
          extensions: {},
        },
      ],
      dictionaries: [
        {
          type: "ai.labs.parser.dictionaries.integer",
          displayName: "Integer Dictionary",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.dictionaries.decimal",
          displayName: "Decimal Dictionary",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.dictionaries.ordinalNumber",
          displayName: "Ordinal Numbers Dictionary",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.dictionaries.punctuation",
          displayName: "Punctuation Dictionary",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.dictionaries.time",
          displayName: "Time Expression Dictionary",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.dictionaries.email",
          displayName: "Email Dictionary",
          configs: {},
          extensions: {},
        },
        {
          type: "ai.labs.parser.dictionaries.regular",
          displayName: "Regular Dictionary",
          configs: {
            uri: {
              displayName: "Resource URI",
              fieldType: "URI",
              optional: false,
            },
          },
          extensions: {},
        },
      ],
    },
  },
  {
    type: "ai.labs.behavior",
    displayName: "Behavior Rules",
    configs: {
      appendActions: {
        displayName: "Append Actions",
        fieldType: "BOOLEAN",
        defaultValue: true,
        optional: false,
      },
      uri: {
        displayName: "Resource URI",
        fieldType: "URI",
        optional: false,
      },
    },
    extensions: {},
  },
  {
    type: "ai.labs.output",
    displayName: "Output Generation",
    configs: {
      uri: {
        displayName: "Resource URI",
        fieldType: "URI",
        optional: false,
      },
    },
    extensions: {},
  },
  {
    type: "ai.labs.templating",
    displayName: "Templating",
    configs: {},
    extensions: {},
  },
  {
    type: "ai.labs.property",
    displayName: "Property Extraction",
    configs: {},
    extensions: {},
  },
  {
    type: "ai.labs.callback",
    displayName: "External Callback",
    configs: {
      callbackUri: {
        displayName: "Callback URI",
        fieldType: "URI",
        optional: false,
      },
      callOnActions: {
        displayName: "Call on Actions",
        fieldType: "STRING",
        defaultValue: "",
        optional: true,
      },
      timeoutInMillis: {
        displayName: "Timeout in Milliseconds",
        fieldType: "URI",
        defaultValue: 10000,
        optional: true,
      },
    },
    extensions: {},
  },
  {
    type: "ai.labs.httpcalls",
    displayName: "Http Calls",
    configs: {
      uri: {
        displayName: "Resource URI",
        fieldType: "URI",
        optional: false,
      },
    },
    extensions: {},
  },
];
```

_Response Code_

`200`


# Input Pattern Matching for Agent Routing

## Overview

The **Pattern Matcher** (historically called "Semantic Parser") is EDDI's input classification system that transforms raw user input into **structured expressions** for agent routing and orchestration decisions.

**Role in Multi-Agent Orchestration:**

- **Route to Agents**: Match input patterns to determine which AI agent should handle the request
- **Categorize Requests**: Classify user intent for orchestration rules (e.g., "support" → support agent, "sales" → sales agent)
- **Whitelist Patterns**: Define allowed vocabulary and patterns for security and compliance
- **Extract Parameters**: Pull structured data from input for agent context

**What it actually does:**

- Matches words and phrases from dictionaries
- Applies fuzzy matching corrections (typos, phonetics, merged terms)
- Converts matched patterns to expression strings
- Enables pattern-based orchestration logic

**What it's NOT:**

- Not natural language understanding (NLU)
- Not machine learning-based
- Not semantic meaning extraction
- Not context-aware interpretation

### Role in Orchestration Pipeline

```
User Input: "I need technical support"
    ↓
Pattern Matcher (using dictionaries)
    ↓
Expressions: "intent(support),category(technical)"
    ↓
Orchestration Rules evaluate expressions
    ↓
Route to: Technical Support Agent (specific LLM or API)
```

The pattern matcher is the **first step** in the Orchestration Pipeline after receiving user input.

### Why Use Pattern Matching for Agent Orchestration?

**Without pattern matching** (hardcoded routing):

```javascript
if (
  input.contains("support") ||
  input.contains("help") ||
  input.contains("issue")
) {
  if (input.contains("billing") || input.contains("payment")) {
    routeToAgent("billing-support");
  } else if (input.contains("technical") || input.contains("bug")) {
    routeToAgent("technical-support");
  }
}
```

Brittle, hard to maintain, requires code changes for new routing rules!

**With pattern matching** (dictionary-based orchestration):

```json
// Dictionary: Support Category Classification
{
  "lang": "en",
  "words": [
    {
      "word": "billing",
      "expressions": "category(billing),intent(support)",
      "frequency": 0
    },
    {
      "word": "payment",
      "expressions": "category(billing),intent(support)",
      "frequency": 0
    },
    {
      "word": "bug",
      "expressions": "category(technical),intent(support)",
      "frequency": 0
    },
    {
      "word": "technical",
      "expressions": "category(technical)",
      "frequency": 0
    }
  ]
}
```

```json
// Orchestration Rule: Route based on category
{
  "behaviorRules": [
    {
      "name": "Route to Billing Agent",
      "conditions": [
        {
          "type": "inputmatcher",
          "configs": { "expressions": "category(billing)" }
        }
      ],
      "actions": ["agent(billing-specialist)"]
    },
    {
      "name": "Route to Technical Agent",
      "conditions": [
        {
          "type": "inputmatcher",
          "configs": { "expressions": "category(technical)" }
        }
      ],
      "actions": ["agent(technical-expert)"]
    }
  ]
}
```

**Agent Orchestration Benefits:**

- **Declarative Routing**: Define routing in configuration, not code
- **Multi-Agent Coordination**: Same input can trigger multiple agents
- **Dynamic Agent Selection**: Change routing rules at runtime
- **Pattern Reusability**: Share vocabularies across agent configurations
- **Fuzzy Matching**: Handle user typos and variations automatically

### Key Components

1. **Dictionaries**: Define words/phrases and their classification
   - `"billing"` → `category(billing),intent(support)`
   - `"technical issue"` → `category(technical),intent(support)`
   - Used for agent routing and request classification

2. **Built-in Dictionaries**: Pre-configured for common patterns
   - **Integer**: `"42"` → `integer(42)`
   - **Decimal**: `"3.14"` → `decimal(3.14)`
   - **Email**: `"user@example.com"` → `email(user@example.com)`
   - **Time**: `"13:43"` → `time(<epoch-millis>)` — 24-hour clock only, no am/pm and no relative dates
   - **Punctuation**: `"!"` → `punctuation(exclamation_mark)`
   - **Ordinal Number**: `"1st"` → `ordinal_number(1)`

   See [Dictionary Types Reference](#dictionary-types-reference) for the exact emitted expression names.

3. **Corrections**: Handle typos and variations
   - **Levenshtein**: `"helo"` → `"hello"` (distance 1-2 characters)
   - **Phonetic**: `"nite"` → `"night"`
   - **Merged Terms**: Handles words without spaces

### Example Flow: Agent Routing

**User Input**: "I need help with a billing issue"

**Pattern Matcher Processing**:

1. Tokenizes: `["I", "need", "help", "with", "a", "billing", "issue"]`
2. Looks up in dictionaries:
   - `"help"` → `intent(support)`
   - `"billing"` → `category(billing)`
   - `"issue"` → `type(problem)`
3. Applies corrections (if needed)
4. Produces expressions: `intent(support),category(billing),type(problem)`

**Orchestration Rule** routes to appropriate agent:

```json
{
  "conditions": [
    {
      "type": "inputmatcher",
      "configs": {
        "expressions": "category(billing)",
        "occurrence": "currentStep"
      }
    }
  ],
  "actions": ["route_to_billing_agent"]
}
```

**Result**: Request is routed to specialized billing support agent (could be a specific LLM configuration, a human agent queue, or a billing API).

## Creating a Regular Dictionary

Regular dictionaries define custom words and phrases for agent routing. We'll create a dictionary and then configure a parser to use it.

### Step 1: Create a Regular Dictionary for Agent Routing

Make a `POST` request to `/dictionarystore/dictionaries` with this JSON:

```json
{
  "lang": "en",
  "words": [
    {
      "word": "support",
      "expressions": "intent(support)",
      "frequency": 0
    },
    {
      "word": "help",
      "expressions": "intent(support)",
      "frequency": 0
    },
    {
      "word": "billing",
      "expressions": "category(billing)",
      "frequency": 0
    },
    {
      "word": "technical",
      "expressions": "category(technical)",
      "frequency": 0
    },
    {
      "word": "sales",
      "expressions": "category(sales)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "technical support",
      "expressions": "intent(support),category(technical)"
    },
    {
      "phrase": "billing question",
      "expressions": "intent(inquiry),category(billing)"
    },
    {
      "phrase": "sales inquiry",
      "expressions": "intent(inquiry),category(sales)"
    }
  ]
}
```

**Request:**

```bash
curl -X POST http://localhost:7070/dictionarystore/dictionaries \
  -H "Content-Type: application/json" \
  -d '{
    "lang": "en",
    "words": [
      {"word": "billing", "expressions": "category(billing),intent(support)", "frequency": 0}
    ],
    "phrases": [
      {"phrase": "technical support", "expressions": "intent(support),category(technical)"}
    ]
  }'
```

**Response:** HTTP `201 Created`

The response's `Location` header contains the URI of the created dictionary:

```
Location: http://localhost:7070/dictionarystore/dictionaries/DICT_ID?version=1
```

This gives you the reference URI:

```
eddi://ai.labs.dictionary/dictionarystore/dictionaries/DICT_ID?version=1
```

**Key Points:**

- `lang`: ISO language code (e.g., `"en"`, `"de"`, `"fr"`) — **this is a filter, not just an annotation** (see the upgrade note below)
- `word`: The actual word to match
- `expressions`: Classification/routing information (can have multiple, comma-separated)
- `frequency`: Usage frequency (0 = common, higher = less common)
- `phrases`: Multi-word expressions treated as single units

> **Upgrade note — `lang` now gates the dictionary.** In earlier releases `lang` was recorded but never evaluated: every dictionary was consulted for every turn. From v6.x on, a dictionary whose `lang` is set is only consulted when it matches the conversation's language — on the direct lookup **and** on the corrections path (Levenshtein, phonetic, merged terms), so a mismatched dictionary can no longer sneak back in through a typo correction.
>
> The conversation language comes from the `lang` conversation property and defaults to `"en"` when that property is not set. So a deployment with, say, a `"de"` dictionary and no `lang` property recognises nothing after the upgrade. Two ways to keep the pre-upgrade behaviour:
>
> - leave `lang` unset (or empty) on the dictionary — an unset language means "applies to every language"; or
> - set the `lang` conversation property (e.g. via a property setter or the request context) to the dictionary's language.

### Step 2: Create a Parser Configuration

Now create a parser that uses your dictionary along with built-in dictionaries.

Make a `POST` request to `/parserstore/parsers` with this JSON:

> **Important:** Replace `<DICT_ID>` with your dictionary ID from Step 1!

## Example Parser Configuration

```json
{
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
          "uri": "eddi://ai.labs.dictionary/dictionarystore/dictionaries/<DICT_ID>?version=1"
        }
      }
    ],
    "corrections": [
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
  "config": null
}
```

**Request:**

```bash
curl -X POST http://localhost:7070/parserstore/parsers \
  -H "Content-Type: application/json" \
  -d '{
    "extensions": {
      "dictionaries": [
        {"type": "eddi://ai.labs.parser.dictionaries.integer"},
        {"type": "eddi://ai.labs.parser.dictionaries.regular",
         "config": {"uri": "eddi://ai.labs.dictionary/dictionarystore/dictionaries/DICT_ID?version=1"}}
      ],
      "corrections": [
        {"type": "eddi://ai.labs.parser.corrections.levenshtein", "config": {"distance": "2"}}
      ]
    }
  }'
```

**Response:** HTTP `201 Created`

The response's `Location` header contains the parser URI:

```
Location: http://localhost:7070/parserstore/parsers/PARSER_ID?version=1
```

This gives you the parser reference:

```
eddi://ai.labs.parser/parserstore/parsers/PARSER_ID?version=1
```

### Dictionary Types Reference

> **These are the exact expression names the parser emits.** Behavior rules match on the emitted name, so a rule written against a different name never fires — copy the names from this table verbatim.

| Type           | EDDI URI                                           | Description                                                                                                                   | Example                                          |
| -------------- | -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Integer        | `eddi://ai.labs.parser.dictionaries.integer`       | Matches positive integers                                                                                                     | `"42"` → `integer(42)`                           |
| Decimal        | `eddi://ai.labs.parser.dictionaries.decimal`       | Matches decimal numbers (both `.` and `,` separators)                                                                       | `"3.14"` → `decimal(3.14)`                       |
| Punctuation    | `eddi://ai.labs.parser.dictionaries.punctuation`   | Matches common punctuation: `!` (exclamation_mark), `?` (question_mark), `.` (dot), `,` (comma), `:` (colon), `;` (semicolon) | `"!"` → `punctuation(exclamation_mark)`          |
| Email          | `eddi://ai.labs.parser.dictionaries.email`         | Matches email addresses                                                                                                       | `"user@example.com"` → `email(user@example.com)` |
| Time           | `eddi://ai.labs.parser.dictionaries.time`          | Matches 24-hour clock formats only: `13:43`, `13:43:23`, `01h20`, `22h`. **No am/pm parsing** — `"3pm"` is not a time.       | `"13:43"` → `time(<epoch-millis>)`               |
| Ordinal Number | `eddi://ai.labs.parser.dictionaries.ordinalNumber` | Ordinal numbers, either in English suffix notation (1st, 2nd, 3rd, …) or in dot notation (`3.`, at most two digits)         | `"1st"` → `ordinal_number(1)`, `"3."` → `ordinal_number(3)` |
| Regular        | `eddi://ai.labs.parser.dictionaries.regular`       | Custom dictionary for agent routing                                                                                           | `"billing"` → `category(billing)`                |

> **Dot notation affects sentence-final numbers.** Because `"5."` is an ordinal number, an English sentence ending in a number — `"I want 5."` — now yields `ordinal_number(5)` for the last token where it previously yielded `unknown`. Conversely a bare `"."` is no longer treated as an ordinal and is normalised as punctuation. Only enable the ordinal-number dictionary when you actually want that reading.

> **Time values are epoch milliseconds, not a formatted clock string.** The matched token is converted to a `java.sql.Time` and the expression carries `Time#getTime()` — e.g. `"13:43"` becomes something like `time(45780000)` (the exact number depends on the JVM's time zone). Match on the presence of `time(*)` rather than on a literal value.

### Correction Types Reference

| Type         | EDDI URI                                        | Description                                      | Example                            |
| ------------ | ----------------------------------------------- | ------------------------------------------------ | ---------------------------------- |
| Levenshtein  | `eddi://ai.labs.parser.corrections.levenshtein` | Matches words with typos (configurable distance) | `"helo"` → `"hello"` (distance=1)  |
| Phonetic     | `eddi://ai.labs.parser.corrections.phonetic`    | Matches phonetically similar words               | `"nite"` → `"night"`               |
| Merged Terms | `eddi://ai.labs.parser.corrections.mergedTerms` | Handles words without spaces                     | `"techsupport"` → `"tech support"` |

**Levenshtein config keys**

| Key             | Default | Description                                                                                                                                                                            |
| --------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `distance`      | `2`     | Maximum edit distance a dictionary word may have from the input token.                                                                                                                 |
| `maxCandidates` | `5`     | Upper bound on how many correction candidates one token may produce. Every candidate becomes another branch in the parser's match matrix, so raising this multiplies the search space. |

Candidates are sorted by edit distance first, so the closest matches survive the cap. A missing, non-numeric or non-positive value falls back to the default.

> **Corrections respect the dictionary language too.** A dictionary whose `lang` does not match the conversation language is skipped by the corrections exactly as it is skipped by the direct lookup — otherwise a foreign-language word would come back as a "correction" at distance 0 for every unknown token.

> **There is no stemming correction.** EDDI ships exactly the three corrections above; referencing `eddi://ai.labs.parser.corrections.stemming` (or any other unregistered extension URI) makes workflow initialization fail with `UnrecognizedExtensionException` and the agent will not start.

## Testing the Pattern Matcher

Once you've created both dictionary and parser, you can test it standalone.

Make a `POST` request to `/parser/{PARSER_ID}?version={VERSION}` with plain text in the body:

**Request:**

```bash
curl -X POST "http://localhost:7070/parser/PARSER_ID?version=1" \
  -H "Content-Type: text/plain" \
  -d "I need billing support"
```

**Response:**

```json
[
  {
    "expressions": "intent(support),category(billing)"
  }
]
```

The parser returns an array of solutions, where each solution contains expressions representing the classification of the input.

## Using Pattern Matcher in Agent Orchestration

To use the pattern matcher in your agent orchestration, add it as the first step of your workflow configuration:

```json
{
  "workflowSteps": [
    {
      "type": "eddi://ai.labs.parser",
      "extensions": {
        "dictionaries": [
          {
            "type": "eddi://ai.labs.parser.dictionaries.regular",
            "config": {
              "uri": "eddi://ai.labs.dictionary/dictionarystore/dictionaries/DICT_ID?version=1"
            }
          }
        ],
        "corrections": [
          {
            "type": "eddi://ai.labs.parser.corrections.levenshtein",
            "config": {
              "distance": "2"
            }
          }
        ]
      },
      "config": {
        "includeUnknown": true,
        "includeUnused": true
      }
    }
  ]
}
```

**Configuration Options** (all live under the step's `config` object):

| Option              | Default | Description                                                                       |
| ------------------- | ------- | --------------------------------------------------------------------------------- |
| `includeUnknown`    | `true`  | Include `unknown(...)` expressions for unrecognized words                         |
| `includeUnused`     | `true`  | Include `unused(...)` expressions for words that matched no dictionary entry      |
| `appendExpressions` | `true`  | Append the freshly parsed expressions to the ones already in the step             |
| `maxInputTokens`    | `200`   | Hard cap on tokens taken from one input; anything beyond is dropped               |
| `maxSuggestions`    | `1000`  | Hard cap on dictionary suggestions evaluated per input                            |
| `maxSolutions`      | `100`   | Hard cap on solutions collected per input                                         |

The three `max*` limits guard against pathological inputs. Values below `1` are ignored and fall back to the default.

## Complete Example: Multi-Agent Customer Service Orchestration

Let's build an agent routing system for customer service:

### 1. Create Dictionary for Agent Routing

```json
{
  "lang": "en",
  "words": [
    {
      "word": "billing",
      "expressions": "category(billing),intent(support)",
      "frequency": 0
    },
    {
      "word": "payment",
      "expressions": "category(billing),intent(support)",
      "frequency": 0
    },
    {
      "word": "invoice",
      "expressions": "category(billing),intent(inquiry)",
      "frequency": 0
    },
    {
      "word": "technical",
      "expressions": "category(technical)",
      "frequency": 0
    },
    {
      "word": "bug",
      "expressions": "category(technical),intent(support)",
      "frequency": 0
    },
    {
      "word": "feature",
      "expressions": "category(technical),intent(inquiry)",
      "frequency": 0
    },
    {
      "word": "sales",
      "expressions": "category(sales),intent(inquiry)",
      "frequency": 0
    },
    {
      "word": "pricing",
      "expressions": "category(sales),intent(inquiry)",
      "frequency": 0
    },
    {
      "word": "demo",
      "expressions": "category(sales),intent(inquiry)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "billing issue",
      "expressions": "category(billing),intent(support),urgency(high)"
    },
    {
      "phrase": "technical problem",
      "expressions": "category(technical),intent(support),urgency(high)"
    },
    {
      "phrase": "interested in",
      "expressions": "category(sales),intent(inquiry)"
    }
  ]
}
```

### 2. User Says: "I have a billing issue"

### 3. Pattern Matcher Output:

```json
[{ "expressions": "category(billing),intent(support),urgency(high)" }]
```

### 4. Orchestration Rule Routes to Agent:

```json
{
  "name": "Route Billing Issues to Specialist",
  "conditions": [
    {
      "type": "inputmatcher",
      "configs": { "expressions": "category(billing)" }
    }
  ],
  "actions": ["agent(billing-specialist)", "set_priority(high)"]
}
```

### 5. Result:

- Request routed to Billing Specialist Agent (e.g., GPT-4 with billing context)
- Priority set to high for escalation tracking
- Conversation context includes category and intent for agent

## Best Practices for Agent Orchestration

1. **Use Category-Based Routing**: `category(billing)` is better than `entity(invoice)`
2. **Combine Intent + Category**: `intent(support),category(technical)` enables flexible routing
3. **Define Urgency Levels**: `urgency(high)` helps prioritize agent allocation
4. **Test Thoroughly**: Use the `/parser` endpoint to verify routing classifications
5. **Start Broad, Then Specialize**: Begin with major categories, add subcategories as needed
6. **Document Expression Schema**: Keep a reference of all category/intent/urgency values used
7. **Version Dictionaries**: Use version control for routing changes

## Troubleshooting

**Problem**: Requests not routing to expected agent  
**Solution**: Test pattern matcher output - verify expressions match orchestration rules

**Problem**: Too many unknown expressions  
**Solution**: Add more words to dictionary or enable fuzzy corrections

**Problem**: Multiple agents triggered for same input  
**Solution**: Make conditions more specific or add rule priority

**Problem**: Corrections too aggressive (wrong routing)  
**Solution**: Reduce Levenshtein distance or disable specific corrections

> **Example:** To reduce the Levenshtein distance threshold for fuzzy matching, lower `distance` on the correction entry in the parser configuration (there is no YAML configuration file — parsers are JSON documents stored via `/parserstore/parsers`):
>
> ```json
> {
>   "type": "eddi://ai.labs.parser.corrections.levenshtein",
>   "config": {
>     "distance": "1"
>   }
> }
> ```

> **Note:** The pattern matcher is optimized for conversational inputs, not full-text documents. Design dictionaries for typical user queries.

## Related Documentation

- [Behavior Rules](behavior-rules.md) - Using expressions for agent routing
- [Architecture Overview](architecture.md) - Understanding the orchestration pipeline
- [LangChain Integration](langchain.md) - Configuring AI agents
- [HTTP Calls](httpcalls.md) - Integrating business system agents

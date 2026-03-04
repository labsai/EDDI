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
- Applies fuzzy matching corrections (typos, stemming)
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
if (input.contains("support") || input.contains("help") || input.contains("issue")) {
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
    {"word": "billing", "expressions": "category(billing),intent(support)", "frequency": 0},
    {"word": "payment", "expressions": "category(billing),intent(support)", "frequency": 0},
    {"word": "bug", "expressions": "category(technical),intent(support)", "frequency": 0},
    {"word": "technical", "expressions": "category(technical)", "frequency": 0}
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
        {"type": "inputmatcher", "configs": {"expressions": "category(billing)"}}
      ],
      "actions": ["agent(billing-specialist)"]
    },
    {
      "name": "Route to Technical Agent",
      "conditions": [
        {"type": "inputmatcher", "configs": {"expressions": "category(technical)"}}
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
   - **Integer**: `"42"` → `number(42)`
   - **Decimal**: `"3.14"` → `decimal(3.14)`
   - **Email**: `"user@example.com"` → `email(user@example.com)`
   - **Time**: `"3pm tomorrow"` → `time(15:00, +1day)`
   - **Punctuation**: `"!"` → `punctuation(exclamation_mark)`
   - **Ordinal Number**: `"1st"` → `ordinal_number(1)`

3. **Corrections**: Handle typos and variations
   - **Stemming**: `"running"` → `"run"`
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

Make a `POST` request to `/regulardictionarystore/regulardictionaries` with this JSON:

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
curl -X POST http://localhost:7070/regulardictionarystore/regulardictionaries \
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
Location: http://localhost:7070/regulardictionarystore/regulardictionaries/DICT_ID?version=1
```

This gives you the reference URI:

```
eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/DICT_ID?version=1
```

**Key Points:**
- `lang`: ISO language code (e.g., `"en"`, `"de"`, `"fr"`)
- `word`: The actual word to match
- `expressions`: Classification/routing information (can have multiple, comma-separated)
- `frequency`: Usage frequency (0 = common, higher = less common)
- `phrases`: Multi-word expressions treated as single units

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
          "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/<DICT_ID>?version=1"
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
         "config": {"uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/DICT_ID?version=1"}}
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

| Type | EDDI URI | Description | Example |
|------|----------|-------------|---------|
| Integer | `eddi://ai.labs.parser.dictionaries.integer` | Matches positive integers | `"42"` → `number(42)` |
| Decimal | `eddi://ai.labs.parser.dictionaries.decimal` | Matches decimal numbers (both `.` and `,` separators) | `"3.14"` → `decimal(3.14)` |
| Punctuation | `eddi://ai.labs.parser.dictionaries.punctuation` | Matches common punctuation: `!` (exclamation_mark), `?` (question_mark), `.` (dot), `,` (comma), `:` (colon), `;` (semicolon) | `"!"` → `punctuation(exclamation_mark)` |
| Email | `eddi://ai.labs.parser.dictionaries.email` | Matches email addresses | `"user@example.com"` → `email(user@example.com)` |
| Time | `eddi://ai.labs.parser.dictionaries.time` | Matches time formats: 01:20, 01h20, 22:40, 13:43:23 | `"3pm"` → `time(15:00)` |
| Ordinal Number | `eddi://ai.labs.parser.dictionaries.ordinalNumber` | Ordinal numbers in English: 1st, 2nd, 3rd, etc. | `"1st"` → `ordinal_number(1)` |
| Regular | `eddi://ai.labs.parser.dictionaries.regular` | Custom dictionary for agent routing | `"billing"` → `category(billing)` |

### Correction Types Reference

| Type | EDDI URI | Description | Example |
|------|----------|-------------|---------|
| Stemming | `eddi://ai.labs.parser.corrections.stemming` | Reduces words to their root form | `"running"` → `"run"` |
| Levenshtein | `eddi://ai.labs.parser.corrections.levenshtein` | Matches words with typos (configurable distance) | `"helo"` → `"hello"` (distance=1) |
| Phonetic | `eddi://ai.labs.parser.corrections.phonetic` | Matches phonetically similar words | `"nite"` → `"night"` |
| Merged Terms | `eddi://ai.labs.parser.corrections.mergedTerms` | Handles words without spaces | `"techsupport"` → `"tech support"` |

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

To use the pattern matcher in your agent orchestration, add it to your package configuration:

```json
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.parser",
      "extensions": {
        "dictionaries": [
          {
            "type": "eddi://ai.labs.parser.dictionaries.regular",
            "config": {
              "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/DICT_ID?version=1"
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

**Configuration Options:**
- `includeUnknown`: Include expressions for unrecognized words (default: true)
- `includeUnused`: Include expressions that weren't matched by orchestration rules (default: true)
- `appendExpressions`: Append new expressions to existing ones (default: true)

## Complete Example: Multi-Agent Customer Service Orchestration

Let's build an agent routing system for customer service:

### 1. Create Dictionary for Agent Routing
```json
{
  "lang": "en",
  "words": [
    {"word": "billing", "expressions": "category(billing),intent(support)", "frequency": 0},
    {"word": "payment", "expressions": "category(billing),intent(support)", "frequency": 0},
    {"word": "invoice", "expressions": "category(billing),intent(inquiry)", "frequency": 0},
    {"word": "technical", "expressions": "category(technical)", "frequency": 0},
    {"word": "bug", "expressions": "category(technical),intent(support)", "frequency": 0},
    {"word": "feature", "expressions": "category(technical),intent(inquiry)", "frequency": 0},
    {"word": "sales", "expressions": "category(sales),intent(inquiry)", "frequency": 0},
    {"word": "pricing", "expressions": "category(sales),intent(inquiry)", "frequency": 0},
    {"word": "demo", "expressions": "category(sales),intent(inquiry)", "frequency": 0}
  ],
  "phrases": [
    {"phrase": "billing issue", "expressions": "category(billing),intent(support),urgency(high)"},
    {"phrase": "technical problem", "expressions": "category(technical),intent(support),urgency(high)"},
    {"phrase": "interested in", "expressions": "category(sales),intent(inquiry)"}
  ]
}
```

### 2. User Says: "I have a billing issue"

### 3. Pattern Matcher Output:
```json
[
  {"expressions": "category(billing),intent(support),urgency(high)"}
]
```

### 4. Orchestration Rule Routes to Agent:
```json
{
  "name": "Route Billing Issues to Specialist",
  "conditions": [
    {"type": "inputmatcher", "configs": {"expressions": "category(billing)"}}
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

> **Example:** To reduce the Levenshtein distance threshold for fuzzy matching, set the value in your pattern matcher configuration (e.g., in `pattern-matcher.yaml`):
>
> ```yaml
> fuzzy_matching:
>   levenshtein_distance: 1
> ```
>
> Or in JSON configuration:
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


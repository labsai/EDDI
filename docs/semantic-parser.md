# Input Pattern Matching for Agent Routing

> **In short:** the parser step turns raw user input into expression strings such as `category(billing)` by looking words up in dictionaries (with fuzzy corrections). Behavior rules then match those expressions with `inputmatcher` and emit actions. Action names are arbitrary strings: they only do something when an output set, HTTP call, LLM task or other step lists them in its own `actions`. Authors looking for the parser step options, dictionary types and corrections can jump to the configuration sections further down.

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
      "actions": ["route_to_billing"]
    },
    {
      "name": "Route to Technical Agent",
      "conditions": [
        {
          "type": "inputmatcher",
          "configs": { "expressions": "category(technical)" }
        }
      ],
      "actions": ["route_to_technical"]
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
  "actions": ["route_to_billing"]
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

The response's `Location` header contains the reference URI of the created dictionary:

```
Location: eddi://ai.labs.dictionary/dictionarystore/dictionaries/DICT_ID?version=1
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

The response's `Location` header contains the parser reference URI:

```
Location: eddi://ai.labs.parser/parserstore/parsers/PARSER_ID?version=1
```

### Dictionary Types Reference

> **These are the exact expression names the parser emits.** Behavior rules match on the emitted name, so a rule written against a different name never fires — copy the names from this table verbatim.

| Type           | EDDI URI                                           | Description                                                                                                                   | Example                                          |
| -------------- | -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Integer        | `eddi://ai.labs.parser.dictionaries.integer`       | Matches positive integers                                                                                                     | `"42"` → `integer(42)`                           |
| Decimal        | `eddi://ai.labs.parser.dictionaries.decimal`       | Matches decimal numbers (both `.` and `,` separators)                                                                       | `"3.14"` → `decimal(3.14)`                       |
| Punctuation    | `eddi://ai.labs.parser.dictionaries.punctuation`   | Matches common punctuation: `!` (exclamation_mark), `?` (question_mark), `.` (dot), `,` (comma), `:` (colon), `;` (semicolon) | `"!"` → `punctuation(exclamation_mark)`          |
| Email          | `eddi://ai.labs.parser.dictionaries.email`         | Matches email addresses, with any top-level domain (`.com` as well as `.technology`)                                          | `"user@example.com"` → `email(user@example.com)` |
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
| `lookupIfKnown` | `false` | Also correct a token that a dictionary already matched. By default a correction only runs on unknown tokens. Accepts a JSON boolean or the string `"true"`; the phonetic correction takes the same key. |

Candidates are sorted by edit distance first, so the closest matches survive the cap. A missing, non-numeric or non-positive value falls back to the default.

> **Corrections respect the dictionary language too.** A dictionary whose `lang` does not match the conversation language is skipped by the corrections exactly as it is skipped by the direct lookup — otherwise a foreign-language word would come back as a "correction" at distance 0 for every unknown token.

> **There is no stemming correction.** EDDI ships exactly the three corrections above; referencing `eddi://ai.labs.parser.corrections.stemming` (or any other unregistered extension URI) makes workflow initialization fail with `UnrecognizedExtensionException` and the agent will not start.

## Testing the Pattern Matcher

Once you've created both dictionary and parser, you can test it standalone.

Make a `POST` request to `/parser/{PARSER_ID}?version={VERSION}` with plain text in the body. With authorization on, the endpoint takes the `eddi-admin` or `eddi-editor` role (the same as the parser store), and with workspaces enforced the caller also needs VIEW on the parser configuration:

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

The parser returns an array of solutions — every reading of the input, best first — where each solution contains expressions representing the classification of the input. The input runs through the parser's normalizers first, as it does in a conversation; the default conversation language (`en`) applies.

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

| Option                     | Default | Description |
| -------------------------- | ------- | ----------- |
| `includeUnknown`           | `true`  | Include `unknown(...)` expressions for words no dictionary or correction recognized |
| `includeUnused`            | `true`  | Include `unused(...)` expressions: words of a phrase that did not complete it, and dictionary words defined without expressions |
| `appendExpressions`        | `true`  | Append the freshly parsed expressions to the ones already in the step |
| `minMatchingAccuracy`      | `0`     | Matches less accurate than this (0..1) contribute no expressions — see [Match quality](#match-quality). `0` keeps every match |
| `maxAlternatives`          | `0`     | Also store up to this many other readings (0..10) as `expressions:alternatives` |
| `matchPartialPhrases`      | `false` | Let a fragment of a phrase yield the phrase — see [Partial phrases](#partial-phrases) |
| `minPartialPhraseCoverage` | `0.5`   | Smallest share of a phrase's words a fragment must cover, when `matchPartialPhrases` is on |
| `semanticFallback`         | —       | Embedding-based fallback for input the dictionaries did not understand — see [Semantic fallback](#semantic-fallback) |
| `maxInputTokens`           | `200`   | Hard cap on tokens taken from one input; anything beyond is dropped and `expressions:inputTruncated` is set |
| `maxSuggestions`           | `1000`  | Hard cap on dictionary suggestions evaluated per input |
| `maxSolutions`             | `100`   | Hard cap on solutions collected per input |

The three `max*` limits guard against pathological inputs. Values below `1` are ignored and fall back to the default. A fraction outside 0..1 (or `0` for `minPartialPhraseCoverage`) falls back to its default too, with a warning in the log.

### How the parser chooses a reading

Every token can have several matches: a dictionary word, a phrase word, a regex, a number, typo corrections. The parser tries the combinations **best first** — first every token's best match, then each combination that takes one token's second-best, and so on — and keeps the first that completes a phrase, or else the first one. Dictionaries are consulted in the order they are configured, temporary (quick-reply) dictionaries before them, corrections after them. A phrase is only matched in a conversation whose language its dictionary applies to.

### Match quality

Every match carries an accuracy between 0 and 1: `1.0` for the word as typed, `0.9` when only the case differs, lower for a typo correction (by edit distance), `0.3` for a phonetic guess. A phrase is as accurate as its least accurate word, so a phrase put together from a typo correction is not reported as exact. The parser stores:

| Memory key | Content |
| ---------- | ------- |
| `expressions:parsed` | The chosen expressions — what behavior rules match on |
| `expressions:matches` | Which input word produced which expression, marked `[phrase]`, `[regex]` and, for anything not exact, `[accuracy 0.67, corrected]` |
| `expressions:confidence` | The accuracy of the least accurate match that contributed an expression; absent when nothing was understood |
| `expressions:alternatives` | With `maxAlternatives` above 0: the other readings, best first, as expression strings |
| `expressions:inputTruncated` | `true` when the input had more than `maxInputTokens` tokens |

`minMatchingAccuracy` is the switch for "don't act on a guess": with `0.9`, `"delte my account"` no longer becomes `delete(account)` through a typo correction. The rejected match contributes no expression at all — it does not turn into `unknown(...)` either — so write the fallback rule for "nothing matched" (for example on the absence of the expressions you expect), not for `unknown(*)`. The threshold also decides which reading is chosen: a phrase whose accuracy falls below it (assembled from corrections, or a fragment whose coverage brings it below) is not taken, so it cannot push out a word reading that does qualify.

### Partial phrases

With `matchPartialPhrases: true`, a run of input words that is a run of a longer phrase's words yields that phrase: for the phrase `good morning sir`, the input `good morning` matches with accuracy `2/3`, the share of the phrase it covers. The fragment has to cover at least `minPartialPhraseCoverage` of the phrase. When two phrases are covered equally well by the same words — `good` of `good morning` and of `good night` — the reading is ambiguous and neither is taken. A full match always wins over a fragment. It is off by default because it lets fewer words trigger a phrase than the agent designer wrote.

### Semantic fallback

Dictionaries only know the words they were given. The semantic fallback catches the paraphrases they miss, without giving up deterministic rules: when the dictionaries produced nothing but `unknown`/`unused` for an input, the parser compares the input with example sentences by embedding similarity and, if one is similar enough, uses **that example's expressions**. Behavior rules keep matching on expressions they know.

```json
"config": {
  "semanticFallback": {
    "embeddingProvider": "openai",
    "embeddingParameters": {
      "apiKey": "${vault:openai-key}",
      "modelName": "text-embedding-3-small"
    },
    "minScore": 0.82,
    "includeDictionaryPhrases": true,
    "examples": [
      { "text": "I want to cancel my order", "expressions": "intent(cancel_order)" },
      { "text": "ich möchte meine Bestellung stornieren", "expressions": "intent(cancel_order)", "lang": "de" }
    ]
  }
}
```

| Key | Default | Description |
| --- | ------- | ----------- |
| `embeddingProvider`, `embeddingParameters` | — (required) | The embedding model, configured exactly as for a [knowledge base](rag.md): `openai`, `azure-openai`, `ollama`, `mistral`, `bedrock`, `cohere`, `gemini`, `vertex`. API keys are vault references |
| `minScore` | `0.8` | Least cosine similarity that counts as a match, above 0 and at most 1 |
| `includeDictionaryPhrases` | `true` | Use the parser's dictionary phrases that carry expressions as examples, so a paraphrase of a phrase you already wrote finds it |
| `includeDictionaryWords` | `false` | Use the single dictionary words as examples too |
| `examples` | — | Further examples: `text`, `expressions` and optionally `lang` (an example with a `lang` is only used in conversations in that language) |

What it costs and how it fails:

- **One embedding request per turn the dictionaries did not understand**, and one batch of requests for the examples the first time it is used (at most 2,000 examples; inputs are cut at 1,000 characters). Turns the dictionaries understood never reach the provider. The counter `eddi_parser_semantic_fallback_total{outcome="match"|"no_match"}` counts the consultations.
- **A provider failure only means "no match"**: the turn continues with the dictionaries' result. If the examples cannot be embedded, the next attempt waits 60 seconds.
- **The examples are embedded again when the model changes**: when a global variable or secret the embedding configuration uses changes, or the model's client was idle for 30 minutes and was rebuilt. The input is always embedded by the same model as the examples it is compared with.
- **A malformed block fails the deployment**, not a conversation: a missing or unsupported provider, no examples at all, an example without parseable expressions or a `minScore` out of range are refused when the workflow is built.
- The matched example shows up in `expressions:matches` as `"<input>" [semantic 0.87, like "<example>"] → <expressions>`, and its similarity is the stored `expressions:confidence`.
- The standalone `/parser` endpoint below does not consult the fallback; it shows what the dictionaries make of an input.

### Referencing a stored parser instead

Instead of carrying the parser inline, the step can reference a parser document created through `/parserstore/parsers` (this is the shape the setup wizard, the MCP setup tools and the Manager's pipeline builder write):

```json
{ "type": "eddi://ai.labs.parser", "config": { "uri": "eddi://ai.labs.parser/parserstore/parsers/PARSER_ID?version=1" } }
```

The document's `config` and `extensions` are used as if they were written in the step. Both may be combined: a `config` key the step sets **overrides** the document's, and the step's own `extensions` entries are **appended after** the document's for each extension point (normalizers, dictionaries, corrections — so the document's normalizers run first). A `uri` that cannot be read fails the deployment. Before this was supported, such a step ran with no dictionaries at all, silently.

### Quick replies as a temporary dictionary

The quick replies the agent offered on the previous turn — from its output set, or built by an httpcall / LLM / MCP `postResponse` — become a temporary dictionary for the next input, so a click on one produces its `expressions` (or, without any, an expression generated from its value: its letters and digits, of any script, lower-cased and joined by `_` — `"Größe M"` becomes `größe_m`). Their values pass through the parser's normalizers like the input does, so a quick reply with punctuation still matches its own click under the punctuation normalizer. Quick replies a **client** sends as `context` are only displayed: they are never matched and never produce an expression, so a client cannot hand itself an expression (and, with `expressionsAsActions`, an action) by offering itself a quick reply.

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
  "actions": ["route_to_billing", "flag_high_priority"]
}
```

### 5. Result:

> These action names are illustrative. Nothing routes or prioritises by itself: a later task (for example an API call or a behavior rule) must be configured to listen for them.

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
**Solution**: Make conditions more specific, or put the rules in one behavior group, where only the first matching rule fires (rules have no priority field)

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

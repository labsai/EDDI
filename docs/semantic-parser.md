# Semantic Parser

In this section we will be showcasing the semantic parser, which is a very important module of EDDI that plays the part of the engine that parses the semantics introduced in EDDI Chabot's definitions.

We will need regular dictionaries in order to store our custom words and phrases .

First, we will make a `POST` to `/regulardictionarystore/regulardictionaries` with a `JSON` in the body like this:

```javascript
{
  "language": "en",
  "words": [
    {
      "word": "hello",
      "expressions": "greeting(hello)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "good afternoon",
      "expressions": "greeting(good_afternoon),language(english)"
    }
  ]
}
```

The API should return with `201` with a URI referencing the newly created dictionary :

`eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/`**`<UNIQUE_ID>`**`?version=`**`<VERSION>`**

This `URI` will be used in the parser configuration.

The next step is to create a `parser configuration`,  including the reference to the previously created `dictionary` .

A `POST` to `/parserstore/parsers` must be performed.

Submit this type of `JSON`

> **Important:** Don't forget to replace the **`<UNIQUE_ID>`** and **`<VERSION>`** !

## E**xample of a parser configuration**

```javascript
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
        "type": " eddi://ai.labs.parser.dictionaries.ordinalNumber"
      },
      {
        "type": "eddi://ai.labs.parser.dictionaries.regular",
        "config": {
          "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/<UNIQUE_ID>?version=<VERSION>"
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

### Description of Semantic Parser types

| Type        | EDDI URI                                           | Description                                                                                                                                                                                                                                                                                                                                   |
| ----------- | -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Integer     | `eddi://ai.labs.parser.dictionaries.integer`       | Matches all **positive** integers                                                                                                                                                                                                                                                                                                             |
| Decimal     | `eddi://ai.labs.parser.dictionaries.decimal`       | Matches decimal numbers with `.` as well as `,` as a fractional separator                                                                                                                                                                                                                                                                     |
| Punctuation | `eddi://ai.labs.parser.dictionaries.punctuation`   | <p>Matches common punctuation:</p><p><code>!</code>(<strong>exclamation_mark</strong>) </p><p><code>?</code> (<strong>question_mark</strong>)</p><p><code>.</code> (<strong>dot</strong>)</p><p><code>,</code> (<strong>comma</strong>) </p><p><code>:</code> (<strong>colon</strong>) </p><p><code>;</code> (<strong>semicolon</strong>)</p> |
| Email       | `eddi://ai.labs.parser.dictionaries.email`         | Matches an email address with regex `(\b[A-Z0-9._%+-]+@[A-Z0-9.-]+.[A-Z]{2,4}\b)`                                                                                                                                                                                                                                                             |
| Time        | `eddi://ai.labs.parser.dictionaries.time`          | Matches the following time formats: e.g : **01:20 , 01h20 , 22:40 , 13:43:23**                                                                                                                                                                                                                                                                |
| Number      | `eddi://ai.labs.parser.dictionaries.ordinalNumber` | Ordinal numbers in English language such as **1st, 2nd, 3rd, 4th, 5th, ...**                                                                                                                                                                                                                                                                  |
| Regular     | `eddi://ai.labs.parser.dictionaries.regular`       | URI to a regular dictionary resource: `eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/<`**UNIQUE\_ID**`>version <`**VERSION**`>`                                                                                                                                                                                 |

In order to use the parser based on the created configurations, we will have to make a `POST` to `/parser/`**`<PARSER_ID>`**`?version=`**`<VERSION>`**

In the body just put plain text, it is what you would like to be parsed.

The parser will return `expressions` representing the elements from your plain text

> **Note:** Keep in mind that this parser is made for human dialog, not parsing (`full-text`) documents.

# Semantic Parser

In this section we will be showcasing the sematic parser, which a very important module of EDDI that plays the part of the engine that parses the semantics introduced in EDDIs Chabot's definitions.

We will need regular dictionaries in order to store our custom words and phrases .

First we will make a `POST` to `/regulardictionarystore/regulardictionaries` with a `JSON` in the body like this:

```javascript
{
  "language": "en",
  "words": [
    {
      "word": "hello",
      "exp": "greeting(hello)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "good afternoon",
      "exp": "greeting(good_afternoon),language(english)"
    }
  ]
}
```

The API should return with `201` with an URI referencing the newly created dictionary :

`eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/`**`<UNIQUE_ID>`**`?version=`**`<VERSION>`**

This `URI` will be used in the parser configuration.

The next step is to create a `parser configuration`, including the reference to the previously created `dictionary` .

A `POST` to `/parserstore/parsers` must performed.

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

<table>
  <thead>
    <tr>
      <th style="text-align:left">Type</th>
      <th style="text-align:left">EDDI URI</th>
      <th style="text-align:left">Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left">Integer</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.integer</code>
      </td>
      <td style="text-align:left">Matches all <b>positive </b>integers</td>
    </tr>
    <tr>
      <td style="text-align:left">Decimal</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.decimal</code>
      </td>
      <td style="text-align:left">Matches decimal numbers with <code>. </code>as well as <code>, </code>as
        a fractional separator</td>
    </tr>
    <tr>
      <td style="text-align:left">Ponctuation</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.punctuation</code>
      </td>
      <td style="text-align:left">
        <p>Matches common punctuation:</p>
        <p><code>!</code>(<b>exclamation_mark</b>)</p>
        <p><code>? </code>(<b>question_mark</b>)</p>
        <p><code>. </code>(<b>dot</b>)</p>
        <p><code>, </code>(<b>comma</b>)</p>
        <p><code>: </code>(<b>colon</b>)</p>
        <p><code>; </code>(<b>semicolon</b>)</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Email</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.email</code>
      </td>
      <td style="text-align:left">Matches an email address with regex <code>(\b[A-Z0-9._%+-]+@[A-Z0-9.-]+.[A-Z]{2,4}\b)</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Time</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.time</code>
      </td>
      <td style="text-align:left">Matches the following time formats: e.g : <b>01:20 , 01h20 , 22:40 , 13:43:23</b>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Number</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.ordinalNumber</code>
      </td>
      <td style="text-align:left">Ordinal numbers in English language such as <b>1st, 2nd, 3rd, 4th, 5th, ...</b>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Regular</td>
      <td style="text-align:left"><code>eddi://ai.labs.parser.dictionaries.regular</code>
      </td>
      <td style="text-align:left">URI to a regular dictionary resource: <code>eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/&lt;</code><b>UNIQUE_ID</b><code>&gt;version &lt;</code><b>VERSION</b><code>&gt;</code>
      </td>
    </tr>
  </tbody>
</table>In order to use the parser based on the created configurations we will have to make a `POST` to `/parser/`**`<PARSER_ID>`**`?version=`**`<VERSION>`**

In the body just put plain text, its what you would like to be parsed.

The parser will return `expressions` representing the elements from your plain text

> **Note:** Keep in mind that this parser is made for human dialogs, not parsing \(`full-text`\) documents.


# Output Templating

Some of the coolest features of **EDDI** that it will allow you dynamically template your output based on data that you would receive from `httpCalls` or `context information` for instance, that makes **EDDI's** replies to user interactions rich and dynamic.

The **output templating** is evaluated by `thymeleaf` **templating engine**, that means you can use the majority of `thymeleaf` `tags` and `expression language` to define how you would like your output to be.

## Enabling the feature:

Basically while creating the bot you must include `eddi://ai.labs.output` to one of the `packages` that will be part of the bot.

> Important The templating feature will not work if it is included before `eddi://ai.labs.output` extension, **it must be included after**.

## Example

Here is how the output templating should be specified **inside of a package.**

```javascript
{
  "type": "eddi://ai.labs.templating",
  "extensions": {},
  "config": {},
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.parser",
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
              "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/{{dictionary_id}}?version=1"
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
      "config": {}
    },
    {
      "type": "eddi://ai.labs.behavior",
      "config": {
        "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/{{behaviourset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.httpcalls",
      "config": {
        "uri": "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/{{httpcall_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/{{outputset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.templating",
      "extensions": {},
      "config": {}
    }
  ]
}
```

## _**Additional Information :**_

[Thymeleaf documentation.](https://www.thymeleaf.org/)


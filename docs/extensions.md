# Extensions

In this article we will talk about **EDDI**'s **`extensions`**.

**EDDI's `extensions`** are the features that your current instance of EDDI is supporting, the latter are used in the process of configuring/developing a Chatbot.

The list of `extensions` will allow you to have an overview of what is enabled in your current instance of **EDDI**, the list can be retrieved by calling the API endpoint below.

> **Note** All EDDI's resources start with `eddi://`, this is used to distinguish if the resource is an EDDI extension, e.g : `callbacks`, `httpcalls`, `outputsets`, `bot packages`, etc..

### &#x20;Extensions REST API Endpoint

| Element      | Value                        |
| ------------ | ---------------------------- |
| HTTP Method  | `GET`                        |
| API Endpoint | `/extensionstore/extensions` |

## Model

```javascript
[
  {
    "type": "string",
    "displayName": "string",
    "configs": {},
    "extensions": {}
  }
]
```

### Description of the model

| Element     | Value                                     |
| ----------- | ----------------------------------------- |
| type        | (`String`) The type of the extension      |
| displayName | (`String`) A given name to the extension  |
| configs     | (`Object`) Configuration of the extension |
| extensions  | (`Object`) Extensions of the extension    |

## Example

> More about regular dictionaries can be found [here](creating-your-first-chatbot/#1-creating-a-regular-dictionary).

_Request URL_

`GET http://localhost:7070/extensionstore/extensions`

_Response Body_

```javascript
[ {
  "type" : "ai.labs.parser",
  "displayName" : "Input Parser",
  "configs" : {
    "includeUnknown" : {
      "displayName" : "Include Unknown Expressions",
      "fieldType" : "BOOLEAN",
      "defaultValue" : true,
      "optional" : true
    },
    "includeUnused" : {
      "displayName" : "Include Unused Expressions",
      "fieldType" : "BOOLEAN",
      "defaultValue" : true,
      "optional" : true
    },
    "appendExpressions" : {
      "displayName" : "Append Expressions",
      "fieldType" : "BOOLEAN",
      "defaultValue" : true,
      "optional" : true
    }
  },
  "extensions" : {
    "corrections" : [ {
      "type" : "ai.labs.parser.corrections.levenshtein",
      "displayName" : "Damerau Levenshtein Correction",
      "configs" : {
        "distance" : {
          "displayName" : "Distance",
          "fieldType" : "INT",
          "defaultValue" : 2,
          "optional" : true
        }
      },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.corrections.stemming",
      "displayName" : "Grammar Stemming Correction",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.corrections.phonetic",
      "displayName" : "Phonetic Matching Correction",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.corrections.mergedTerms",
      "displayName" : "Merged Terms Correction",
      "configs" : { },
      "extensions" : { }
    } ],
    "normalizer" : [ {
      "type" : "ai.labs.parser.normalizers.punctuation",
      "displayName" : "Punctuation Normalizer",
      "configs" : {
        "removePunctuation" : {
          "displayName" : "Remove Punctuation",
          "fieldType" : "BOOLEAN",
          "defaultValue" : false,
          "optional" : true
        },
        "punctuationRegexPattern" : {
          "displayName" : "Punctuation RegEx Pattern",
          "fieldType" : "STRING",
          "defaultValue" : "!?:.,;",
          "optional" : true
        }
      },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.normalizers.specialCharacter",
      "displayName" : "Convert Special Character Normalizer",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.normalizers.contractedWords",
      "displayName" : "Contracted Word Normalizer",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.normalizers.allowedCharacter",
      "displayName" : "Remove Undefined Character Normalizer",
      "configs" : { },
      "extensions" : { }
    } ],
    "dictionaries" : [ {
      "type" : "ai.labs.parser.dictionaries.integer",
      "displayName" : "Integer Dictionary",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.dictionaries.decimal",
      "displayName" : "Decimal Dictionary",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.dictionaries.ordinalNumber",
      "displayName" : "Ordinal Numbers Dictionary",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.dictionaries.punctuation",
      "displayName" : "Punctuation Dictionary",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.dictionaries.time",
      "displayName" : "Time Expression Dictionary",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.dictionaries.email",
      "displayName" : "Email Dictionary",
      "configs" : { },
      "extensions" : { }
    }, {
      "type" : "ai.labs.parser.dictionaries.regular",
      "displayName" : "Regular Dictionary",
      "configs" : {
        "uri" : {
          "displayName" : "Resource URI",
          "fieldType" : "URI",
          "optional" : false
        }
      },
      "extensions" : { }
    } ]
  }
}, {
  "type" : "ai.labs.behavior",
  "displayName" : "Behavior Rules",
  "configs" : {
    "appendActions" : {
      "displayName" : "Append Actions",
      "fieldType" : "BOOLEAN",
      "defaultValue" : true,
      "optional" : false
    },
    "uri" : {
      "displayName" : "Resource URI",
      "fieldType" : "URI",
      "optional" : false
    }
  },
  "extensions" : { }
}, {
  "type" : "ai.labs.output",
  "displayName" : "Output Generation",
  "configs" : {
    "uri" : {
      "displayName" : "Resource URI",
      "fieldType" : "URI",
      "optional" : false
    }
  },
  "extensions" : { }
}, {
  "type" : "ai.labs.templating",
  "displayName" : "Templating",
  "configs" : { },
  "extensions" : { }
}, {
  "type" : "ai.labs.property",
  "displayName" : "Property Extraction",
  "configs" : { },
  "extensions" : { }
}, {
  "type" : "ai.labs.callback",
  "displayName" : "External Callback",
  "configs" : {
    "callbackUri" : {
      "displayName" : "Callback URI",
      "fieldType" : "URI",
      "optional" : false
    },
    "callOnActions" : {
      "displayName" : "Call on Actions",
      "fieldType" : "STRING",
      "defaultValue" : "",
      "optional" : true
    },
    "timeoutInMillis" : {
      "displayName" : "Timeout in Milliseconds",
      "fieldType" : "URI",
      "defaultValue" : 10000,
      "optional" : true
    }
  },
  "extensions" : { }
}, {
  "type" : "ai.labs.httpcalls",
  "displayName" : "Http Calls",
  "configs" : {
    "uri" : {
      "displayName" : "Resource URI",
      "fieldType" : "URI",
      "optional" : false
    }
  },
  "extensions" : { }
} ]
```

_Response Code_

`200`

_Response Headers_

```javascript
{
  "access-control-allow-origin": "*",
  "date": "Sat, 19 May 2018 22:00:11 GMT",
  "content-type": "application/json",
  "access-control-allow-headers": "authorization, Content-Type",
  "content-length": "6208",
  "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
  "access-control-expose-headers": "location"
}
```

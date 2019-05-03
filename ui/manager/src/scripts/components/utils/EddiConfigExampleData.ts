import {
  BEHAVIOR,
  BOT,
  HTTPCALLS,
  OUTPUT,
  PACKAGE,
  REGULAR_DICTIONARY,
} from './EddiTypes';

export const REGULAR_DICTIONARY_POST_EXAMPLE: string = `{
    "words": [
        {
            "word": "Hello",
            "exp": "greeting(hello)",
            "frequency": 0
        }
    ],
    "phrases": [
        {
            "phrase": "Good morning",
            "exp": "greeting(good_morning)"
        }
    ]
}`;

export const BEHAVIOUR_POST_EXAMPLE: string = `{
  "behaviorGroups": [
    {
      "name": "Smalltalk",
      "behaviorRules": [
        {
          "name": "Greeting",
          "actions": [
            "greet"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "greeting(*)",
                "occurrence": "currentStep"
              }
            }
          ]
        }
      ]
    }
  ]
}`;

export const OUTPUT_POST_EXAMPLE: string = `{
  "outputSet": [
    {
      "action": "greet",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Hi there! Nice to meet up! :-)",
            "Hey you!"
          ]
        }
      ]
    }
  ]
}`;

export const HTTPCALLS_POST_EXAMPLE: string = `{
  "targetServer": "https://api.openweathermap.org/data/2.5/weather",
  "httpCalls": [
    {
      "name": "currentWeather",
      "saveResponse": true,
      "responseObjectName": "currentWeather",
      "actions": [
        "current_weather_in_city"
      ],
      "request": {
        "path": "",
        "headers": {},
        "queryParams": {
          "APPID": "c3366d78c7c0f76d63eb4cdf1384ddbf",
          "units": "metric",
          "q": "[[\${memory.current.input}]]"
        },
        "method": "get",
        "contentType": "",
        "body": ""
      }
    }
  ]
}`;
/* tslint:disable */
export const BOT_POST_EXAMPLE: string = `{
"packages": [
"eddi://ai.labs.package/packagestore/packages/<UNIQUE_PACKAGE_ID>?version=<PACKAGE_VERSION>"
],
"channels": []
}`;

export const PACKAGE_POST_EXAMPLE: string = `{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.normalizer",
      "config": {
        "allowedChars": "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ !?:;.,",
        "convertUmlaute": "true"
      }
    },
    {
      "type": "eddi://ai.labs.parser",
      "extensions": {
        "dictionaries": [
          {
            "type": "eddi://ai.labs.parser.dictionaries.regular",
            "config": {
              "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/<UNIQUE_DICTIONARY_ID>?version=<DICTIONARY_VERSION>"
            }
          }
        ]
      },
      "config": {}
    },
    {
      "type": "eddi://ai.labs.behavior",
      "config": {
        "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/<UNIQUE_BEHAVIOR_ID>?version=<BEHAVIOR_VERSION>"
      }
    },
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/<UNIQUE_OUTPUTSET_ID>?version=<OUTPUTSET_VERSION>"
      }
    }
  ]
}`;
/* tslint:enable */
export function getPostExample(eddiType: string) {
  switch (eddiType) {
    case REGULAR_DICTIONARY:
      return REGULAR_DICTIONARY_POST_EXAMPLE;
    case BEHAVIOR:
      return BEHAVIOUR_POST_EXAMPLE;
    case OUTPUT:
      return OUTPUT_POST_EXAMPLE;
    case HTTPCALLS:
      return HTTPCALLS_POST_EXAMPLE;
    case BOT:
      return BOT_POST_EXAMPLE;
    case PACKAGE:
      return PACKAGE_POST_EXAMPLE;

    default:
      return '{}';
  }
}

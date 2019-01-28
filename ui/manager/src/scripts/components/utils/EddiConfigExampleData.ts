import {
  BEHAVIOR,
  BOT,
  HTTPCALLS,
  OUTPUT,
  PACKAGE,
  PACKAGE_PATH,
  REGULAR_DICTIONARY,
} from './EddiTypes';

export const REGULAR_DICTIONARY_POST_EXAMPLE: string = `{
  "words": [
    {
      "word": "string",
      "exp": "string",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "string",
      "exp": "string"
    }
  ]
}`;

export const BEHAVIOUR_POST_EXAMPLE: string = `{
  "behaviorGroups": [
    {
      "name": "string",
      "behaviorRules": [
        {
          "name": "string",
          "actions": [
            "string"
          ],
          "children": [
            {
              "type": "string",
              "values": {},
              "children": [
                {}
              ]
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
      "action": "string",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "string",
          "valueAlternatives": [
            "string"
          ]
        }
      ],
      "quickReplies": [
        {
          "value": "string",
          "expressions": "string"
        }
      ]
    }
  ]
}`;

export const HTTPCALLS_POST_EXAMPLE: string = `{
  "targetServer": "string",
  "httpCalls": [
    {
      "name": "string",
      "fireAndForget": true,
      "saveResponse": true,
      "responseObjectName": "string",
      "actions": [
        "string"
      ],
      "request": {
        "path": "string",
        "headers": {},
        "queryParams": {},
        "method": "string",
        "contentType": "string",
        "body": "string"
      },
      "postResponse": {
        "qrBuildInstruction": {
          "pathToTargetArray": "string",
          "iterationObjectName": "string",
          "templateFilterExpression": "string",
          "quickReplyValue": "string",
          "quickReplyExpressions": "string"
        }
      }
    }
  ]
}`;

export const BOT_POST_EXAMPLE: string = `{
  "packages": [
    "string"
  ],
  "channels": [
    {
      "type": "string",
      "config": {}
    }
  ]
}`;

export const PACKAGE_POST_EXAMPLE: string = `{
  "packageExtensions": [
    {
      "type": "string",
      "extensions": {},
      "config": {}
    }
  ]
}`;

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

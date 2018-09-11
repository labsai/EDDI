export const REGULAR_DICTIONARY_POST_EXAMPLE: string =
  '{\n' +
  '  "words": [\n' +
  '    {\n' +
  '      "word": "string",\n' +
  '      "exp": "string",\n' +
  '      "frequency": 0\n' +
  '    }\n' +
  '  ],\n' +
  '  "phrases": [\n' +
  '    {\n' +
  '      "phrase": "string",\n' +
  '      "exp": "string"\n' +
  '    }\n' +
  '  ]\n' +
  '}';

export const BEHAVIOUR_POST_EXAMPLE: string =
  '{\n' +
  '  "behaviorGroups": [\n' +
  '    {\n' +
  '      "name": "string",\n' +
  '      "behaviorRules": [\n' +
  '        {\n' +
  '          "name": "string",\n' +
  '          "actions": [\n' +
  '            "string"\n' +
  '          ],\n' +
  '          "children": [\n' +
  '            {\n' +
  '              "type": "string",\n' +
  '              "values": {},\n' +
  '              "children": [\n' +
  '                {}\n' +
  '              ]\n' +
  '            }\n' +
  '          ]\n' +
  '        }\n' +
  '      ]\n' +
  '    }\n' +
  '  ]\n' +
  '}';

export const OUTPUT_POST_EXAMPLE: string =
  '{\n' +
  '  "outputSet": [\n' +
  '    {\n' +
  '      "action": "string",\n' +
  '      "timesOccurred": 0,\n' +
  '      "outputs": [\n' +
  '        {\n' +
  '          "type": "string",\n' +
  '          "valueAlternatives": [\n' +
  '            "string"\n' +
  '          ]\n' +
  '        }\n' +
  '      ],\n' +
  '      "quickReplies": [\n' +
  '        {\n' +
  '          "value": "string",\n' +
  '          "expressions": "string"\n' +
  '        }\n' +
  '      ]\n' +
  '    }\n' +
  '  ]\n' +
  '}';

export const HTTPCALLS_POST_EXAMPLE: string =
  '{\n' +
  '  "targetServer": "string",\n' +
  '  "httpCalls": [\n' +
  '    {\n' +
  '      "name": "string",\n' +
  '      "saveResponse": true,\n' +
  '      "responseObjectName": "string",\n' +
  '      "actions": [\n' +
  '        "string"\n' +
  '      ],\n' +
  '      "request": {\n' +
  '        "path": "string",\n' +
  '        "headers": {},\n' +
  '        "method": "string",\n' +
  '        "contentType": "string",\n' +
  '        "body": "string"\n' +
  '      }\n' +
  '    }\n' +
  '  ]\n' +
  '}';

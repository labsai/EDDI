export interface ISnippet {
  scope: string;
  name: string;
  tabTrigger: string;
  content: string;
}
export const regularDictionarySnippets: ISnippet[] = [
  {
    scope: 'json',
    name: 'phrases',
    tabTrigger: 'phrases',
    content: `"phrases": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'phrase',
    tabTrigger: 'phrase',
    content: `{
  "phrase": "\${1:phrase}",
  "exp": "\${2:exp_name}(\${3:exp_value})",
  "frequency": 0
}`,
  },
  {
    scope: 'json',
    name: 'words',
    tabTrigger: 'words',
    content: `"words": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'word',
    tabTrigger: 'word',
    content: `{
  "word": "\${1:word}",
  "exp": "\${2:exp_name}(\${3:exp_value})",
  "frequency": 0
}`,
  },
];
export const behaviorSnippets: ISnippet[] = [
  {
    scope: 'json',
    name: 'behaviorGroups',
    tabTrigger: 'behaviorGroups',
    content: `"behaviorGroups": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'behaviorGroup',
    tabTrigger: 'behaviorGroup',
    content: `{
  "name": "\${1:name}",
  "behaviorRules": [\${2:}]
}`,
  },
  {
    scope: 'json',
    name: 'behaviorRule',
    tabTrigger: 'behaviorRule',
    content: `{
  "name": "\${1:name}",
  "actions": [\${2:}],
  "children": [\${3:}]
}`,
  },
  // ---------- RuleChild -----------------
  {
    scope: 'json',
    name: 'inputmatcher',
    tabTrigger: 'inputmatcher',
    content: `{
  "type": "inputmatcher",
  "values": {
    "expressions": "\${1:expressions}",
    "occurrence": "\${2:occurrence}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'contextmatcher:expressions',
    tabTrigger: 'contextmatcher:expressions',
    content: `{
  "type": "contextmatcher",
  "values": {
    "contextType": "expressions",
    "contextKey": "\${1:contextKey}",
    "expressions": "\${2:expressions}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'contextmatcher:object',
    tabTrigger: 'contextmatcher:object',
    content: `{
  "type": "contextmatcher",
  "values": {
    "contextType": "object",
    "contextKey": "\${1:contextKey}",
    "objectKeyPath": "\${2:objectKeyPath}",
    "objectValue": "\${3:objectValue}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'contextmatcher:string',
    tabTrigger: 'contextmatcher:string',
    content: `{
  "type": "contextmatcher",
  "values": {
    "contextType": "string",
    "contextKey": "\${1:contextKey}",
    "string": "\${2:string}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'contextmatcher',
    tabTrigger: 'contextmatcher',
    content: `{
  "type": "contextmatcher",
  "values": {
    "contextType": "\${1:contextType}",
    "contextKey": "\${2:contextKey}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'connector',
    tabTrigger: 'connector',
    content: `{
  "type": "connector",
  "values": {
    "operator": "\${1:operator}",
  }
}`,
  },
  {
    scope: 'json',
    name: 'occurrence',
    tabTrigger: 'occurrence',
    content: `{
  "type": "occurrence",
  "values": {
  "minTimesOccurred": "\${1:minTimesOccurred}",
  "maxTimesOccurred": "\${2:maxTimesOccurred}",
  "behaviorRuleName": "\${3:behaviorRuleName}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'dependency',
    tabTrigger: 'dependency',
    content: `{
      "type": "dependency",
      "values": {
        "reference": "\${1:reference}",
  }
}`,
  },
  {
    scope: 'json',
    name: 'actionmatcher',
    tabTrigger: 'actionmatcher',
    content: `{
  "type": "actionmatcher",
  "values": {
    "actions": "\${1:actions}",
    "occurrence": "\${2:occurrence}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'dynamicvaluematcher',
    tabTrigger: 'dynamicvaluematcher',
    content: `{
  "type": "dynamicvaluematcher",
  "values": {
    "valuePath": "\${1:valuePath}",
    "contains": "\${2:contains}",
    "equals": "\${3:equals}"
  }
}`,
  },
];
export const outputSnippets: ISnippet[] = [
  {
    scope: 'json',
    name: 'outputset',
    tabTrigger: 'outputsets',
    content: `"outputset": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'output',
    tabTrigger: 'output',
    content: `
{
  "action": "\${1:action}",
  "timesOccurred": \${2:0},
  "outputs": [\${3:}]
}`,
  },
  {
    scope: 'json',
    name: 'outputValue',
    tabTrigger: 'outputValue',
    content: `{
  "type": "\${1:type}",
  "valueAlternatives": [\${2:}]
}`,
  },
  {
    scope: 'json',
    name: 'quickReplies',
    tabTrigger: 'quickReplies',
    content: `"quickReplies": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'quickReply',
    tabTrigger: 'quickReply',
    content: `{
  "value": "\${1:value}",
  "expressions": "\${2:exp_name}(\${3:exp_value})"
}`,
  },
];
export const httpCallsSnippets: ISnippet[] = [];
export const packageSnippets: ISnippet[] = [
  {
    scope: 'json',
    name: 'packageExtensions',
    tabTrigger: 'packageExtensions',
    content: `"packageExtensions": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'behavior',
    tabTrigger: 'behavior',
    content: `{
  "type": "eddi://ai.labs.behavior",
  "config": {
    "uri": "\${1:uri}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'output',
    tabTrigger: 'output',
    content: `{
  "type": "eddi://ai.labs.output",
  "config": {
    "uri": "\${1:uri}"
  }
}`,
  },
  {
    scope: 'json',
    name: 'regularDictionary',
    tabTrigger: 'regularDictionary',
    content: `{
  "type": "eddi://ai.labs.parser.dictionaries.regular",
  "config": {
    "uri": "\${1:uri}"
  },
}`,
  },
  {
    scope: 'json',
    name: 'parser',
    tabTrigger: 'parser',
    content: `{
  "type": "eddi://ai.labs.parser",
  "extensions": {
    "dictionaries": [\${1:}],
    "corrections": [\${2:}],
  },
}`,
  },
];
export const botSnippets: ISnippet[] = [
  {
    scope: 'json',
    name: 'packages',
    tabTrigger: 'packages',
    content: `"packages": [\${1:}]`,
  },
  {
    scope: 'json',
    name: 'channels',
    tabTrigger: 'channels',
    content: `"channels": [\${1:}]`,
  },
];

# Configuration gotchas

Each of these saves and deploys without an error and then misbehaves at runtime. Check
every hand-written configuration against this list. The full rules are in
`read_docs("agent-config-authoring")`; the per-extension references are
`read_docs("behavior-rules")`, `read_docs("properties")`, `read_docs("httpcalls")` and
`read_docs("output-configuration")`.

## 1. `{properties.x}` is the raw value

Templates are Qute (`{…}`), not Thymeleaf (`[[${…}]]`). A property in a template is
already its value:

```
✅ {properties.city}
❌ {properties.city.valueString}     fails at runtime
```

Other template roots: `{memory.current.input}`, `{context.<key>}`, `{snippets.<name>}`,
`{vars.<key>}`, `{userInfo.userId}`, `{conversationInfo.conversationId}`,
`{conversationLog}`.

## 2. Every behavior rule needs an `actionmatcher` on `lastStep`

A rule with only an `inputmatcher` fires whenever the input matches, at any point in the
conversation. Tie it to the step before:

```json
{
  "name": "Start over",
  "actions": ["ask_for_agent_name"],
  "conditions": [
    { "type": "actionmatcher", "configs": { "actions": "confirm_creation", "occurrence": "lastStep" } },
    { "type": "inputmatcher", "configs": { "expressions": "start_over", "occurrence": "currentStep" } }
  ]
}
```

And within one group only the **first** matching rule fires (`executeUntilFirstSuccess`
is the default). Independent rules go in separate groups, or the group sets
`"executionStrategy": "executeAll"`.

## 3. A comma list in `actionmatcher` means AND

`"actions": "a,b"` matches only when `a` and `b` both appear, next to each other, in the
step's actions. For "either", emit a shared action and match that, or use a `connector`
condition with `"operator": "OR"` around two `actionmatcher`s.

## 4. Placeholders need the templating step, last

Any `{…}` in an output text or a system prompt is shown to users literally unless the
workflow's **last** step is `eddi://ai.labs.templating` (it has no config URI). Raw braces
in a reply mean this step is missing or not last.

## 5. Reserved actions are not quick-reply expressions

`CONVERSATION_START`, `CONVERSATION_END`, `STOP_CONVERSATION` and `PAUSE_CONVERSATION`
are engine actions. A quick reply needs its own identifier (`get_started`, not
`CONVERSATION_START`). `PAUSE_CONVERSATION` is the human-approval gate — emitting it pauses
the conversation until someone decides (`read_docs("hitl")`).

## Also

- **Secrets** are `${vault:<name>}` references, never literal keys. An unresolvable
  reference stops the call rather than sending the placeholder.
- **Runtime data is never a template.** Do not build a template string out of user input,
  a property value or an API response; pass those as data.
- **v6 URIs**: `eddi://ai.labs.llm/llmstore/llms/<id>?version=<n>`,
  `eddi://ai.labs.rules/rulestore/rulesets/<id>?version=<n>`,
  `eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=<n>`,
  `eddi://ai.labs.output/outputstore/outputsets/<id>?version=<n>`,
  `eddi://ai.labs.property/propertysetterstore/propertysetters/<id>?version=<n>`. Copy
  URIs from `list_agent_resources` rather than composing them.
- **LLM `timeout`** is milliseconds as a number string (`"30000"`), not `"30s"`.

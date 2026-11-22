## 🐛 fix(compat): no false TEMPLATE_DOES_NOT_PARSE for a prompt that mentions a vault reference (2026-11-05)

**Repo:** EDDI (`fix/compat-template-parse-vault-prose`)

### What changed and why

Every Platform Operator deployment logged `[TEMPLATE_DOES_NOT_PARSE] llm task '…'.parameters.systemMessage: … empty
expression found {vault:…}, so every render of it fails`, while the agent answered correctly. The warning was a false
positive: at runtime `LlmTask.runTemplateEngineOnParams` first wraps `{vault:…}`, `{vars:…}`, `{connection:…}` and
`{caller:…}` mentions in Qute raw sections (`escapeConfigReferenceMentions`), so a prompt that only documents the syntax
renders fine. The deploy-time lint parsed the unescaped text.

- [`CompatibilityRules.scanLlmTaskTemplates`](../../src/main/java/ai/labs/eddi/engine/compat/CompatibilityRules.java)
  applies the same escaping before parsing, and only to the task's own `parameters` — the strings the runtime escapes.
  Cascade step parameters, API-call, property and output templates are rendered without it and still warn.
  `AgentCompatibilityLint` uses it for LLM tasks; `LlmTask.escapeConfigReferenceMentions` is now public for this.
- A genuinely broken template next to a mention still warns. No Manager change, so no operator revision bump.

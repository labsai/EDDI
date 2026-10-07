## fix(manager): config editors and the Agent wizard (2026-10-04)

**Repo:** EDDI (`fix/manager-editors-wizard`), `ui/manager`

### What changed and why

Findings from a UX review of the Manager's config editors and the Agent setup wizard.

**LLM editor.** `modelName` (`deploymentName` for Azure OpenAI), `apiKey` and `temperature` were only reachable through a collapsed generic key/value grid. They now have their own "Model" section above the prompt: a model field with the provider's suggestions, the existing vault/secret picker for the key (no connection references, which the backend refuses in model parameters), and a temperature field that validates its input. The model key, credential key and temperature come from a per-provider table checked against the backend builders (`components/editors/llm/model-params.ts`): `model` for Ollama, `modelId` for Bedrock, Hugging Face and Vertex, `deploymentName` for Azure, `modelName` otherwise; the credential is `apiKey`, `accessToken` (Hugging Face) or `authToken` (Jlama, optional), and is not offered for Ollama, Bedrock, Vertex and Oracle GenAI, which authenticate elsewhere. Only the keys the section surfaces for the provider leave the generic grid, and a grid row cannot be renamed onto them. A new task starts with the `send_message` action and the default provider's model instead of no actions and no model; a task with no actions or no model shows a warning. Changing the provider swaps the model only when it is exactly the previous provider's default.

**Shared components.** `ActionTags` existed four times (LLM, rules, API calls, property setter) and none of them kept text typed but not yet added, so an edit was lost on blur and the dirty check never saw it. One component in `components/editors/action-tags.tsx` now commits on Enter, comma, blur and the Add button, splits a pasted comma list, ignores Enter during IME composition, and suggests actions emitted elsewhere in the same config. `components/editors/editor-field.tsx` adds a `Field` that wires `id`/`htmlFor`/`aria-describedby`.

**Rules editor.** New condition configs use `nextFreeKey` (`key${count}` overwrote `key1` after `key0` was deleted). `occurrence` (inputmatcher, actionmatcher), `contextType` and the capability `strategy` are selects; a stored custom value stays selectable. Changing a condition's type asks first when the user entered something beyond the type's presets. Lint warnings: a rule with no `actionmatcher` on `lastStep`, and a comma list in `actionmatcher.actions` (AND semantics). Rules and groups can be moved up and down, since order decides which rule fires first.

**Accessibility.** Labels tied to inputs in the LLM, rules, API-calls, MCP-calls, output and property-setter editors (`htmlFor`/`id` where the label is visible, `aria-label` in compact rows); names on the icon-only remove and expand buttons.

**Output editor.** Invalid JSON in the onPress / fallback field was silently reverted on blur; it now stays in the field with an inline error until fixed or cleared.

**Agent wizard.** There is now one deploy mechanism: the Review step has a deploy target (default **Test**, was Production) and two buttons, "Create without deploying" and "Create & deploy to test/production". The Auto-Deploy toggle on the Features step is gone. Review lists the API key as "None", "Not needed", "Vault reference X" or "Entered here", never the key. The model field is prefilled with the provider's default (also after a provider switch). Step circles have an accessible name and `aria-current`; the API-key label points at the field; the Model step is a `<form autocomplete="off">` so the browser stops warning about a password field outside a form.

**Agents list.** The card footer wraps instead of clipping "Undeploy from production" and wrapping the timestamp at 375px.

### Design decisions

- Deploy is decided by the button, not a switch, because a switch on an earlier step could disagree with the button pressed later.
- A comma list in an actionmatcher is warned about, not blocked: AND is legitimate for an action sequence.
- Labels in dense rows use `aria-label` with the visible text rather than a layout rewrite.

### Deliberately skipped

- The `<label>` elements used as captions over groups of controls in the LLM editor (they label no single control) were left as they are.
- Index-keyed rule and condition components keep their expanded state by position when reordered; only group expansion follows the group.

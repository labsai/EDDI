# Agent Config Authoring — Rules and Pitfalls

This page collects the rules that are easy to get wrong when writing agent JSON by hand — behavior rules, property setters, output sets, HTTP calls, workflows and import ZIPs. Each one has caused a real defect: a config that saved and deployed cleanly and then misbehaved at runtime. The per-extension pages ([Behavior Rules](behavior-rules.md), [Properties](properties.md), [HTTP Calls](httpcalls.md), [Output Configuration](output-configuration.md)) are the full references; this page is the checklist to read before writing one.

> **Contributors:** the repository's `AGENTS.md` (§5) sends AI coding assistants here before they write a config, so keep this page exact — a wrong sentence is repeated in every config an assistant writes.

## Template Syntax

EDDI v6 uses **Qute templates** with `{expression}` syntax, NOT Thymeleaf `[[${expression}]]`.

### ⚠️ `properties` returns RAW values, NOT Property objects

`MemoryItemConverter.convert()` puts `ConversationProperties.toMap()` into the template context. The `toMap()` method returns **raw Java values** (String, Integer, Map, etc.), NOT `Property` objects.

```
✅ CORRECT:   {properties.agentName}        → returns the String value
❌ WRONG:     {properties.agentName.valueString}  → fails at runtime (String has no .valueString)
```

This is because `ConversationProperties.put()` stores `property.getValueString()` (or `getValueObject()`, etc.) directly into the internal `propertiesMap`. By the time templates see it, the Property wrapper is gone.

### Template variables available in all contexts

| Variable | Returns | Example |
| --- | --- | --- |
| `{properties.key}` | Raw value (string, int, map) | `{properties.agentName}` |
| `{memory.current.input}` | User's input text for current step | Used in property setter to capture free-text |
| `{memory.current.output}` | Output text for current step | |
| `{memory.last.input}` | Previous step's input | |
| `{context.key}` | Context variable set by client | `{context.language}` |
| `{snippets.name}` | Prompt snippet content | `{snippets.cautious_mode}` |
| `{vars.key}` | Global variable value | `{vars.default-model}` |
| `{userInfo.userId}` | Authenticated user ID | |
| `{conversationInfo.agentId}` | Current agent ID | |
| `{conversationInfo.conversationId}` | Current conversation ID | |
| `{conversationLog}` | Formatted conversation history | |

## Conversation Lifecycle for Rule-Based Agents

```
1. Conversation.init()
   └─→ Step 0 created
   └─→ CONVERSATION_START action added to step 0
   └─→ Pipeline runs with empty input ("")
   └─→ Output for CONVERSATION_START fires (greeting shown BEFORE user says anything)

2. User sends first message → say(message)
   └─→ Step 1 created (startNextStep)
   └─→ User input stored in memory
   └─→ Behavior rules evaluate:
       • lastStep = Step 0 (has CONVERSATION_START action)
       • currentStep = Step 1 (has user's input/expressions)
   └─→ Output fires for matched actions

3. User sends second message → say(message)
   └─→ Step 2 created
   └─→ lastStep = Step 1, currentStep = Step 2
   └─→ ... and so on
```

> **Key insight**: The greeting output fires automatically at `init()` — the user doesn't need to type anything first.

## Behavior Rule Safety Rules

### Every rule MUST have an `actionmatcher` on `lastStep`

Within a group, rules are evaluated in order and — by default (`executionStrategy: executeUntilFirstSuccess`) — **only the first rule whose conditions match fires**; the rest of the group is skipped. Put independent rules in separate groups, or set `"executionStrategy": "executeAll"` on the group to let every matching rule fire. Either way, rules with only `inputmatcher` conditions are dangerous — they match globally regardless of conversation state.

```
❌ DANGEROUS: Rule fires on ANY step if user somehow sends matching expression
{
  "name" : "Start over",
  "actions" : [ "ask_for_agent_name" ],
  "conditions" : [ {
    "type" : "inputmatcher",
    "configs" : { "expressions" : "start_over", "occurrence" : "currentStep" }
  } ]
}

✅ SAFE: Rule only fires when the confirmation step was the previous step
{
  "name" : "Start over",
  "actions" : [ "ask_for_agent_name" ],
  "conditions" : [ {
    "type" : "actionmatcher",
    "configs" : { "actions" : "confirm_creation", "occurrence" : "lastStep" }
  }, {
    "type" : "inputmatcher",
    "configs" : { "expressions" : "start_over", "occurrence" : "currentStep" }
  } ]
}
```

### Quick reply expressions must be unique identifiers

Do NOT reuse system action names as quick reply expressions. The reserved actions are `CONVERSATION_START`, `CONVERSATION_END`, `STOP_CONVERSATION`, and `PAUSE_CONVERSATION` (the HITL human-approval gate — see [Human-in-the-Loop](hitl.md)). Use dedicated identifiers:

```
❌ WRONG:  "expressions" : "CONVERSATION_START"   (system action name)
✅ RIGHT:  "expressions" : "get_started"           (dedicated identifier)
```

> **HITL**: EDDI has two human-approval gates. (1) A behavior rule that emits `PAUSE_CONVERSATION` gates a **whole turn** — the conversation pauses (`AWAITING_HUMAN`) until a human approves or rejects via `POST /agents/{conversationId}/resume`. (2) `hitlConfig.toolApprovals` gates **individual LLM tool calls** — when the model invokes a tool matching a `requireApproval` pattern (any of the 7 tool sources: `builtin`, `http`, `mcp`, `a2a`, `dynamic`, `memory`, `recall`), the conversation pauses *before* the tool runs (`hitlPauseType: "TOOL_CALL"`, resumed through the same endpoint). Both share the same pause/timeout/audit/Slack machinery; timeout behavior is configured via `hitlConfig` on the agent (with a tool-level override). Full reference: [Human-in-the-Loop](hitl.md).

### `actionmatcher` comma-separated values = AND (contiguous sublist), NOT OR

When you specify `"actions" : "action_a,action_b"`, the engine checks if BOTH actions appear as a **contiguous sublist** of the step's action list (`Collections.indexOfSubList`). This means ALL listed actions must be present — it is AND semantics, not OR.

```
❌ WRONG — tries to match any of these, but actually requires ALL FIVE contiguously:
"actions" : "ask_for_model,ask_for_model_ollama,ask_for_model_jlama,ask_for_model_bedrock,ask_for_model_oracle"

✅ RIGHT — Option A: emit a common action alongside provider-specific ones:
Rule actions: [ "set_provider_ollama", "ask_for_model", "ask_for_model_ollama" ]
Matcher:      "actions" : "ask_for_model"

✅ RIGHT — Option B: use a connector with OR operator:
{
  "type" : "connector",
  "configs" : { "operator" : "OR" },
  "conditions" : [ {
    "type" : "actionmatcher",
    "configs" : { "actions" : "ask_for_model", "occurrence" : "lastStep" }
  }, {
    "type" : "actionmatcher",
    "configs" : { "actions" : "ask_for_model_ollama", "occurrence" : "lastStep" }
  } ]
}
```

## Property Setter Patterns

### Scope values

| Scope | Behavior |
| --- | --- |
| `step` | Cleared at end of turn |
| `conversation` | Lives for the session (default for most agent-building properties) |
| `longTerm` | Persisted to `usermemories` collection across conversations |
| `secret` | Auto-vaulted: plaintext stored in SecretsVault, raw input scrubbed from memory, vault reference (`${vault:...}`) stored as property value |

> **Warning**: `scope: "secret"` requires the vault to be active (`EDDI_VAULT_MASTER_KEY` env var set). If the vault is disabled — which is the shipped default — `autoVaultSecret()` **fails closed**: it scrubs the plaintext from the conversation step, logs an ERROR, and throws a `LifecycleException` naming `EDDI_VAULT_MASTER_KEY`. The whole turn fails; the plaintext is never persisted. (An earlier release persisted the plaintext instead; that behaviour was removed deliberately — see the comment in `PropertySetterTask.autoVaultSecret` and [Properties](properties.md).) For wizard-style agents that collect API keys and pass them to an endpoint (see [Reference Implementation](#reference-implementation)), prefer `scope: "conversation"` and delegate vaulting to the receiving service — a dev instance without a master key cannot complete a secret-scoped turn at all.

### Capturing user input vs. setting fixed values

```json
// Capture free-text input from user
{ "name" : "agentName", "valueString" : "{memory.current.input}", "scope" : "conversation" }

// Set a fixed value (from quick reply selection)
{ "name" : "provider", "valueString" : "anthropic", "scope" : "conversation" }

// When a quick reply has a default value, use a dedicated action + fixed value
{ "name" : "baseUrl", "valueString" : "http://localhost:11434", "scope" : "conversation" }
```

### Qute template safety in HTTP call bodies

When embedding `{properties.x}` in HTTP call body templates, be aware:
- A missing property renders as an **empty string**, in every profile. This takes *two* settings in `application.properties`, and both are deliberate:
  - `quarkus.qute.strict-rendering=false` stops the render from throwing. There is no `%prod` override — dev, test and production must fail identically. (Earlier releases turned strict rendering **on** in prod only, which meant a missing property rendered blank in dev but leaked the raw `{properties.x}` literal to the end user in production.)
  - `quarkus.qute.property-not-found-strategy=NOOP` decides what is written instead. Without it a missing value resolves to Qute's NotFound sentinel and the **literal string `NOT_FOUND`** reaches the output — system prompts, HTTP call bodies and user-visible replies alike ("Your favourite programming language is: NOT_FOUND."). Dev mode defaults to throwing instead, so the two did not even agree. This was a live defect, not a hypothetical. The runtime engine now also enforces the empty rendering itself, so it no longer depends on this setting alone.
- Do NOT use `.orEmpty` on properties — it's for Qute iterables, not strings, and fails on `NOT_FOUND`. If you want an explicit fallback in the template itself, the Qute idiom is the elvis operator: `{properties.x ?: 'unknown'}`
- **Only author-written fields are templates; data never is.** A value substituted into a template (`{memory.current.input}`, a property, an API response) is output literally, braces and all — Qute does not re-parse what an expression resolved to. The same holds for values a property instruction reads through `fromObjectPath` (stored verbatim, never rendered) and for output a `postResponse` builds from a response (rendered once, then marked so the templating task does not render it again). Never introduce a code path that passes runtime data as the template *string* to `ITemplatingEngine.processTemplate`; if EDDI must splice generated text into a template's source, wrap it with `TemplateEscaping.unparsedBlock` first.
- **Runtime templates use a restricted engine** (`RuntimeTemplateEngineFactory`), not the Quarkus-injected one: no `config:`/`inject:`/`cdi:` namespaces, no `{#eval}`/`str:eval`/`{#include}`, reflection limited to reading properties, and per-render caps (`eddi.templating.max-output-chars`, `eddi.templating.max-iterations`). Details in [Security](security.md#runtime-template-engine).

### Calling an API as the signed-in user

An HTTP call **header** may reference the authenticated caller, so the agent
calls the API with that user's credentials instead of a static one:

| Reference | Resolves to |
| --- | --- |
| `${caller:token}` | The caller's raw bearer token |
| `${caller:userId}` | The caller's principal name (not a secret) |

```json
"headers": { "Authorization": "Bearer ${caller:token}" }
```

Use this whenever the agent calls **EDDI's own API**. A static credential there
expires within the hour, cannot be least-privilege, and attributes every action
to one synthetic principal.

Resolution is narrow and fails loudly rather than degrading quietly:
- **Same origin, or EDDI itself** — released only to the exact
  `scheme://host:port` the caller addressed (read from the inbound request, not
  config), or to this deployment's own address (`SelfUrlResolver`:
  `eddi.self.base-url`, else `http://127.0.0.1:${quarkus.http.port}`, or the
  `quarkus.http.host` address when the listener binds one specific non-loopback
  address — deployment
  config only, never agent config or a request; on a random port
  (`quarkus.http.port=0`) with no override it is *unresolved* and only the
  caller's origin qualifies). A config naming a third-party
  host cannot exfiltrate the token. The self address bypasses any reverse proxy,
  so EDDI's own authorization is what guards it; a deployment that also relies on
  proxy path rules sets `eddi.caller-identity.self-release.enabled=false`. A
  caller whose origin could not be captured never gets the self release.
- **Headers only** — `${caller:token}` in a query parameter, request body or
  path is rejected. `${caller:userId}` is allowed in headers and query
  parameters. An MCP server's `apiKey` may also carry it, which sends the tool
  call as the chatting user. The same destination rule applies there — the
  MCP server's URL is the target checked — so a third-party MCP endpoint never
  receives the token (see [MCP Server](mcp-server.md)).
- **Authenticated turns only** — scheduled jobs and triggers cannot satisfy it.
- **Fails closed** — an unsatisfiable reference errors instead of sending
  `"Bearer "` with an empty token.

The token is never persisted: authorization headers are scrubbed before the
request is written to conversation memory. Disable with
`eddi.caller-identity.enabled=false`. Full reference: [HTTP Calls](httpcalls.md).

### Requesting specialized input fields from the UI

The output system supports an `inputField` output type that tells the UI to switch its input control. Both the **Manager** (`SecretInputField` in `ui/manager`'s `chat-panel.tsx`) and the **Chat UI** (`SecretInput.tsx` in `ui/chat`) handle this natively.

```json
{
  "valueAlternatives": [{
    "type": "inputField",
    "subType": "password",
    "placeholder": "Paste your API key here",
    "label": "API Key"
  }]
}
```

Supported `subType` values: `"password"`, `"text"`, `"email"`. When the UI receives an `inputField` in the output array, it replaces the standard text input with the appropriate specialized field for that turn. The field reverts to normal text input on the next response.

> **Key pattern**: Add the `inputField` output item alongside regular `text` outputs in the same action's output set. The text explains what the user should enter, and the `inputField` controls how the input is rendered.

## ZIP Structure for Agent Import

Agent ZIP files are imported via `RestImportService`. **All IDs in URIs and filenames must be valid hex identifiers** (24-char hex strings like MongoDB ObjectIds, or UUIDs). The import service extracts each ID with `RestUtilities.extractResourceId()`, which returns no ID for anything shorter than 18 characters or containing a character other than `0-9a-fA-F` and `-` — and the import then fails further down. Semantic names like `my-agent-wf1` will be rejected.

The file naming convention is `{id}.{type}.json` where `{id}` matches the last path segment of the resource URI:

```
{agentId}.agent.json              → Agent configuration
{agentId}.descriptor.json         → Agent descriptor (name, description, version)
{workflowId}/
  1/
    {workflowId}.workflow.json    → Workflow definition
    {workflowId}.descriptor.json
    {behaviorId}.behavior.json    → Behavior rules (file ext stays "behavior", URI uses "rules")
    {behaviorId}.descriptor.json
    {propertyId}.property.json    → Property setter
    {propertyId}.descriptor.json
    {httpcallsId}.httpcalls.json  → HTTP API calls (file ext stays "httpcalls", URI uses "apicalls")
    {httpcallsId}.descriptor.json
    {outputId}.output.json        → Output messages + quick replies
    {outputId}.descriptor.json
    {llmId}.langchain.json        → LLM configuration (file ext stays "langchain", URI uses "llm")
    {llmId}.descriptor.json
    {dictionaryId}.regulardictionary.json → Regular dictionary (URI uses "dictionary")
    {dictionaryId}.descriptor.json
    {mcpId}.mcpcalls.json         → MCP tool calls
    {mcpId}.descriptor.json
    {ragId}.rag.json              → RAG retrieval configuration
    {ragId}.descriptor.json
snippets/
  {snippetId}.snippet.json        → Prompt snippets (root, agent, or version level)
schedules/
  {scheduleId}.schedule.json      → Agent schedules
connections/
  {connectionId}.connection.json  → Connections the configs reference as ${connection:name} (references only — never resolved secrets, never grants; skipped on import when the name already exists)
```

> The authoritative list of file extensions is `AbstractBackupService`'s `*_EXT` constants —
> thirteen of them. Check against that file rather than against this block if the two ever disagree.

> **Important**: File extensions use legacy names (`behavior`, `httpcalls`, `langchain`) while URIs use v6 names (`rules`, `apicalls`, `llm`). The import service maps between them via `AbstractBackupService` constants.

> **Pipeline step**: If your output templates contain `{properties.x}` placeholders, you **must** include `eddi://ai.labs.templating` as the last workflow step. Without it, Qute expressions are not resolved.

### URI format (v6 canonical)

Always use v6 canonical URIs in new configs:

| Resource | URI Pattern |
| --- | --- |
| Agent | `eddi://ai.labs.agent/agentstore/agents/{id}?version=1` |
| Workflow | `eddi://ai.labs.workflow/workflowstore/workflows/{id}?version=1` |
| Rules | `eddi://ai.labs.rules/rulestore/rulesets/{id}?version=1` |
| ApiCalls | `eddi://ai.labs.apicalls/apicallstore/apicalls/{id}?version=1` |
| Property | `eddi://ai.labs.property/propertysetterstore/propertysetters/{id}?version=1` |
| Output | `eddi://ai.labs.output/outputstore/outputsets/{id}?version=1` |
| LLM | `eddi://ai.labs.llm/llmstore/llms/{id}?version=1` |
| Dictionary | `eddi://ai.labs.dictionary/dictionarystore/dictionaries/{id}?version=1` |

> Legacy URIs (e.g. `ai.labs.bot/botstore/bots/`) are auto-normalized by `AbstractBackupService.normalizeLegacyUris()` during import, but new configs should always use v6 format.

### Workflow step types

| Step `type` | Config URI prefix | Required? |
| --- | --- | --- |
| `eddi://ai.labs.parser` | — (no config URI) | Yes — always first |
| `eddi://ai.labs.behavior` | `eddi://ai.labs.rules/...` | Yes — the orchestrator |
| `eddi://ai.labs.property` | `eddi://ai.labs.property/...` | Optional — slot-filling |
| `eddi://ai.labs.httpcalls` | `eddi://ai.labs.apicalls/...` | Optional — API calls |
| `eddi://ai.labs.output` | `eddi://ai.labs.output/...` | Usually yes — user messages |
| `eddi://ai.labs.llm` | `eddi://ai.labs.llm/...` | Optional — LLM interaction |
| `eddi://ai.labs.mcpcalls` | `eddi://ai.labs.mcpcalls/...` | Optional — MCP tool calls |
| `eddi://ai.labs.rag` | `eddi://ai.labs.rag/...` | Optional — binds a knowledge base; retrieval itself runs inside the LLM task |
| `eddi://ai.labs.templating`| — (no config URI) | Yes when any output or system prompt contains `{…}` placeholders — must be last |

`eddi://ai.labs.rules` and `eddi://ai.labs.apicalls` are also registered as step types (aliases of `behavior` and `httpcalls`). Existing configs use the names in the table; write those.

## Reference Implementation

`docs/agent-configs/rule-based-reference/` is a complete, working, rule-based agent config — a conversational wizard that provisions another agent over EDDI's own REST API. It is a **reference and test fixture only**: nothing ships or deploys it, and agents are created in practice through the Manager's Platform Operator, its agent wizard, or the setup API. Use it as the canonical reference for:

- Behavior rule patterns with `actionmatcher` + `inputmatcher`
- Property setter capturing free-text input via `{memory.current.input}` (it uses `scope: "conversation"` throughout and delegates secret vaulting to the receiving `create_agent` HTTP call — the wizard pattern from [Property Setter Patterns](#property-setter-patterns), deliberately **not** `scope: "secret"`)
- HTTP call template syntax
- Output with quick replies
- Provider-aware branching (local vs. cloud LLM providers). Its provider chooser is a worked example, not a provider catalogue — [LLM Integration](langchain.md) is the source of truth for what EDDI supports, so don't "complete" the fixture to match it.

**For contributors:** two unit tests sweep `docs/agent-configs`, so breaking this config fails the plain unit run — but mind what they actually check. `StrictBoundaryShippedConfigsTest` parses only files whose suffix is in its `BY_SUFFIX` map (descriptors, patches and unmapped names are counted as *skipped*, not passed), and `RuleSetStoreShippedRulesetsTest` validates only documents containing `behaviorGroups`. Neither opens a ZIP. So a green sweep means "the config documents this fixture supplies still parse and still save", not "every file here is valid".

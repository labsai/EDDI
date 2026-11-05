# Tool-Call Guardrails

`toolCallGuardrails` screens the **arguments** of the tool calls a model asks for — the body of a GitHub comment it is about to post, the text of an e-mail, the brief of a handoff — before anyone approves the call and before it runs. It uses the same guardrail types, actions and outcomes as the [input and output guardrails](langchain.md#input-and-output-guardrails), with two extra fields that say which calls and which parts of their arguments an entry applies to.

Available since EDDI 6.7. Absent or empty, nothing runs: the tool loop is exactly what it was without the field.

## Configuration

`toolCallGuardrails` is a list on an LLM task (`langchain.json`, next to `toolApprovals`). Each entry is a guardrail — `type`, `action`, `maxRetries`, `blockMessage`, `parameters` — plus:

| Field | Required | Meaning |
| --- | --- | --- |
| `tools` | yes | The calls the entry applies to, in the [tool-approval pattern language](hitl.md#pattern-language): `mcp:add_issue_comment`, `http.post:/issues/*`, `builtin:*`, a bare tool name. The same matcher as `toolApprovals`, so a pattern means the same thing in both places. |
| `fields` | no | JSON pointers (RFC 6901) into the arguments: `/body`, `/items/0/text`. A segment that is exactly `*` means every element of an array or member of an object (`/items/*/text`). `""` is the whole arguments document as JSON — for `expression` and `json-object`. **Absent: every string in the arguments.** |

**A pointer that matches nothing screens nothing.** A pointer the arguments do not contain selects no field, so the entry has nothing to run on for that call: it does not run, and `failMode` does not apply (it only covers a check that ran and could not decide). A pointer that does not fit a tool's argument shape therefore leaves that call unscreened by the entry. Check each pointer against the tool's argument schema, and for a tool whose shape you are unsure of, leave `fields` out so every string is screened.

Entries run in the order written. For each call, every entry whose `tools` match it screens each of its `fields`; a redaction by one entry is what the next entry sees.

### Example: a DevRel desk that answers GitHub issues

```json
"toolApprovals": { "requireApproval": ["mcp:add_issue_comment"] },
"toolCallGuardrails": [
  { "tools": ["mcp:add_issue_comment", "mcp:create_issue"], "fields": ["/body"],
    "type": "regex-filter", "action": "block",
    "blockMessage": "The reply names a customer, pilot or prospect. Rewrite it without naming any organisation.",
    "parameters": { "patterns": "\\b(acme|globex|initech)\\b\n\\bpilot (customer|partner)s?\\b" } },
  { "tools": ["mcp:add_issue_comment", "mcp:create_issue"], "fields": ["/body"],
    "type": "pii-redaction", "action": "redact",
    "parameters": { "patterns": "email,phone", "failMode": "closed" } },
  { "tools": ["mcp:add_issue_comment"], "fields": ["/body"],
    "type": "judge", "action": "reprompt", "maxRetries": 1,
    "parameters": {
      "provider": "openai", "modelName": "gpt-4.1-mini", "apiKey": "${vault:openai-key}",
      "promptTemplate": "Approved product claims:\n1. ...\n2. ...\n\nA public GitHub comment about to be posted:\n{output}\n\nCheck the comment only against the approved claims.",
      "issues": "{\"UNAPPROVED_CLAIM\": \"Remove every product claim that is not in the approved list.\", \"NAMES_CUSTOMER\": \"Do not name any customer, pilot or prospect.\"}",
      "timeoutMs": "8000", "failMode": "closed" } },
  { "tools": ["mcp:add_issue_comment"], "fields": ["/body"],
    "type": "disclosure", "action": "redact",
    "parameters": { "text": "— AI-assisted reply", "position": "append" } }
]
```

What it does, for a comment the model wants to post:

1. A named prospect is **blocked** deterministically; the model is told why and the call never reaches the approver.
2. E-mail addresses and phone numbers are **redacted** in place; the approver sees the redacted comment.
3. The judge checks the comment against the approved claims. An unapproved claim is sent back to the model once, with the author's fix text (`reprompt`); a second offence blocks the call.
4. The disclosure label is appended — once, however often the comment is screened.
5. Only then does the call reach the human approval gate.

MCP tools are registered under the server's own tool name, without a server prefix — GitHub's official MCP server calls its comment tool `add_issue_comment`, so the pattern is `mcp:add_issue_comment`. A cross-team handoff is a tool call too: once the team handoff tool (`team:handoff_to_team`) is available, an entry with `"tools": ["team:handoff_to_team"]` and `"fields": ["/brief"]` keeps untrusted issue text — an injection attempt, a customer's e-mail address — from crossing teams in a brief.

**Be honest about what is deterministic.** "Never name a prospect" is a denylist, and a `regex-filter` enforces it. "Make no claim outside the approved list" is an allowlist of meaning, which no pattern can check: only a model can, and a model can be talked into the wrong answer — including by text the reply quotes from an issue. The judge narrows its answer to the issue codes you declare, but it can still be persuaded to report none. Put the hard guarantees on the deterministic types and on the human approval gate; treat the judge as defence in depth.

## Types and actions

Every built-in type runs on tool-call arguments: `regex-filter`, `pii-redaction`, `max-length`, `prompt-injection` (catches an injection payload echoed into a post), `judge`, `classifier`, `expression`, `json-object`, and `disclosure`. In the judge's and the classifier's `promptTemplate`, `{output}` is the screened field and `{input}` the user's last message; put the tool's name in the prompt text yourself.

| Action | On the live path |
| --- | --- |
| `block` (default) | The call does not run. The model gets a `NOT_EXECUTED` result with the block message. |
| `redact` | The field is rewritten in place with the guardrail's rewrite (a type without one substitutes the block message). A rewrite that cannot be applied — its target is a number or an object — blocks the call instead. |
| `reprompt` | The call does not run. The model gets a `NOT_EXECUTED` result carrying your fix text (`repromptMessage`, else the judge's issue fix texts) and `retryAllowed: true`. At most `maxRetries` (default 2, at most 5) per entry per turn — a HITL pause does not reset the count — then the call is blocked. `onExhausted: deliver-best` is refused: there is no best call to deliver. |
| `warn` | Recorded only. |

A guardrail that cannot decide — its model is unreachable, its spend is refused, its patterns ran out of time — follows `failMode` as everywhere else: by default `block` and `reprompt` fail **closed** (the call is refused), `redact` and `warn` fail open. For `redact` on a tool that publishes, set `"failMode": "closed"`: an unreachable PII check should not let an e-mail address into a public comment. Arguments that are not JSON, or that are too large to screen, are "could not decide" too.

### `disclosure`

Adds a fixed label to a text that does not carry it yet. Parameters: `text` (required, at most 500 characters), `position` (`append`, the default, or `prepend`), `separator` (default a blank line). It only works with `redact`, which is enforced when the configuration is saved. A text that already ends (or, for `prepend`, starts) with the label, ignoring surrounding whitespace, is left alone, so the label is added once however often the text is screened — through a reprompt, a resume, a cascade step. It runs on output too, to label a chat answer.

## Where it runs

```
model response
 └ call caps (maxToolCallsPerIteration, maxToolCallsPerTurn)
 └ toolCallGuardrails over the admitted calls
      block / reprompt → NOT_EXECUTED result; the call leaves the batch
      redact / disclosure → the arguments are rewritten in the model's own message
 └ tool-approval gate → quota pre-check → credential-reference refusal
 └ pause (the approval card shows the redacted arguments)  or  execute

approved call, on resume
 └ request pinning, self-conversation and self-ungating checks
 └ toolCallGuardrails again, block-only           (before the journal claim)
 └ journal claim → execute
```

**Before the gate**, because [a call that is certain to be refused is never put in front of a human](hitl.md#request-pinning--approval-binds-to-a-request-not-a-tool-name). A redaction rewrites the call in the model's own message, so the transcript, the trace, the pending approval, the exchange a cascade hands on and the tool all see the same request — and a blocked call whose guardrail found something to rewrite keeps the rewritten arguments in the transcript, so what was found does not stay in conversation memory.

**After approval, block-only.** Every approval surface — the REST resume endpoint, Slack, the MCP `resume_conversation` tool — executes through one path, and there an approved call is screened again before it is claimed and run:

- Nothing is rewritten after approval. Approval binds to the request that runs; rewriting an approved text would send something nobody approved. So any `block`, `reprompt` or `redact` finding, and any check that cannot decide and fails closed, **refuses** the call with `NOT_EXECUTED`; a `warn` is recorded.
- A call the approver **edited** is new text and gets every entry. A call approved as it was already passed the live screen, so it is re-checked only with the cheap, deterministic types — a judge or classifier would add cost and could flip at random.
- A `disclosure` entry on an edited call passes when the label was kept and refuses the call when the edit dropped it (reason `disclosure_added`, read "label missing").
- The refusal comes before the journal claim, so it consumes nothing and the call can be approved again. A call the journal already ran (a replay) or lost mid-tool is never executed again, so it is not screened.

Streaming needs nothing: tool-call arguments are never streamed to a client. Cascade steps, group-member turns and agent test runs all use the same tool loop.

## What a refused call returns

```json
{"status":"NOT_EXECUTED","reason":"tool_call_guardrail","guardrail":"judge","field":"/body",
 "issues":["UNAPPROVED_CLAIM"],"message":"Remove every product claim that is not in the approved list.","retryAllowed":true}
```

Only configuration and EDDI vocabulary: your block message or fix text, the issue codes you declared, the configured field pointer (`*` when the entry screens every string). Reasons and issue codes are filtered to a short token shape (lower-case letters, digits and `_:,.-`, at most 120 characters), which keeps free text and long excerpts out. That is a shape check, not a vocabulary: a custom guardrail that returned the screened value itself as its reason, in token shape, would be recorded. The built-in types never do. **The built-in types never echo the screened arguments** — not to the model, the audit ledger, the event stream or the log — and a custom guardrail type is responsible for the same.

## Limits and cost

- At most 256 fields and 256 KiB of text are screened per call and entry; past that the call is "could not decide".
- At most 20 model-backed checks (judge, classifier) per tool-loop run; past that, "could not decide" (`tool_guardrail_model_cap`). `expression` and the other deterministic types make no model call and do not count. After a human approves, the limit applies to each approved call that was amended (only an amended call gets the model-backed types again), so a batch with two edited calls can make up to 40 checks in total.
- A model-backed check is one model call per call, entry and field — and without `fields`, per string in the arguments, which a deploy-time warning points out. Name the field.
- Every model-backed check is admitted against the conversation's cost ceiling and the tenant budget first, with the run's unrecorded spend counted; a refusal is "could not decide". Its cost is charged to the conversation as a priced tool call (`toolCallGuardrail`), so it is part of the run's tool cost, the cost ceiling between rounds, `maxBudgetPerConversation` and the turn's audit cost. It is priced like the judge or classifier everywhere else, which means **you must set `inputPricePer1M` and `outputPricePer1M` in the entry's `parameters`**: without them its checks add nothing to the tracked cost or the ceilings (the deploy-time warning says so), and so does a deployment that has cost tracking turned off. The example above leaves them out for brevity; add them for a real agent.

## Validation

When the configuration is **saved** (HTTP 400): at most 32 entries; `tools` non-empty, at most 32 valid patterns; at most 16 `fields`, each a valid pointer; `action` exactly one of `block`, `redact`, `reprompt`, `warn` (a typo in a hard block is an error, not a silent block); `disclosure` only with `redact`; `maxRetries` 0–5; `onExhausted` absent or `block`; `blockMessage` at most 1,000 characters.

When the agent is **deployed**: each entry's parameters are checked by its type, and an unknown type, or one that does not run on tool-call arguments, refuses the deploy. (Input and output guardrails skip an unknown type with a warning instead, so a configuration written for a newer EDDI still loads; this field is new, and a skipped hard block is a disabled one.)

An agent cannot remove its own guardrails: an approved call that writes an LLM configuration carrying `toolCallGuardrails` is refused, exactly like one carrying `toolApprovals` ([self-ungating](hitl.md#request-pinning--approval-binds-to-a-request-not-a-tool-name)). Editing them is a human's job, in the Manager.

## Observability

Every finding is recorded like any other guardrail outcome, with phase `tool_call`:

- an audit record under `audit:guardrail:tool_call:<type>`, with `tool`, `field` and `stage` (`live` or `resume`) next to the usual `phase`, `type`, `action`, `reason`;
- a `guardrail_triggered` event on the conversation's event stream;
- `eddi_guardrail_triggered_total{phase="tool_call"}`;
- a `tool_call_guardrail` step in the tool trace.

Per screened call, `eddi_guardrail_toolcall_decisions_total{stage, outcome}` counts `allowed`, `rewritten`, `blocked`, `reprompted` and `undecided` — `stage="live"` counts every screened call before the approval gate, gated or not, and `stage="resume"` counts approved calls as they are re-screened, edited or not (an unchanged one with the deterministic types only). So `live/blocked` also includes calls that would never have needed approval, and `resume/blocked` can include an approved call whose arguments were not changed. The labels do not tell those cases apart. No tool tag: tool names are unbounded.

## Tool results

`toolCallGuardrails` checks what goes **into** a tool. What comes **out** is governed by [`toolResultGuardrails`](mcp-client.md), on by default for every source: a provenance envelope that tells the model the result is data, not instructions, and redaction of directive-shaped text. Configurable checks on results (regex, PII) are a planned follow-up, in this shape: `toolResultGuardrails.rules: [{tools, type, action, parameters}]`, deterministic types only (`prompt-injection`, `regex-filter`, `pii-redaction`, `max-length`) and actions `redact`, `block`, `warn` — no judge or classifier on bulk third-party text, which would both cost a model call per result and make the judge itself an injection target.

## Compatibility

An EDDI older than 6.7 ignores the field and runs the tools unscreened. Agent-level and group-level `toolCallGuardrails` (inherited by every task, merged strictly like `toolApprovals`) do not exist yet; the list is per task.

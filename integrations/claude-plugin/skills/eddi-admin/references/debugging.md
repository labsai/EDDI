# Debugging an agent

Work from what the user saw towards the configuration that caused it. Do not change
anything until you can point at the cause.

## The path

1. **Find the conversation.** Ask for its id, or `list_conversations` with the `agentId`
   (filter by `conversationState`, e.g. `ERROR`).
2. **What was said** — `read_conversation_log`. Reproduce the user's complaint in one
   sentence: which turn, what was expected, what happened.
3. **What the engine did** — `read_audit_trail` for the conversation. Per pipeline task:
   the actions emitted, the LLM request (system prompt as rendered, model, tool calls and
   their arguments, tokens, cost) and timing. This answers most "why did it say that"
   questions: the prompt the model actually saw, which rule fired, which tool returned
   what.
4. **What went wrong underneath** — `read_agent_logs` with the `conversationId` (provider
   errors, timeouts, refused secrets — none of which reach conversation memory). Without a
   `conversationId` it reads the shared server log, which needs `eddi-admin`.
5. **The configuration** — `list_agent_resources`, then `read_resource` for the step the
   audit trail points at.
6. **Fix** through [change-workflow.md](change-workflow.md), then replay the same input
   in `test`.

`read_audit_trail` and conversation-scoped `read_agent_logs` need `eddi-admin` or
`eddi-viewer`.

## Symptom → cause

| Symptom | Likely cause | Check / fix |
| --- | --- | --- |
| Reply shows `{properties.x}` or other raw braces | No templating step, or not last | `read_workflow`; `eddi://ai.labs.templating` must be the last step — adding it is a workflow edit, done in the Manager or over REST |
| A rule fires on the wrong turn, or on every turn | Rule has only an `inputmatcher` | Add an `actionmatcher` on `lastStep` ([config-gotchas.md](config-gotchas.md)) |
| A rule never fires | Comma list in `actionmatcher` (AND), or an earlier rule in the same group matched first | Audit trail's emitted actions; split groups or use an `OR` connector |
| Template error mentioning `valueString` | `{properties.x.valueString}` | Use `{properties.x}` |
| Conversation in `ERROR` | The LLM task failed unrecoverably: rejected key, unknown model, a non-retriable 4xx, retries exhausted | `read_agent_logs` carries the provider's own error; fix the `langchain` resource. The next message works again |
| `EXECUTION_INTERRUPTED` after about 60 s | The whole turn exceeded the server's agent timeout | Faster model or lower provider `timeout`; the server setting is an operator change. The next message recovers |
| Model got "Execution timed out … for tool" or "Rate limit exceeded for tool" | Tool timeout or rate limit | `defaultToolTimeoutMs` / `toolTimeoutsMs` on the LLM task |
| Error naming an unresolved `${vault:…}` reference | Secret missing, vault off, or not granted | An admin stores the secret; the agent references its exact name |
| "Agent is not deployed or not ready" | No `READY` version in that environment — often deployed only to `test` | `get_deployment_status` for the version and environment |
| Messages refused: awaiting human approval | A `PAUSE_CONVERSATION` rule or a tool-call approval paused it | `get_approval_status`; a person decides (`eddi` skill) |
| `undeploy_agent` refused | The version still has open conversations | Deploy a compatible newer version first; see [change-workflow.md](change-workflow.md) |
| `FORBIDDEN` / "requires one of roles" | Caller's role lacks the tool's tier | Grant the role; there is no role hierarchy, each role is checked literally |
| Model cascade "all steps exhausted" | Every configured model failed | The message lists each step's error |
| Group member skipped: "Agent not deployed" | Member agent not deployed in the group's environment | Deploy it |

For anything not in this table: `read_docs("troubleshooting")`, which is organised by
error message.

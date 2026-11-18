## fix(llm,mcp): guardrail types, cascade credentials, MCP errors, audit and bounds (2026-11-18)

**Repo:** EDDI (`fix/llm-guardrails-cascade-mcp-errors`)

Findings O1, O3, O4, O7 (LLM providers, cascade, guardrails) and N1, N2, N3, N5 (MCP server) of the November 2026 code review.

### Upgrade notes

- **An unknown or mistyped input/output guardrail type now stops a deploy.** A stored LLM config written by an older version
  that carries such a type (for example `pii-redacton`) fails to deploy with a message naming the task, the list, the index,
  the type and the known types, and can no longer be saved. This is the intended fail-closed behaviour: until now the entry
  was skipped with one WARN and the protection silently did not exist.

### O4 — unknown guardrail type is a deploy error

`GuardrailService.unknownTypeProblems(listName, configs)` lists the entries of an input/output list whose type is missing or
not registered. `LlmTask.validateGuardrails` (deploy) and `LlmStore.create/update` (save, via the new `GuardrailService`
constructor argument; the two-argument constructor keeps working and skips the check) refuse them. The run-time WARN-and-skip
stays as a last line. Tool-call guardrails already rejected unknown types.

**Files:** [`GuardrailService.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/GuardrailService.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`LlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java),
[`langchain.md`](../langchain.md), [`security.md`](../security.md), [`tool-call-guardrails.md`](../tool-call-guardrails.md)

# Group Convergence Judge: SERVICE

A repeating group phase can stop early once its members stop moving (see
[convergence in Group Conversations](group-conversations.md#per-phase-controls)). Something has to decide that, and
`convergence.judge` picks what:

| `judge` | What runs | Needs a moderator | Cost |
| --- | --- | --- | --- |
| `MODERATOR` (default) | One full turn of the group's moderator agent, in its own private conversation | Yes — without one the check is skipped | The moderator's model and prompt, through its whole pipeline |
| `SERVICE` with a `service` model | **One direct model call**, no agent, no pipeline | No | Whatever the named model costs for one short call |
| `SERVICE` without a `service` model | Falls back to `MODERATOR` and logs a warning | Yes | As `MODERATOR` |

Use `SERVICE` when the moderator runs on an expensive model, or when the group has no moderator at all and still
should stop repeating once it has converged.

## Configuration

```json
{
  "name": "Estimates",
  "type": "OPINION",
  "repeats": 4,
  "convergence": {
    "enabled": true,
    "minRepeats": 2,
    "threshold": 0.8,
    "judge": "SERVICE",
    "service": {
      "llmProvider": "openai",
      "llmModel": "gpt-4o-mini",
      "parameters": { "apiKey": "${vault:openai-key}" },
      "inputPricePer1M": 0.15,
      "outputPricePer1M": 0.60,
      "timeoutMs": 20000
    }
  }
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `llmProvider` | — | Provider type, the same names an LLM task uses (`openai`, `anthropic`, `ollama`, ...). Required together with `llmModel` |
| `llmModel` | — | The model to ask. Written under the provider's own model key, so `ollama`, `bedrock` and `azure-openai` work as well |
| `parameters` | `{}` | Extra model-builder parameters: `apiKey`, `baseUrl`, `temperature`, ... |
| `inputPricePer1M` / `outputPricePer1M` | unpriced | USD per million tokens. Absent or negative means the call books $0, the same rule as `contextWindow` and `stanceSummary` |
| `timeoutMs` | `20000` | Per-call timeout. Capped at `60000`; a `timeout` in `parameters` is overridden |

Both modes work: `SIMILARITY` (the default) compares this repeat with the previous one against `threshold`,
`VERDICT` reviews the latest repeat against the question. The judge receives exactly the prompt the moderator
judge would have received.

The Manager's phase editor shows these fields when **Judge** is set to **Service**.

## Credentials

The judge has no LLM task to inherit a key from, so it carries its own — and a group configuration must not
become a plaintext key store. At save time:

- a credential-named parameter (`apiKey`, `secretKey`, `accessKey`, `token`, any name ending in `key`, and
  everything the shared config-secret classifier recognises) must hold a `${vault:...}` or `${vars:...}`
  reference. A literal is refused with a 400 that names the field, never the value;
- a `${connection:...}` reference is refused, because model parameters do not accept connection references at
  call time either;
- a `service` block that names only one of `llmProvider` and `llmModel` is refused;
- every `${vault:...}` reference in `service.parameters` must name a secret that **exists** and whose grant is
  **open to every agent** (`allowedAgents` contains `"*"`; a legacy secret with no grant list reads the same way).
  A group is not an agent, so there is no identity to check a narrower grant against; without this rule an editor
  could aim a judge at someone else's key and an endpoint they chose. The 400 names the parameter and says to grant
  the secret to all agents. Only the secret's metadata is read, never its value. `${vars:...}` references are not
  followed: only an admin may store a global variable that resolves to a secret.

The grant is checked again just before the judge resolves its parameters, so narrowing it after the group was saved
makes that judge fail (`outcome=error`, the phase carries on as "not converged") instead of using the secret; the
discussion is not failed.

At call time the model registry resolves `${vars:...}` and `${vault:...}` and applies the vault host allow-list,
exactly as for an LLM task. See [Secrets Vault](secrets-vault.md).

## Spend, budgets and failures

- Every check is **one** call. It counts against `protocol.maxTurns` and is skipped once the discussion has
  reached its cost ceiling, the same two gates the moderator judge has.
- The cost is booked on the discussion's ledger under `system:judge:<round>:<phaseIndex>:<repeat>` and announced with a
  `cost_updated` event (display name `Convergence Judge`), so it shows in `memberCosts` and `totalCost` next to
  the members' own spend. A reply that cannot be read was still paid for and is still booked.
- The judge **never ends a phase on doubt**. A provider error, a timeout or a reply that is not the expected
  JSON is "not converged", and the phase runs its next repeat. The provider's error text is not copied into the
  transcript; the log names only the exception class.
- The contributions are sent as the call's user message, as data. The system message is a fixed instruction
  that tells the model to treat member text as material, not as instructions.

## Observability

Timer `eddi.group.convergence.judge.duration`, tagged `judge=service`, `mode` (`SIMILARITY` or `VERDICT`) and
`outcome`: `converged`, `continue`, `unparseable`, `error`, or `fallback_moderator` when `judge: SERVICE` has no
model and the moderator ran instead. The `convergence_checked` and `convergence_reached` events and the
`CONVERGENCE` transcript entry are the same for both judges.

## Compatibility

A config saved before the `service` block existed behaves exactly as before: `judge: SERVICE` without a model
runs the moderator agent, and saving such a config again logs a warning instead of failing. An unknown `judge`
value runs as `MODERATOR` and is warned about at save time.

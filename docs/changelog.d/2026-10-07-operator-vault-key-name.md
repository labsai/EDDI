## 🐛 fix(operator): a vault key goes in by name, and a call that cannot succeed is never put up for approval (2026-10-07)

**Repo:** EDDI (`fix/operator-vault-key-name`)

### What happened

An admin asked the Platform Operator for an agent on "the anthropic key available". The operator copied `${vault:anthropic-api-key}` out of its own LLM config into the `setupAgent` body and was refused; told the admin that typing the reference themselves would make it acceptable (false); proposed the call again, which the admin approved, and only then was it refused again; and finally sent the admin to build the agent by hand.

### Why

`ConfigReferenceGuard` refuses any `${vault|eddivault|connection|caller:…}` in a rendered request value that the configuration did not itself write. That is the right control and is unchanged — it is about where a reference came from, not who typed it, so no amount of retyping can satisfy it. Two things around it were wrong:

- **The operator was taught to write references.** Its prompt said to pass `${vault:key-name}` in `apiKey`. The supported path was already there: `vaultKeyName` takes a bare name, leaves `apiKey` empty, requires the entry to exist, and still goes through the deploy-time grant check (`createApiAgent` already carries the same field).
- **The approval was spent before the refusal.** Gate time resolves each gated http call to pin it, which runs the same request build as execution, guard included. But pinning is never allowed to fail a pause, so the guard's refusal was swallowed as "approved unpinned" and the approver was shown a card for a call that was refused the moment they approved it.

### What changed

- **Gate-time refusal.** `ConfigReferenceGuard` now throws its own `ConfigReferenceRefusedException` (still an `IllegalArgumentException`). `ApiCallExecutor.resolve` lets it through unwrapped for a call whose resolution is what execution builds, and `ToolLoopRunner` drops such a gated call from the pending batch before the pause, returning the guard's message to the model as that call's tool result (trace `credential_reference_refused`). Other gated and ungated calls in the batch are unaffected. Any other resolve failure, and a call that can diverge between resolve and execute (pre-request property instructions, batching, retries), still pauses and is approved unpinned.
- **Clearer message.** The refusal now says retrying with the same text cannot succeed whoever typed it, and what to do instead, generically: send the credential's name in a field the endpoint documents (for example `vaultKeyName`), or edit with a partial update that does not repeat it. The guard still knows no endpoint.
- **Operator prompt** (`system-prompt.ts`, revision bumped): a "Credentials in tool-call bodies" section — never put a `${vault:…}`/`${connection:…}`/`${caller:…}` reference in any tool-call body whoever typed it; use `vaultKeyName` with a bare name and an empty `apiKey`; it cannot read the vault so it never claims a key exists; the new agent must be granted the secret (Secrets page, `allowedAgents`) or its deploy is refused and the operator cannot grant — create with `deploy: false` first; on a refusal explain the rule and retry with the name rather than sending the admin to do it by hand. The sentences that taught the opposite in the setup, cheatsheet and "making changes" sections are amended, not duplicated.
- **No approval-card flag.** A body carrying a credential reference can no longer reach the card for any call the operator's generated tools produce (they are always pinnable), so an `escalation-flags.ts` warning would be dead on that surface and would cost translations in every locale.

### Docs

[`secrets-vault.md`](../secrets-vault.md#where-vault-references-work) (the provenance rule is about origin, not author; the `vaultKeyName` path and its grant consequence) and [`hitl.md`](../hitl.md#request-pinning--approval-binds-to-a-request-not-a-tool-name) (gate-time refusal).

**Files:** [`ConfigReferenceGuard.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ConfigReferenceGuard.java), [`ConfigReferenceRefusedException.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ConfigReferenceRefusedException.java), [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts), [`operator-revision.json`](../../ui/manager/src/lib/operator/operator-revision.json)

```decision-log
| 2026-10-07 | A gated call whose request the credential-reference guard refuses is refused before the pause, not approved unpinned | An admin approved a setupAgent call that was refused the instant they approved it | Adding an "allowed if unchanged from the stored document" exemption to the guard; weakening the guard; an approval-card warning flag (dead on every call the operator can make) |
```

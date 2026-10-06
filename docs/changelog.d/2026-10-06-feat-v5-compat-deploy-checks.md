## fix+feat(migration): carry 5.x LLM defaults over, unencoded URL hosts, deploy-time compatibility check (2026-10-06)

**Repo:** EDDI (`feat/v5-compat-deploy-checks`)

### What changed and why

Findings from a production 5.x to 6.x migration: valid 5.x configuration that 6.x accepts, deploys
READY, and then handles differently on the first real turn.

- **JSON mode (#7).** A migrated LLM task with `parameters.responseFormat: "json"` and no
  `convertToObject` gets `convertToObject: "true"`; `responseFormat` stays. Without it the model
  wrapped its JSON in a Markdown code fence.
- **Tools default (#8).** A migrated LLM task without `enableHttpCallTools` / `enableMcpCallTools`
  gets both written as `false`, which is what 5.x did (6.x defaults them to `true`, turning a chat task
  into a tool-calling one, which on Gemini also silently drops JSON mode). One transform,
  `LegacyDocumentMigrations.llm()`, runs in the V6 collection sweep (`llms` and `llms.history`, a
  database 5.x wrote) and in the import of a ZIP whose workflow still references the 5.x LLM
  authority. Tasks authored in 6.x are never touched. Idempotent.
- **Templated URL host (#9).** `https://{properties.apiHost}` was rendered as `api%2Eexample%2Ecom`
  because every substituted value was percent-encoded as a path segment. The authority is now
  rendered separately and keeps letters, digits, `.`, `-`, `_`, `:`; `/ @ ? #`, spaces and `%` are
  still encoded, so a value cannot add a path, userinfo or query to the host. The resolved URL is
  unchanged for the SSRF checks (private/loopback refused with
  `eddi.security.ssrf-protection.enabled`, cloud metadata always).
- **Deploy-time compatibility check (#6, #11).** `AgentCompatibilityLint` runs when an agent is
  deployed, reads its LLM, API-call, property-setter and output configs, and reports: a template in an
  LLM `apiKey` (task, cascade steps, judge model; vault/vars/connection references are fine),
  `responseFormat: json` without `convertToObject`, `convertToObject` with tools on a provider that
  cannot combine them (from `JsonResponseFormatPolicy`), strings that do not parse with the restricted
  runtime Qute engine, and left-over `[[${...}]]` Thymeleaf. Warnings only: one WARN line per
  finding, and a new additive `warnings` list on each entry of the deployment-status listing
  (`AgentDeploymentStatus`, `IAgent.getDeploymentWarnings()`). It never changes the status or blocks a
  deployment, and a failing lint is swallowed.
- `docs/upgrading-from-5x.md` section 6.1: every behaviour change with before/after config.

### Design decisions

- The 5.x origin of an LLM document is known, not guessed: the V6 sweep runs once over a database 5.x
  wrote, and an import counts as 5.x only when the workflow names its LLM step by the 5.x authority.
  A v6 document with an absent flag keeps meaning "on".
- The lint does not report a templated URL host (#9, now supported) or `fromObjectPath` naming
  `responseObjectName` (#5, handled in the engine).
- `LlmTask.TEMPLATE_SKIP_PARAMS` and `CONFIG_REF_MENTION` became public so the lint shares the
  engine's own definition of "credential" and "reference".
- A templated host is a trust decision: whoever controls the substituted value chooses where the
  call goes. Documented; the metadata address stays refused regardless.

### Follow-ups

- `GET /administration/preflight` for an unmigrated 5.x database (out of scope here).
- Showing `warnings` in the Manager's deployment view.
- The Postgres backend has no 5.x data, so the sweep has no Postgres twin; only ZIP import applies.

**Files:** [`LegacyDocumentMigrations.java`](../../src/main/java/ai/labs/eddi/configs/migration/LegacyDocumentMigrations.java),
[`V6RenameMigration.java`](../../src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`AgentCompatibilityLint.java`](../../src/main/java/ai/labs/eddi/engine/compat/AgentCompatibilityLint.java),
[`CompatibilityRules.java`](../../src/main/java/ai/labs/eddi/engine/compat/CompatibilityRules.java),
[`upgrading-from-5x.md`](../upgrading-from-5x.md)

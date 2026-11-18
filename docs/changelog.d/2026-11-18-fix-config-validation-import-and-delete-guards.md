## fix(configs): save-time validation, in-use delete guards, fail-closed import, whole-or-nothing export, deployment failure reason (2026-11-18)

**Repo:** EDDI (`fix/config-validation-import-and-delete-guards`)

### What changed and why

Findings E4-E9 and F5 of the November 2026 code review (live: L5, L11, I1, I2, OI4). One commit per block.

**Import must not produce a hollow agent silently (E6, E7, E8; I1, I2).** A `strategy=create` import (and the first promotion of `…/sync`) whose workflow names a configuration the archive does not contain used to drop the step, log a WARN and answer 201 — an agent that deploys `READY` and cannot answer. It is now refused with `422` listing every missing reference, nothing written; `allowIncomplete=true` imports it anyway, and the 201 then carries `X-Import-Warnings` and a JSON body (`resourceUri`, `warnings`, `dropped`). Everything an import writes goes through the stores' save-time checks in warning mode, so an LLM config naming an unknown provider or a `${vault:…}` secret this instance lacks (new check, `ImportVaultReferences`) is reported in the same warnings (and in `UpgradeResult.warnings` for upgrade and sync) instead of surfacing on the first turn. Two same-name snippet files in one archive no longer create two snippets, and the ingestion schedules a created RAG config gets are removed again when the import rolls back. Files: `RestImportService`, `IRestImportService`, `UpgradeExecutor`, `ImportVaultReferences`.

**Save-time validation (E4; L11).** New `LlmConfigSaveValidator` and `WorkflowConfigSaveValidator`, one line each in `LlmStore` / `WorkflowStore`: LLM tasks, cascade steps and the judge need a known provider type, a model parameter must not be blank, numeric parameters must lie in the range `model-parameters.json` declares (no range invented here), retry settings must be able to work, plus the existing cascade and self-hosted endpoint checks; workflow steps need a known `eddi://ai.labs.*` step type and, for the steps whose task cannot configure without one, a well-formed `config.uri`. A missing referenced resource is not checked (it may be created next); duplicate steps are legal (the engine keys components by position). The agent's workflow list was already validated. `ConfigValidationPolicy` decides what a finding does: interactive saves throw (400 naming the field); import, sync, upgrade and duplicate run in a lenient scope and only warn.

**In-use guards on delete (E5; L5, OI4).** `ResourceInUseGuard`, called once from `RestVersionInfo.delete`: deleting a workflow an agent lists, or an LLM/behavior/httpcalls/mcpcalls/output/property/dictionary/rag/parser config a workflow uses, answers `409` (`code: RESOURCE_IN_USE`, up to 10 users named) unless `?force=true`; `permanent=true` checks every version. Deleting a deployed agent with open conversations answers `409` (`code: AGENT_HAS_OPEN_CONVERSATIONS`) unless `endAllActiveConversations=true` (the undeploy parameter), which ends them first. Agent cascade deletes run unguarded. MCP `delete_resource` gained `force`, `delete_agent` gained `endAllActiveConversations`.

**Export is whole-or-nothing (E9).** An export that cannot serialize or write a configuration, the snippets or the schedules now fails with `500 {"error": "Export failed: the archive could not include …"}` instead of producing an archive that looks complete. `docs/agent-sync-guide.md` documents what an export contains and what it deliberately omits (E10: global variables, channel integrations, group configuration and membership, test suites).

**Deployment failure reason (F5), part.** A failed deployment now carries a reason code from a closed set (`VAULT_GRANT_MISSING`, `STEP_TYPE_UNKNOWN`, `CONFIG_INVALID`, `REFERENCE_MISSING`, `DEPLOYMENT_FAILED`) with fixed text, returned by default as `failure: {code, message}` in the deployment status to everyone who may use the agent (secret names and the grant fix stay behind `format=detailed` for editors), and counted in `eddi.deployment.failed{reason}`. `DeploymentFailures`, `AgentFactory`, `RestAgentAdministration`.

### Design decisions

- One thread-local policy (`ConfigValidationPolicy`) rather than a flag on every store method: the stores stay one call site, and every path that reaches them from an import (create, merge, upgrade, sync, duplicate) is lenient without being touched.
- The in-use guard sits in `RestVersionInfo.delete` (keyed by the store's resource URI) instead of in nine REST beans: one hunk, no signature changes; `force` and `endAllActiveConversations` are read from the request's query string. Rejected: adding a `force` parameter to every `IRest*Store.delete*`.
- The 409 lookup fails open (a failing reverse lookup logs and deletes as before) because it must never lock a resource in place.
- The failure reason is not persisted in the deployment record: that needs a PostgreSQL column and touches `PostgresDeploymentStorage` and the undeploy ordering that open PRs #1398 and #1416 change; the startup redeploy recomputes it anyway.

### Behaviour changes an upgrader will notice

- `strategy=create` imports of archives that name missing configurations now answer 422 (add `allowIncomplete=true` for the old behaviour).
- Saving an LLM config with an unknown provider or an out-of-range catalog parameter, or a workflow with an unknown step type, is now a 400.
- Deleting a used workflow/config, or a deployed agent with open conversations, is now a 409.
- A failed export is a 500 instead of a smaller archive.

### What is left

Persisting the deployment failure reason (both backends); `executeSyncBatch` has no `allowIncomplete`; dictionary/httpcalls/mcpcalls REST validators are not yet shared with the import create path; Manager "used by" confirmation (follow-up branch).

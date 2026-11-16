## 🐛 fix(backup): an upgrade or sync refuses invalid adopted agent settings before writing (2026-11-12)

**Repo:** EDDI (`fix/upgrade-validate-before-write`, based on `feat/agent-health-h3`)

### What changed and why

Review finding on PR #1389. A `create` or `merge` ZIP import already validated the agent before any write. An
`upgrade` import or a sync did not. It can adopt the source agent's settings, but those settings were checked only
inside `agentStore.updateAgent`, the last write of the run. The snippets, extensions and workflows had already
been written by then. The refusal (`BadRequestException` or `IllegalArgumentException`) was wrapped into
`RuntimeException("Upgrade failed: … Agent config update failed: …")`, so the caller got a 500, and nothing that
had been written was rolled back.

- **One list of the agent-settings checks.** `RestAgentStore.validateSettings` runs every check the agent update
  applies to an agent's own settings: the security flags (`signInterAgentMessages` / `requirePeerVerification` need
  a signing key), `RatingConfigValidator`, `HealthConfigValidator`, and `AgentStore.validateSettings`. That last
  method is new and holds the HITL config, the Dream settings and the continuation bounds, which `AgentStore.create`
  and `update` now call too. An `IllegalArgumentException` from it becomes the same 400 the
  `IllegalArgumentExceptionMapper` gives a save. The workflow-list checks are left out, because the upgrade replaces
  the list with the target's own.
- **`UpgradeExecutor.executeUpgrade` checks the adopted settings before its first write.** The agent row is now
  resolved right after the preview, before the snippets. That block used to run after the workflows and has only
  moved. When the settings will be adopted (an agent `UPDATE`, or a `CONFLICT` named in `selectedResources`), they
  are checked there. The preview content already carries the target's identity, so the security-flag check reads
  the key the write would keep. The `BadRequestException` goes out as is (`WebApplicationException` is not wrapped),
  so a ZIP upgrade and a single sync answer 400 naming the field, as
  [`docs/agent-sync-guide.md`](../agent-sync-guide.md#response-codes) now documents. A batch sync records it on that
  agent's row and goes on with the rest. A ZIP upgrade also rolls back the connections it created, as for any other
  throw. When the settings are not adopted, nothing changes: an unselected settings conflict is not checked, and the
  run writes the workflows exactly as before.
- **Tests.** `UpgradeExecutorSyncTest.refusedAdoptedSettingsWriteNothing` has five cases: `healthConfig`,
  `ratingConfig`, `maxContinuations`, Dream, and a signing flag without a key. Each one asserts a 400 naming the
  field, and that no snippet, extension, workflow, agent or descriptor was written.
  `unadoptedSettingsAreNotChecked` and `validAdoptedSettingsAreWritten` pin the unchanged paths.
  `RestImportServiceSyncCoverageTest` pins the 400 of a single sync and the failed batch row.
- **Docs.** [`docs/agent-config-authoring.md`](../agent-config-authoring.md) no longer says that upgrade and sync
  check only at the agent update. The 400 row and the batch paragraph of
  [`docs/agent-sync-guide.md`](../agent-sync-guide.md) name the new refusal.

### What's next

`RestImportService.validateAgentFiles` (`create` / `merge`) still runs only HITL, continuation and `healthConfig`.
A `create` import writes the agent through `IAgentStore` directly, which adds the Dream check after the workflows
are written. Ratings and the security flags are not checked on that path at all, because the rating endpoints clamp
whatever they read. Calling `RestAgentStore.validateSettings` there would close the Dream gap. It would also start
refusing archives that import today, so that is a separate decision.

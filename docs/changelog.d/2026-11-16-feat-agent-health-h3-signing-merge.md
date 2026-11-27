## Agent Health h3: upgrade settings check no longer refuses the signing flag (2026-11-16)

**Repo:** EDDI · **Branch:** `feat/agent-health-h3`

Merging `feat/agent-health-h2b` brought in the end-to-end agent signing-key work, where `RestAgentStore`'s signing check reads the vault per agent and a sync turns `signInterAgentMessages` off with a warning when the target cannot sign (`UpgradeExecutor.signOnlyIfTargetCan`). This branch's `RestAgentStore.validateSettings`, which `UpgradeExecutor` runs on adopted settings before its first write, still called the old static keypair check, so it would have refused the flag the sync is meant to downgrade.

- `RestAgentStore.validateSettings` now runs ratings, `healthConfig` and `AgentStore.validateSettings` (HITL, Dream, continuation, presentation), and leaves signing to the sync's own downgrade. The old static `validateSecurityFlags(AgentConfiguration)` is gone; the instance check from the base is the only one.
- `UpgradeExecutorSyncTest.refusedSettings` loses its `signInterAgentMessages` case; the base's `signingFlagIsTurnedOffWhereTheTargetCannotSign` covers the behaviour.
- [`agent-config-authoring.md`](../agent-config-authoring.md) no longer lists security flags among the upgrade-time refusals.

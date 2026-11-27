## Upgrades check the presentation blocks before their first write (2026-11-15)

**Repo:** EDDI · **Branch:** `feat/agent-health-h3`

Merging `feat/agent-health-h2b` brought in `AgentPresentationValidator` (`appearance`, `titleGeneration`), which `AgentStore.create`/`update` called next to the HITL, Dream and continuation checks. This branch had already gathered those checks into `AgentStore.validateSettings`, which `UpgradeExecutor` runs on the settings an upgrade or a sync adopts **before its first write**. Taking the base's lines would have left the presentation check out of that early pass. An adopted `appearance` with a bad colour would then be refused only at the agent update, after the snippets, extensions and workflows had already been written.

- `AgentStore.validateSettings` now ends with `AgentPresentationValidator.validate`; `create` and `update` keep calling `validateSettings` alone.
- The Javadocs of both `validateSettings` methods (`AgentStore`, `RestAgentStore`) and the upgrade sentence in [agent-config-authoring.md](../agent-config-authoring.md) name the presentation blocks.
- `UpgradeExecutorSyncTest.refusedAdoptedSettingsWriteNothing` gains `appearance.accentColor` and `titleGeneration.maxLength` cases.
- `agent-config-authoring.md` keeps both new sections: `healthConfig` from this branch, then Agent Presentation and Conversation Titles from the base.

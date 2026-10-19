## feat(backup): declare a ZIP import or agent sync compatible (2026-10-19)

**Repo:** EDDI (`feat/compatible-import-sync`)

### What changed and why

A conversation runs on the newest ready version of its compatibility generation, so a compatible save lets running conversations follow and a breaking one pins them. ZIP import (`strategy=upgrade`, and the update half of `strategy=merge`) and agent sync always wrote a breaking version, so promoting an agent from staging to production pinned every running production conversation to the pre-promotion version, and operators ended those conversations to get the fix out.

- **`compatible` parameter** (default `false`, the safe default is unchanged) on `POST /backup/import` (merge and upgrade) and `POST /backup/import/sync`, and a per-request `compatible` field on `SyncRequest` for `POST /backup/import/sync/batch`. It is threaded to the agent write (`RestImportService.createOrUpdateAgent`, `UpgradeExecutor.updateAgentConfig`) and is ignored when a new agent is created or nothing is written. The old method forms remain as `default` overloads on `IRestImportService` and `UpgradeExecutor.executeUpgrade`, so existing callers are unchanged. No MCP tool exposes import or sync, so there is nothing to change there.
- **Merge rollback.** A failed merge writes the pre-import content back through the same agent write, so the restore carries the same declaration. Breaking import: aborted version G+1, restore G+2, so no later breaking save can share a generation with the aborted version (the original reasoning, still true). Compatible import: nothing bumped, aborted version and restore both keep G, so conversations that had already followed onto the aborted version follow on to the restored content, whereas a breaking restore would pin them to the aborted import's content. Generations never decrease along the version chain in either case. Both outcomes are tested.
- **Preview.** `ImportPreview.conversationImpact` (`PromotionImpact`) on the upgrade, merge and sync previews: the target's current version and generation, the generation each declaration would produce, the open conversations on every deployed version and how many would follow if declared compatible. It answers for both declarations because the preview is read before the choice, so the Manager updates instantly without another round trip to the source. Counts only, by the conversations' own compatibility generation (a conversation follows the newest ready version of its generation whatever version it last ran on, so a count per deployed version missed idle conversations stored on undeployed versions): one grouped count, `IConversationMemoryStore.getActiveConversationCountsByGeneration` (Mongo `$group`, Postgres `GROUP BY`), never the active-conversation memory listing. Computed by `RestAgentAdministration.previewNextVersion` with the same follow rule as the deployment impact and the generation rule of `AgentStore.nextCompatibilityGeneration` (now public). Omitted when nothing would be written, there is no existing target, or the caller lacks EDIT on it.
- **Result.** `UpgradeResult` (upgrade, sync, batch) gains `compatible` and `compatibilityGeneration` of the written agent version; a merge import answers with `X-Agent-Compatible` and `X-Agent-Compatibility-Generation`. Additive.
- **Legacy edge.** A target whose current version has no generation still starts generation 1 on a compatible save; the preview reports `startsNewChain: true` and the Manager shows the existing `compatibleLegacyNote` text.
- **Manager.** `PromotionCompatibilityPanel` (reusing `CompatibleVersionCheckbox`, unticked by default) in the import dialog's preview step and in each agent's detail on the Sync page, with the impact sentence and `agentVersioning.promotionImpact*` keys in all 11 locales.
- **Docs.** `deployment-management-of-agents.md` (when to tick compatible on a promotion, both outcomes), `agent-sync-guide.md`, `import-export-an-agent.md`.

### Design decisions

- Merge shares the update path with the rollback in question, so it takes `compatible` as well as upgrade.
- Preview parameters were rejected in favour of returning both answers.
- A version deployed in both environments is counted once: conversation counts are per agent version, not per environment.

### Tests

New: `RestImportServiceCompatibleTest`, `RestAgentAdministrationPromotionImpactTest`, additions to `UpgradeExecutorSyncTest` and `RestImportServiceSyncCoverageTest`, and `promotion-compatibility.test.tsx`. The key backend tests were mutation-checked.

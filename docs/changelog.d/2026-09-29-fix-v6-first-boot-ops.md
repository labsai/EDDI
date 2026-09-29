## 🐛 fix(upgrade): the 5.x first boot deletes no conversations, survives its probes, runs its conversation pass server-side, and is documented (2026-09-29)

**Repo:** EDDI (`fix/v6-first-boot-ops`)

### Why

These are the operational hazards of a 5.x → 6.x first boot, found in the same review as `fix/v6-first-boot-blockers` and `fix/v6-first-boot-data`. Four of them:
- The first boot permanently deleted every ended conversation older than a year.
- The shipped startup probe killed the pod mid-migration, so the migration never finished.
- The slowest pass could overwrite concurrent writes.
- The documentation was wrong in two places and had no upgrade guide at all.

### What changed

**No retention deletes on the migration boot.** EDDI 5 kept ended conversations for ever; 6.x defaults to 365 days, and the daily sweep starts at boot. [`RestConversationStore`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java) now deletes nothing while the rename migration is pending or was started by this process ([`V6RenameMigration.ranInThisProcess`](../../src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java)). Instead it logs how many ended conversations it would have deleted and how to keep them. From the next restart on it runs as configured; `eddi.migration.v6-rename.allow-retention-on-first-boot=true` lets it run on the migration boot itself.

**The startup probe checks liveness.** Readiness is DOWN for as long as the migrations run, and a startup probe on readiness gave the pod 60 s. On a throttled database the migration took 24 minutes, so the pod crash-looped, and each restart began the migration again. The chart ([`deployment.yaml`](../../helm/eddi/templates/deployment.yaml)), `k8s/base` and `k8s/quickstart.yaml` now probe `/q/health/live` at startup. The chart makes path, `failureThreshold` and `periodSeconds` configurable under `eddi.startupProbe`. Chart `2.2.0 → 2.3.0`. `DeploymentManifestsTest` and the CI chart render check both cover it.

**The conversation pass is server-side and bumps the revision.** `V6RenameMigration` renamed `botId`/`botVersion` and the environment in every conversation by reading each document into Java and replacing it whole. That cost about 20 of the 24 minutes on a throttled database, and could overwrite a concurrent write. It is now one `updateMany` with an aggregation pipeline, and it bumps `_rev`/`_histRev` the way a store write does, as does the step-shape pass from `fix/v6-first-boot-blockers`. A writer holding a pre-migration copy now gets the store's ordinary conflict instead of silently reverting the migration. Legacy `eddi://` URIs inside stored conversation step data are no longer rewritten: no 6.x code reads them, and finding them means reading every document in full. Deployment rows keep the per-row pass, because a row can collide with another on its v6 key.

**Documentation.**
- [`configuration-reference.md`](../configuration-reference.md): `eddi.migration.backupBeforeWrite` writes `<collection>.premigrationbackup`, and only for the legacy-format step, not `.history` for every migration.
- [`output-templating.md`](../output-templating.md): templates are converted only with `eddi.migration.v6-qute.enabled=true`.
- `eddi.migration.v6-rename.enabled` is now in `application.properties`.
- The new [`upgrading-from-5x.md`](../upgrading-from-5x.md) covers backup, both migration flags, retention, probes, what the first boot does, how to check it, and what isn't migrated. It is linked from the README and `SUMMARY.md`.

### Decisions

- **Retention holds for the migration boot, not for a fixed time.** A restart is a deliberate act, and the WARN tells the operator what the next one will delete and how to prevent it.
- The startup probe moved to **liveness** rather than getting a longer readiness budget. No budget fits every database, and readiness still keeps traffic away until the agents are deployed.

### Tests

`RestConversationStoreTest` (held while pending, held on the migration boot, allowed by the flag, runs on later boots), `V6RenameMigrationFirstBootTest` (server-side field rename and revision bump, a stale pre-migration write refused, an ambiguous `botId`+`agentId` document left alone, `ranInThisProcess`), and `DeploymentManifestsTest.startupProbeChecksLiveness`. Each was mutation-checked.

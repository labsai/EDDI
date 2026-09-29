# Upgrading from EDDI 5.x to 6.x

This guide is for an existing **EDDI 5.x database on MongoDB** that you want to run on EDDI 6.
The first 6.x boot migrates the database in place: collection names, field names, URIs,
workflow step types, environment names, templates and user properties. Done as described
here, it comes up with every previously deployed agent READY and every conversation's
history intact, with no script to run by hand.

Read it all before the first boot. Two steps can't be undone: the migrations rewrite
documents in place, and the retention sweep deletes old conversations.

---

## 1. Before you start

1. **Rehearse on a copy.** Restore a recent dump into a throwaway MongoDB, boot the target
   6.x image against it as below, and check it (section 6). Rehearsing is the only way to
   learn how long *your* migration takes, and whether anything needs manual attention.
2. **Stop every 5.x instance.** Don't run a rolling upgrade. The migrations rewrite documents
   that a running 5.x would keep writing in the v5 shape, and they take no cluster-wide lock.
3. **Take a full backup** (`mongodump`). The rename and template migrations rewrite documents
   **with no backup of their own**. `eddi.migration.backupBeforeWrite` covers only the older
   legacy-format step, which writes `<collection>.premigrationbackup`.

## 2. Configure the first boot

| Setting | Value | Why |
| --- | --- | --- |
| `EDDI_MIGRATION_V6_RENAME_ENABLED` | `true` | Renames the v5 collections and fields. **Default off: without it no agent deploys**, because 6.x looks for its configs under the v6 names. |
| `EDDI_MIGRATION_V6_QUTE_ENABLED` | `true` | Converts Thymeleaf templates to Qute. Default off: without it, v5 templates render as literal text. |
| `EDDI_CONVERSATIONS_DELETEENDEDCONVERSATIONSONCEOLDERTHANDAYS` | `-1` to keep old conversations | See [retention](#3-retention-decide-before-the-first-boot). |
| `EDDI_VAULT_MASTER_KEY` | a strong key | Needed for `${vault:…}` references and secret-scoped properties. |

Authentication, roles and CORS changed between 5.x and 6.x as well. See the
[configuration reference](configuration-reference.md) and [security](security.md) before
exposing the instance.

## 3. Retention: decide before the first boot

EDDI 5 kept ended conversations for ever (`deleteEndedConversationsOnceOlderThanDays=-1`). EDDI 6
defaults to **365 days**, and the sweep that deletes older ended conversations **permanently**
runs daily, starting at boot.

On a database that comes from EDDI 5, the sweep **deletes nothing until you decide**, on every
boot and every replica. Instead it logs daily how many ended conversations it would delete. This
holds while the migration is pending, after it has completed, and even when a 5.x database is
booted with the migration flag off. Decide with one of:

- `EDDI_CONVERSATIONS_DELETEENDEDCONVERSATIONSONCEOLDERTHANDAYS=-1` to keep them, as EDDI 5 did;
- `EDDI_MIGRATION_V6_RENAME_RETENTION_CONFIRMED=true` to let the configured retention apply.

## 4. Health probes during the first boot

Readiness (`/q/health/ready`) stays **DOWN until the migrations have finished** and the agents
are deployed, so no traffic reaches a half-migrated instance. On a large or slow database that
can take minutes. **Liveness** (`/q/health/live`) is UP throughout.

- **Helm chart 2.3.0 or later, and `k8s/base`:** the startup probe checks liveness, so the pod
  isn't killed mid-migration. Its limits are `eddi.startupProbe.*` in the chart.
- **An older chart, or your own manifests:** if the startup probe checks readiness, the kubelet
  kills the pod after its budget (60 s in older charts), mid-migration, and every restart begins
  the migration again. Point it at `/q/health/live`, or run the first boot as a one-off pod
  without probes.
- **Docker / Compose:** the image's `HEALTHCHECK` uses readiness, so the container shows
  `unhealthy` during the migration. Plain Docker doesn't act on that. An orchestrator that
  replaces unhealthy containers would, so disable its check for the first boot.
- **Run one replica** for the first boot. Scale out once it is ready.

## 5. What the first boot does

Separately, as the application starts, the legacy **`properties`** collection is copied into
long-term user memory and renamed `properties_migrated_v6`. Per-request identity (`userInfo`) and
credential-shaped values are **not** copied (see
[user memory](user-memory.md#migration-from-legacy-properties)).

A second later the migrations run in this order. Each records its completion in the
`migrationlog` collection, so a boot that stops part-way resumes where it left off.

1. **Rename migration** (`v6-rename-migration-complete`):
   - collections `bots`, `packages`, `behaviorrulesets`, `httpcalls`, `langchain`,
     `regulardictionaries` and `bottriggers` (with their `.history` twins where they exist) are
     renamed to their v6 names;
   - config URIs are rewritten, and the LLM workflow step `eddi://ai.labs.langchain` becomes
     `eddi://ai.labs.llm`;
   - `bot*` fields become `agent*` in conversations, deployments, triggers and user-conversation
     mappings, and the environments `unrestricted` / `restricted` become `production`;
   - conversation steps get their v6 shape.

   The deployment sweep and readiness wait for it.
2. **Template migration** (`v6-qute-migration-complete`): Thymeleaf → Qute.
3. Channel-connector and workspace access-index migrations, then the legacy-format pass for
   v5-era property, API-call and output documents (normally already recorded on a 5.5.1 database).
4. The deployment sweep deploys every agent version that was deployed on 5.x, and readiness goes
   UP.

A 5.x database that was created before 6.3 also has a unique `descriptors.resource_1` index. The
store replaces it with the non-unique 6.x one when it starts, and logs that at WARN.

## 6. Check the result

- **Log.** `V6 rename migration complete` and `V6 Qute migration complete`. A line starting
  `V6 Qute migration left <collection>/<id> unchanged` names each field whose template could
  not be converted safely. Convert those by hand. Until then the template migration runs again
  on every boot. Fix any `could not be migrated` or `aborted` line the same way and restart.
- **Agents.** `GET /administration/production/deploymentstatus` lists every agent READY.
  Readiness reports failed deployments as data: `agentsInErrorCount` should be `0`. A deployment
  that failed is retried on its own with a growing delay.
- **Managed conversations.** `GET /AgentTriggerStore/agenttriggers/{intent}` answers for every
  intent you had.
- **Templates.** Render a converted one with `POST /administration/preview/template`.

## 7. What is not migrated automatically

- **Plaintext credentials in agent configs** (API keys in `httpcalls`, `langchain` parameters)
  stay where they are. Move them into the [secrets vault](secrets-vault.md) and reference them as
  `${vault:…}`.
- **Legacy properties that look like credentials**, and all of `userInfo`, stay only in
  `properties_migrated_v6`. The check is cautious: a long random-looking identifier can be held
  back too. Its value is still there to restore by hand.
- **Templates the converter cannot convert safely** (a template that generates template syntax)
  are left in Thymeleaf and reported, as above.
- **Old `eddi://` URIs inside stored conversation step data** are left as they were. No 6.x code
  reads them.
- **REST clients.** The store paths were renamed (the bot, package, behavior, httpcalls, langchain and
  dictionary stores are now the agent, workflow, rules, API-call, LLM and dictionary stores).
  `LegacyPathRewriteFilter` still rewrites the v5 paths and the `unrestricted` / `restricted`
  environment segments to their v6 equivalents, so existing clients keep working. Port them anyway:
  the filter is scheduled for removal in v7.

## 8. Afterwards

- The migration flags can stay on. Every migration is a no-op once recorded.
- Once you have checked the result, drop the backups: `*.premigrationbackup` and
  `properties_migrated_v6`, which still hold the legacy values, credentials included.
- If this database was already migrated by an earlier 6.x, which copied `userInfo` into memory,
  remove those entries:

  ```javascript
  db.usermemories.deleteMany({ category: "legacy", key: "userInfo" })
  ```

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
| `EDDI_CONVERSATIONS_MAXIMUMLIFETIMEOFIDLECONVERSATIONSINDAYS` | `-1` to keep open conversations open | See [the idle sweep](#the-idle-sweep-five-minutes-after-boot). |
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

### The idle sweep, five minutes after boot

A separate daily job **ends** (sets to `ENDED`; it deletes nothing) every conversation idle longer
than `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` (default `90`). It also undeploys
old agent versions that no active conversation uses any more. It first runs **five minutes after
boot**. EDDI 5 had the same job, but it ran five hours after boot, and a date-arithmetic bug meant it
rarely ended anything. So conversations that 5.x kept open can be closed shortly after the first 6.x
boot. A conversation paused for human approval is never ended.

To keep every conversation open through the upgrade, set
`EDDI_CONVERSATIONS_MAXIMUMLIFETIMEOFIDLECONVERSATIONSINDAYS=-1`. From 6.5.0, any value below 1
disables idle-ending, and startup logs that it is off. **Before 6.5.0, `-1` ended every
conversation**, so on an older 6.x use a large number instead (for example `36500`). Old agent
versions with no active conversation are still undeployed either way. An idle conversation that is
still open counts as active, so it keeps its version deployed.

**The sweep is memory-safe.** It reads one small projection per open conversation (id, state, agent,
version, last-interaction time) in batches of 200, never the conversation itself. Earlier releases
loaded every open conversation in full: a production 5.x deployment with tens of thousands of open
conversations of several hundred KB each ran out of heap shortly after boot. If you switched the sweep off
with `-1` for that reason, you can switch it back on. A conversation the sweep ends records the end
reason `idle`.

### Ending a backlog of idle conversations in one call

After a migration, 5.x can leave tens of thousands of conversations open. To end them without
waiting for the daily job, an admin can run one database operation:

```
POST /conversationstore/conversations/end-inactive?inactiveForDays=90
POST /conversationstore/conversations/end-inactive?inactiveForDays=90&agentId=<agentId>&dryRun=true
```

It ends every `READY` conversation whose last interaction is older than `inactiveForDays`
(optionally of one agent), with end reason `idle`, and answers `{"count": n, "dryRun": false}`.
`dryRun=true` ends nothing and returns what it would end: run it first. The role is `eddi-admin`,
`inactiveForDays` must be at least 1, and every call is written to the audit ledger.

**Ending conversations can make them eligible for deletion by the next retention run** (see the retention bullet below), so check `deleteEndedConversationsOnceOlderThanDays` before you run it for real.

- A conversation paused for human approval (`AWAITING_HUMAN`), one in the middle of a turn
  (`IN_PROGRESS`) and one with no timestamp at all are left alone.
- Conversation descriptors are **not** touched, so the retention clock
  (`deleteEndedConversationsOnceOlderThanDays`) keeps counting from the conversation's last
  interaction, exactly as for conversations the sweep ends. A conversation idle for longer than the
  retention is therefore deleted by the next retention run: decide on retention first (see above).
- The listing re-derives a conversation's state from the conversation itself, so it shows `ENDED`
  at once.
- On PostgreSQL the update runs in batches of 500 rows, each its own transaction, so a large
  backlog never holds one huge transaction open.

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
   - in conversation descriptors, `botResource` / `botName` become `agentResource` / `agentName`,
     and a descriptor that an earlier 6.x rewrote without its agent gets it back from its
     conversation;
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

- **Log.** `V6 rename migration complete` and `V6 Qute migration complete`.
  - A document whose template can't be converted safely is logged **once at ERROR**, as
    `V6 Qute migration left <collection>/<id> unchanged`, with its fields and what to do. Later
    boots list all such documents in a **single WARN** (`… still hold templates that cannot be
    converted automatically …`).
  - Until none is left, the template migration isn't marked complete, and each boot checks those
    documents again, converting any you have fixed.
  - Fixing a config through the API keeps the refused version in `<collection>.history`, which is
    checked too. Once no deployed agent uses that version, remove that history row by hand.
  - Fix any `could not be migrated` or `aborted` line and restart.
- **Conversations by agent.** `GET /conversationstore/conversations?agentId=<id>` lists the
  conversations that agent had on 5.x. A 5.x database often holds descriptors whose conversation
  was deleted long ago; the listing leaves those out, so compare the result with
  `conversationmemories`, not with `descriptors`. The metric
  `eddi.conversations.listing.orphaned_descriptors` counts how many it skipped. Page with `index`
  until a page comes back empty. Before 6.5.0 a filtered listing repeated rows across pages and
  returned more than `limit`, so a count taken by paging came out too high.
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
  `properties_migrated_v6`. A key is held back when its value is:
  - under a credential-named field (`token`, `apiKey`, `password`, …);
  - in a known credential format (JWT, `Bearer …`, provider key prefixes);
  - or high-entropy under a field that isn't identifier-named.

  Identifier fields (`id`, `…Id`, `…_id`) holding plain ids are migrated normally. Skipped keys
  are logged by name and count only, never by value, and stay readable in
  `properties_migrated_v6`. Move any you really need into the secrets vault by hand.
- **Templates the converter cannot convert safely** (a template that generates template syntax)
  are left in Thymeleaf and reported, as above.
- **Credentials recorded in stored conversations.** EDDI 5 stored whatever a turn carried, and no
  migration rewrites stored conversations. So every conversation can still hold:
  - context values the client sent, such as a user token under `userInfo`;
  - properties set from plaintext configs (an API key a property setter copied into the conversation);
  - the request headers of every HTTP call the agent made, `Authorization` included.
- **Old plaintext in the config history.** Moving a credential into the vault writes a new config
  version. The `.history` collections keep every earlier version, plaintext included.
- **Old `eddi://` URIs inside stored conversation step data** are left as they were. No 6.x code
  reads them.

**Rotate every credential that was ever in a 5.x config, or sent to it by a client.** Copies of them
exist in stored conversations, in the config history and in the backups the migration keeps. Rotating
is the only step that makes all of them worthless at once. Removing the copies afterwards is hygiene
on top of it.

To see where credential-named fields remain, run this in `mongosh` against the EDDI database. It
prints collection names, counts and field names, **never values**. A hit means a field with a
credential-like name exists: check whether it holds plaintext or a `${vault:…}` reference. Add the
property names your own configs used for credentials to `NAMES`.

```javascript
const NAMES = ["token", "apikey", "api_key", "authorization", "password", "secret"];
function walk(v, hits) {
  if (v === null || typeof v !== "object" || v._bsontype) return;
  if (Array.isArray(v)) { for (const x of v) walk(x, hits); return; }
  // Property instructions name the credential in a value: {name: "…", valueString: …}
  if (typeof v.name === "string" && NAMES.includes(v.name.toLowerCase()) && ("valueString" in v || "value" in v))
    hits.add("name=" + v.name);
  for (const k of Object.keys(v)) {
    if (NAMES.includes(k.toLowerCase())) hits.add(k);
    walk(v[k], hits);
  }
}
const report = {};
for (const c of db.getCollectionNames().sort()) {
  let docs = 0; const byKey = {};
  db.getCollection(c).find().forEach(d => {
    const h = new Set(); walk(d, h);
    if (h.size) { docs++; for (const k of h) byKey[k] = (byKey[k] || 0) + 1; }
  });
  if (docs) report[c] = { documents: docs, byKey };
}
printjson(report);
```

It reads every document, so run it against a restored copy or off-peak. To remove what it finds,
prefer targeted server-side updates on the exact paths (`updateMany` with array filters or an update
pipeline). Don't read and rewrite whole conversations from a client: that races live turns. Rehearse
on a copy and take a backup first.
- **REST clients.** The store paths were renamed (the bot, package, behavior, httpcalls, langchain and
  dictionary stores are now the agent, workflow, rules, API-call, LLM and dictionary stores).
  `LegacyPathRewriteFilter` still rewrites the v5 paths and the `unrestricted` / `restricted`
  environment segments to their v6 equivalents, so existing clients keep working. Port them anyway:
  the filter is scheduled for removal in v7.

## 8. Afterwards

- The migration flags can stay on. Every migration is a no-op once recorded.
- Once you have checked the result, drop the backups: `*.premigrationbackup` and
  `properties_migrated_v6`, which still hold the legacy values, credentials included.
- Rotate the credentials the 5.x deployment used, and clear their stored copies ([section
  7](#7-what-is-not-migrated-automatically)).
- If this database was already migrated by an earlier 6.x, which copied `userInfo` into memory,
  remove those entries:

  ```javascript
  db.usermemories.deleteMany({ category: "legacy", key: "userInfo" })
  ```

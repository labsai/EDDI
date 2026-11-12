## 🐛 fix(migration): migrated Slack channels get descriptors; the legacy migration loses its fake running guard (2026-11-07)

**Repo:** EDDI (`fix/migration-descriptors`)

### What changed and why

- **Migrated channels were invisible.** [`ChannelConnectorMigration`](../../src/main/java/ai/labs/eddi/configs/migration/ChannelConnectorMigration.java)
  turned the deprecated `channels` list on an agent into channel integrations with `channelStore.create` and wrote no
  descriptor. Everything that finds a channel integration lists `ai.labs.channel` descriptors — the Slack router, the
  REST and MCP listings, and the migration's own duplicate check — and `DocumentDescriptorFilter` runs only for HTTP
  responses. So the router kept routing through the legacy connector, the integrations appeared nowhere, and a re-run
  after one failed channel created every channel again. Each created channel now gets its descriptor, with the access
  index built (`DescriptorAccess.rebuildIndex`), named like the config, and owned and filed like the agent it routes
  to by default. That agent's visibility is copied only when it is `private`; anything wider becomes `space`, since a
  channel holds the bot's credentials. A descriptor that cannot be written fails that channel (no completion flag) and
  removes the document again, so the retry does not duplicate it. An agent with no descriptor leaves its channel
  unowned, as the agent is; an agent descriptor that cannot be *read* aborts the run instead (no completion flag), since
  an unowned channel is open to everyone under the default legacy policy and an unknown owner is not an absent one.
- **A failed duplicate check no longer creates duplicates.** When the channel listing itself failed, the run went on
  "but may create duplicates". It now aborts and retries on the next start.
- **Back-fill.** The completion flag is renamed to `channel-connector-migration-v2-complete`, so a deployment that
  finished the first run migrates once more and gets visible integrations; Slack then routes through them. The
  documents the first run created stay in the database unreferenced: no API lists or reads them. Noted in
  [Upgrading from 6.5](../upgrading-from-6.5.md#slack-channels-still-declared-on-an-agent).
- **The document migrations run on one node at a time.** [`AgentDeploymentManagement.runDocumentMigrations`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java)
  (Qute, channel connector, workspace access index) had no cluster-wide exclusion: each migration reads its flag,
  works, then writes the flag, so replicas starting together all ran the pass. The in-place rewrites converge, but the
  channel migration *creates* a configuration under a fresh id, and its duplicate check cannot see what another node
  is creating at that moment, so each channel was migrated once per node — now visible ones, and the renamed flag
  makes every existing cluster run it again on upgrade. The pass now holds the cluster lease `migration.documents`
  (the existing `IConversationLeaseManager`; NATS KV in cluster mode, granted at once on a single node). A waiting
  node gets it when the holder is done and finds every flag set; a crashed holder loses it after the lease TTL. A node
  still without it after 15 minutes, or refused it while NATS is unreachable under `degraded.turns=reject`, skips the
  pass with a WARN and starts as before; what is left is retried on the next start.
- **`MigrationManager` drops `isCurrentlyRunning`.** [`startMigrationIfFirstTimeRun`](../../src/main/java/ai/labs/eddi/configs/migration/MigrationManager.java)
  is `synchronized`, so the flag could only ever be seen by a call from inside its own callback — which it then
  skipped without running that caller's callback. Nothing calls back in today; the two tests named "concurrent calls
  guarded" made two sequential calls, and are renamed to say so.
- **`V6RenameMigration` counts a failed agent rewrite.** The `packages` → `workflows` pass over `agents` called
  `replaceOne` bare and counted the document migrated whatever came back; it now goes through `saveDocument`, like the
  history pass beside it, so a replace that matched nothing (or threw) keeps the migration incomplete and it retries.

### Tests

`ChannelConnectorMigrationTest`: the descriptor's resource URI, name, owner, space, narrowed visibility and index; a
private agent's channel stays private; an agent without a descriptor gives an unowned channel, and an unreadable one
aborts the run; a re-run after a partial failure creates only the failed channel (stores that
remember the first run's writes); a descriptor write failure removes the document and leaves the flag unset; a listing
failure creates nothing; the first run's flag does not skip the second. `MigrationManagerTest`: a call from inside the
completion callback runs its own callback. `V6RenameMigrationTest`: an agent rewrite that matched nothing leaves the
completion log unwritten, and one that matched writes it. `AgentDeploymentManagementMigrationLeaseTest`: the lease
acquired runs the three migrations inside it and releases it; held elsewhere, refused while degraded, or unanswered
past the wait, the pass is skipped and startup still deploys; a migration that throws still releases the lease.

```decision-log
| 2026-11-07 | A migrated channel's descriptor copies the default-target agent's owner and space, but narrows `internal`/`published` to `space` and copies no grants | Channel migration wrote no descriptors; a startup job has no caller to stamp with | Leaving it unowned (legacy: visible to everyone under the default legacy policy, with the bot's credentials); copying the agent's visibility verbatim (publishing a credentials-bearing config) |
```

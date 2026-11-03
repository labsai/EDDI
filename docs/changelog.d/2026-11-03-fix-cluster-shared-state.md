## 🐛 fix(cluster): shared state, events, deployments, node identity, signed control plane (2026-11-03)

**Repo:** EDDI (`fix/cluster-shared-state`) — workstream C of the cluster production-readiness
hardening (findings B1–B5, C4 signing part, C6, C15 versioning part, D6 presence part, D7).

### What changed and why

- **B1 — `autoDeploy=false` deployments are recorded.** They existed only as `deployment.changed`
  events, so a lost undeploy, an outbox that delivered an undeploy before its redeploy, or a node
  that joined later left them deployed (or missing — 404 for about 1/N of the requests) on some
  nodes for good. They now live in a `<prefix>_DEPLOYMENTS` KV bucket as `DEPLOYED` or an
  `UNDEPLOYED` tombstone, ordered by the KV revision; the deployment sweep reconciles them like the
  deployment collection, refreshes them (compare-and-set) while they are served and re-creates an
  expired one only when it was confirmed within the TTL. A deployment event is checked against the
  records before it is applied: an undeploy older than a redeploy, or a transient deploy older than
  its tombstone, is dropped. Not in the database, as the review suggested: the record must vanish
  once the whole cluster is gone (that is what "not recorded" means), and a new status value in the
  deployment collection would have broken older nodes that parse it during a rolling upgrade.
- **B2 — events.** A node resyncs once after its first subscription (it subscribes at
  `DeliverPolicy.New`, so whatever was published while it booted with NATS down never arrived),
  and when the event stream was recreated — detected by its creation time (checked every 30 s) or
  by a sequence that goes back — it re-subscribes and resyncs.
- **B3 — cache events.** A read-through fill (`ICache.fill` / `fillIfAbsent`) is no longer
  announced as an eviction; only mutations are. Dispatch runs on two bounded lanes (cache
  evictions vs everything else), so a burst of evictions no longer delays a secret rotation; a
  full lane drops the event and resyncs.
- **B4 — webhook de-duplication fails closed** while NATS is unreachable under
  `eddi.cluster.degraded.nonces=reject` (the default): 503, the sender retries. The docs now list
  the cache TTLs of secrets (5 min), connections (1 min) and global variables (2 min) and no longer
  claim that security state always fails closed.
- **B5.** A GDPR stop waits for the member count it read itself, with its own timeout
  (`eddi.cluster.gdpr-stop-timeout`, 10 s), and the user-id hash travels sealed (AES-GCM, key derived
  from the signing key) on the RPC, on `gdpr.user-erased` and in cache-eviction keys when a signing
  key is set. Audit-position counters that are (re)seeded are counted (`outcome=seeded`).
- **C4 (signing part) — signed control plane.** With `eddi.cluster.signing.key` every node RPC, RPC
  reply and cluster event carries an `Eddi-Sig` header: HMAC-SHA256 over the subject, a timestamp, a
  random nonce and the SHA-256 of the body, compared in constant time. Without a key a WARN is logged
  and nothing changes; `eddi.cluster.signing.required=true` refuses unsigned messages and refuses to
  start without a key. Configuration lives in the new `ClusterSigningConfig`, not `ClusterConfig`.
- **C6 — duplicate node ids.** The presence record is written with compare-and-set; a node whose key
  is held by another boot waits one interval and takes over only a record that stopped changing
  (a crashed predecessor). A live twin stops announcing itself, logs an ERROR, reports
  `eddi_cluster_node_duplicate 1` and its new `cluster-node-identity` readiness check is DOWN.
  Falling back to `eddi.schedule.instance-id` as the node id now logs a WARN.
- **C15 (versioning part).** RPCs and events carry their payload version (`Eddi-V`, event `v`); an
  unversioned message is version 1; a newer one is handled best-effort and counted. Presence records
  carry the payload protocol, and members on mixed versions log a WARN (`eddi_cluster_versions`).
- **D6 (presence part).** Members come from a KV watch on the presence bucket instead of listing it
  and reading every record every few seconds on every node.
- **D7.** The event stream is updated only when its configuration differs, and a cluster-wide
  resync starts after a random delay (`eddi.cluster.events.resync-jitter`, 5 s).

### Threat model of the signing

Who is kept out: anyone who holds the shared NATS credentials (every pod has them) or can read and
write NATS traffic, but not the signing key. Such a party could drain every node, stop a user's
work, undeploy agents or trigger resync storms; with a key, a message must carry a valid HMAC made
with it. A signature is bound to the subject and the body hash, so it cannot be moved to another
operation or payload. Replay is bounded twice: a request older (or further ahead) than
`eddi.cluster.signing.max-age` (60 s) is refused, and within that window a nonce already seen is
refused; an event's window is the event retention plus that, because a reconnecting node
legitimately receives events that old. A reply is bound to the nonce of its request, so an old
answer cannot be passed off as a new one. Not covered: confidentiality (use NATS TLS), the KV and
stream state (per-node NATS permissions, workstream D), flooding NATS, and a holder of the key. The
nonce memory is per node and in memory: a node that restarts inside the window could accept one
replay of a message it had already seen, which the 60 s window keeps short.

### Design decisions

- Rolling upgrade first: unsigned and unversioned messages from older nodes stay accepted unless
  `eddi.cluster.signing.required=true`; a node without a key accepts signed messages it cannot
  check. The three-step order is in [Clustering](../clustering.md#rolling-upgrades).
- The audit-counter margin the review recommends was **not** added: a margin is a gap in the
  conversation's chain, which verification grades `BROKEN`. A re-seed is counted instead, so a
  duplicate position can be attributed.

### Needs from other workstreams

- WS-A: `KvLeaseManager` should not sweep or take over "our earlier boot" leases while
  `ClusterPresence.isDuplicate()` is true (a twin still runs its lease sweeps).
- WS-B: `RestAgentAdministration` keeps calling `announceTransientDeployment`; nothing else needed.

**Files:** [`TransientDeployments.java`](../../src/main/java/ai/labs/eddi/engine/cluster/TransientDeployments.java),
[`AgentDeploymentManagement.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java),
[`DeploymentStore.java`](../../src/main/java/ai/labs/eddi/configs/deployment/mongo/DeploymentStore.java),
[`JetStreamEventBus.java`](../../src/main/java/ai/labs/eddi/engine/cluster/events/JetStreamEventBus.java),
[`ClusterMessageSigner.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterMessageSigner.java),
[`ClusterSigningConfig.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterSigningConfig.java),
[`NatsClusterRpc.java`](../../src/main/java/ai/labs/eddi/engine/cluster/rpc/NatsClusterRpc.java),
[`ClusterPresence.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterPresence.java),
[`NodeIdentityHealthCheck.java`](../../src/main/java/ai/labs/eddi/engine/cluster/NodeIdentityHealthCheck.java),
[`ClusterInvalidatingCache.java`](../../src/main/java/ai/labs/eddi/engine/caching/ClusterInvalidatingCache.java),
[`CacheFactory.java`](../../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java),
[`WebhookEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/WebhookEventHandler.java),
[`AuditClusterSupport.java`](../../src/main/java/ai/labs/eddi/engine/cluster/AuditClusterSupport.java),
[`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java);
docs [Clustering](../clustering.md), [Configuration Reference](../configuration-reference.md),
[Metrics](../metrics.md), [Security](../security.md), [GDPR](../gdpr-compliance.md).

```decision-log
| 2026-11-03 | Transient (autoDeploy=false) deployments are recorded in a NATS KV bucket with tombstones, not in the deployment collection | B1: they existed only as events | A database status + epoch: survives a full cluster restart (against autoDeploy=false semantics) and an unknown status value breaks older nodes mid-upgrade |
| 2026-11-03 | Cluster messages are signed with an HMAC key kept outside NATS; unsigned accepted unless eddi.cluster.signing.required=true | C4: one NATS credential controlled every node | Per-message asymmetric signatures (key distribution, cost); refusing unsigned by default (breaks rolling upgrades and existing installs) |
| 2026-11-03 | No margin when an audit position counter is re-seeded | B5 recommended one | A margin is a chain gap that verification grades BROKEN; re-seeds are counted instead |
```

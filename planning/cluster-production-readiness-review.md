# Cluster mode (NATS JetStream): production-readiness review

**Date:** 2026-11-03 · **Code reviewed:** `main` at `1f8d55356` · **Scope:** everything active under `eddi.messaging.type=nats`: leases and fencing, the turn path, shared state and events, operations and security, scalability, and test evidence.

**Method:** five independent read-only reviews:
- one first pass on leases and fencing;
- four deep passes, each on one area.

Every finding below was traced to code by its reviewer. Findings marked **[V]** were also re-read in the code by the author of this summary. Nothing was run: no builds, no tests, no live cluster. Line numbers are approximate (±3).

## Verdict

**Not ready for a large enterprise production rollout yet.** The architecture is sound and should be kept. Its strengths:
- leases created by KV `create`, with the server-side TTL deciding expiry;
- the lease revision used as a fencing token;
- the fence compared inside the database write itself;
- the database as the only source of truth;
- explicit degraded modes;
- a strong operator console.

The implementation is under three weeks old (#956, 2026-10-15). Its edge cases are where enterprises get hurt, and this review found:

- **Correctness:** 10 issues that can lose, duplicate, interleave or strand turns, or leave a cluster inconsistent. Most need a NATS slowdown or an outage, not exotic timing. Several are enshrined as intended behaviour by existing unit tests.
- **Security and operations:** 6 issues where a realistic deployment either quietly loses its guarantees or has a large blast radius:
  - TLS without hostname verification;
  - a misconfiguration that runs the whole cluster unfenced while green;
  - R1 storage on an external NATS;
  - an unsigned control plane behind one shared identity.
- **Scale:** about 12 serial NATS round trips per turn on a single stream leader. Several O(N) or O(N²) paths: whole-bucket scans, polling, and announcing an eviction on every cache put. Below tens of nodes and hundreds of turns per second, these are what hit first.
- **Evidence:** nothing runs more than one JVM against a real database and a real NATS. There is no fault injection (timeouts, latency, partitions), no soak test, and no scale test on separate hosts. The 13-scenario `scripts/cluster-demo` is not run in CI and has no recorded results.

The plan at the end orders the work so that correctness and security come first, then the evidence that proves them, then scale.

---

## A. Correctness: turn path, leases, fencing

| ID | Sev | Finding | Where |
|---|---|---|---|
| A1 | **Critical** | **A holder cut off from NATS never stops itself.** Renewal I/O failures are swallowed forever (`KvLeaseManager` renew), and nothing tracks the last successful renewal. After `lease.ttl` another node takes the conversation, but the partitioned holder keeps going: LLM and tool calls, external side effects, the **unfenced** long-term user-memory upsert (`Conversation.storePropertiesPermanently`, which runs *before* the fenced write), audit entries, and a 200 to its client. Only the final snapshot write is refused. A client retry or a replay then repeats every side effect. `KvLeaseManagerTest.heartbeatIoFailureKeepsLease` asserts this as intended. | `KvLeaseManager.java:816` **[V]**, `Conversation.java:752,880` |
| A2 | **Critical** | **A renewal that the server applied but whose reply timed out is reported as a lost lease.** `h.revision` stays stale, the next compare-and-set conflicts, and `lose("taken_over")` fires without checking that the key still names this node and boot. The healthy turn is cancelled and dead-lettered (409, or `lease-lost` after its 200 went out). Then `release()` returns early for the lost handle, and `isStale` never treats this node's own boot as stale, so the conversation blocks for a full TTL, even for this node. `create()` has the same problem when a create is applied but times out. A NATS leader election or a rolling NATS upgrade can trigger this across the cluster at once. | `KvLeaseManager.java:805-821` **[V]**, `:379-384`, `:505-511`, `:301` |
| A3 | **High** | **The fence is raised after the turn has read the conversation.** `getStateAndRevision` and `reloadIfSuperseded` run before `raiseFence`, and the idempotency check runs inside that reload. An expired predecessor can still append in between. The successor then runs without that step, its properties overwrite the predecessor's (last writer wins), and **a duplicate `Idempotency-Key` can run twice**. A failed `raiseFence` is only logged (`ConversationStepRunnerLeaseTest.raiseFailureDoesNotStopTheTurn`). | `ConversationStepRunner.java:441,465,521,557` **[V]**, `ConversationService.java:1555` |
| A4 | **High** | **Writes without a fence token rewrite `_fence` with the value they loaded.** Full-document writes (Mongo `replaceOne`, Postgres `data=?::jsonb`) persist `effectiveFence()`. The senders are undo/redo (which run on the REST thread, outside the lease), degraded turns, and schedule `carryProperties`. The `_rev` bump still refuses most stale writers, but they are refused as `ConcurrentConversationModificationException`, which is **not dead-lettered**. A turn whose 200 already went out is then lost silently. | `ConversationMemoryStore.java:180-183,235-239`, `PostgresConversationMemoryStore.java:145,243`, `ConversationService.java:1818,1876` |
| A5 | **High** | **A HITL resume that loses its lease leaves the conversation stuck.** On lease loss only `memory.setCancelled(true)` runs. `persistResumeOutcome` then returns ("the cancel path owns the state"), but no cancel path exists for a lease loss. The state stays IN_PROGRESS, no dead letter is written, and say/undo/redo are refused. `ClusterHitlRecovery` restores AWAITING_HUMAN only after about 4 minutes, and by then the approval decision is gone. Tools that already ran will run again on re-approval. A2 can trigger this without any partition. | `ConversationHitlService.java:805,663` **[V]** |
| A6 | **High** | **A fenced pause or resume commit is silently dropped.** `storeConversationMemorySnapshotIfState` returns `false` on a fence miss, and the caller treats that as "a concurrent writer": no `ConversationFencedException`, no dead letter. This contradicts `clustering.md`. | `ConversationStepRunner.java:789-800`, Mongo store `:223-245` |
| A7 | **High** | **Dead-letter replay deletes the entry when the replayed turn is queued, not when it commits.** A replayed turn that is then skipped, times out on acquire or is drained loses its input for good. The legacy `POST /administration/coordinator/dead-letters/{id}/replay` takes no `ADMIN` claim, so two admins can replay the same entry twice. It also writes nothing to the audit ledger. | `RestCoordinatorAdmin.java:131-142` **[V]**, `ClusterAdminService.java:875-882` |
| A8 | Medium | **"No loss" in degraded mode is overstated.** Appends merge, but every top-level `$set` (conversation properties) is last-writer-wins between unfenced and fenced turns. | `docs/clustering.md:86` |
| A9 | Medium | **A turn the runtime refused after its lease was granted is both dead-lettered and answered "retry".** If the client retries and an admin also replays, it runs twice. | `ClusterConversationCoordinator.java:205-217` |
| A10 | Medium | **The node-local dead-letter ring is capped at 1000 and lives only in memory.** During a long outage the oldest entries are dropped silently, and a restart loses all of them. The docs say they are "kept and forwarded". | `recordLocalDeadLetter` |
| A11 | Low | **Lease and recovery edge cases.** <ul><li>Waiter markers are last-writer-wins, `grant` deletes another node's marker, and a waiter that times out never cleans its own up, so a contending node can be starved.</li><li>`ClusterHitlRecovery.eligible` uses `peek`, which maps a NATS failure to "no lease".</li><li>The group lease is not exclusive: when `tryAcquireKey` fails the discussion still runs, so a cancel can be forwarded to the wrong node.</li></ul> | `KvLeaseManager.java:423,877`, `GroupConversationService.java:2639` |

## B. Correctness: shared state, events, deployments

| ID | Sev | Finding | Where |
|---|---|---|---|
| B1 | **High** | **Agents deployed with `autoDeploy=false` exist only as events.** The reconciliation sweep and a resync both skip them. So a lost undeploy, an event published out of order from the outbox (an undeploy before the redeploy, or the reverse), or a node that joins later (HPA scale-up) leaves the agent deployed, or missing, on some nodes indefinitely. A late-joining node answers 404 for about 1/N of that agent's requests. | `AgentDeploymentManagement.java:356-358,479-483,246-250` **[V: `unrecorded` set]**, `JetStreamEventBus.java:184-186,222-226` |
| B2 | **High** | **A node subscribes with `DeliverPolicy.New` and never resyncs on its first connect.** A node that booted while NATS was down (a supported case) misses every event published in the meantime, including secret and connection invalidations, transient deploys and `gdpr.user-erased`. Gap detection cannot fire without an earlier sequence. If the EVENTS stream is recreated, an ordered consumer may also go silently deaf (plausible, not verified). | `JetStreamEventBus.java:264-274,111` |
| B3 | **High** | **Every cache `put` announces a cluster-wide eviction, including read-through fills.** Under round-robin, nodes evict each other's fresh copies, the hit rate collapses, and the database takes the read load. That is at least two events per turn delivered to every node, on one unbounded single-thread dispatch queue, which delays `SECRET_CHANGED` and deployment events behind it. The 100k-message cap then keeps about 50 s of history at 2k events/s, so short disconnects become full flushes. | `ClusterInvalidatingCache.java:159-161` **[V]**, `CacheFactory.evictLocal:152`, `JetStreamEventBus.java:84,103` |
| B4 | Medium | **The cache-staleness list in the docs is incomplete.** It omits secrets (5 min), connections (1 min) and global variables (2 min), so a revoked secret keeps working that long on a node that missed the event. "Security state always fails closed" is overstated: webhook dedup (the only replay protection for the github and generic-hmac schemes) falls back to node-local when NATS is down. | `clustering.md:317`, `CacheFactory.java:100` |
| B5 | Low | **Audit and GDPR edge cases.** <ul><li>A lost AUDIT_SEQ key is re-seeded from the store maximum, so positions already handed out collide.</li><li>gdpr-stop compares a fresh peer count with one cached for 3 s, and shares the 2 s RPC timeout.</li><li>User ids travel as unsalted SHA-256 hashes.</li></ul> | `AuditClusterSupport.java:103`, `NatsClusterRpc.java:172,189` |

## C. Security and operations

| ID | Sev | Finding | Where |
|---|---|---|---|
| C1 | **Critical** | **An external `tls://` NATS gets no hostname verification.** The verifying SSL context is installed only when `eddi.nats.tls.enabled=true`. The chart's external path (`nats.externalUrl=tls://…`) never sets that flag, so jnats uses the JVM default context, which accepts any CA-signed certificate for any host. A man-in-the-middle can then steal the shared NATS password, which leads to C4. `opentls://` (trust all) is also accepted silently. | `NatsConnectionManager.java:199-201` **[V]**, `configmap.yaml:129-143` |
| C2 | **Critical** | **Two misconfigurations silently remove all cross-node guarantees while every check stays green.** <ul><li>**C2a:** a bad credential, an auth violation or a TLS failure makes the node "degraded", not failed. Readiness stays UP, `degraded.turns=local` runs every turn **unfenced**, and the TLS cause is logged only at DEBUG. A rotated password then removes all cross-node ordering across the whole fleet.</li><li>**C2b:** the Helm in-chart NATS with `nats.tls.enabled=true` cannot work. No CA is mounted into EDDI, and the truststore loader cannot read the PEM `ca.crt` the chart requires.</li></ul> | `NatsConnectionManager.java:157-166,226-233` **[V]**, `ClusterHealthCheck.java:316`, Helm templates (no truststore mount) **[V]** |
| C3 | **High** | **An external NATS gets R1 streams and buckets.** `EDDI_NATS_REPLICAS` is rendered only for the in-chart NATS, and the code defaults to 1. An enterprise that brings its own 3-node NATS gets R1 for LEASES, NONCES, AUDIT_SEQ, EVENTS and DEAD_LETTERS. Losing one NATS server then means degraded mode across the cluster, and possibly the loss of dead letters that are the only copy of audit entries. | `configmap.yaml:189-191` **[V]**, `ClusterConfig.java:57` |
| C4 | **High** | **NATS is an unsigned control plane behind one shared identity.** One publish to `eddi.<prefix>.rpc.all.admin-drain` drains every node, which is a total outage that bypasses the REST last-node guard and the audit ledger. The same credential, which every pod holds, can: <ul><li>read dead letters (user input) and agent exports;</li><li>reset `COSTS` and `RATELIMIT`;</li><li>delete `NONCES`, enabling replay;</li><li>corrupt `AUDIT_SEQ`;</li><li>plant leases;</li><li>delete streams through `$JS.API.>`.</li></ul> | `ClusterAdminService.java:213` **[V: handler exists]**, `NatsClusterRpc.java:111-140`, `nats.yaml:37-40` |
| C5 | **High** | **A dead NATS connection takes minutes to detect.** No `pingInterval` or `maxPingsOut` is set, so jnats defaults apply (2 min × 2). A NATS pod lost without a TCP reset leaves EDDI "connected" for minutes while every KV call times out, so turns get 409 after the 45 s acquire timeout. This contradicts "one NATS node lost: nothing visible". | `NatsConnectionManager.java:176-186` **[V]** |
| C6 | **High** | **Duplicate node ids are neither detected nor refused.** Twins see each other's leases as "our earlier boot", delete them on every sweep and steal them on contention, so mutual exclusion is gone. Presence flaps and RPC replies go to whichever twin answers. Triggers: a constant `EDDI_CLUSTER_NODE_ID` or `eddi.schedule.instance-id` (a documented fallback), or the same pod names under one prefix. | `NodeIdentity.java:199-217`, `ClusterPresence.java:88`, `KvLeaseManager.java:378,864` |
| C7 | Medium | **Turns are refused from the moment of SIGTERM, and there is no `preStop` hook.** POSTs that arrive while the endpoint is still being removed get 503 on every rolling update and every HPA scale-in. | `GracefulShutdownService.java:168-184`, `deployment.yaml` |
| C8 | Medium | **The `k8s/overlays/nats` overlay is unhardened**, yet the "production" postgres-ha example composes it. It has no client or route authentication, no TLS and no `max_file_store`. | `k8s/overlays/nats/nats-statefulset.yaml` |
| C9 | Medium | **JetStream storage is not guarded.** `ARCHIVES` has no `maxBytes` and shares storage with `LEASES`, so a large export can block lease creates. `max_file_store` equals the PVC size. | `ClusterArchiveStore.java:68`, `nats.yaml:29` |
| C10 | Medium | **NATS StatefulSet gaps.** <ul><li>Anti-affinity is only preferred, with no zone spread.</li><li>`nats:2.11-alpine` is not pinned to a digest, against the repo's own policy.</li><li>No metrics exporter or ServiceMonitor, no `GOMEMLIMIT`.</li><li>A single password, so it cannot be rotated without downtime.</li></ul> | `nats.yaml:112-121`, `values.yaml:452` |
| C11 | Medium | **Two deployments on one NATS are not isolated.** The prefix only names things. Shared `$JS.API.>`, `$KV.*.>` and `_INBOX.>` permissions let one deployment read the other's replies and delete its buckets. | `NatsConnectionManager.java` |
| C12 | Medium | **Alerting gaps.** <ul><li>The chart's PrometheusRule ships no cluster rules.</li><li>The fence-rejected and acquire-timeout meters are registered lazily, so `increase()` misses the first occurrence on each pod.</li><li>No alerts for member loss, takeovers, outbox overflow, JetStream storage, a full dead-letter stream or slow consumers.</li></ul> | `ConversationService.java:2983`, `KvLeaseManager.java:455` |
| C13 | Medium | **The Helm ingress handles neither `/mcp` affinity nor SSE.** It has no affinity for `/mcp` and leaves the 60 s read timeout, which the docs say must be at least 120 s. | `helm/eddi/templates/ingress.yaml` |
| C14 | Medium | **The default HPA scales on memory as well as CPU.** Its memory target (80 % of a 3Gi request) ratchets a JVM deployment up to `maxReplicas`. | `values.yaml:593` |
| C15 | Low | **Smaller gaps.** <ul><li>No configuration validation for: acquire timeout versus TTL versus agent timeout, the degraded grace, non-positive durations, auth methods set together, or replicas above the NATS cluster size.</li><li>Payloads are unversioned (the event field `v` is never checked), so mixed versions during a rolling upgrade are unmanaged.</li><li>No `traceparent` on RPCs.</li><li>Plain-text passwords in `nats.conf`.</li></ul> | `ClusterConfig.java:198-219`, `JetStreamEventBus.java:321` |

## D. Scalability and performance

| ID | Sev | Finding | Where |
|---|---|---|---|
| D1 | **High** | **About 12 serial round trips to the LEASES leader per turn**; the docs say "about four". <ul><li>Release writes a delete marker, so the next `create` costs 3 round trips inside jnats.</li><li>Release itself costs 3 more: the compare-and-set delete, the notification, and an unconditional waiter-marker read.</li><li>Each audit entry costs a get plus an update, at about 3.2 entries per turn.</li></ul> The measured 61–86 NATS messages per turn match this. | `KvLeaseManager.java:301,513-517` **[V]**, `AuditClusterSupport.java:102-112` |
| D2 | **High** | **Round-robin routing makes lease contention.** The reply goes out before persist and release, so the next turn on another node finds the lease still held: 13–32 % of turns measured. The release notification is at-most-once core NATS, with a 5 s poll fallback (`ttl/4`). Every contended turn also pays a full memory reload. | `ClusterConversationCoordinator.java:188`, `KvLeaseManager.java:356` |
| D3 | **High** | **A bucket-reopen storm.** Any failed call, including a full outgoing queue, drops the cached handle. Every concurrent caller then reopens the bucket at once, each sending STREAM.INFO, unsynchronized. | `NatsSharedKv.java:97-128` **[V]** |
| D4 | **High** | **Heartbeats do not scale to thousands of leases.** <ul><li>Each pass starts one virtual thread per held lease and sends them all at once. 5,000 leases fill jnats' 5,000-message outgoing queue, failures are swallowed, and D3 follows.</li><li>All renewals of the cluster land on one RAFT leader.</li><li>A slow leader costs many leases at once.</li></ul> | `KvLeaseManager.java:775-800` |
| D5 | **High** | **Every reconnect scans the whole LEASES bucket on every node.** `sweepOwnStaleLeases` lists all keys cluster-wide, then gets each one serially, on every node at once. | `KvLeaseManager.java:853-873` |
| D6 | Medium | **Housekeeping does not scale with node count.** <ul><li>Presence is polled, which costs O(N²) cluster-wide.</li><li>About 11 periodic jobs share 2 platform threads, so blocked jobs can delay presence writes past their 30 s TTL. Peers are then judged dead and their leases taken.</li><li>The Manager's lease page scans 5,000 keys plus up to 500 consistent reads every 5 s per open tab, on the leader that serves every turn.</li></ul> | `ClusterPresence.java:146`, `NatsConnectionManager.java:133`, `ClusterAdminService.java:503` |
| D7 | Medium | **Fan-out and stampedes.** <ul><li>Every node receives every `lease.released` notification.</li><li>A resync flushes all caches on all nodes simultaneously.</li><li>Every node calls `updateStream` on every reconnect.</li></ul> | `NatsLeaseManager.java:85`, `JetStreamEventBus.java:255-309` |
| D8 | Medium | **Other scale limits.** <ul><li>A group discussion runs entirely on one node, a hot spot never load-tested.</li><li>Memory snapshots held while waiting (up to 45 s) are uncapped.</li><li>The documented NATS sizing ("thousands of turns/s per vCPU") is about 10× too optimistic; measured 140–220 turns/s.</li><li>Reads on the leader only (needed for correctness) make one NATS server per bucket the hot spot.</li></ul> | `clustering.md:118-122` |

## E. Test evidence

| Test | NATS | Database | What it actually proves |
|---|---|---|---|
| `KvLeaseManagerTest`, `KvLeaseManagerAdminTest`, `ClusterConversationCoordinatorTest` and the other unit tests | `InMemorySharedKv` fake or mocks | none | Logic only. The fake cannot model a write that was applied but whose reply timed out, latency, or a full queue, so A2, D3 and D4 cannot be reproduced there |
| Mongo and Postgres store fence tests | none | **real** | Stale writes are fenced, in one process. Not covered: an unfenced full write after a raise (A4) |
| `ClusterCoordinatorIT` (runs in CI) | **real, but a single R1 server** | none (synthetic turns) | Several nodes inside one JVM: no overlap, takeover, events, RPC, dead letters, an outage, bucket recreation |
| `scripts/cluster-demo` (13 scenarios) | real R3 | real | **Not in CI, no recorded results** |
| Load tests | real R3 | real | Run on one saturated laptop VM; the two N=2 runs differ by 4× |

Never exercised anywhere: several JVMs together with a real database, network fault injection, partition or GC pause, failover of the NATS stream leader under load, a soak test, scale on separate hosts, version skew, and group or HITL flows across nodes.

---

## Plan to production

Each phase lands as reviewable PRs (one concern each), with a changelog fragment, and with the docs corrected in the same PR.

### Phase 1: correctness (blocks GA)
1. **Lease validity (A1, A2).**
   - Track the last successful renewal, and lose the lease locally at `ttl − 2×heartbeat`.
   - On a compare-and-set conflict, re-read the key and adopt the lease if it still names this node and boot.
   - Adopt or delete an orphan created by this node and boot.
   - Run `deleteIfStillOurs` for lost handles too.
   - Check the lease is still valid before the user-memory upsert and before rendering the reply.
   - Rewrite `heartbeatIoFailureKeepsLease`.
2. **Fence first (A3).**
   - Raise the fence before any state read, reload or idempotency check.
   - If raising fails, answer 409 and keep the input unconsumed.
3. **Every write path fenced (A4, A6).**
   - Write `_fence` only with `$max` or `GREATEST` on full writes.
   - Run undo/redo through `submitInOrder`.
   - Map a fence-caused compare-and-set miss to `ConversationFencedException`, which is dead-lettered.
   - Dead-letter `ConcurrentConversationModificationException` after a reply has gone out.
4. **HITL resume on lease loss (A5).** Dead-letter it, then restore AWAITING_HUMAN with a compare-and-set, but only if no newer holder has written.
5. **Replay (A7, A9, A10).**
   - Delete a dead letter only when the replayed turn commits.
   - Route the legacy endpoint through `replayOne` (claim, audit, actor).
   - Give each replay an `Idempotency-Key` derived from the entry id.
   - Persist the node-local ring to disk.
   - Do not dead-letter a turn that was answered "retry".
6. **Deployments (B1).** Record transient deployments in the database with a status and an epoch, reconcile them in the sweep, and drop stale events.
7. **Event bus (B2, B3).**
   - Resync once after the first subscribe.
   - Detect a recreated stream.
   - Fill caches by read-through without announcing; announce only mutations.
   - Bound the dispatch queue.
8. **Node identity (C6).** `create` the presence key at boot and refuse to become ready on a live duplicate. Never fall back to the schedule instance id silently.

### Phase 2: security and operations (blocks GA)
1. **TLS (C1, C2b).**
   - Always build EDDI's own verifying context for `tls://` and `wss://`.
   - Reject `opentls://`.
   - Accept PEM truststores.
   - Have the chart mount the CA.
   - Add a chart test with a cert-manager-style secret.
2. **Fail loudly (C2a, C5).**
   - Auth or TLS errors, and "never connected since boot", fail readiness, or the time a node may run unfenced is capped. Default `degraded.turns=reject` for production profiles.
   - Log causes at WARN or ERROR.
   - Add `eddi_cluster_nats_errors_total{cause}`.
   - Set `pingInterval` to about 5 s with `maxPingsOut(2)`.
3. **Replication (C3).** Add a chart value for external NATS defaulting to R3, and warn at boot when a cluster of 3 or more servers is running R1.
4. **Control plane (C4, C11).**
   - Per-node NKey or JWT users with least-privilege subject permissions (no `STREAM.DELETE`).
   - One NATS account per deployment, with a custom inbox prefix.
   - Sign RPC and event messages with an HMAC key kept outside NATS.
   - Admin RPCs re-check the last-node guard on the receiving side and write to the audit ledger.
5. **Kubernetes (C7–C10, C13, C14).**
   - A `preStop` sleep, and keep admitting turns during the readiness grace.
   - Harden the kustomize NATS overlay.
   - Give `ARCHIVES` a `maxBytes` and leave filesystem headroom.
   - NATS: digest-pinned image, required anti-affinity plus zone spread, exporter and ServiceMonitor, `GOMEMLIMIT`, rotatable credentials.
   - Ingress: `/mcp` affinity and SSE timeouts.
   - Drop the memory metric from the HPA defaults.
6. **Observability (C12).** Ship the cluster PrometheusRule from the chart, register meters eagerly, and add the missing alerts.
7. **Configuration and upgrades (C15, B4, B5).**
   - Validate timing and auth combinations at boot.
   - Version every NATS payload and add a warning for mixed versions.
   - Document which versions may run side by side in a rolling upgrade.
   - Make webhook dedup fail closed.
   - Fix the gdpr-stop count and timeout.
   - Use HMAC instead of plain hashes for user ids.
   - Seed the audit position counter with a margin.

### Phase 3: evidence (blocks GA claims)
1. **A multi-JVM end-to-end suite.**
   - Three EDDI JVMs, an R3 NATS, real Mongo and real Postgres.
   - A history checker (Elle or Knossos style) on per-conversation step sequences.
   - Toxiproxy between EDDI and NATS: latency, timeouts after commit, half-open connections.
   - SIGSTOP of a holder for longer than the TTL, and a partition of one node.
   - kill -9, and failover of the NATS stream leader under load.
   - Runs nightly in CI, plus on every PR that touches `engine/cluster/**`.
2. **`cluster-demo` in CI** (nightly), with its results committed.
3. **Rolling upgrade with version skew,** drain handoff, and HITL and group flows across nodes.
4. **A 24–72 h soak test with churn:** heap, threads, subscriptions, bucket and stream growth.
5. **Scale test:** 10–20 EDDI nodes on separate hosts, NATS on dedicated hosts, 5,000 held leases per node. Then fix the sizing section of the docs from the measured numbers.

### Phase 4: scale and efficiency
1. **Fewer round trips per turn (D1).**
   - Acquire with an expected-last-sequence publish, or release by `update` to a free value instead of a delete.
   - Drop the waiter-marker read.
   - Cache the audit counter while the lease is held.
   - Target: about 5 round trips per turn.
2. **Less contention (D2).**
   - Consistent-hash on conversationId in the reference ingress configs (for performance only, not correctness).
   - Hand the lease straight to the next turn queued on the same node.
   - Cut the poll fallback to about 250–500 ms.
   - Load memory after the lease is granted.
3. **Resilience at scale (D3–D5).**
   - Reopen bucket handles once, under a lock, only on errors that mean the handle is stale.
   - Bounded, spread-out renewals, or a per-node epoch key.
   - A per-node sweep scoped to this node's own leases.
   - Shard LEASES by hash prefix.
4. **Housekeeping (D6–D8).**
   - Watch presence instead of polling it.
   - Run blocking jobs on virtual threads.
   - Cache the admin console's reads on the server.
   - Address release notifications to the waiting node.
   - Add jitter to resyncs.
   - Distribute group discussions, or at least load-test them.

### Docs to correct now, whatever the schedule
These statements in `clustering.md` go further than the code:
- "never interleaved" (A3, undo/redo in A4);
- the partition-cancellation claim (A1);
- "no loss" in degraded mode (A8);
- "a busy node does not lose its leases" (A2, D4);
- "refused turn is dead-lettered" (A6);
- "one NATS node lost: nothing visible" (C3, C5);
- "about four KV operations" and the NATS sizing (D1, D8);
- the incomplete cache-staleness list and "security state always fails closed" (B4).

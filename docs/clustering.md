# Clustering: horizontal scaling on NATS JetStream

One published image, selected at runtime:

- `eddi.messaging.type=in-memory` (the default) — a single node, no
  dependencies. Nothing described on this page is active; no NATS class is
  even instantiated.
- `eddi.messaging.type=nats` — any number of EDDI replicas behind a **plain
  round-robin load balancer** (no sticky sessions), sharing one database
  (MongoDB or PostgreSQL) and one NATS JetStream cluster.

Any other value fails the boot. Every property is in the
[Configuration Reference](configuration-reference.md#clustering-only-when-eddimessagingtypenats).

## Topology

```
            clients
               │
        ┌──────▼──────┐   round robin, no affinity
        │ load balancer│  (SSE: proxy_buffering off, read timeout ≥ 120 s)
        └──┬───┬───┬──┘
           │   │   │
      ┌────▼┐┌─▼──┐┌▼────┐
      │EDDI ││EDDI││EDDI │   N replicas, same image, eddi.cluster.node-id unique
      └─┬──┬┘└┬──┬┘└┬──┬─┘
        │  └──┼──┼──┘  │
        │  ┌──▼──▼──┐  │      NATS JetStream cluster (3 nodes, R3):
        │  │  NATS  │◄─┘      KV buckets, event stream, dead letters, RPC
        │  └────────┘
        ▼
   MongoDB / PostgreSQL       the only source of truth for conversations
```

Everything in NATS is coordination or a copy: losing the whole NATS cluster
loses no conversation, no configuration and no audit entry the database has
stored. The exception is an audit entry the database refused while NATS was
up: it is kept only in the dead-letter stream, so losing NATS within the
dead-letter retention loses it.

## What stays where

A turn **runs on the node that received the request** — SSE streaming, the
caller's identity, live group discussions and HITL keep working exactly as on
one node. NATS adds:

| Concern | Mechanism |
|---|---|
| Turns of one conversation never overlap anywhere | A conversation **lease** in the `<prefix>_LEASES` KV bucket (a write that succeeds only while the key holds no lease = acquire, heartbeat every `lease.heartbeat-interval`, server-side TTL `lease.ttl`). Turns on one node stay FIFO; the next turn queued on the same node takes the lease over directly, without a release and a new acquire, unless another node is waiting for it. |
| A node that lost its lease cannot overwrite newer turns | The lease revision is a **fencing token**; both conversation stores refuse a write older than the `_fence` the document carries — an ordinary turn's write, a pause or resume commit and an undo or redo alike. The refused turn is dead-lettered with its input (`reason: fenced`, both tokens). A turn raises the fence to its own token **before** it reads the conversation, and a turn that cannot raise it does not run (409 + `Retry-After`, input not consumed). No write moves the fence backwards, a write without a token (a degraded-mode turn, a schedule) included. Undo and redo run through the coordinator, under the lease, like a turn. |
| A turn that lost its lease does not vanish | Losing the lease cancels the turn at its next task boundary — and inside an LLM task's tool loop before its next model call or tool call, so a long agent loop does not keep spending and calling tools for a conversation another node now runs (the one call already in flight finishes). The turn asks the lease itself, not only the lease manager's callback, so a lease that can no longer be trusted stops it at once; in particular it then skips the long-term user-memory write, which is not fenced. It stores nothing, stamps no state, and is dead-lettered with its input (`reason: lease-lost`, its fencing token, `failedOn` the node). If its reply has not gone out yet, its caller is told it was not processed: 409 with `Retry-After` over REST, an error event on a stream. A turn whose reply went out and whose write then lost to another writer is dead-lettered too (`reason: conflict`). A HITL resume that loses its lease restores the pause at once (only while no newer holder has started on the conversation) and is dead-lettered; the decision has to be given again, and tools it had already executed run a second time on re-approval. |
| A crashed node does not block a conversation | Its lease expires (≤ `lease.ttl`), or is taken over at once when the node's presence record is gone or shows a new boot. |
| Caches stay coherent | Cluster events on the `<prefix>_EVENTS` stream: secrets, global variables, connections, deployments, deleted workflows, agent triggers, user conversations, conversation states, GDPR restriction verdicts. Only a change is announced — a cache filled from the database is not. A node that may have missed events (gap, long disconnect, outbox overflow, its first subscription after boot, an event stream that was deleted and recreated) flushes everything. Cache evictions and the other events are handled in two separate bounded queues, so a burst of evictions does not delay a secret rotation. |
| Deploy and undeploy reach every node | A `deployment.changed` event (about a second), plus a two-way reconciliation sweep every 10 s that undeploys what the database no longer lists as deployed. A deploy with `autoDeploy=false` (deliberately not written to the deployment collection) is recorded in the `<prefix>_DEPLOYMENTS` KV bucket instead — deployed, or a tombstone once undeployed — and the sweep reconciles it like a recorded one, so a node that joins later deploys it and a lost undeploy event is still applied. The record lasts while the cluster runs and is forgotten once no node has served it for `eddi.cluster.deployments.transient-ttl`. An event older than the records (an undeploy that arrives after the redeploy that followed it) is dropped. A request that reaches a node before its sweep has caught up deploys the agent there on demand and waits for it (at most 15 s) instead of answering 404. |
| Exported agent archives download through any node | The archive is also copied to the `<prefix>_ARCHIVES` object store for the export retention; a node without the file fetches it from there. |
| Cancel/end/GDPR stop reach the running turn | Node-addressed RPC to the lease holder; GDPR stop is a scatter to every node; the user travels as a hash — sealed with the cluster signing key when one is set — and the erasure waits up to `eddi.cluster.gdpr-stop-timeout` (10 s) for every member of a fresh member count. Group-discussion cancel is forwarded to the node holding the discussion's `g.<id>` lease; a second node does not start a discussion whose lease a live node holds (only while NATS is unreachable does a discussion run without it). The lease is not a fence, though: a discussion's writes carry no fencing token and a holder that loses its lease is not stopped, so if it is cut off past `lease.ttl` and another node takes the lease, both can act on the discussion until the first one finishes its current leg. |
| Security and tool state is cluster-wide | KV buckets: replay nonces (`NONCES`), tool rate limits (`RATELIMIT`), per-conversation cost totals (`COSTS`), A2A task mappings (`A2A_*`), paginated tool responses (`TOOL_PAGES`), Slack event and webhook delivery de-duplication (`DEDUP`), channel thread locks (`CHANNEL`), audit chain positions (`AUDIT_SEQ`), knowledge-base ingestion statuses (`RAG_INGESTION`, so an ingestion's status answers on any node). |
| Dead letters are shared | The `EDDI_DEAD_LETTERS` stream: every node lists, discards and replays the same entries. It is capped by age (`dead-letter.max-age`) and size (`dead-letter.max-bytes`, 1 GiB); when full it refuses new entries, which stay in the node-local ring and are forwarded once there is room. That ring is held **in memory only**, capped by `eddi.coordinator.max-dead-letters` (1000, oldest evicted): entries beyond the cap are dropped, and a restart of the node loses all of them. Forward them from the console once NATS is back. A locally kept entry is never forwarded for a conversation that a GDPR erasure marked (`ERASED` bucket) or that no longer exists; if the marker cannot be read (NATS dropped again) the entry stays on the node and is retried. |
| A retried request is still one turn | An `Idempotency-Key` duplicate that reaches another node while the first request runs queues for the lease and, holding it and having raised the fence, finds the key already recorded by the turn that just committed: it is answered 409 + `Retry-After` instead of running again, and its retry gets the stored answer. A turn the runtime refused after its lease was granted is answered the same way, and its dead letter cannot be replayed (`ANSWERED_RETRY`), so the retry and a replay never both run it. |
| HITL crash recovery does not hit live turns | No recovery at boot; a leader-elected sweep recovers only conversations without a lease that stayed unchanged for `hitl-recovery.min-age`. A drained node hands its leader lease over at once. |
| A busy node keeps its leases while NATS answers | Lease heartbeats run on a scheduler thread of their own and renew the leases due side by side, at most 64 at a time, the least recently confirmed first; reconnect work runs on separate threads. A renewal the server applied but whose reply was lost (a timeout, a NATS leader change) is recognised by the lease id in the value and kept, not reported as a takeover. What no heartbeat can do is keep a lease whose renewals are not acknowledged: see the next row. |
| A holder cut off from NATS stops itself | A lease is trusted for `lease.ttl − 2 × lease.heartbeat-interval` (10 s by default) after the last renewal NATS acknowledged, counted from when that renewal was sent. Past that it counts as lost on its node — whether NATS is unreachable, slow, or answering — and the running turn is cancelled at its next task boundary, before any other node can take the conversation over at `lease.ttl`. |

## Ordering contract

- While NATS is healthy, turns of one conversation never overlap anywhere in
  the cluster.
- Each turn runs on, and commits on top of, every turn committed before it
  (the turn reloads a superseded snapshot; its write carries the fence).
- Turns arriving at one node run in arrival order.
- Turns sent concurrently to different nodes are serialized in lease
  acquisition order — any order is a valid serialization. A turn raises the
  conversation's fence before it reads it, so a holder whose lease expired can
  no longer slip a step in between; undo and redo take the lease like a turn.
  What the fence does not cover: an unfenced writer (a turn run in degraded mode,
  see below) and side effects outside the conversation document (tool calls,
  external APIs) of a holder that lost its lease mid-turn.
- A client that waits for each response before sending the next gets strict
  program order.
- A turn that waited longer than `lease.acquire-timeout` for another node is
  answered **409 with `Retry-After: 2`**; its input was not consumed.

## Degraded mode (NATS unreachable)

A disconnection shorter than `eddi.cluster.degraded.grace` (5 s) is ridden
out. Longer, each area applies its policy:

| Area | Default | Alternative |
|---|---|---|
| Turns | run with node-local ordering only, **unfenced**: steps appended concurrently on different nodes merge, but the conversation properties a turn writes are last-writer-wins, so concurrent turns can overwrite each other's properties and miss each other's context | `degraded.turns=reject`: 409 + `Retry-After` |
| Replay nonces | **reject** (fail closed): a nonce can only be registered cluster-wide, so a signature that needs a fresh one is refused. Inter-agent signing in group discussions then records the entry **unsigned** (the discussion carries on); nothing else uses nonces | `degraded.nonces=local` |
| Tool rate limits | `local-share`: this node enforces the global limit ÷ known members, the per-conversation limit in full | `degraded.rate-limits=reject` |
| Cost budgets | local total | — |
| A2A mappings, tool pages, Slack dedup, thread locks | node-local copy | — |
| Webhook delivery de-duplication (the only replay protection of the `github` and `generic-hmac` schemes) | follows `degraded.nonces`: **reject** with 503 + `Retry-After`, the sender retries | `degraded.nonces=local` |
| Audit chain positions | entries recorded unsequenced (verification: UNAVAILABLE, never BROKEN) | — |
| Cache events | outbox, flushed on reconnect (and every 2 s while connected, for a publish that failed without a disconnect); overflow ⇒ cluster-wide resync, coalesced per node so N nodes asking at once cause one flush each | — |
| Cancel / GDPR stop | this node + database CAS only | — |
| HITL recovery | paused | — |

Readiness stays UP during an ordinary outage — a NATS outage that took every
replica out of the load balancer at once would turn a coordination outage into
a total one. Set `eddi.cluster.readiness.require-nats=true` to change that.
Liveness never depends on NATS. Boot never waits for NATS either, but two
states are **not** an outage, and report the node not-ready by default:

- **Never connected since boot** (`eddi.cluster.readiness.require-first-connect`,
  default `true`). A node that has not reached NATS once cannot take a single
  lease, and a wrong URL used to keep it serving every turn unfenced, forever,
  while every check stayed green. A node that *has* connected stays ready
  through a later outage. Set it to `false` to serve degraded from boot (a
  cluster that must come up while NATS is down).
- **A failure retrying cannot heal** (`eddi.cluster.readiness.fail-on-misconfiguration`,
  default `true`): an authentication or authorization error (a rotated
  password), a TLS failure (an untrusted CA, a certificate that does not name
  the host), or client options that could not be built (an unreadable trust
  store). The cause is logged at ERROR, counted in
  `eddi_cluster_nats_errors_total{cause}` and raised as
  `EddiClusterNatsMisconfigured`. This deliberately takes every node with the
  same bad credential out of the load balancer: loud, instead of every turn
  running unfenced.

**Recommended for production: `degraded.turns=reject`.** The default, `local`,
keeps serving through an outage at the price of running turns unfenced. The
Helm chart takes it as `eddi.cluster.degradedTurns`, and the Kustomize `nats`
component sets `reject`.

A node partitioned from NATS while the others are connected can run unfenced
turns concurrently with fenced turns elsewhere. Operators who cannot accept
that set `degraded.turns=reject`. Replay protection fails closed by default (nonces, webhook
de-duplication). Cached security state does not: a node that missed an invalidation keeps
using a rotated secret for up to 5 min (`eddi.vault.cache-ttl-minutes`), a changed connection
for up to 1 min and a changed global variable for up to 2 min (`eddi.variables.cache-ttl-minutes`).

## Failure modes

| Event | Effect |
|---|---|
| `kill -9` of an EDDI node mid-turn | Its clients see a reset. The conversation's lease expires within `lease.ttl` (or is taken over immediately after the pod restarts under the same name). The next turn runs normally; a late write from the dead node is refused by the fence. |
| Two replicas started with the same node id (a constant `EDDI_CLUSTER_NODE_ID`, a shared `eddi.schedule.instance-id`) | The second one finds the first one's live presence record, stops announcing itself, logs an ERROR, reports `eddi_cluster_node_duplicate 1` and its readiness (`cluster-node-identity`) goes DOWN until the other one is gone. A pod restarted under its own name is not a duplicate: its previous boot's record stops changing and is taken over after one presence interval. |
| One NATS node lost | With R3 buckets and streams (`eddi.nats.replicas=3`: the in-chart three-node NATS sets it, `nats.externalReplicas` defaults to 3 for an external one), JetStream keeps quorum. The EDDI nodes connected to the lost server notice through missed pings — `eddi.nats.ping-interval` × `max-pings-out`, about 10–15 s when the server vanished without a TCP reset — and reconnect to another; KV calls in that window time out, so a turn can be answered 409 + `Retry-After`. With R1, the buckets and streams that lived on that server are unavailable or lost (EDDI warns at boot when R1 meets a NATS cluster of three or more servers). |
| Wrong NATS credentials, a rotated password, a certificate that does not match | Not an outage: the node reports not-ready, logs the cause at ERROR and counts it in `eddi_cluster_nats_errors_total` (cause `auth`, `tls` or `config`); `EddiClusterNatsMisconfigured` fires. Fix the credential or the certificate; the node becomes ready on its next successful connect. |
| All NATS lost | Degraded mode as above; automatic recovery and resync when NATS returns. |
| NATS accepts connections but stops answering | Calls fail after `eddi.nats.request-timeout` (2 s) each. A failed turn's dead letter tries the stream for at most about 2 s before it is kept node-local; audit positions stop being requested for one timeout after a failure (entries are recorded unsequenced meanwhile), so a turn does not wait out one timeout per entry. |
| Rolling update | The chart's `preStop` sleep (5 s) lets the endpoint removal reach every proxy; readiness then goes DOWN, turns still routed here are admitted for the readiness grace (3 s), and the node drains (`eddi.shutdown.drain-timeout-seconds`), releases its leases (side by side, within a few NATS timeouts; what it misses expires by TTL) and leaves presence; queued turns that never got a lease are answered 409 + `Retry-After` and retried by clients on another node. |
| Lease lost while a turn runs (long GC pause, partition, an administrator releasing it) | The turn is cancelled at the next task boundary (or the next round of an LLM tool loop) and dead-lettered (`lease-lost`); it does not write long-term user memory. Its caller gets 409 + `Retry-After` unless the reply had already gone out. If it still reaches a write, the fence refuses it and it is dead-lettered (`fenced`). How soon a partitioned holder notices depends on the lease manager declaring the lease lost (see `lease.ttl`); a model or tool call already in flight finishes. |

## Sizing

- **NATS**: three nodes, `eddi.nats.replicas=3`, file storage. The lease of an
  uncontended turn costs three round trips to the `LEASES` stream leader (read
  the key, write at the revision of the last release's delete marker, delete),
  none for a turn queued behind another on the same node, plus one renewal
  every 3.75–5 s for long turns. A contended turn adds one read per poll (every
  250–750 ms) and a waiter marker; a waiter woken by the holder's release
  acquires with one write. Each audit entry adds a read and a compare-and-set. A NATS node with 1 vCPU and 1 GiB is
  ample for thousands of turns per second.
- **EDDI**: unchanged per-node sizing; add replicas for throughput. The
  database is the shared bottleneck.
- **Database connections**: every replica opens its own pool, so the database
  sees *replicas × pool size*. The defaults are the drivers': MongoDB 100 per
  node (`maxPoolSize` in the connection string), PostgreSQL 50 per node
  (`quarkus.datasource.jdbc.max-size`). A stock PostgreSQL allows 100
  connections in total (`max_connections`), so a third replica at the default
  pool already fails to connect under load. Either lower the pool per node —
  turns spend most of their time waiting on the LLM, not holding a connection,
  so 10–20 is usually enough — raise `max_connections`, or put PgBouncer in
  front in **session** pooling mode. Transaction pooling breaks EDDI: it takes
  session-level advisory locks (the startup lock that runs the schema migrations
  one replica at a time, and maintenance jobs such as the audit store's). Size
  for the HPA's `maxReplicas`, not for the current replica count.
- **Timings**: keep `lease.acquire-timeout` (45 s) below
  `systemRuntime.agentTimeoutInSeconds` (60 s) and above `lease.ttl` (20 s).
  An agent's turn deadline (`turnDeadlineMs`, or the caller's `X-EDDI-Turn-Deadline-Ms`)
  counts from the request's arrival, so time a turn spends waiting for its lease is
  spent from that budget too.

### Autoscaling

A node that waits on LLM answers runs hundreds of turns at little CPU, so an HPA on
CPU alone may never scale up while turns queue or are refused with 503
`capacity_exceeded`. Scale on in-flight turns per pod instead:
`eddi_runtime_executor_active{pool="turn"}` against the node's limit
`eddi_conversation_max_in_flight_turns` (about 1000 on a 3 GiB pod). Expose it to the
HPA with a custom-metrics adapter (prometheus-adapter or KEDA's Prometheus scaler) and
add it under the chart's `autoscaling.extraMetrics`; a target of about 70 % of the limit
leaves room for the turns that arrive while a new pod starts. `autoscaling.behavior`
takes scale-down stabilization — scaling in drains a pod for up to
`eddi.shutdown.drain-timeout-seconds`, so avoid flapping.

## Runbook

The Manager's **Cluster** screen answers most of these at a glance: the health verdict with
its reasons, one card per node, the leases with suspicious ones flagged, the dead letters
filterable by reason (`fenced`, `lease-lost`, `timeout`, `failed`), node and agent, a cluster-wide activity
timeline, a "why is this conversation stuck?" lookup, and the recovery actions — force-release
a lease, resync caches, reconcile deployments, drain a node, forward local dead letters. The
API behind it, and why each action is safe, is in
[Coordinator Admin](coordinator-admin.md#the-cluster-console).

| Symptom | Look at | Action |
|---|---|---|
| `eddi_cluster_degraded == 1` | `/q/health/ready` (`cluster` check: `nats`, `degradedSince` — reported only with `eddi.health.verbose=true`), `GET /administration/cluster/overview`, node log | Restore NATS reachability; the node recovers by itself. |
| Rising `eddi_cluster_lease_acquire_seconds_count{outcome="timeout"}` | `GET /administration/coordinator/status?scope=cluster` (queue depths per node) | A conversation is hammered from several clients at once, or turns are slower than the acquire timeout. |
| `eddi_cluster_fence_rejected_total` increases | Dead letters | Every refusal is a turn that was dead-lettered; replay or discard it. Frequent refusals mean nodes lose leases — check GC pauses and NATS latency (`natsRtt` in presence). |
| `eddi_coordinator_dead_letters > 0` | `GET /administration/coordinator/dead-letters` | Replay (`POST …/{id}/replay`) or discard (`DELETE …/{id}`) — see [Coordinator Admin](coordinator-admin.md). |
| An agent still answers after undeploy | Node log ("Undeployed agent … on this node"), `deployment.propagated` entries on the activity timeline | It is undeployed within ~1 s (event) or 10–20 s (sweep); *Reconcile deployments* runs the sweep on every node at once. |
| A conversation answers 409 for a long time | The console's stuck-conversation lookup, the lease list (`HOLDER_GONE`, `NOT_RENEWED`, `LONG_RUNNING`) | Wait for the lease TTL, or force-release it — the fence keeps a late write of the old holder out. |
| A node must be restarted without failed turns | Its node card | *Drain* it: no new turns reach it (409 + `Retry-After`, readiness `DOWN`), running ones finish, and a leader lease it held passes to another node; restart it, or *Undrain*. |

The JetStream objects are all named `<prefix>_<NAME>`, where the prefix is
`eddi.nats.prefix` (`EDDI` by default). KV buckets: `LEASES`, `NODES`, `ADMIN` (console
replay claims and drain state), `NONCES`, `RATELIMIT`, `COSTS`, `AUDIT_SEQ`, `A2A_TASKS`,
`A2A_CONTEXTS`, `DEPLOYMENTS` (the `autoDeploy=false` deployments and their undeploy tombstones), `ERASED` (the GDPR erasure marker of a conversation: written before its dead letters are purged, and checked by a node that forwards a dead letter it kept locally; it expires after `eddi.cluster.erasure-marker-ttl`, `P7D`), `A2A_ERASING` (the GDPR erasure marker that closes the A2A task store to a
principal's writes on every node), `A2A_INDEX` (per principal, the task and context ids it
holds — how a GDPR sweep or export on one node reaches records another node wrote, since
bucket keys are hashed and cannot be enumerated by principal), `TOOL_PAGES`, `DEDUP`,
`CHANNEL` and `RAG_INGESTION`; object store: `ARCHIVES`;
streams: `EVENTS`, `DEAD_LETTERS` and `ACTIVITY` (the console's timeline, subjects
`eddi.<prefix>.ops.>`). EDDI's own subjects live under `eddi.<prefix>.>`.

A NATS user restricted to EDDI needs:

- **publish:** `eddi.>`, `$JS.API.>`, `$KV.*.>`, `$O.*.>`, `$JS.ACK.>`, `$JS.FC.>` and `<inbox>.>`;
- **subscribe:** `eddi.>`, `$JS.API.>`, `$KV.*.>` and `<inbox>.>`,

where `<inbox>` is `eddi.nats.inbox-prefix` (`_INBOX` by default). EDDI never deletes a
stream, so deny it **publish** to `$JS.API.STREAM.DELETE.>`, `$JS.API.SERVER.>`,
`$JS.API.META.>` and `$JS.API.ACCOUNT.PURGE.>` — the one credential every pod holds then
cannot wipe the lease, nonce and audit-position buckets of the cluster. (It still purges
dead letters, through `$JS.API.STREAM.PURGE.>`.) The Helm chart's in-chart NATS and the
Kustomize `nats` component grant exactly this set. Without `$O` access, exported archives stay on the node that made them.

**Two deployments on one NATS** are isolated by accounts, not by the prefix: the prefix
only names things, and `$JS.API.>`, `$KV.*.>` and `_INBOX.>` reach across it. Give each
deployment its own NATS account (JetStream enabled), and its own `eddi.nats.inbox-prefix`
named in its permissions. The chart's `nats.isolation.enabled=true` does both for the
in-chart NATS (account `EDDI`, inbox `_INBOX_EDDI`); it moves the buckets into the new
account, so enable it on a fresh install. Per-node identities (one NKey or JWT user per
pod, `eddi.nats.creds-file`) need an external NATS with its own account management.

A NATS wildcard matches whole tokens only, and a KV subject is
`$KV.<bucket>.<key>` with the bucket as one token. So `$KV.EDDI_>` and
`$KV.EDDI_*.>` match no bucket at all: every KV write is refused (verified on
NATS 2.11). To narrow an account shared with other applications, replace
`$KV.*.>` with one `$KV.<prefix>_<BUCKET>.>` per bucket above, and `$O.*.>` with
`$O.<prefix>_ARCHIVES.>`.

**NATS TLS verifies the broker's host name** (`eddi.nats.tls.verify-hostname`, default
`true`) — for `eddi.nats.tls.enabled=true` **and** for every `tls://` or `wss://` server.
The certificate must name one of the hosts in `eddi.nats.url`, and the check is made
against those configured names, whichever address the client ends up dialling — so it
also holds for servers jnats learns from the cluster's INFO and dials by IP. The node
dials the host name itself (jnats `HappyEyeballs` resolution), so the TLS handshake also
carries the name (SNI).
Before, a `tls://` URL without `tls.enabled` (the chart's external path) fell back to the
JVM's default context, which accepts any CA-signed certificate for any host: a man in the
middle with any public certificate received the node's NATS credentials. `opentls://`
(trust anything) now fails the boot. The trust store may be PKCS12, JKS or PEM — a
cert-manager `ca.crt` works as it is; the chart mounts the in-chart NATS' CA
(`nats.tls.secretName`) or an external one (`nats.externalCaSecretName`) and points
`eddi.nats.tls.truststore-path` at it. Without `eddi.nats.tls.truststore-path` the JVM's
default trust store applies (`javax.net.ssl.trustStore`, else its public CAs), and without
`eddi.nats.tls.keystore-path` the JVM's default key store (`javax.net.ssl.keyStore`) supplies
the client certificate for mutual TLS.

The broker certificate must name every host in `eddi.nats.url` as a SAN: a DNS SAN for a
host name, an IP SAN for an IP address. With the Helm chart's in-cluster NATS those hosts
are `<fullname>-nats-<n>.<fullname>-nats` (the chart's full name), so a `*.<fullname>-nats`
SAN covers them, while a certificate issued only for the fully qualified
`…svc.cluster.local` names does not. A node whose broker certificate does not match cannot
connect: it logs the TLS cause at ERROR and reports not-ready (see
[Degraded mode](#degraded-mode-nats-unreachable)). Reissue the certificate, or set
`EDDI_NATS_TLS_VERIFY_HOSTNAME=false` to go back to the old behaviour (logged as a warning).

### Signed control plane

**NATS is inside the trust boundary unless you set a signing key.** Every node uses one NATS
identity. Without `eddi.cluster.signing.key`, whoever holds those credentials can publish an
`admin-drain`, cancel or GDPR-stop request, or a `deployment.changed` or resync event, and
every node obeys. A WARN at boot says so.

With a key, every node RPC, every RPC reply and every cluster event carries an
`Eddi-Sig` header: an HMAC-SHA256 over the subject, a timestamp, a random nonce and the
SHA-256 of the body. A receiving node:

- refuses a message whose signature does not verify (compared in constant time), was made
  with an unknown key, or is bound to another subject or body;
- refuses an RPC older (or further in the future) than `eddi.cluster.signing.max-age` (60 s),
  and an event older than the event retention plus that window;
- refuses a nonce it has already seen within that window, so a captured message cannot be
  replayed;
- drops a reply that is not signed over the nonce of the request it answers.

A refused request gets no reply; the caller sees what an unreachable node looks like.
Refusals are counted in `eddi_cluster_signing_rejected_total{kind,reason}`.

The key must be the same on every node, at least 32 characters (`openssl rand -base64 48`),
and kept **outside NATS**: a Kubernetes Secret, a vault, not the NATS credentials. For a
rotation, set the new key and list the old one in `eddi.cluster.signing.previous-keys` on
every node, then remove it once every node signs with the new one. The same key also seals
the user-id hash a GDPR stop sends (AES-GCM), which would otherwise be a plain SHA-256 that
can be reversed from a list of e-mail addresses. A rotated node also opens hashes sealed with a
key it lists in `previous-keys`, but a node still on the old key cannot open one sealed with the
new key. So while the rotation rolls out, a GDPR stop that reaches a node not yet rotated can
miss that node's running turn and report the erasure step incomplete: **re-run any erasure made
during the rolling window once every node signs with the new key.**

What signing does not cover: the payloads are not encrypted (turn on `nats.tls`), the KV
buckets and streams are state rather than messages (restrict them with per-node NATS
permissions), and NATS can still be flooded. Keep restricting NATS ports with a
NetworkPolicy (the Helm chart and the `nats` overlay do).

### Rolling upgrades

Each node's presence record carries its EDDI version and its payload protocol; members on
more than one log a WARN and `eddi_cluster_versions` is above 1 until the rollout finishes.
Every RPC and event carries its payload version (`Eddi-V` header, event field `v`). A
message without one comes from an older node and is read as version 1; a newer version is
handled on a best-effort basis and counted in `eddi_cluster_payload_newer_total`.

Turning signing on without an outage takes three rollouts:

1. **Upgrade every node** to this version with no key. Nothing is signed, nothing is refused.
2. **Set `eddi.cluster.signing.key`** (the same value everywhere) and roll the nodes. A node
   with the key signs and still accepts the unsigned messages of nodes that do not have it
   yet; a node without it accepts signed messages it cannot check.
3. Once every node signs (no more `eddi_cluster_signing_unsigned_total` increments, no
   "Accepted an unsigned cluster …" WARN), **set `eddi.cluster.signing.required=true`** and
   roll again. From then on an unsigned message is refused, and a node without the key
   refuses to start.

Going back runs the same steps in reverse. During step 2 a GDPR stop from a node with the key
cannot be read by a node without it; that node answers an error, so the erasure reports its
cluster step incomplete rather than a clean erasure — run it again after the rollout.

### Upgrading from the build-profile NATS coordinator

The `nats` Maven build profile and its coordinator are gone; the published
image selects cluster mode at runtime. Its properties — `eddi.nats.stream-name`,
`eddi.nats.max-retries`, `eddi.nats.ack-wait-seconds`,
`eddi.nats.stream-max-age`, `eddi.nats.stream-max-messages`,
`eddi.nats.stream-max-bytes` — no longer do anything (a warning is logged once
if one is set), and the `EDDI_CONVERSATIONS` stream it created is unused and
can be deleted (`nats stream rm EDDI_CONVERSATIONS`). Its `eddi_nats_*`
meters no longer exist; see the [cluster metrics](metrics.md#cluster-metrics).

## Deployment

- **Helm** (chart 2.7.0+): `eddi.messagingType=nats` with `nats.enabled=true`
  (a three-node JetStream StatefulSet with client and route authentication and
  optional TLS) or
  `nats.externalUrl`; then `eddi.replicas > 1`, `autoscaling.enabled` and a
  `RollingUpdate` are allowed, and the chart sets the drain, the termination
  grace, the node ids and a topology spread. See
  [Kubernetes](kubernetes.md#cluster-mode-horizontal-scaling).
- **Kustomize**: the `k8s/overlays/nats` component (three NATS nodes, three EDDI
  replicas), composed in `k8s/examples/postgres-ha`.
- **Docker Compose**: `docker-compose.cluster.yml` — three EDDI replicas, a
  three-node NATS cluster with a password, MongoDB and an nginx load balancer
  (`docker/cluster/nginx.conf`). `docker-compose.nats.yml` is a one-replica
  overlay for trying cluster mode next to the base file.
- **Alerts**: [`monitoring/eddi-cluster-alerts.yml`](monitoring/eddi-cluster-alerts.yml)
  holds Prometheus rules for the runbook above — degraded and misconfigured nodes, NATS
  errors by cause, member loss, lease takeovers, fenced writes, acquire timeouts, an event
  outbox backlog, dead letters, and from the NATS exporter JetStream storage, a full
  dead-letter stream and slow consumers. The chart's PrometheusRule includes them in cluster
  mode (`prometheusRule.enabled=true`; the NATS group needs `nats.metrics.enabled=true`); the
  [cluster dashboard](monitoring/eddi-cluster-dashboard.json) charts the same series.
- **Demo**: [`scripts/cluster-demo/`](../scripts/cluster-demo/README.md) builds
  this topology in Docker — three nodes of one build, three NATS nodes, MongoDB
  or PostgreSQL, nginx and a mock LLM — and runs the failure scenarios listed in
  its README against it, each with a pass/fail verdict. The residual limitations
  below are not among them.

## Moving from one node to a cluster, and back

**To a cluster.** Start NATS first (`nats.enabled=true`, or your own cluster), then switch
`eddi.messaging.type` to `nats`. Do the **first** switch with a `Recreate` rollout
(`--set eddi.updateStrategy=Recreate` for that one `helm upgrade`, then remove it): with the
default `RollingUpdate` the old single-node pod, whose conversation lock, nonces and caches
live in its JVM, would run next to the new cluster pod for as long as the new one takes to
boot. Afterwards scale `eddi.replicas` (or enable the HPA). Nothing in the database changes:
existing conversations and audit chains continue — each chain position counter is seeded from
the highest stored position the first time a conversation is written in cluster mode.

**Back to one node.** Set `eddi.replicas=1`, disable the HPA and the PDB, and switch
`eddi.messaging.type` back to `in-memory` (again with `Recreate`, and only once the cluster
has drained to a single pod). Conversations, configurations and audit entries are in the
database and carry over. What lives only in NATS is dropped: unreplayed dead letters (list and
replay or discard them first), A2A task mappings and unread paginated tool pages (both
short-lived), and the exported archives of the last retention window. The JetStream objects
can then be deleted (`nats stream rm <prefix>_EVENTS`, `<prefix>_DEAD_LETTERS`, `<prefix>_ACTIVITY`, and the
`<prefix>_*` KV buckets).

## How cluster mode is tested

Unit tests and `ClusterCoordinatorIT` (one JVM, one NATS server) cover the logic. Three
heavier layers cover what only several processes show. The end-to-end suite and the cluster
demo run nightly in
[`cluster-e2e-nightly.yml`](../.github/workflows/cluster-e2e-nightly.yml), which uploads
their results as artifacts; the soak and scale harness has no workflow job and is run by
hand. None of them is part of the normal build or a required check.

- **The end-to-end suite** (`src/test/java/ai/labs/eddi/integration/cluster`). It starts
  three EDDI processes from the packaged app, a three-server NATS cluster at R3, a real
  MongoDB or PostgreSQL and the demo's mock LLM. A Toxiproxy sits between every EDDI
  process and every NATS server. Turns go through the REST API while one fault is injected:
  latency on every NATS link, a partitioned node, NATS replies dropped after the server
  applied the write, a half-open link, a holder frozen with SIGSTOP past `lease.ttl`,
  repeated stream-leader elections under load, and `kill -9` mid-turn. A history checker
  then compares what clients were answered, what was committed and what the LLM saw. It
  flags lost, duplicated, phantom and out-of-order turns, overlapping turns, a turn that
  did not run on its predecessor, and a 409 whose input was committed anyway. A 200 for a
  turn that was then fenced off counts as lost, even when it was dead-lettered (see the
  residual limitation below). The nodes run `degraded.turns=reject`. Scenarios that the
  code does not meet yet name the finding of the
  [readiness review](../planning/cluster-production-readiness-review.md) in their title.
  Opt-in, because it takes about 15 minutes per database:

  ```bash
  ./mvnw package -DskipTests -DskipUi=true
  ./mvnw test -Dtest=MongoClusterE2EIT -Deddi.cluster.e2e=true -Dsurefire.failIfNoSpecifiedTests=false
  ```

  On Windows PowerShell, quote each `-D` argument. Results, one JSON file per scenario plus
  every node's log, go to `target/cluster-e2e/<db>/`.
- **The [cluster demo](../scripts/cluster-demo/README.md)**: 13 scenarios through the
  published runtime image with the build mounted, on MongoDB and on PostgreSQL.
- **Soak and scale** ([load test](../scripts/loadtest/README.md#soak-and-scale-cluster)):
  hours of churn with heap, thread, subscription and stream-growth sampling, and many
  concurrently held leases per node, on one host or on a cluster deployed elsewhere. These
  run by hand. The sizing numbers above were not produced by them.

## Residual limitations

- **A load balancer must not eject a node for answering 503.** EDDI answers 503
  (with `Retry-After`) for ordinary load shedding — executor full, quota
  accounting or NATS unavailable under `degraded.*=reject`, a node shutting down
  — and every node does so at once under a shared overload. With nginx's
  defaults (`max_fails=1`) and `http_503` in `proxy_next_upstream`, one such
  answer per node marks every node failed and the whole API answers 502 for
  `fail_timeout`. The shipped configs set `max_fails=0` on the upstream servers,
  so no node is taken out of rotation. nginx still retries a 503 on the next node
  for an idempotent request (GET). A POST, which is how a turn arrives, is not
  re-sent, so its client gets the 503 with `Retry-After`. Neither shipped config
  adds `non_idempotent`: `timeout` also covers a read timeout after the turn
  reached a node, so it would run the turn twice. A node that refuses connections
  is skipped at once. A node that
  hangs is no longer ejected: each request that lands on it waits out
  `proxy_connect_timeout` (2 s) before it moves on. Do the same, or leave `http_503`
  out of the retry conditions, on any proxy you put in front. Kubernetes
  ingress-nginx retries only `error timeout` by default.

- **`/mcp` needs client affinity.** An MCP (Streamable HTTP) session lives on
  the node that answered `initialize`; any other node answers a request with
  that `Mcp-Session-Id` 404, which makes a compliant client start over. Route
  `/mcp` so a client stays on one node: by client address
  (`docker/cluster/nginx.conf` does `hash $remote_addr consistent`, and the Helm
  chart's ingress does the same for ingress-nginx with an `<release>-mcp` Ingress),
  with `sessionAffinity: ClientIP` on a Kubernetes Service that only serves `/mcp`,
  or with your ingress's cookie affinity. Every other path needs none.

- A Slack follow-up in the thread of a group discussion is routed only by the
  node that ran the discussion (the listener holds live state); on another
  node the reply is handled as an ordinary thread message.
- Paginated tool responses larger than the NATS payload limit (1 MiB by
  default) stay node-local; a page fetched through another node then fails as
  on a single node.
- **A node that crashes can leave a gap in an audit chain.** A chain position is taken from
  the shared counter when an entry is queued, and the queue is flushed to the database every
  few seconds; the entries still queued when the node dies were never stored, and their
  positions are not handed out again. Verification then reports that conversation `BROKEN`
  (a missing position is indistinguishable from a deleted row), although every stored entry
  verifies. On a single node the same crash loses the same entries but leaves no gap, because
  the positions are re-seeded from the store. Lower `eddi.audit.flush-interval-seconds` to
  shrink the window.
- **Cache coherence is event-driven with a time backstop.** A node that misses an event (NATS
  down, restarting) serves stale data for at most: 60 s (agent triggers, user conversations),
  30 s (conversation state), 15 min (a rotated key in an already built chat model), 5 min
  (prompt snippets, which have no event of their own), 30 s (the A2A agent roster), 5 min (a
  rotated secret, `eddi.vault.cache-ttl-minutes`), 1 min (a connection), 2 min (a global
  variable, `eddi.variables.cache-ttl-minutes`). A NATS outage longer than the event retention
  flushes everything on reconnect, and so does a node's first subscription after it booted.
- **An agent undeployed on one node can answer on another for up to ~20 s** if a request
  deployed it there on demand at the very moment of the undeploy (the deployment was started
  from a record that was already being retired); the reconciliation sweep removes it after two
  passes. An `autoDeploy=false` deployment is reconciled from its `DEPLOYMENTS` record; a node
  cut off from NATS for longer than `eddi.cluster.deployments.transient-ttl` may miss its
  undeploy tombstone and keeps serving it locally (without re-announcing it) until restarted.
- **NATS holds some personal data for a short time, and the erasure does not purge all of
  it.** A failed turn's dead letter is purged with the user's conversation, but an audit
  entry the database refused waits, with its input and output, on the
  `eddi.<prefix>.dlq.audit` subject of the dead-letter stream until
  `eddi.coordinator.dead-letter.max-age` (7 days by default) expires it. That stream is then
  its only copy: losing NATS within that window loses the entry. Only when the stream itself
  cannot take the entry does it go to the JSONL file at `eddi.audit.dead-letter-path`, which
  nothing expires. Paginated tool responses expire on their own 15-minute TTL. A2A task
  mappings are **not** in this group any more: the erasure's `a2aTasks` step on the erasing node sweeps records written on any
  node through the shared index (next bullet), and their 24 h TTL only bounds the one
  record that step can miss. An erasure that must also cover the stream and the tool pages
  should wait out their windows or purge the streams, and handle the JSONL file separately (see
  [GDPR](gdpr-compliance.md#the-audit-dead-letter-sink-holds-personal-data-and-erasure-does-not-reach-it)).
- **An A2A task write on another node can, in theory, outlive the erasure of its
  principal.** The erasure cascade's stop phase sets a marker in `A2A_ERASING` that every
  node's A2A task store reads before it writes, and the erasing node sweeps `A2A_TASKS` and
  `A2A_CONTEXTS` several steps later, through the per-principal `A2A_INDEX` so records other
  nodes wrote are reached (the index is kept with a revision-checked compare-and-set on the
  bucket, so concurrent writers on any nodes all land their ids). One gap remains, and it is
  narrow: on the erasing node the check and the write are serialised against the sweep; on
  another node they are not, so a write that read the marker as absent an instant before it
  was set and whose `put` reaches the bucket only after the sweep survives, until the 24 h
  task TTL expires it. The write would have to stall for the whole cascade. The conversation
  such a record points at is deleted by the cascade regardless, so it holds the peer's own
  message and the reply, not a live conversation.
- An erasure marks the user on every node before it pseudonymises the stored
  audit rows: each node does so while answering the `gdpr-stop` request the
  erasing node waits for, and from then on that node pseudonymises the user's
  late audit entries when they are queued and again when they are written. A
  node that does not answer `gdpr-stop` in time (it is named in the log) is
  covered only once the `gdpr.user-erased` event reaches it, so an entry it
  writes between the scrub and that event keeps the raw id.
- A turn whose write the fence refuses has usually already answered its
  caller: the reply is rendered inside the pipeline, before the write. The
  conversation history does not contain that turn and it is dead-lettered —
  replay it if the answer should count. The same holds for a turn whose lease
  is lost after its reply went out but before it was stored: the client has its
  200, the turn is dead-lettered (`lease-lost`). A turn that loses its lease
  earlier is answered 409 instead (an error event on a stream, whose tokens up to
  then were already sent). Its client is expected to retry, so replay its dead
  letter only if the client did not; otherwise the turn runs twice.
- A paginated tool response is bound to its conversation, on every node: its
  pages are keyed by `(conversationId, responseId)` and the page-fetch tool a
  turn is given fetches for that turn's conversation only, so another
  conversation that knows a response's random id gets "not found" (until 6.6.0
  the id alone was enough — security review 2026-10-15, L13). It is not bound to
  a caller beyond that: whoever may continue the conversation may read its pages.
- On PostgreSQL, replicas that boot together run their startup one after
  another (an advisory lock around table creation and migrations), so the
  last of N replicas of a first install becomes ready later than the first.

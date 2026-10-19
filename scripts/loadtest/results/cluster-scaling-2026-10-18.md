# Cluster scaling — 2026-10-18

The first multi-node throughput measurement of cluster mode. The setup: N EDDI replicas
(`eddi.messaging.type=nats`) behind an nginx round-robin balancer with no affinity, a three-node NATS
JetStream cluster (R3) and one shared MongoDB, plus one in-memory single node as the baseline. It is
the harness's `cluster-matrix`; see the [README](../README.md#cluster-mode-n-replicas).

> **Read this first: the host was saturated for most of the sweep.** All of it ran on one Docker
> Desktop VM (20 CPUs) on a workstation that was also running other sessions' load tests, builds and
> test suites. Host CPU averaged 85–99 %, and it was at or above 95 % for up to 94 % of a run's
> samples (last column). So this report measures **cluster-mode overhead and the shape of the curve
> under a CPU-bound host**. It does not measure horizontal scaling on independent machines, and it
> does not confirm or refute the expected database knee. The two N=2 runs below (8.9 vs 35.7 turns/s)
> show how far one point can move with the host's other load.

```bash
./mvnw package -DskipTests -DskipUi=true
python scripts/loadtest/loadtest.py cluster-matrix --baseline --nodes 1,2,4,8 --vus-per-node 100 --turns 10 --warmup-turns 3 --label sweep
python scripts/loadtest/loadtest.py cluster-matrix --nodes 2 --vus-per-node 100 --turns 10 --warmup-turns 3 --label sweep-rerun
```

Scenario `steady`: 100 conversations per node, so the offered load grows with N. Each conversation
runs 10 turns back to back, after a 3-turn warm-up on a fresh stack (empty database) per point. Mock
LLM fixed delay **3000 ms**. Fixed for every point, per node: `eddi.conversation.max-in-flight-turns=400`,
MongoDB `maxPoolSize=100`, `-Xmx1536m`, 2 CPUs and 2 GB. MongoDB has 4 CPUs; each NATS node has
1 CPU, the sizing [clustering.md](../../../docs/clustering.md#sizing) calls ample. The runtime is
`labsai/eddi:6.5.0` with this branch's build mounted. Its Java code is that of `main` at `4b83ffb8b`
(the commit below changes only the harness).

## Results

| Run | Nodes | Messaging | Conv. | OK turns | Turns/s | Efficiency | p50 ms | p95 ms | p99 ms | 409 | 503 | other err. | Lease acquire: count · mean ms | Fence rejected | Mongo ops/turn | Mongo ops/s | Mongo CPU % mean/peak | NATS CPU % mean/peak (3 nodes) | NATS msgs/turn | EDDI CPU % mean per node | All containers CPU % mean (other stacks) | Host CPU % mean · time at >= 95 % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| sweep | 1 | in-memory | 100 | 1000 | 21.3 | - | 4220 | 5733 | 6594 | 0 | 0 | 0 | - | 0 | 23.2 | 496 | 98/223 | 14/64 | - | 184 | 624 (292) | 99 · 94 % |
| sweep | 1 | nats | 100 | 1000 | 16.1 | 1.00 | 5608 | 8577 | 9287 | 0 | 0 | 0 | acquired 1000 · 151.3 | 0 | 23.4 | 376 | 52/153 | 25/59 | 67.4 | 99 | 359 (165) | 87 · 37 % |
| sweep | 2 | nats | 200 | 1997 | 8.9 | 0.28 | 6960 | 13633 | 17263 | 0 | 0 | 3 | acquired 1741 · 624.8; acquired (contended) 256 · 1594.1 | 0 | 23.7 | 212 | 73/242 | 43/172 | 85.9 | 82 | 551 (250) | 97 · 73 % |
| sweep-rerun | 2 | nats | 200 | 2000 | 35.7 | 1.11 | 4534 | 8994 | 9590 | 0 | 0 | 0 | acquired 1631 · 236.8; acquired (contended) 369 · 503.5 | 0 | 23.2 | 828 | 99/247 | 49/128 | 61.2 | 135 | 494 (46) | 85 · 7 % |
| sweep | 4 | nats | 400 | 4000 | 42.0 | 0.65 | 8035 | 14324 | 17281 | 0 | 0 | 0 | acquired 3046 · 792.3; acquired (contended) 954 · 1547.8 | 0 | 23.3 | 980 | 224/433 | 134/223 | 78.8 | 202 | 1365 (96) | 97 · 80 % |
| sweep | 8 | nats | 800 | 8000 | 51.6 | 0.40 | 12513 | 21570 | 25290 | 0 | 0 | 0 | acquired 5448 · 1128.5; acquired (contended) 2552 · 2644.6 | 0 | 23.8 | 1227 | 225/498 | 112/204 | 83.4 | 127 | 1509 (75) | 97 · 76 % |

Efficiency = turns/s(N) / (N × turns/s of the 1 × nats point). "Lease acquire" splits
`eddi_cluster_lease_acquire_seconds` by outcome, summed over all nodes; `(contended)` means another
holder had the lease when the turn asked for it. "NATS msgs/turn" is the `in_msgs` of all three NATS
servers divided by OK turns, so it includes R3 replication and route traffic. "Other stacks" is the
container CPU of other projects on the same Docker VM. Host CPU is the Windows `% Processor Time`,
which also covers everything outside Docker.

## What the numbers say

**No errors.** Across 17,997 OK turns there were 0 × 409, 0 × 503, 0 fence rejections and no lease
timeouts. Three 408s appeared in the noisy N=2 run. Correctness under load held at every point.

**Cluster mode costs about a quarter of a single node's throughput.** At N=1, `nats` ran 16.1 turns/s
with p50 5.6 s; `in-memory` ran 21.3 turns/s with p50 4.2 s on the same stack. That is −24 % and
+1.4 s at p50. The cluster node used *less* CPU (0.99 vs 1.84 CPUs), so the difference is waiting,
not computing. Every turn paid an uncontended lease acquire of **151 ms on average**. A NATS KV
create is a millisecond-scale operation, so most of that time is queueing in or around the lease
path; the host's CPU pressure accounts for some of it, but not all. The lease path is the first
place to profile (`KvLeaseManager.acquire`).

**Round-robin without affinity makes lease contention.** Each conversation has exactly one client
and sends its next turn only after the previous reply. Even so, the share of contended acquires
grows with the cluster: 0 % at N=1, 13–18 % at N=2, 24 % at N=4, 32 % at N=8. A turn keeps its lease
until it is persisted, which happens after the reply went out. With N nodes, the next turn lands on
another node with probability (N−1)/N and finds the lease still held. On one node, the hand-off is
local and never counts as contended. A contended acquire waited 0.5 s (quiet N=2) to 2.6 s (N=8) on
top of the uncontended cost. This is the documented serialization ("turns sent concurrently to
different nodes are serialized in lease order"), triggered by an ordinary sequential client. Two
remedies would remove it: releasing the lease before the post-reply work, or a sticky route per
conversation in the balancer.

**MongoDB is not where this sweep stopped.** Operations per turn were flat at 23.2–23.8 for every N,
the same as the single-node WS2 report, so cluster mode adds no database work per turn. The primary
peaked at **1,227 ops/s** (N=8), using 2.25 of its 4 CPUs on average. Single-node WS2 drove the same
MongoDB image to about 6,200 ops/s (270 turns/s × 23) on this workstation. The pools did fill at
N=8: 100 of `maxPoolSize=100` checked out on six of eight nodes. With the database CPU half idle,
that is slow operations on a starved host, not a database limit.

**NATS is not the limit either, but it costs more than the docs say.** The three NATS servers
together used 0.25–1.34 CPUs, never more than 0.51 per node, and handled 61–86 server-counted
messages per turn. Per turn, that is roughly 14 ms (quiet N=2) to 22 ms (N=8) of NATS CPU summed
over the three nodes, minus an idle floor of about 0.2 CPUs for presence and heartbeats. Because R3
means every node does work for every turn, 1 vCPU per NATS node would carry roughly 140–220 turns/s,
measured on a starved host. [clustering.md](../../../docs/clustering.md#sizing) says "ample for
thousands of turns per second". That claim needs a check on a quiet machine before it stays.

**The knee here is the host.** Efficiency was 1.11 at N=2 (quiet re-run; the first N=2 run, 0.28,
coincided with another stack's spike), 0.65 at N=4 and 0.40 at N=8.
- At **N=4**, every node sat at its 2-CPU cap (2.0–2.05 CPUs each), and host CPU was ≥ 95 % for 80 %
  of the run. The nodes were CPU-bound, with the host already full around them.
- At **N=8**, the stack's containers used 15.1 of the VM's 20 CPUs while the host was pinned. Each
  node got only 1.2–1.3 CPUs of its 2. N=4 → N=8 doubled the nodes and added 23 % throughput,
  while MongoDB and NATS both had headroom.

So on this machine the saturation order is: **node CPU (N=4), then host CPU (N=8), with MongoDB and
NATS still below their limits**. The "shared database primary at roughly 4–6 nodes" expectation is
neither confirmed nor refuted. At 23 ops/turn, and if the MongoDB ceiling WS2 reached holds, the
primary saturates around 270 turns/s, i.e. ~8–13 nodes of the size measured here (20–35 turns/s each
with 2 CPUs and a 3 s LLM). That is an extrapolation, not a measurement.

**CPU per turn is high, and it got worse as the host filled.** Node CPU per OK turn was 86 ms
(in-memory, N=1), 61 ms (nats, N=1), 76 ms (quiet N=2), 192 ms (N=4) and 197 ms (N=8). The rise from
N=2 to N=4 is not a cluster property: with every container on a full host, the same work took
longer. Even the quiet figure of 60–90 ms for a turn whose LLM call is a mock is worth a profile of
its own. It is why one 2-CPU node is CPU-bound at roughly 20–35 turns/s.

## What a trustworthy run needs

- **A quiet host, or better, separate machines.** One VM per role, at least: EDDI nodes, MongoDB,
  NATS, the load generator. Then the efficiency column measures EDDI and not the workstation.
  Nothing else should run during the sweep; check that the host column stays well below 95 %.
- **Run every point twice.** The two N=2 runs differ by 4×. Report the median or range.
- **Push the database** with more nodes or more conversations per node until MongoDB CPU or
  `ops/s` flattens. Only then is the knee the database's.

## Follow-ups this run points to

1. Profile the uncontended lease acquire (151–1,128 ms against a millisecond-scale KV create).
2. Decide whether the lease should be released before post-reply persistence, or whether the
   reference nginx config should route by conversation id, to remove the self-inflicted contention.
3. Re-measure NATS CPU per turn on a quiet host and correct the sizing sentence in
   `docs/clustering.md` if it holds.

## Per node

**1 x in-memory** (`sweep`)

| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |
|---|---|---|---|---|---|---|---|---|---|---|
| eddi-1 | 1000 | 21.3 | 4220 | 5733 | 6594 | 0 | 0 | 100 | - | 184 |

**1 x nats** (`sweep`)

| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |
|---|---|---|---|---|---|---|---|---|---|---|
| eddi-1 | 1000 | 16.1 | 5608 | 8577 | 9287 | 0 | 0 | 100 | 101 | 99 |

**2 x nats** (`sweep`)

| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |
|---|---|---|---|---|---|---|---|---|---|---|
| eddi-1 | 997 | 4.5 | 7799 | 15768 | 18378 | 0 | 0 | 129 | 125 | 83 |
| eddi-2 | 1000 | 4.5 | 6257 | 10339 | 12178 | 0 | 0 | 100 | 101 | 81 |

**2 x nats** (`sweep-rerun`)

| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |
|---|---|---|---|---|---|---|---|---|---|---|
| eddi-1 | 1001 | 17.9 | 4574 | 8993 | 9500 | 0 | 0 | 130 | 114 | 136 |
| eddi-2 | 999 | 17.8 | 4506 | 8996 | 9691 | 0 | 0 | 108 | 108 | 135 |

**4 x nats** (`sweep`)

| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |
|---|---|---|---|---|---|---|---|---|---|---|
| eddi-1 | 1000 | 10.5 | 6637 | 10767 | 12527 | 0 | 0 | 109 | 114 | 200 |
| eddi-2 | 1000 | 10.5 | 10003 | 15826 | 17450 | 0 | 0 | 150 | 134 | 203 |
| eddi-3 | 1000 | 10.5 | 7028 | 12858 | 14760 | 0 | 0 | 105 | 102 | 201 |
| eddi-4 | 1000 | 10.5 | 9754 | 15811 | 18513 | 0 | 0 | 128 | 126 | 205 |

**8 x nats** (`sweep`)

| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |
|---|---|---|---|---|---|---|---|---|---|---|
| eddi-1 | 1000 | 6.4 | 11698 | 16500 | 18823 | 0 | 0 | 121 | 122 | 124 |
| eddi-2 | 1001 | 6.5 | 12306 | 18824 | 21532 | 0 | 0 | 127 | 120 | 132 |
| eddi-3 | 1001 | 6.5 | 11451 | 16732 | 19051 | 0 | 0 | 105 | 102 | 127 |
| eddi-4 | 999 | 6.4 | 12423 | 20323 | 23518 | 0 | 0 | 118 | 122 | 130 |
| eddi-5 | 1000 | 6.4 | 15361 | 24105 | 27899 | 0 | 0 | 152 | 142 | 131 |
| eddi-6 | 999 | 6.4 | 12950 | 22780 | 25229 | 0 | 0 | 126 | 122 | 126 |
| eddi-7 | 1001 | 6.5 | 13975 | 24811 | 28113 | 0 | 0 | 159 | 143 | 129 |
| eddi-8 | 999 | 6.4 | 11528 | 17831 | 19933 | 0 | 0 | 103 | 105 | 119 |

Raw numbers: the `.json` files next to this report (not committed).

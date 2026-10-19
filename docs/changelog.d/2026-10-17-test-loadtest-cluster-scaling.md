## test(loadtest): cluster mode for the turn load test, N replicas behind nginx (2026-10-17)

**Repo:** EDDI · **Branch:** `test/loadtest-cluster-scaling`

### What changed and why

Cluster mode (NATS leases with fencing, the cluster event bus, degraded mode) is well tested for
correctness, but nothing measured multi-node throughput: the single-node WS2 report was the only data,
and the sizing claims in [clustering.md](../clustering.md#sizing), including "the database is the
shared bottleneck", were unverified.

- **`scripts/loadtest/docker-compose.cluster.yml`** (new): the topology of the repository's
  `docker-compose.cluster.yml` and `scripts/cluster-demo` (N EDDI replicas with
  `eddi.messaging.type=nats`, three NATS nodes at R3, one MongoDB, nginx round-robin), plus the
  WireMock LLM and k6. It has its own ports (75xx), so it runs next to the single-node stack.
- **`scripts/loadtest/nginx-cluster.conf`** (new): the cluster balancer's rules, sized for thousands
  of connections. It adds `X-Loadtest-Upstream` to every response, and only a refused connection moves
  on to the next replica, so 409s and 503s reach k6 and get counted.
- **`k6/turns.js`**: with `NODE_ADDRS` set, every turn metric is tagged with the replica that answered,
  and one no-op threshold per node makes k6 write the per-node submetrics into its summary.
- **`loadtest.py`**: new commands `cluster-up --nodes N`, `cluster-run`, `cluster-down`,
  `cluster-matrix --nodes 1,2,4,8 [--baseline]` and `cluster-report`. Every point starts from a fresh
  stack and gets a warm-up run that is thrown away. Each run collects, per node: turns/s, p50/p95/p99,
  409/503, `eddi_cluster_lease_acquire_seconds` by outcome, `eddi_cluster_fence_rejected_total`, peak
  in-flight turns and leases held. Across the cluster it collects MongoDB ops per turn and per second,
  NATS messages per turn, and the CPU of every container (other stacks' too) plus the host's.
  `--baseline` adds one single node with `eddi.messaging.type=in-memory`, so the overhead of cluster
  mode at N=1 shows up.

### Design decisions

- **Comparability is explicit.** In-flight turns per node (`--max-in-flight`, 400), the MongoDB
  `maxPoolSize` (100), heap, memory and CPUs per node are passed on every point. None of them is left
  to `max-in-flight-turns=auto`, which depends on the heap.
- **Host CPU is measured, not assumed.** On Docker Desktop, the replicas, MongoDB, NATS, nginx and k6
  share one VM with everything else on the machine. The report gives the host's CPU (typeperf on
  Windows) and other stacks' container CPU next to the efficiency column, because on one machine a
  falling efficiency can come from the host as easily as from the database.
- **Agent setup goes to a node directly.** Right after start, the balancer can still hold an address
  it resolved before the replica was up, and it answers the setup POST with a 502.
- **MongoDB gets `restart: on-failure`.** On a busy host the image's two-step first start can fail
  with exit 48 (address in use).

### Results

[`scripts/loadtest/results/cluster-scaling-2026-10-18.md`](../../scripts/loadtest/results/cluster-scaling-2026-10-18.md).
The setup was 100 conversations per node, a 3 s mock LLM and 2 CPUs per node. The host was saturated
(85–99 % CPU, shared with other sessions' work), so the report measures overhead and shape, not true
horizontal scaling.

- **Errors:** none across 18k turns — no 409, no 503, no fence rejection.
- **Overhead at N=1:** cluster mode ran 16.1 turns/s against 21.3 in memory (−24 %, +1.4 s p50). Every
  turn paid an uncontended lease acquire of ~151 ms.
- **Lease contention:** round-robin without affinity makes 13–32 % of acquires contended (0.5–2.6 s
  extra) even with one sequential client per conversation. A turn holds its lease until persisted,
  which is after the reply.
- **Efficiency:** 1.11 at N=2 (a quiet re-run; the first N=2 run read 0.28), 0.65 at N=4, 0.40 at N=8.
- **Where it saturated:** node CPU at N=4, then host CPU at N=8. MongoDB peaked at 1.2k ops/s
  (23 ops/turn, flat in N, 2.25 of 4 CPUs) and NATS at 0.5 CPU per node, so neither was the limit.
  The expected database knee is untested here.
- **NATS cost:** about 14–22 ms of NATS CPU per turn across R3. That puts a 1-vCPU NATS node nearer
  150–200 turns/s than the docs' "thousands", and needs a re-check on a quiet machine.

### Follow-ups

Profile the uncontended lease acquire. Decide between releasing the lease before post-reply
persistence and routing by conversation in the reference nginx config. Re-measure NATS CPU and the
database knee on separate machines.

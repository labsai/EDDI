## Review of the cluster console: leader-only KV reads, no 503 ejection, viewer privacy, drawer focus (2026-10-04)

**Repo:** EDDI · **Branch:** `review/p19` (review of `feat/nats-cluster-console`)

- **KV reads come from the stream leader.** jnats creates KV buckets with `allow_direct`, so any
  replica could answer a `get` — including a lagging one on the server the node is attached to.
  CAS writes were always safe (they go through the leader), but the decisions taken on a read
  (stale-lease detection, counters, who holds a lease) could differ between nodes. Buckets are now
  created with direct get off and existing ones are updated on the next connect
  (`NatsSharedStateFactory.leaderReads`). Belongs on `feat/nats-cluster-state`.
- **nginx no longer ejects a node for answering 503** (`max_fails=0` in `docker/cluster/nginx.conf`
  and the demo config). EDDI's ordinary load-shedding 503s took every node out of rotation at once
  (502 for everything). Belongs on `feat/nats-cluster-deploy`; see `docs/clustering.md`.
- **The read-only viewer no longer sees who an administrator is** in the activity timeline
  (`an administrator` instead of the principal name); `IRestClusterAdminRoleGateTest` pins the exact
  set of endpoints open to `eddi-viewer`.
- **Cluster drawer focus**: an inline `onClose` re-ran the focus-return effect on every refresh and
  pulled focus out of the dialog; Tab no longer counts disabled controls as the last stop.

## fix(engine): review findings — runtime, scheduling, cluster (2026-10-31)

**Repo:** EDDI (`fix/review-engine-runtime`)

Fixes from the CodeRabbit review and the full code review (against `01c3250`) for the engine runtime and the packages around it. Each fix has a unit test that fails without it.

### What changed and why

**Runtime and REST**
- `CronParser.parseField` checks a range's endpoints against the field's domain *before* enumerating it; `0-2000000000` used to allocate billions of boxed integers first (reachable from group cadences and schedules). The stepped loop no longer wraps on a huge step.
- `DocumentDescriptorFilter` takes the deleted resource's id from the request path and the `version` parameter only; `?x=/<other id>` made it mark another resource's descriptor deleted.
- `LegacyPathRewriteFilter` rewrites a v5 store prefix only at the start of the path, and `restricted`/`unrestricted` only in `/administration/{environment}/…`. A substring replace turned `DELETE /admin/gdpr/restricted` into an erasure of user `production`.
- `WorkflowStoreClientLibrary` reads secret instructions from the executable `PropertySetter` the task configures to; the check for `PropertySetterConfiguration` never matched, so failed turns of secret-collecting agents kept their input in dead letters.
- `RestInterfaceFactory` addresses the HTTP listener EDDI is bound to (`SelfUrlResolver`'s logic), not always `127.0.0.1`, and forwards no caller token when that address cannot be resolved.

**Scheduling and cluster**
- A schedule whose claim expired without an outcome (its node died or the fire wedged) counts as a failed attempt instead of being re-fired at once with `failCount` untouched — a node-killing fire could never dead-letter. The poll runs with `concurrentExecution = SKIP`. `tryClaim` re-checks `enabled` (MongoDB and PostgreSQL). `POST /{id}/fire` and the MCP `fire_schedule_now` tool are bounded by the fire timeout like a polled fire, so a manual Dream or ingestion run cannot outlast its lease and start twice. A disabled schedule can no longer be fired manually.
- A scheduled fire that starts a conversation runs the agent's startup workflow under `maxCostPerFire`, and the turn gets the remainder.
- A dead letter a node kept locally while cut off from NATS is discarded, not forwarded, when its conversation no longer exists (erased in the meantime).
- NATS TLS verifies the broker host name (`eddi.nats.tls.verify-hostname`, default `true`); jnats sets no endpoint identification, so any trusted certificate for any host was accepted. **Upgrade note:** the broker certificate must now carry a SAN for every host in `eddi.nats.url` (an IP SAN for an IP address); see [Clustering](../clustering.md).
- `CacheFactory.evictLocal` remembers key hashes instead of SHA-256-ing every cached key on every eviction event. `BoundedLogStore`'s DB queue is capped (50,000; drops are counted and logged at flush). `BoundedFanOut` claims "not started" by compare-and-set, so an item reported as not sent can no longer go on and send. `AgentTestRunner` charges an abandoned (timed-out) turn's cost when it completes.

**Cleanup:** unused `ClusterConfig.withDegradedNonces/withDegradedRateLimits/withHitlRecovery` removed; misplaced Javadoc in `ScheduleFireExecutor` and `SchedulePollerService` moved to its methods; the nine fire-log blocks in `ScheduleFireExecutor` share `recordFire`.

Docs: [Clustering](../clustering.md), [Scheduling](../scheduling.md), [Configuration Reference](../configuration-reference.md).

- Second review round: a manual fire cut off at its deadline (REST and MCP) answers a timeout (504 / an error result) instead of an empty 200, and leaves the schedule claimed until its lease expires rather than releasing it while the worker may still run (`FireAbandonedException`); a local dead letter whose conversation cannot be checked stays on the node instead of being published, and a legacy conversation without a stored state is no longer mistaken for a missing one; an unreadable startup spend leaves the turn no allowance instead of the full `maxCostPerFire`; `BoundedFanOut` no longer reports a gate-refused item as started; `BoundedLogStore` hands the database fixed-size batches; two docs/test corrections.

### Design decisions

- **An expired claim is a failure, not a retry-now.** The lease already outlasts the fire timeout by a margin, so a claim that expires means the fire is gone.
- **Hostname verification is on by default** and can be turned off; an IP address in `eddi.nats.url` then needs an IP SAN.

### Not done (deferred)

- **Late charges of an abandoned agent-test turn** (`AgentTestRunner`) are still not persisted into the already-finalised run record; that needs an atomic adjustment of the terminal record or a hold on finalisation until the turn's persistence completes.
- **Local dead-letter forwarding vs GDPR erasure** has no shared erasure marker: a node that reconnects between the shared purge and the snapshot deletion can still publish an erased turn (the existence check now closes the common case).
- **The `SKIP` poll cycle** still waits for its batch's fires, so one long fire delays claiming other schedules on that instance; splitting the short claim cycle from the bounded fire wait is a larger change to the poller.

- **One fire-timeout mechanism** instead of the executor's turn latch plus the poller's deadline: both now use `eddi.schedule.fire-timeout`, and the manual path is bounded; merging them is a refactor.

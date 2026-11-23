## ✨ feat(org): the data-layout floor guard and the org marker (organizations Phase 1, package 2) (2026-11-23)

**Repo:** EDDI (`feat/org-2-layout-guard`, stacked on `feat/org-1-context`)

### Release note: this is the floor release

**The first release that contains this change is the floor release for organizations.**

- **What every later release does.** Every later EDDI release checks the stored data layout at
  startup, and refuses to start on a layout it does not support.
- **What cannot be stopped.** A release older than the floor has no such check. Once a
  deployment has enabled organizations, rolling back below the floor release is not
  supported. On MongoDB an older binary would read every organization's data without org
  filters.
- **Today's deployments.** No deployment has a raised layout yet. Organizations cannot be
  enabled in this release.

**Behaviour change: startup now needs the database.** EDDI reads the layout record before
anything else at startup.

- **What happens.** If the database cannot be reached, EDDI retries for
  `eddi.org.layout-guard.wait` (default `PT60S`) and then **exits non-zero**. Earlier releases
  started anyway and stayed not-ready until the database came back.
- **Why.** Failing closed is deliberate: a binary must never run unchecked against a layout it
  may not support.
- **What you see.** On Kubernetes, a database outage at pod start now shows as restarts rather
  than a not-ready pod, and `quarkus:dev` without a reachable database now exits after the
  wait. Raise the wait if your database routinely becomes reachable after EDDI
  does.

### What changed and why

Plan §5.4 and ADR 0001 DV-11. All new code is in `ai.labs.eddi.engine.org`:

- **`OrgMarker` and `OrgMarkerState`.** The marker states are `ABSENT` → `SWITCHING` → `ENABLED`;
  it only moves forward, and a fresh install may go straight to `ENABLED`. The record also
  holds `layout.required`, which only ever rises.
- **`IOrgMarkerStore`** has MongoDB and PostgreSQL implementations:
  - one platform-plane record, `org-platform`, in `org_platform_state`;
  - `read()` creates nothing: Mongo issues a plain `find`, and Postgres checks `to_regclass`
    first, because it runs before anything may write;
  - the writers create what they need, use compare-and-set for the marker, and use a
    raise-only update for the layout;
  - on MongoDB every operation goes to the primary with majority read and write concern,
    because EDDI's client otherwise reads `nearest()` and a lagging secondary could hide a
    raised layout.
- **`DataLayoutGuard`**:
  - observes `StartupEvent` at `Integer.MIN_VALUE`, before every other startup observer and
    before the HTTP listener opens (shown by Phase 0 Spike C); `OrgConfig` sits at
    `MIN_VALUE + 1`, and a test fails if anything else takes `MIN_VALUE`;
  - refuses a `layout.required` above `SUPPORTED_LAYOUT`, which is `0` in this release, so it
    also refuses a deployment that a later release enabled;
  - refuses at once, without retrying, a record it cannot interpret: an unknown marker state, or a
    level that is not a whole number in range. Reading such a value as 0 would fail open;
  - retries an unreadable record with backoff (500 ms doubling to 5 s) until the wait has
    passed, then refuses to start.
- **Configuration.** `IOrgMarkerStore` is wired through `DataStoreProducers`.
  `eddi.org.layout-guard.wait` is in `application.properties` and
  `docs/configuration-reference.md`.

Nothing writes the record yet: the enablement migration (a later phase) does. A single-tenant
deployment reads "no record", and the check passes.

### Tests

- `DataLayoutGuardTest` (fake store, virtual clock) covers:
  - no record;
  - a supported and a raised layout, including a raised layout while the marker is absent;
  - transient failures with the exact backoff;
  - an unreadable record that refuses at the deadline without sleeping past it;
  - a zero wait;
  - interrupts;
  - a corrupt record refused at once, without retrying;
  - an enormous wait that must not overflow the deadline;
  - strict parsing (a Long, a whole Double, and the values that would truncate or are unknown);
  - the reserved `Integer.MIN_VALUE` priority;
  - the forward-only marker.
- `OrgMarkerStoreContractTest`, run against a real MongoDB (`MongoOrgMarkerStoreTest`) and a
  real PostgreSQL (`PostgresOrgMarkerStoreTest`), checks that:
  - a read on a fresh database creates nothing;
  - the marker compare-and-set refuses a stale expectation;
  - a backward move is refused before touching the database;
  - a fresh install may move straight from `ABSENT` to `ENABLED`;
  - an unknown stored state is unsupported, not retried;
  - the layout only rises;
  - the guard refuses a layout raised in the real store.

  `MongoOrgMarkerStoreTest` adds the BSON-typing cases: a level stored as a Long or a whole Double,
  values that would truncate, and a record with missing fields that can still be moved and raised.
- `DataLayoutGuardIT` runs the real image on both datastores with `layout.required = 1`. It
  checks the exit is non-zero and that the database still
  holds only `org_platform_state`.

### What's next

Phase 1 packages 3+:

- `@OrgBound` on the REST and MCP classes, and binding the background entry points;
- `OrgQuotaKeys` at the 22 quota call sites;
- `OrgCollections`/`OrgSql` with stamping;
- NATS and loopback headers;
- the catalog and the report-mode guards.

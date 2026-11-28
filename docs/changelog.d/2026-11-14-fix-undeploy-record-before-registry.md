## 🐛 fix(deployment): flip the record before leaving the registry on undeploy (2026-11-14)

**Repo:** EDDI (`fix/undeploy-record-before-registry`)

### What changed and why

Since #1353 the deployment sweep derives what it tracks from the registry: it
reads the registry, then the deployment records, and deploys every version that
is recorded `deployed` but not served READY. Every undeploy path, though,
removed the agent from the registry first and flipped its record to
`undeployed` second. A sweep whose two reads both fell into that one-write gap
saw the agent missing and still wanted, and deployed it again. On a single node
`reconcileUndeployed` does not run, so the agent then stayed served under a
record saying undeployed until a restart. Before #1353 the tracked entry had
blocked that redeploy.

Every undeploy path now writes the record **before** it leaves the registry:
the REST undeploy (`RestAgentAdministration.undeploy`), the daily retirement
(`retireIfConversationsCanMove` and `manageDeploymentOfOldAgent`, which run
outside the sweep's lock), `teardown_agent` and the end-of-discussion
cleanup. With the registry read first and the record written first, a
registry read that no longer finds the agent is always followed by a records
read that no longer wants it.

If the registry removal then fails, the record is put back to `deployed`, so
it never says undeployed for an agent that still answers. The REST path does
that only when the record said `deployed` before (it reads it first), so a
failed undeploy of an `autoDeploy=false` deployment does not invent a record.
Cluster mode is unchanged in effect: other nodes act on the
`deployment.changed` event, which now goes out before the local removal and
finds the record already undeployed.

Also: `awaitClusterDeployment` answers at once for a version this node lacks
while the startup migrations run or are owed, instead of waiting the full
15 s for a deploy that cannot happen.

Not changed: the delete cascade (`RestAgentStore`) still undeploys before it
deletes the records, but it deletes the agent config first, and the sweep
retires a record whose config is missing before it deploys anything. The
`deployOnDemand` race in `awaitClusterDeployment` is left for a follow-up.

### Tests

- `AgentDeploymentManagementVersionFollowingTest`: a sweep run inside the
  retirement (after the record write and after the registry removal) does not
  deploy the version again; a failed registry removal restores the record.
- `RestAgentAdministrationUndeployResilienceTest`, `DynamicAgentToolsTest`,
  `GroupLifecycleOpsTest`: record before registry, and the restore on failure.
- `AgentDeploymentManagementClusterTest`: the on-demand wait returns at once
  while migrations are owed.

**Files:** [`AgentDeploymentManagement.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java),
[`RestAgentAdministration.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java),
[`TeardownAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/TeardownAgentTool.java),
[`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java)

```regression-note
| 2026-11-14 | An agent undeployed while a deployment sweep ran could be deployed again and stay served under an undeployed record (single node) | #1353 made the sweep's tracking follow the registry, and every undeploy left the registry before it flipped the record | Undeploy paths write the record first and restore it if the registry removal fails | fix/undeploy-record-before-registry |
```

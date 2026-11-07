## 🐛 fix(deployment): cluster wait never enters the sweep; tracking derived from the registry (2026-11-07)

**Repo:** EDDI (`fix/deployment-tracking`)

### What changed and why

Two defects in `AgentDeploymentManagement`, both in cluster mode.

- **A request waited behind the whole deployment sweep.** When a request reached a node
  before that node had deployed an agent another node had just deployed,
  `awaitClusterDeployment` ran the full sweep under its lock before it started its
  15 s wait. The request queued behind any running sweep, deployed every other agent the
  node was missing before its own, and a burst of requests ran one full sweep each. A
  version whose deployment had failed on the node cost every request the full 15 s,
  because it never became ready. The wait now starts its clock first, reads the
  deployment records and this node's registry once, and submits a deployment of just the
  missing versions of the requested agent to the runtime executor. Requests that arrive
  while that deployment is queued or running do not submit it again. A version in `ERROR`
  on this node is neither retried nor waited for, since the sweep owns retries and their
  backoff. While the startup migrations run or are deferred, nothing is deployed on
  demand, as before.
- **A version this node undeployed itself stayed "tracked".** The sweep kept a private
  list of the deployments it had confirmed READY and skipped them from then on. Only an
  undeploy that arrived as an event from another node removed an entry. A REST undeploy on
  this node, the daily retirement, the delete cascade and sub-agent teardown left the entry
  in place. When the same version was deployed again by a request on another node, this
  node's sweep found it tracked and never deployed it, and that node's share of the
  traffic waited 15 s and was answered as not ready until a restart. The sweep now starts
  by dropping every tracked row this node's registry does not hold READY. A registry that
  cannot be read drops nothing. The tracked set is a concurrent set, so an undeploy event
  no longer takes the sweep lock: one undeploy arriving during a long sweep used to stall
  every later cluster event on the node, cache invalidations included.

### Design decisions

- Tracking is derived from `IAgentFactory.getAllDeployedAgents` on every sweep instead of
  hooking every local undeploy path. The registry is the fact; the list was a second copy
  of it, the same lesson `AgentFactory` records for its `eddi_agents_deployed` gauge.
- The on-demand path reads the registry with `getAllDeployedAgents`, not `getAgent`, which
  blocks up to 60 s on a deployment in progress.
- Rejected: running `checkDeployments()` asynchronously from the request. It would still
  deploy every other missing agent before this one and still queue one sweep per request.

**Files:** [`AgentDeploymentManagement.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java),
[`IAgentDeploymentManagement.java`](../../src/main/java/ai/labs/eddi/engine/runtime/IAgentDeploymentManagement.java),
[`AgentDeploymentManagementClusterTest.java`](../../src/test/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagementClusterTest.java),
[`AgentDeploymentManagementBranchTest.java`](../../src/test/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagementBranchTest.java),
[`clustering.md`](../clustering.md)

# Deployment Management of Agents

## Overview

**Deployment Management** controls the lifecycle of your agents across different environments. In EDDI, agents go through a **create → configure → deploy** workflow before they can process conversations.

### Why Deployment Management?

Deployment management provides:

- **Environment Isolation**: Test agents without affecting production
- **Version Control**: Deploy specific agent versions, roll back if needed
- **Gradual Rollout**: Test agents in `test` environment before deploying to `production`
- **Zero-Downtime Updates**: Deploy new versions while old ones are still running
- **Audit Trail**: Track what's deployed, when, and by whom

### EDDI Environments

| Environment      | Purpose                            | Access Control             |
| ---------------- | ---------------------------------- | -------------------------- |
| **`test`**       | Development and testing            | Same as production         |
| **`production`** | Live deployments (default)         | Optional OAuth (Keycloak)  |

### Deployment Lifecycle

```
1. CREATE Agent
   POST /agentstore/agents
   → Agent exists but is NOT deployed

2. DEPLOY Agent
   POST /administration/production/deploy/agent123?version=1
   → Agent becomes active and can handle conversations

3. USE Agent
   POST /agents/agent123/start
   → Users can now interact with the agent

4. UPDATE Agent
   Create new version → Deploy new version
   → Old version still available if specified

5. UNDEPLOY Agent
   POST /administration/production/undeploy/agent123?version=1
   → Agent stops processing new conversations
```

### Auto-Deploy Feature

- **`autoDeploy=true`** (the default): the deployment is persisted, so this exact agent version is deployed again automatically after a restart
- **`autoDeploy=false`**: the agent is deployed into the running instance only — nothing is persisted, so the deployment is gone after a restart

Neither value deploys a new version created by an agent update: a new version always needs its own explicit deploy call.

This is useful for:

- **Development**: `autoDeploy=false` in `test`, so a throwaway version does not survive the next restart
- **Production**: `autoDeploy=true` in `production`, so the deployed version comes back up with the instance

### Checking Deployment Status

You can check:

- **Single Agent Status**: Is agent123 deployed in production?
- **All Deployments**: List all deployed agents across environments
- **Version Info**: Which version is currently deployed?

## Deployment Operations

In this section we will discuss the deployment management of Agents, including deployment/undeployment, checking deployment status, and listing all deployed Agents.

After all the required resources for the agent have been created and configured (**`Dictionary`**, **`Behavior Rules`**, **`Output`**, **`Workflow`**, etc.) and the Agent is created through **`POST`** to **`/agentstore/agents`**, deployment management is key to having granular control over deployed agents.

## **Deployment of an Agent :**

The deployment of a specific agent is done through a **`POST`** to **`/administration/{environment}/deploy/{agentId}`**

### Deploy Agent REST API Endpoint

| Element       | Value                                                                                    |
| ------------- | ---------------------------------------------------------------------------------------- |
| HTTP Method   | `POST`                                                                                   |
| API endpoint  | `/administration/{environment}/deploy/{agentId}`                                         |
| {environment} | (`Path parameter`):`String` deployment environment: `production` (default) or `test`             |
| {agentId}     | (`Path parameter`):`String` id of the agent that you wish to **deploy**.                 |

### Example _:_

_Request URL:_

`http://localhost:7070/administration/production/deploy/5aaf98e19f7dd421ac3c7de9?version=1&autoDeploy=true`

_Response Body:_

`no content`

_Response Code:_

`202`


## **Undeployment of an Agent**

The undeployment of a specific agent is done through a **`POST`** to **`/administration/{environment}/undeploy/{agentId}`**

### Undeploy Agent REST API Endpoint

| Element       | Value                                                                                    |
| ------------- | ---------------------------------------------------------------------------------------- |
| HTTP Method   | `POST`                                                                                   |
| API endpoint  | `/administration/{environment}/undeploy/{agentId}`                                       |
| {environment} | (`Path parameter`):`String` deployment environment: `production` (default) or `test`             |
| {agentId}     | (`Path parameter`):`String` id of the agent that you wish to **undeploy**.               |
| version       | (`Query parameter`, **required**):`Integer` version of the agent that you wish to **undeploy**. |
| endAllActiveConversations | (`Query parameter`, optional, default `false`):`Boolean` end the agent's active conversations instead of refusing. Without it, undeploying an agent that has active conversations returns `409`. |
| undeployThisAndAllPreviousAgentVersions | (`Query parameter`, optional, default `false`):`Boolean` also undeploy every earlier version, counting down to version 1. |

### Example :

**Undeploy an agent**

_Request URL_

`http://localhost:7070/administration/production/undeploy/5aaf98e19f7dd421ac3c7de9?version=1`

_Response Body_

`no content`

_Response Code_

`202`

### Conflict: the agent has active conversations

If the agent has at least one active conversation and `endAllActiveConversations` is `false`,
the endpoint answers `409 Conflict` with a `text/plain` body naming the count. Nothing is
undeployed. This is the normal case for a live agent, so a caller that treats any non-2xx as a
hard failure will abort a rollback here.

Retry with `endAllActiveConversations=true` to end those conversations and proceed:

`http://localhost:7070/administration/production/undeploy/5aaf98e19f7dd421ac3c7de9?version=1&endAllActiveConversations=true`


## **Check the deployment status of an agent:**

Check the deployment status of an agent is done through a **`GET`** to **`/administration/{environment}/deploymentstatus/{agentId}`**

Deployment status of an Agent REST API Endpoint

| Element       | Value                                                                                             |
| ------------- | ------------------------------------------------------------------------------------------------- |
| HTTP Method   | `GET`                                                                                             |
| Api endpoint  | `/administration/{environment}/deploymentstatus/{agentId}`                                        |
| {environment} | (`Path parameter`):`String` deployment environment: `production` (default) or `test`             |
| {agentId}     | (`Path parameter`):`String` id of the agent that you wish to **check** its **deployment status**. |
| Response      | JSON `{"status": ...}`, where `status` is one of `NOT_FOUND`, `IN_PROGRESS`, `ERROR` and `READY`. Add `?format=text` for the bare status word as plain text (deprecated). |

### Example*:*

_Request URL_

`http://localhost:7070/administration/production/deploymentstatus/5aaf98e19f7dd421ac3c7de9?version=1`

_Response Body_

`{"status":"READY"}`

_Response Code_

`200`


## **List all deployed Agents:**

To list all deployed Agents, send a `GET` to `/deploymentstore/deployments`:

### List of Deployed Agents REST API Endpoint

| Element      | Value                          |
| ------------ | ------------------------------ |
| HTTP Method  | `GET`                          |
| API endpoint | `/deploymentstore/deployments` |

### Example:

_Request URL_

`http://localhost:7070/deploymentstore/deployments`

_Response Code_

`200`

_Response Body_

```json
[
  {
    "agentId": "5aaf90e29f7dd421ac3c7dd4",
    "agentVersion": 1,
    "environment": "production",
    "deploymentStatus": "deployed"
  },
  {
    "agentId": "5aaf98e19f7dd421ac3c7de9",
    "agentVersion": 1,
    "environment": "production",
    "deploymentStatus": "deployed"
  }
]
```

---

## Deleting an Agent

### Simple Delete (Soft-Delete)

Marks the agent as deleted but keeps it in the database. The agent can potentially be restored.

| Element      | Value                                                                                       |
| ------------ | ------------------------------------------------------------------------------------------- |
| HTTP Method  | `DELETE`                                                                                    |
| API endpoint | `/agentstore/agents/{id}?version={version}`                                                 |
| {id}         | (`Path parameter`) `String` agent ID                                                        |
| version      | (`Query parameter`) `Integer` version                                                       |
| permanent    | (`Query parameter`) `Boolean` default `false`. If `true`, permanently removes from database |

```
DELETE /agentstore/agents/5aaf98e19f7dd421ac3c7de9?version=1
→ 200 OK (soft-deleted)

DELETE /agentstore/agents/5aaf98e19f7dd421ac3c7de9?version=1&permanent=true
→ 200 OK (permanently removed)
```

### Cascade Delete

Deletes the agent **and all its child resources** in one operation. This is the recommended way to fully clean up an agent and avoid orphaned resources.

```
Agent
 └── Workflow 1
 │    ├── Behavior Set
 │    ├── HTTP Calls
 │    ├── Output Set
 │    ├── LangChain Config
 │    ├── Property Setter
 │    └── Parser (with dictionaries)
 └── Workflow 2
      └── ...
```

| Element      | Value                                                                                                  |
| ------------ | ------------------------------------------------------------------------------------------------------ |
| HTTP Method  | `DELETE`                                                                                               |
| API endpoint | `/agentstore/agents/{id}?version={version}&cascade=true&permanent=true`                                |
| cascade      | (`Query parameter`) `Boolean` default `false`. If `true`, deletes packages and all extension resources |
| permanent    | (`Query parameter`) `Boolean` default `false`. Applies to the **agent only** — see below                |

#### Example

```
DELETE /agentstore/agents/5aaf98e19f7dd421ac3c7de9?version=1&cascade=true&permanent=true
→ 200 OK
```

This will:

1. Read the agent configuration to discover its packages
2. Resolve each package reference to the package's **current** version — references are version-pinned and are not re-pointed when the package is edited, so the pinned version frequently is not the one that exists
3. Skip any package another agent still references (at any version), and any package with no live version left
4. Soft-delete each remaining package, which cascades the same way into its extensions (behavior sets, HTTP calls, output sets, langchains, property setters, parser dictionaries)
5. Delete the agent itself

> **`permanent=true` never cascades.** It applies to the resource named in the request — every
> version and every history row of that agent, plus its Ed25519 signing keys in the secrets vault,
> which no endpoint can regenerate. Cascaded resources are always **soft-deleted**, whatever
> `permanent` says: the "is anyone else using this?" check can only speak for the versions it can
> see, while `permanent` erases all of them. To erase a shared resource, delete it explicitly,
> without cascade.

> **`permanent=true` requires the current version.** It is ID-scoped, so a request naming a stale
> version is refused with **409** before anything is deleted — as `cascade=true` already was. A
> resource that is already soft-deleted has no current version to be stale against: purging its
> remaining history still works (and does remove its vault keys), and the cascade is skipped rather
> than refused.

> **Note:** Cascade delete is error-tolerant. If individual resource deletions fail (e.g., resource already deleted), the operation continues and the agent itself is still deleted. Failures are logged server-side.

> **Safety:** Cascade delete checks for shared references before deleting each resource. If a package is used by another agent, or an extension resource is used by another package, it will be **skipped** (not deleted). Only resources exclusively owned by the deleted agent are removed.

### Cascade Delete for Workflows

Workflows can also be individually cascade-deleted:

```
DELETE /workflowstore/workflows/{id}?version={version}&cascade=true
→ 200 OK (package deleted, exclusively-owned extension resources soft-deleted)
```

The workflow delete reports how many extensions the cascade left alone — still referenced, not
routable, or a delete that failed — in the `X-Cascade-Skipped` response header. It is absent when
nothing was skipped, so a cascade that removed everything it walked no longer looks exactly like one
that removed half the graph. The header is **not** propagated onto the agent-delete response; the
packages a cascading agent delete skipped are named in the server log.

### Important: Undeploy Before Deleting

If the agent is currently deployed, you should **undeploy** it first:

```
POST /administration/production/undeploy/{agentId}?version=1&endAllActiveConversations=true
→ 202 Accepted

DELETE /agentstore/agents/{agentId}?version=1&cascade=true&permanent=true
→ 200 OK
```

---

## Orphan Detection and Cleanup

Over time, resources can become orphaned — they exist in the database but are no longer referenced by any agent or package. The orphan admin endpoint helps detect and clean up these resources.

### Scan for Orphans (Dry Run)

```
GET /administration/orphans
→ 200 OK
```

Returns a report listing all unreferenced resources across all stores (workflows, behavior sets, HTTP calls, output sets, LLMs, property setters, dictionaries, parsers).

| Element        | Value                                                                                     |
| -------------- | ------------------------------------------------------------------------------------------ |
| HTTP Method    | `GET`                                                                                     |
| API endpoint   | `/administration/orphans`                                                                 |
| includeDeleted | (`Query parameter`) `Boolean` default `false`. `true` also includes soft-deleted resources |

**Example Response:**

```json
{
  "totalOrphans": 3,
  "deletedCount": 0,
  "scanComplete": true,
  "scanWarning": null,
  "orphans": [
    {
      "resourceUri": "eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=1",
      "type": "ai.labs.workflow",
      "name": "Unused Workflow",
      "deleted": false
    },
    {
      "resourceUri": "eddi://ai.labs.rules/rulestore/rulesets/def456?version=1",
      "type": "ai.labs.rules",
      "name": "Old Behavior Set",
      "deleted": true
    }
  ]
}
```

> **Check `scanComplete` before acting on this list.** It is `false` when part of the traversal
> failed — an unreadable agent or workflow, an unreadable deployment record, a store type past the
> scan ceiling — and `scanWarning` then says which. Every failure *removes* entries from the
> referenced set, so a partial scan lists live, in-use resources as orphans. The read-only scan still
> answers so you can see the cause; the purge refuses outright (409, below).

What counts as a reference: the current version of every agent, the current version of every
workflow, **and** every agent version named by a `deployed` deployment record together with the exact
workflow versions that version pins. A resource referenced only by a superseded *and* undeployed
agent version is still reported as an orphan — history is not a reference — so rolling an agent back
to an older version after a purge can leave it unresolvable. References are compared by resource
identity, not by URI string, so a version-pinned reference protects every version of the resource,
and a reference written with a legacy authority (`ai.labs.behavior`, `ai.labs.httpcalls`,
`ai.labs.regulardictionary`) protects the same resource as the canonical one.

### Purge Orphans

```
DELETE /administration/orphans
→ 200 OK
```

Permanently deletes all orphaned resources. This is **irreversible** — it removes the current
document *and* its entire version history.

| Element        | Value                                                                                                    |
| -------------- | -------------------------------------------------------------------------------------------------------- |
| HTTP Method    | `DELETE`                                                                                                 |
| API endpoint   | `/administration/orphans`                                                                                |
| includeDeleted | (`Query parameter`) `Boolean` default `false`. `true` also purges soft-deleted resources                 |
| 409 Conflict   | Returned when the reference scan is incomplete; **nothing is deleted**. Body: `{"error":"incomplete_scan"}` |

Pass the **same** `includeDeleted` value you used for the scan, so the purge acts on the set you
reviewed.

> **Changed in 6.1.x:** `includeDeleted` was previously an *equality* filter — `true` matched only
> soft-deleted resources instead of adding them to the live ones — and this endpoint defaulted to
> `true` while the scan defaulted to `false`, so a scan followed by a purge operated on **disjoint**
> sets. `true` now means "live and soft-deleted", and the default is `false`. A client relying on the
> old default now purges *less*; pass `includeDeleted=true` to restore the wider sweep.

The purge refuses with **409** rather than proceeding when the referenced-resource scan could not be
completed (an unreadable Agent or workflow, an unreadable deployment record, or a store type
exceeding the scan ceiling). A partial reference set makes live, in-use resources look unreferenced,
so purging against one could destroy working configuration.

Each candidate is re-checked against a fresh reverse lookup immediately before it is deleted, so a
resource that became referenced between the scan and the purge is skipped rather than erased. That
narrows the mark/sweep window rather than closing it: there is no deployment-wide write lock, because
taking one would block configuration editing for the duration of an administrative sweep.

The purge also removes each purged resource's descriptor, so the sweep converges. Descriptors are
normally only *flagged* as deleted — the HTTP delete filter reads one back afterwards and a missing
row would answer 404 to a delete that succeeded — but that filter does not run for
`/administration/orphans`, and a flagged descriptor whose resource is gone is exactly what
`includeDeleted=true` selects. Left in place, every purged resource came back as an orphan on the next
`includeDeleted=true` run and was counted as deleted again, forever.

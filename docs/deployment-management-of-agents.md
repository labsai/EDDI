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
   POST /administration/production/undeploy/agent123
   → Agent stops processing new conversations
```

### Auto-Deploy Feature

- **`autoDeploy=true`**: Automatically deploy new versions when agent is updated
- **`autoDeploy=false`**: Manual deployment required for each version

This is useful for:

- **Development**: Auto-deploy to `test` for rapid iteration
- **Production**: Manual deployment to `production` for controlled releases

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

### Example :

**Undeploy an agent**

_Request URL_

`http://localhost:7070/administration/production/undeploy/5aaf98e19f7dd421ac3c7de9?version=1`

_Response Body_

`no content`

_Response Code_

`202`


## **Check the deployment status of an agent:**

Check the deployment status of an agent is done through a **`GET`** to **`/administration/{environment}/deploymentstatus/{agentId}`**

Deployment status of an Agent REST API Endpoint

| Element       | Value                                                                                             |
| ------------- | ------------------------------------------------------------------------------------------------- |
| HTTP Method   | `GET`                                                                                             |
| Api endpoint  | `/administration/{environment}/deploymentstatus/{agentId}`                                        |
| {environment} | (`Path parameter`):`String` deployment environment: `production` (default) or `test`             |
| {agentId}     | (`Path parameter`):`String` id of the agent that you wish to **check** its **deployment status**. |
| Response      | `NOT_FOUND`, `IN_PROGRESS`, `ERROR` and `READY`.                                                  |

### Example*:*

_Request URL_

`http://localhost:7070/administration/production/deploymentstatus/5aaf98e19f7dd421ac3c7de9?version=1`

_Response Body_

`READY`

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
| permanent    | (`Query parameter`) `Boolean` default `false`. Recommended `true` with cascade                         |

#### Example

```
DELETE /agentstore/agents/5aaf98e19f7dd421ac3c7de9?version=1&cascade=true&permanent=true
→ 200 OK
```

This will:

1. Read the agent configuration to discover its packages
2. For each package, read its extensions and delete all resources (behavior sets, HTTP calls, output sets, langchains, property setters, parser dictionaries)
3. Delete each package
4. Delete the agent itself

> **Note:** Cascade delete is error-tolerant. If individual resource deletions fail (e.g., resource already deleted), the operation continues and the agent itself is still deleted. Failures are logged server-side.

> **Safety:** Cascade delete checks for shared references before deleting each resource. If a package is used by another agent, or an extension resource is used by another package, it will be **skipped** (not deleted). Only resources exclusively owned by the deleted agent are removed.

### Cascade Delete for Workflows

Workflows can also be individually cascade-deleted:

```
DELETE /packagestore/packages/{id}?version={version}&cascade=true&permanent=true
→ 200 OK (package + all extension resources deleted)
```

### Important: Undeploy Before Deleting

If the agent is currently deployed, you should **undeploy** it first:

```
POST /administration/production/undeploy/{agentId}?endAllActiveConversations=true
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

Returns a report listing all unreferenced resources across all stores (packages, behavior sets, HTTP calls, output sets, langchains, property setters, dictionaries, parsers).

| Element        | Value                                                                         |
| -------------- | ----------------------------------------------------------------------------- |
| HTTP Method    | `GET`                                                                         |
| API endpoint   | `/administration/orphans`                                                     |
| includeDeleted | (`Query parameter`) `Boolean` default `false`. Include soft-deleted resources |

**Example Response:**

```json
{
  "totalOrphans": 3,
  "deletedCount": 0,
  "orphans": [
    {
      "resourceUri": "eddi://ai.labs.package/packagestore/packages/abc123?version=1",
      "type": "ai.labs.package",
      "name": "Unused Workflow",
      "deleted": false
    },
    {
      "resourceUri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/def456?version=1",
      "type": "ai.labs.behavior",
      "name": "Old Behavior Set",
      "deleted": true
    }
  ]
}
```

### Purge Orphans

```
DELETE /administration/orphans
→ 200 OK
```

Permanently deletes all orphaned resources. This is **irreversible**.

| Element        | Value                                                                                 |
| -------------- | ------------------------------------------------------------------------------------- |
| HTTP Method    | `DELETE`                                                                              |
| API endpoint   | `/administration/orphans`                                                             |
| includeDeleted | (`Query parameter`) `Boolean` default `true`. Include soft-deleted resources in purge |

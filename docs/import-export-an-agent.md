# Import/Export an Agent

## Overview

**Import/Export** functionality allows you to package entire agents (including all their dependencies) into portable ZIP files. This is essential for agent lifecycle management, collaboration, and deployment automation.

### Why Import/Export?

**Use Cases**:

- **Backup & Restore**: Protect your agent configurations from accidental deletion or corruption
- **Version Control**: Store agent configurations alongside code in Git
- **Environment Migration**: Move agents from development → staging → production
- **Continuous Sync**: Keep agents synchronized across environments with **merge imports**
- **Team Collaboration**: Share agents with team members or customers
- **Disaster Recovery**: Quickly restore agents after system failures
- **Agent Templates**: Create reusable agent templates for similar use cases
- **CI/CD Integration**: Automate agent deployment in your pipeline

### What Gets Exported?

When you export an agent, EDDI packages:

- ✅ Agent configuration (package references)
- ✅ All packages used by the agent
- ✅ All extensions (behavior rules, dictionaries, HTTP calls, outputs, etc.)
- ✅ Version information
- ✅ Configuration metadata
- ✅ **Origin IDs** (resource identifiers for merge tracking)

**Note**: Conversations and conversation history are **NOT** exported (only configurations).

### Import Strategies

EDDI supports two import strategies:

| Strategy             | Behavior                                          | Use Case                            |
| -------------------- | ------------------------------------------------- | ----------------------------------- |
| **Create** (default) | Always creates a new agent with new IDs           | First-time import, creating copies  |
| **Merge**            | Updates existing resources by matching origin IDs | Syncing changes across environments |

### Export/Import Workflow

**First-time import (Create):**

```
DEVELOPMENT EDDI
    ↓
1. Export Agent
   POST /backup/export/agent123?agentVersion=1
   ← Returns: agent123-1.zip
    ↓
2. Download ZIP file
   GET /backup/export/agent123-1.zip
   ← Receives: agent123-1.zip file
    ↓
3. Store in version control / backup / transfer
    ↓
PRODUCTION EDDI
    ↓
4. Upload ZIP file
   POST /backup/import
   Body: (application/zip with ZIP file)
   ← Returns: New agent ID (Location header)
    ↓
5. Deploy imported agent
   POST /administration/production/deploy/{newAgentId}?version=1
```

**Subsequent imports (Merge/Sync):**

```
DEVELOPMENT EDDI  (agent updated since last sync)
    ↓
1. Export latest version
   POST /backup/export/agent123?agentVersion=2
    ↓
2. Download ZIP
    ↓
PRODUCTION EDDI  (has the agent from first import)
    ↓
3. Preview what would change
   POST /backup/import/preview
   ← Returns: list of resources with CREATE/UPDATE/SKIP actions
    ↓
4. Review changes, optionally deselect resources
    ↓
5. Merge import (updates existing, no duplicates)
   POST /backup/import?strategy=merge&selectedResources=origin1,origin2
   ← Returns: Same agent ID, incremented version
    ↓
6. Deploy updated agent
```

### Best Practices

- **Version Your Exports**: Include version numbers in filenames: `customer-support-agent-v2.3.zip`
- **Preview Before Merge**: Always use the preview endpoint before merging to review changes
- **Selective Merge**: Only merge the resources that actually changed to minimize risk
- **Document Changes**: Keep a changelog of what changed between exports
- **Regular Backups**: Schedule automated exports of production agents
- **Test Imports**: Always test imported agents in a test environment first
- **Store Securely**: Keep exports in secure, version-controlled storage (e.g., Git LFS, S3)

### Common Scenarios

**Scenario 1: Promoting to Production (first time)**

```bash
# 1. Export from test environment
curl -X POST http://test.eddi.com/backup/export/agent123?agentVersion=1

# 2. Download the ZIP
curl -O http://test.eddi.com/backup/export/agent123-1.zip

# 3. Import to production (creates new agent)
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent123-1.zip http://prod.eddi.com/backup/import

# 4. Deploy in production
curl -X POST http://prod.eddi.com/administration/production/deploy/{newAgentId}?version=1
```

**Scenario 2: Syncing Updates (merge)**

```bash
# 1. Export updated agent from dev
curl -X POST http://dev.eddi.com/backup/export/agent123?agentVersion=3
curl -O http://dev.eddi.com/backup/export/agent123-3.zip

# 2. Preview what would change in production
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent123-3.zip http://prod.eddi.com/backup/import/preview

# 3. Merge import — updates existing resources, no duplicates
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent123-3.zip "http://prod.eddi.com/backup/import?strategy=merge"

# 4. Redeploy
curl -X POST http://prod.eddi.com/administration/production/deploy/{agentId}?version=2
```

**Scenario 3: Selective Merge (only specific resources)**

```bash
# Preview first to get the origin IDs
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent123-3.zip http://prod.eddi.com/backup/import/preview
# Response includes originId for each resource

# Merge only the behavior rules and HTTP calls (by origin ID)
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent123-3.zip \
  "http://prod.eddi.com/backup/import?strategy=merge&selectedResources=origin-beh-1,origin-http-1"
```

**Scenario 4: Disaster Recovery**

```bash
# Regular automated backup (cron job)
#!/bin/bash
DATE=$(date +%Y%m%d)
curl -X POST http://prod.eddi.com/backup/export/agent123?agentVersion=1
curl -O http://prod.eddi.com/backup/export/agent123-1.zip
mv agent123-1.zip "backups/agent123-$DATE.zip"
aws s3 cp "backups/agent123-$DATE.zip" s3://agent-backups/

# Restore after failure
aws s3 cp s3://agent-backups/agent123-20250103.zip ./
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent123-20250103.zip http://prod.eddi.com/backup/import
```

---

## Using the Manager UI

The EDDI Manager provides a guided import wizard accessible from the **Agents** page:

1. **Upload** — Drag-and-drop or browse for a `.zip` export file
2. **Choose Strategy**:
   - **Create New Agent** — Always creates a fresh agent (default)
   - **Merge / Sync** — Updates an existing agent if one with matching origin IDs exists
3. **Preview** (merge only) — Shows a table of all resources with their planned action:
   - 🟢 **New** — Resource doesn't exist locally, will be created
   - 🔵 **Update** — Resource exists locally, will be updated to the imported version
   - ⚪ **Skip** — Resource is identical, no changes needed
4. **Select Resources** — Checkboxes let you pick which resources to merge (all selected by default)
5. **Confirm** — Executes the import

---

## How Merge Tracking Works

When an agent is first imported into an EDDI instance, EDDI stores the **origin ID** of each resource (the ID it had on the source system) in the `DocumentDescriptor`. On subsequent imports with `strategy=merge`:

1. EDDI reads each resource from the ZIP
2. Looks up the origin ID in the local descriptor store (`findByOriginId`)
3. If found → **updates** the existing resource (creating a new version)
4. If not found → **creates** a new resource
5. The agent itself is updated with references to the (possibly new) resource versions

This means the **agent ID stays the same** across merge imports — only the version increments. Deployments, triggers, and integrations that reference the agent ID continue to work without reconfiguration.

---

## API Reference

### Exporting an Agent

Send a **`POST`** request to export. The response `Location` header contains the download URL.

| Element      | Value                                             |
| ------------ | ------------------------------------------------- |
| HTTP Method  | `POST`                                            |
| API Endpoint | `/backup/export/{agentId}?agentVersion={version}` |
| Response     | `Location` header with ZIP download URL           |

**Example:**

```bash
curl -X POST http://localhost:7070/backup/export/agent123?agentVersion=1
# Response Header: Location: /backup/export/agent123-1.zip

curl -O http://localhost:7070/backup/export/agent123-1.zip
```

### Importing an Agent (Create)

Upload a ZIP file to create a new agent.

| Element      | Value                                |
| ------------ | ------------------------------------ |
| HTTP Method  | `POST`                               |
| API Endpoint | `/backup/import`                     |
| Content-Type | `application/zip`                    |
| Request Body | ZIP file binary                      |
| Response     | `Location` header with new agent URI |

**Example:**

```bash
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip http://localhost:7070/backup/import
```

### Preview Merge Import

Dry-run analysis: returns what would change without modifying any data.

| Element      | Value                            |
| ------------ | -------------------------------- |
| HTTP Method  | `POST`                           |
| API Endpoint | `/backup/import/preview`         |
| Content-Type | `application/zip`                |
| Request Body | ZIP file binary                  |
| Response     | JSON with resource diff analysis |

**Response format:**

```json
{
  "agentOriginId": "original-agent-id-from-source",
  "agentName": "My Agent",
  "resources": [
    {
      "originId": "original-resource-id",
      "resourceType": "agent",
      "name": "My Agent",
      "action": "UPDATE",
      "localId": "local-agent-id",
      "localVersion": 1
    },
    {
      "originId": "original-behavior-id",
      "resourceType": "behavior",
      "name": "Greeting Rules",
      "action": "CREATE",
      "localId": null,
      "localVersion": null
    }
  ]
}
```

**Actions:**

- `CREATE` — No matching local resource found; will be created
- `UPDATE` — Matching local resource found; will be updated
- `SKIP` — Resource is unchanged; will be skipped

### Importing an Agent (Merge)

Update an existing agent by matching origin IDs.

| Element      | Value                                                      |
| ------------ | ---------------------------------------------------------- |
| HTTP Method  | `POST`                                                     |
| API Endpoint | `/backup/import?strategy=merge`                            |
| Content-Type | `application/zip`                                          |
| Request Body | ZIP file binary                                            |
| Query Params | `strategy=merge`, optional `selectedResources=id1,id2,...` |
| Response     | `Location` header with updated agent URI                   |

**Example (merge all):**

```bash
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip "http://localhost:7070/backup/import?strategy=merge"
```

**Example (selective merge):**

```bash
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip \
  "http://localhost:7070/backup/import?strategy=merge&selectedResources=origin-id-1,origin-id-2"
```

> **Important:** The agent will not be deployed after import — you must deploy it yourself using the [Deployment API](deployment-management-of-agents.md).

---

## Upgrade Strategy

In addition to `create` (new agent) and `merge` (by origin ID), EDDI supports an **`upgrade`** strategy that uses structural matching to sync content into an existing agent — even if the agents were created independently (no shared origin IDs):

```bash
# Preview what would change
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip \
  "http://localhost:7070/backup/import/preview?targetAgentId=local-agent-id"

# Execute upgrade
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip \
  "http://localhost:7070/backup/import?strategy=upgrade&targetAgentId=local-agent-id"
```

The upgrade strategy matches resources by **structure** (workflow position, extension type, snippet name) rather than by origin ID. See [Agent Sync](agent-sync-guide.md) for details on how structural matching works.

## Live Sync (Without ZIP)

If both EDDI instances are reachable over HTTP, you can skip the ZIP step entirely and sync directly between instances. See the **[Agent Sync Guide](agent-sync-guide.md)** for the full workflow.

---

## See Also

- [Agent Sync Guide](agent-sync-guide.md) — Live instance-to-instance sync and upgrade imports
- [Deployment Management](deployment-management-of-agents.md) — Deploying agents after import
- [Secrets Vault](secrets-vault.md) — How API keys are scrubbed and re-vaulted during import

# Agent Sync — Live Instance-to-Instance Synchronization

## Overview

Agent Sync lets you synchronize agent configurations between two running EDDI instances **without exporting/importing ZIP files**. It uses the same structural matching and content diffing pipeline as ZIP imports, but reads directly from a remote EDDI instance over HTTP.

### When to Use

| Scenario | Use |
|----------|-----|
| One-off agent migration between environments | ZIP Import/Export |
| Regular dev → staging → production promotions | **Agent Sync** |
| Keeping multiple EDDI instances in sync | **Agent Sync** |
| Sharing agents with external teams | ZIP Import/Export |
| CI/CD pipeline deployments | Either (Sync for live, ZIP for artifact-based) |

## Prerequisites

- Both EDDI instances must be reachable over HTTP/HTTPS
- The **source** instance must have the agent deployed
- If the source requires authentication, you'll need a valid Bearer token

## Workflow

### 1. List Remote Agents

First, discover which agents are available on the remote instance:

```bash
curl -X GET "http://localhost:7070/backup/import/sync/agents?sourceUrl=https://source-eddi.example.com" \
  -H "X-Source-Authorization: Bearer <token>"
```

**Response:** List of agent descriptors from the remote instance.

### 2. Preview Changes (Single Agent)

Before syncing, preview what would change:

```bash
curl -X POST "http://localhost:7070/backup/import/sync/preview?sourceUrl=https://source-eddi.example.com&sourceAgentId=remote-agent-id&sourceAgentVersion=1&targetAgentId=local-agent-id" \
  -H "X-Source-Authorization: Bearer <token>"
```

**Response:** An `ImportPreview` with resource diffs:

```json
{
  "resources": [
    {
      "resourceType": "agent",
      "action": "UPDATE",
      "originId": "remote-agent-id",
      "localId": "local-agent-id"
    },
    {
      "resourceType": "llm",
      "action": "UPDATE",
      "originId": "remote-llm-id",
      "localId": "local-llm-id"
    },
    {
      "resourceType": "behavior",
      "action": "SKIP",
      "originId": "remote-behavior-id",
      "localId": "local-behavior-id"
    }
  ]
}
```

**Actions explained:**

| Action | Meaning |
|--------|---------|
| `CREATE` | Resource doesn't exist locally — will be created |
| `UPDATE` | Resource exists locally — content differs, will be updated |
| `SKIP` | Resource is identical — no changes needed |
| `CONFLICT` | Structural mismatch — review needed |

### 3. Preview Batch (Multiple Agents)

Preview sync for multiple agents at once. The request body is a JSON array of `SyncMapping` objects:

```bash
curl -X POST "http://localhost:7070/backup/import/sync/preview/batch?sourceUrl=https://source-eddi.example.com" \
  -H "Content-Type: application/json" \
  -H "X-Source-Authorization: Bearer <token>" \
  -d '[
    { "sourceAgentId": "agent-1", "sourceAgentVersion": 1, "targetAgentId": "local-1" },
    { "sourceAgentId": "agent-2", "sourceAgentVersion": 2, "targetAgentId": "local-2" }
  ]'
```

**Response:** A JSON array of `ImportPreview` objects, one per mapping.

### 4. Execute Sync

Once you've reviewed the preview and are satisfied:

```bash
curl -X POST "http://localhost:7070/backup/import/sync?sourceUrl=https://source-eddi.example.com&sourceAgentId=remote-agent-id&sourceAgentVersion=1&targetAgentId=local-agent-id" \
  -H "X-Source-Authorization: Bearer <token>"
```

You can also pass `selectedResources` and `workflowOrder` as query parameters for fine-grained control:

```bash
curl -X POST "http://localhost:7070/backup/import/sync?sourceUrl=https://source-eddi.example.com&sourceAgentId=remote-agent-id&sourceAgentVersion=1&targetAgentId=local-agent-id&selectedResources=res-1,res-2" \
  -H "X-Source-Authorization: Bearer <token>"
```

### 5. Execute Batch Sync

Sync multiple agents in one call. The request body is a JSON array of `SyncRequest` objects:

```bash
curl -X POST "http://localhost:7070/backup/import/sync/batch?sourceUrl=https://source-eddi.example.com" \
  -H "Content-Type: application/json" \
  -H "X-Source-Authorization: Bearer <token>" \
  -d '[
    {
      "sourceAgentId": "agent-1",
      "sourceAgentVersion": 1,
      "targetAgentId": "local-1",
      "selectedResources": null,
      "workflowOrder": null
    },
    {
      "sourceAgentId": "agent-2",
      "sourceAgentVersion": 2,
      "targetAgentId": "local-2",
      "selectedResources": ["res-a", "res-b"],
      "workflowOrder": null
    }
  ]'
```

> **Partial success:** If one agent fails during batch sync, the remaining agents still sync. The response indicates success/failure per agent.

## API Reference

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/backup/import/sync/agents` | List remote agents |
| `POST` | `/backup/import/sync/preview` | Single-agent sync preview |
| `POST` | `/backup/import/sync/preview/batch` | Multi-agent sync preview |
| `POST` | `/backup/import/sync` | Execute single-agent sync |
| `POST` | `/backup/import/sync/batch` | Execute multi-agent sync |

### Parameters

**Query parameters (all endpoints):**

| Parameter | Required | Description |
|-----------|----------|-------------|
| `sourceUrl` | Yes | Base URL of the source EDDI instance |
| `sourceAgentId` | Yes (single) | Agent ID on the remote instance |
| `sourceAgentVersion` | No | Version to sync (null = latest) |
| `targetAgentId` | No | Local agent to upgrade (null = create new) |
| `selectedResources` | No (execute only) | Comma-separated resource IDs to sync |
| `workflowOrder` | No (execute only) | Desired workflow order after sync |

> **Note:** `sourceUrl` and agent parameters are query parameters. Batch endpoints accept `SyncMapping[]` / `SyncRequest[]` as a JSON request body for the per-agent mappings.

**Request header:**

| Header | Required | Description |
|--------|----------|-------------|
| `X-Source-Authorization` | No | Bearer token for authenticated source instances |

## How Structural Matching Works

Agent Sync uses **structural matching** — not ID matching — to pair source and target resources. This means it works even when the source and target agents were created independently.

| Resource Type | Matching Strategy | Rationale |
|---------------|-------------------|-----------|
| **Agent** | Direct (by `targetAgentId` parameter) | User explicitly selects the target |
| **Workflows** | Position index in agent's workflow list | Workflows have a defined order |
| **Extensions** | `WorkflowStep.type` URI (e.g., `ai.labs.llm`) | Each type appears at most once per workflow |
| **Snippets** | `PromptSnippet.name` (natural key) | Names are unique by convention |

### Key Design Decisions

- **In-place upgrade:** Target resource IDs are preserved. URI references, deployments, and triggers continue to work
- **Version increments:** Each updated resource gets a new version (history preserved)
- **Secret scrubbing:** API keys and vault references are **never** transferred. The target instance uses its own secrets
- **SSRF protection:** The remote URL is validated (HTTPS required in production, private IPs blocked)

## Upgrade Strategy (ZIP Import)

The same structural matching is available for ZIP imports using `strategy=upgrade`:

```bash
# Preview what would change
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip \
  "http://localhost:7070/backup/import/preview?targetAgentId=local-agent-id"

# Execute upgrade (updates existing resources in-place)
curl -X POST -H "Content-Type: application/zip" \
  --data-binary @agent-export.zip \
  "http://localhost:7070/backup/import?strategy=upgrade&targetAgentId=local-agent-id"
```

This is the same pipeline as Live Sync — the only difference is the transport (ZIP file vs HTTP).

## Selective Export

Export only the resources you want:

```bash
# 1. Preview the export tree
curl -X POST "http://localhost:7070/backup/export/agent-id/preview?agentVersion=1"

# 2. Select specific resources and export
curl -X POST "http://localhost:7070/backup/export/agent-id?agentVersion=1&selectedResources=res1,res2,res3"
```

The preview returns a resource tree with selectability flags. Agent and workflow skeletons are always included — you can deselect individual extensions, behavior rules, or prompt snippets.

## See Also

- [Import/Export an Agent](import-export-an-agent.md) — ZIP-based import/export (create and merge strategies)
- [Agent Sync Architecture](agent-sync-architecture.md) — Internal architecture and matching algorithm details
- [Deployment Management](deployment-management-of-agents.md) — Deploying agents after sync

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
- The agent only needs to **exist** in the source instance's agent store — it does not have to be deployed
- If the source requires authentication, you'll need a valid Bearer token
- The target must be configured to accept the source's address — see
  [Reaching the source instance](#reaching-the-source-instance). The shipped
  default accepts only a public HTTPS host, which is **not** what two instances
  on one internal network look like

## Reaching the source instance

The source URL is supplied by whoever calls the endpoint, and the
`X-Source-Authorization` bearer travels to whatever host it names. The default
policy is therefore strict: HTTPS only, and no loopback, RFC 1918, ULA, CGNAT or
link-local target. That is the right default for an internet-facing, multi-tenant
deployment, and the wrong one for the most common self-hosted shape — staging and
production as two services on one private network, often speaking plain HTTP to
each other. Three settings express the difference:

| Setting | Default | Use it when |
|---|---|---|
| `eddi.backup.sync.allowed-sources` | *(empty)* | **Prefer this.** Comma-separated exact origins (`scheme://host[:port]`) that are accepted whatever the other two say — `http://eddi-staging:7070,https://staging.internal:7443` |
| `eddi.backup.sync.allow-private-targets` | `false` | The instances are on an internal network and naming each origin is impractical |
| `eddi.backup.sync.require-https` | `true` | TLS is terminated elsewhere, or the two services speak HTTP on a trusted network |

Each rule is separate: turning off `require-https` does not also allow a private
address, and vice versa. An origin in `allowed-sources` bypasses both, compared
as scheme + host + port only — a different port is a different origin.

A refused URL answers **400** with a message naming the setting that would allow
it, so an operator does not have to guess:

```
Source URL must not point to a private IP address: http://10.0.0.5:7070.
Set eddi.backup.sync.allow-private-targets=true to sync between instances on an
internal network, or name this origin in eddi.backup.sync.allowed-sources.
```

> **Dev mode needs none of this.** An instance started with `quarkus:dev` (or in
> test mode) accepts `http://` whatever the policy says. A packaged or
> containerised instance never counts as dev mode — the decision reads the
> launch mode, not `quarkus.profile` — so a container needs the settings above.

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
      "sourceId": "remote-agent-id",
      "targetId": "local-agent-id",
      "targetVersion": 3,
      "matchStrategy": "targetAgent"
    },
    {
      "resourceType": "langchain",
      "action": "UPDATE",
      "sourceId": "remote-llm-id",
      "targetId": "local-llm-id",
      "targetVersion": 2,
      "matchStrategy": "type"
    },
    {
      "resourceType": "behavior",
      "action": "SKIP",
      "sourceId": "remote-behavior-id",
      "targetId": "local-behavior-id",
      "targetVersion": 1,
      "matchStrategy": "type"
    }
  ]
}
```

`targetId` and `targetVersion` are `null` for a `CREATE`, and `matchStrategy` records how the match was found (e.g. `targetAgent`, `position`, `type`, `name` — `null` for `CREATE`).

`resourceType` uses the config file extension labels, not the v6 URI names — the full set is `agent`, `workflow`, `langchain`, `httpcalls`, `behavior`, `parser`, `regulardictionary`, `property`, `output`, `mcpcalls`, `rag`, `snippet`.

**Actions explained:**

| Action | Meaning |
|--------|---------|
| `CREATE` | Resource doesn't exist locally — will be created. A step or a whole workflow the source added is created and wired in, so this is the ordinary result of adding something on the source |
| `UPDATE` | Resource exists locally — content differs, will be updated |
| `SKIP` | Resource is identical — no changes needed |
| `CONFLICT` | The target's copy was **changed on this instance** since the last sync (or import) wrote it, and the source changed it too. Left alone unless named in `selectedResources` — see [Changes made on the target](#changes-made-on-the-target) |
| `REMOVE` | The target has it and the source no longer does: a workflow step, or a whole workflow. The step goes when its workflow is updated; a workflow is taken off the agent. Nothing is deleted from the store — earlier versions still name it. The row's `sourceId` is the target's own id |

Agents and workflows are compared **on the target's own references**: the source's
workflow steps are pointed at the resources the target already has before the two
are compared, so an agent whose pipeline did not change previews as `SKIP` even
though every id differs between the instances. What is left different is what a
sync actually changes — a step added, removed, reordered or reconfigured.

A preview may also carry `warnings`: things to know before approving that are not
failures — for example, two snippets on the source sharing the name the agent
references (only one of them can travel).

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

You can also pass `selectedResources` and `workflowOrder` as query parameters for fine-grained control.
Leave `selectedResources` out to sync everything; when present it must name at least one
resource — an empty value is refused with `400` rather than read as "everything":

```bash
curl -X POST "http://localhost:7070/backup/import/sync?sourceUrl=https://source-eddi.example.com&sourceAgentId=remote-agent-id&sourceAgentVersion=1&targetAgentId=local-agent-id&selectedResources=res-1,res-2" \
  -H "X-Source-Authorization: Bearer <token>"
```

### Response codes

Every execute endpoint answers with one of three **2xx** statuses. A client must branch on
the status code — checking `response.ok` alone reports a half-applied sync as a success:

| Status | Meaning |
|--------|---------|
| `200 OK` | Source and target already agree. Nothing was written, no version was burned. |
| `201 Created` | Everything landed and something was written. |
| `207 Multi-Status` | **Partially applied** — `failures[]` in the body names every resource that could not be written. |

Two failures are reported apart from those, because they are not this instance's
fault and the operator can act on both:

| Status | Meaning |
|--------|---------|
| `400 Bad Request` | The source URL is malformed, or this deployment's policy refuses it — the body names the setting that would allow it, see [Reaching the source instance](#reaching-the-source-instance). Also answered when `selectedResources` is present but names nothing |
| `404 Not Found` | The named `targetAgentId` does not exist here |
| `409 Conflict` | No `targetAgentId` was named and more than one local agent was promoted from this source agent. The body lists them; name one, or pass `createNew=true` |
| `502 Bad Gateway` | The source instance could not be read: down, addressed wrongly, or refusing the token. The body carries the underlying reason |

Every error answers with a JSON body, `{"error": "…"}`.

The body of a single sync is an `UpgradeResult`:

```json
{
  "agentUri": "eddi://ai.labs.agent/agentstore/agents/local-agent-id?version=8",
  "agentUpdated": true,
  "updated": 3,
  "created": 0,
  "skipped": 5,
  "failures": [
    { "sourceId": "…", "resourceType": "langchain", "name": "GPT Config", "reason": "…" }
  ]
}
```

`/backup/import/sync/batch` answers a JSON array of `BatchSyncResult`
(`sourceAgentId`, `targetAgentId`, `result`, `error`) — one entry per request, in request
order, whether it succeeded or not. It answers `500` only when *every* agent failed, and
`207` when some did. A batch **preview** row that failed carries `sourceAgentName: null`
and an `error` field rather than encoding the failure into the agent's name.

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
| `targetAgentId` | No | Local agent to upgrade. When omitted, the target is **the agent an earlier sync or import promoted from this source agent**, recognised by the `originId` its descriptor records — so repeating a sync never creates a second copy. Only when there is none (or `createNew=true`) is the agent created: the source's own export archive is fetched and imported with `strategy=create`, and the new agent records the source's id as its `originId`. The preview marks a target found this way with `matchStrategy: "originId"` on the agent row |
| `createNew` | No | `true` to create a new agent even when one was promoted from this source before |
| `selectedResources` | No (execute only) | Comma-separated ids of the preview rows to apply (the row's `sourceId`; for a `REMOVE` row that is the target's id). Omit to apply everything; an empty value is a `400`. A `CONFLICT` row is applied only when named here |
| `workflowOrder` | No (execute only) | Desired workflow order after sync |

> **Note:** `sourceUrl` and agent parameters are query parameters. Batch endpoints accept `SyncMapping[]` / `SyncRequest[]` as a JSON request body for the per-agent mappings; both take an optional `createNew`, and a `SyncRequest` whose `selectedResources` is an empty list fails that entry the same way.

**Request header:**

| Header | Required | Description |
|--------|----------|-------------|
| `X-Source-Authorization` | No | Bearer token for authenticated source instances |

## How Structural Matching Works

Agent Sync uses **structural matching** — not ID matching — to pair source and target resources. This means it works even when the source and target agents were created independently.

| Resource Type | Matching Strategy | Rationale |
|---------------|-------------------|-----------|
| **Agent** | By `targetAgentId`, or else by the `originId` an earlier promotion recorded | The operator names the target, or it is the copy made from this source |
| **Workflows** | Position index in agent's workflow list | Workflows have a defined order |
| **Extensions** | `WorkflowStep.type` URI plus its occurrence in the workflow (e.g. the second `ai.labs.httpcalls` step) | Each step keeps its own entry, however many of a type there are |
| **Snippets** | `PromptSnippet.name` (natural key) | Names are unique by convention |

> **Which snippets travel:** only the ones the agent references, found by
> scanning its own configuration documents for `{snippets.<name>}` — the same
> rule the ZIP export applies. Syncing one agent never proposes copying the
> source instance's whole snippet library.
>
> **Two snippets with the same name:** nothing stops it, and a template renders
> only one of them. That one — and only that one — travels, and the preview
> carries a warning naming the duplicate. Offering all of them wrote each, in turn,
> over the target's single copy. Give every snippet a unique name.

### Key Design Decisions

- **In-place upgrade:** Target resource IDs are preserved. URI references, deployments, and triggers continue to work
- **Version increments:** Each updated resource gets a new version (history preserved)
- **Secret scrubbing:** every document read from the source goes through the secret scrubber first — the store endpoints hand out raw configuration, so without it the source's plaintext credentials reached the preview (and the operator's browser) and were written into the target. The target keeps its own secrets: wherever the source has a scrubbed credential **or a vault reference**, the target's own value at the same place is put back before anything is compared or written. Which vault entry an environment uses is that environment's business — staging's `${vault:openai-key}` never replaces production's `${vault:prod-openai-key}`. Only where the target has nothing there (a first promotion, a newly added call) does the source's reference travel, as the hint of which entry to create
- **SSRF protection:** The remote URL is validated against this deployment's policy — HTTPS-only and no private address by default, relaxed per [Reaching the source instance](#reaching-the-source-instance) — and redirects are never followed, so a 3xx from the source surfaces as a failed read rather than re-sending the bearer token elsewhere. On a first promotion, the `Location` the source's export answers with is not followed either: only the archive's file name is taken from it, and the download goes to the already-approved base URL
- **Added and removed steps travel:** a step the source added is created and wired into the target workflow when that workflow is updated, and a step it removed is dropped with it. A workflow the source added arrives with all its resources, named by their ids on the target; one it removed comes off the agent. A new resource is created only when the update that places it is certain to happen — if its workflow, or a sibling step's new resource, was left out of `selectedResources`, it is reported in `failures[]` instead of being created unreferenced
- **Agent-level settings travel, the identity does not:** HITL configuration, capabilities, memory policy, channels and the rest of the agent document are taken from the source (with the target's secrets kept). The agent's `identity` is bound to its instance — its private key lives in that instance's vault — and is never replaced
- **A missing resource is recreated, not refused:** when the target's workflow *does* have the step but the resource it names no longer exists — the store confirms it is gone, not merely unreadable — the resource is created from the source and the step repointed at it

## What a promotion carries, and what it does not

Everything the agent's workflows reference travels as content, so a promoted
agent works on the target without being rebuilt there: behavior rules, HTTP
calls, LLM configs, property setters, outputs, MCP calls, knowledge-base (RAG)
configurations with their ingestion sources, parser documents, dictionaries —
including a dictionary that only a parser document names — and the prompt
snippets the agent uses. Every reference between them is repointed at the
target's own copies. A first promotion imports the source's own export archive,
so it also lands the schedules and connection references that archive carries.

A few things stay behind, by design or because they are not configuration:

**Secrets.** An API key is stored as a vault reference (`${vault:<name>}`), and
on a first promotion the reference is what travels: the value never leaves the
source instance, by design. A promoted agent therefore carries a reference to a
vault entry the target may not have, and the first LLM call fails when it does
not. Create the entry on the target under the same name — `POST /secretstore/secrets/{tenantId}/{keyName}`
— or edit the promoted config to name one it already has. From then on the
target's choice sticks: an agent that is *updated* keeps its own credentials and
its own vault references, whatever the source names, so a sync never overwrites a
working key with a placeholder or repoints production at staging's vault entry.
The same applies to the embedding model's key of a knowledge base. A credential
written in plain text on the source (in an `Authorization` header, say) is
scrubbed on the way out and never reaches the target.

**Grants.** A vault secret's grant (`allowedAgents`) lists agent ids, and a
promoted or imported agent gets a new id on the target. If the target's vault
entry is restricted to named agents, the promoted agent is not on it, and with
`eddi.vault.grant-enforcement=enforce` (the default) its first deploy there is
refused. The deploy response says which secret and gives the grant call; add the
agent to the grant — the Manager's deploy dialog does it in one step — then
deploy again. See [Secrets Vault → New agents always need adding](secrets-vault.md#new-agents-always-need-adding).

**Agent triggers (intents).** The mapping from an intent to an agent is not part
of the agent's export, so it is not promoted either. Recreate it on the target
(`POST /AgentTriggerStore/agenttriggers`, or MCP `create_agent_trigger`).

**What a knowledge base has ingested.** The knowledge-base configuration and its
ingestion sources travel; the chunks already embedded into the source's vector
store do not — they are data, not configuration. A source with a `cron` gets its
schedule on the target and fills the knowledge base on its first run. To answer
from it straight away, start each source once — `POST /ragstore/rags/{id}/sources/{sourceId}/run?version=N`,
or **Run now** in the knowledge-base editor — and re-ingest any document that was
uploaded by hand (`POST /ragstore/rags/{id}/ingest`). See [RAG](rag.md#ingestion-sources).
A target that shares the source's vector store needs neither: the store is
addressed by the knowledge base's name, which travels unchanged.

### Changes made on the target

A sync writes over the target's copy of a resource only when that copy is what an
earlier sync wrote. Every version a sync or import writes is recorded on the
resource's descriptor (`syncedVersion`); a version past it was made on this
instance — a hotfix in production, say. When that local version differs from the
synced one in anything other than its own secrets, and the source has changed the
same resource, the preview reports it as `CONFLICT` and a sync of everything leaves
it alone, answering `207` with the reason. Setting the target's own API keys or
vault references after a first promotion is not such a change: a sync keeps them
anyway, so it does not hold anything up.

The same holds for the agent's own settings (HITL, capabilities, memory policy…)
and for a workflow's steps: a gate added in production, or a step added there,
makes that row a `CONFLICT` rather than being dropped by the next promotion.
Moving onto newer versions — what editing an extension in the Manager does to its
workflow and agent — is not counted as an edit. When the agent or a workflow is
left alone this way, everything else in the sync still lands: extension updates
are written and the workflow and agent are repointed at them. To take the source's version, name the row
in `selectedResources` (in the Manager: tick **Overwrite local change** on the
row). To keep the hotfix, carry it back to the source first.

Resources written before this baseline existed have none, and are updated as
before; the first sync that writes one gives it a baseline.

### Parser documents and older promotions

Parser documents travel since this release. Two consequences for what was
promoted before:

- **An agent promoted earlier names a parser its instance never had.** Its parser
  step kept the source's parser id. The next sync recreates that parser on the
  target and repoints the step (it is counted under `created`). Nothing about the
  agent's behaviour changes: the pipeline builds its parser from the workflow step
  itself and never loads the document.
- **An archive exported before this release carries no parser document.** It
  imports exactly as it always did — the parser step is kept and its reference is
  left as the archive wrote it, rather than the step being dropped or the import
  refused.

A dictionary that *only* a parser document names — not the workflow's parser step
— is matched like any other resource: the preview lists it under the parser, a
change to it is written, and one added on the source is created on the target.
The parser document is then written naming the target's copies, at the versions
the sync just wrote. A selective export that leaves such a dictionary out still
imports; the parser keeps naming it as the archive wrote it.

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

The preview returns a resource tree with selectability flags. Agent and workflow skeletons are always included — you can deselect individual extensions, behavior rules, prompt snippets or scheduled triggers.

Snippets and schedules have their own parameters (`selectedSnippets`, `selectedSchedules`).
Deselecting an extension **keeps the workflow step that referenced it** — the archive states
what the source deployment actually runs, and the importer decides what to do with a reference
it cannot satisfy: `merge` answers it from the target's own copy, `create` drops the step and
logs a warning. See [Import/Export an Agent → Selecting What to Export](import-export-an-agent.md#selecting-what-to-export)
for the three-state semantics of each parameter and for the archive retention window.

## See Also

- [Import/Export an Agent](import-export-an-agent.md) — ZIP-based import/export (create and merge strategies)
- [Agent Sync Architecture](agent-sync-architecture.md) — Internal architecture and matching algorithm details
- [Deployment Management](deployment-management-of-agents.md) — Deploying agents after sync

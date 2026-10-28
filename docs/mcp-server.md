# MCP Server — EDDI as MCP Tool Provider

EDDI exposes its agent conversation and administration capabilities via the **Model Context Protocol (MCP)**, enabling AI assistants (Claude Desktop, IDE plugins, custom MCP clients) to interact with deployed agents and manage the platform programmatically.

## Transport

EDDI uses **Streamable HTTP** transport, served by the Quarkus MCP Server extension (`quarkus-mcp-server-http`).

| Endpoint                    | Description                           |
| --------------------------- | ------------------------------------- |
| `http://localhost:7070/mcp` | MCP server endpoint (default + admin) |

**Client notes**

- **Protocol version warnings.** A client that announces an `MCP-Protocol-Version`
  newer than the bundled Quarkus MCP server knows makes the server log
  `Invalid MCP protocol header: <version>` on every call. The call still succeeds
  on the negotiated version; the line is noise until the extension is upgraded.
- **Retries are not idempotent.** Most tools that create things — `setup_agent`,
  `create_api_agent`, `create_group`, `create_schedule` — are not idempotent. If a
  call fails with a transport error such as "session expired", it may still have
  completed on the server. Check first (`list_agents`, `list_groups`, …) before
  retrying, or you get a duplicate.

## Available Tools (94)

### Argument contract

Every tool publishes its arguments in `tools/list`, and the server enforces that schema **before** the tool runs: an argument listed under `required` that is omitted — or sent as `null` — is refused with `Missing required argument: <name>` and the call never reaches EDDI.

- **Only what a tool cannot work without is required** — identifiers, the message, a configuration body, a verdict. Everything a description calls *optional*, *default: …* or *deprecated* may be left out; 128 of the 292 arguments are required, and `list_agents {}` is a complete call. (Up to 6.5.0 every argument was required in 75 of the 84 tools: quarkus-mcp-server makes a `@ToolArg` required unless it says otherwise.)
- **Defaults are real.** Where a default is a literal it is published as the JSON-schema `default` (e.g. `environment` → `production`, `limit` → `20`) and applied when the argument is absent. A `version` that defaults to **latest** resolves the current version (`get_agent`, `read_workflow`, `read_resource`, `list_agent_resources`, `read_channel_integration`, `read_group`); `list_conversations` without `agentVersion` lists every version.
- **Environment** is optional everywhere and means `production` when omitted; `talk_to_agent` and `read_conversation` ignore it (the conversation keeps its own).
- **Failures are tool errors.** A refused or failed call returns `isError: true` with a JSON body `{"error": "...", "errorCode": "..."}` — "Access denied", "not found", validation failures, and a missing role (`errorCode: FORBIDDEN`) alike. A successful call never carries `isError`. Results that report an outcome rather than a refusal stay ordinary results with an `error` *field* among others: `deploy_agent` when the deployment ends in `ERROR` (`"action": "deploy_failed"`), and `fire_schedule_now` when the fire itself failed. An unknown value is refused, not substituted: `create_group` with an unknown `style` answers `BAD_REQUEST` and lists the valid styles. Server-side failures name what failed (`errorCode: INTERNAL`) and keep the exception text in the server log.

`McpToolContractTest` holds every tool to this contract (the tool count, no optional-described argument required, every published default matching its description) and `McpRoleTierParityTest` measures the [role tiers](#role-mapping) tool by tool.

### Conversation Tools (11)

| Tool                    | Description                                                                                                                 |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `list_agents`           | List all deployed agents with status, version, and name                                                                     |
| `list_agent_configs`    | List all agent configurations (including undeployed)                                                                        |
| `create_conversation`   | Start a new conversation with a deployed agent                                                                              |
| `talk_to_agent`         | Send a message and get the agent's response                                                                                 |
| `chat_with_agent`       | Create a conversation and send a message in one call                                                                        |
| `read_conversation`     | Read conversation history, memory, and quick replies                                                                        |
| `read_conversation_log` | Read conversation log as formatted text                                                                                     |
| `list_conversations`    | List all conversations for a specific agent                                                                                 |
| `get_agent`             | Get an agent's full configuration (packages, name, description)                                                              |
| `discover_agents`       | Discover deployed agents enriched with intent mappings from agent triggers. Best way to find agents by purpose              |
| `chat_managed`          | Send a message using intent-based managed conversations (one conversation per intent+userId, auto-creates on first message) |

### Admin Tools (14)

| Tool                    | Description                                                                     |
| ----------------------- | ------------------------------------------------------------------------------- |
| `deploy_agent`          | Deploy an agent version to an environment                                        |
| `undeploy_agent`        | Undeploy an agent from an environment                                            |
| `upgrade_conversations` | Move an agent's running conversations on older compatibility generations onto the current one — dry run by default, see [Moving running conversations onto the current version](deployment-management-of-agents.md#moving-running-conversations-onto-the-current-version) |
| `get_deployment_status` | Get deployment status of a specific agent version                               |
| `list_workflows`        | List all packages (pipeline configurations)                                     |
| `create_agent`          | Create a new agent                                                              |
| `delete_agent`          | Delete an agent (with optional cascade)                                          |
| `update_agent`          | Update an agent's name/description and optionally redeploy                       |
| `read_workflow`         | Read a package's full pipeline configuration                                    |
| `read_resource`         | Read any resource config by type (behavior, langchain, httpcalls, output, rag, etc.) — read only; `rag` has no create/update/delete case |
| `list_agent_triggers`   | List all agent triggers (intent→agent mappings) for managed conversations       |
| `create_agent_trigger`  | Create an agent trigger mapping an intent to one or more agent deployments       |
| `update_agent_trigger`  | Update an existing agent trigger                                                |
| `delete_agent_trigger`  | Delete an agent trigger for a given intent                                       |

### Resource CRUD Tools (5)

| Tool                   | Description                                                                         |
| ---------------------- | ----------------------------------------------------------------------------------- |
| `update_resource`      | Update any resource config by type and ID. Returns the new version URI              |
| `create_resource`      | Create a new resource. Returns the new resource ID and URI                          |
| `delete_resource`      | Delete a resource (soft-delete by default, `permanent=true` for hard delete)        |
| `apply_agent_changes`  | Batch-cascade URI changes through package → agent in ONE pass, optionally redeploy. `compatible: true` declares the new agent version compatible, so running conversations follow it; the default is a breaking change. An `oldUri` the agent does not hold, or a pair naming different kinds of resource, is refused and nothing is written. The same operation is available over REST as `POST /agentstore/agents/{id}/cascade` ([details](deployment-management-of-agents.md#landing-resource-changes-in-one-pass))  |
| `list_agent_resources` | Walk agent → packages → extensions to get a complete resource inventory in one call |

### Diagnostic Tools (2)

| Tool               | Description                                                                                |
| ------------------ | ------------------------------------------------------------------------------------------ |
| `read_agent_logs`  | Read server-side pipeline logs (errors, LLM timeouts) filtered by agent/conversation/level. An unscoped or agent-only read additionally requires `eddi-admin` — it pulls from a shared buffer mixing every user's logs; only a `conversationId`-scoped read is open to `eddi-viewer` (and only for a conversation the caller owns) |
| `read_audit_trail` | Read per-task audit entries with LLM details, timing, cost, and tool calls                 |

### Setup Tools (2)

| Tool               | Description                                                                                                                                                                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `setup_agent`      | Create a fully working agent in one call: creates behavior rules, LangChain config, optional output/greeting, package, agent, and deploys. Supports built-in tools, quick replies, and sentiment analysis. Default: `anthropic`/`claude-sonnet-4-6` |
| `create_api_agent` | Create an agent from an OpenAPI 3.0/3.1 spec. Parses the spec, generates HttpCalls configs (grouped by API tag), creates the full pipeline, and deploys. Supports endpoint filtering, base URL override, auth header propagation, and `mcpServerUrls` to add an MCP server's tools alongside the generated ones. A generated write tool takes the whole request body as one `requestBody` parameter — see below |

> **The approval gate is not settable over MCP.** `POST /administration/agents/setup-api` accepts a `hitlConfig` on the request body, so a caller can provision an agent whose write tools are gated from v1 onward. The MCP `create_api_agent` tool deliberately has **no** such parameter and always passes `null`: it already provisions an agent with a caller-chosen endpoint filter, so letting the caller also choose the gate would make it a complete escape from whatever allow-list governs the agent doing the calling. Provisioning a gated agent goes through REST (`eddi-admin`).

### Schedule Management Tools (6)

| Tool                    | Description                                                                                                                                                                                         |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `create_schedule`       | Create a new scheduled agent trigger (cron job or heartbeat). For CRON: provide `cron`. For HEARTBEAT: provide `heartbeatIntervalSeconds`. Heartbeats default to persistent conversations |
| `list_schedules`        | List all scheduled agent triggers with name, type, cron/interval, status, next fire time, and fire count. Optionally filter by agentId                                                              |
| `read_schedule`         | Read a schedule's full configuration including recent fire history (last 10 executions)                                                                                                             |
| `delete_schedule`       | Delete a scheduled agent trigger                                                                                                                                                                    |
| `fire_schedule_now`     | Manually trigger a schedule fire immediately. Useful for testing or one-off executions                                                                                                              |
| `retry_failed_schedule` | Re-queue a dead-lettered schedule for another fire attempt after fixing the cause of failure                                                                                                        |

### Group Conversation Tools (18)

| Tool                        | Description                                                                                                                                                |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `describe_discussion_styles` | Rich descriptions of all seven built-in discussion styles plus `CUSTOM`, with phase flows, member roles, and use cases |
| `list_groups`               | List all group configurations with name, style, member count                                                                                               |
| `read_group`                | Read a group configuration's full details                                                                                                                  |
| `create_group`              | Create a group (members, moderator, style, roles, member types, tasks). Supports nested groups via `memberTypes=GROUP` and pre-configured TASK_FORCE tasks via `tasks` param |
| `update_group`              | Update a group configuration (full JSON replacement)                                                                                                       |
| `delete_group`              | Delete a group configuration                                                                                                                               |
| `discuss_with_group`        | Start a multi-agent discussion on a question. Returns full transcript + synthesized answer                                                                 |
| `read_group_conversation`   | Read a group conversation transcript                                                                                                                       |
| `list_group_conversations`  | List past group discussions for a group, with state and timestamps                                                                                         |
| `start_group_discussion`    | Start a discussion asynchronously (returns immediately with groupConversationId). Poll with `read_group_conversation`                                     |
| `delete_group_conversation` | Delete a group conversation. Its shared artifacts and any ephemeral agents are deleted; member conversations are **ended**, not deleted, and remain readable |
| `followup_with_member`      | Ask one member a follow-up on a finished discussion. The agent retains its full context; question and answer are both recorded on the group transcript. Accepts an agent ID or a member's display name |
| `continue_group_discussion` | Continue a finished discussion with a new question. Every member re-runs the phases retaining memory of prior rounds; the round counter increments        |
| `close_group_conversation`  | Close a conversation permanently — ends member conversations and cleans up dynamically-created agents. No further follow-ups or continuations             |
| `add_team_task`             | File a task on a standing team's backlog (I13). The backlog outlives any one discussion; cadences pull executable tasks from it into task-force runs      |
| `list_team_backlog`         | List a standing team's backlog (I13) with each task's status, priority, assignee, and verification outcome                                                |
| `list_group_templates`      | List the packaged group templates (I10), each naming the roles `create_group_from_template` expects                                                       |
| `create_group_from_template` | Create a group from a template by assigning agents to its named roles (`roleAssignments` maps role → agent ID, or principal ID for HUMAN roles). Saves through the normal store path, so every save-time validation applies |

See [Group Conversations](group-conversations.md) for full style details, custom phases, and nested groups.

**Errors.** A failure the caller can fix — a validation refusal (for example a member
listed twice), unparseable JSON in an argument, a stale version, a 4xx from the store — comes
back as `{"error": "Failed to <action>: <reason>"}`, and an unknown group or conversation as
`errorCode: NOT_FOUND`. Anything else is this server's problem, and its raw message
(datastore hosts, driver text, class names) is **not** returned: the tool answers
`{"error": "Failed to <action> with an internal error (reference 1a2b3c4d)…",
"errorCode": "INTERNAL", "details": {"reference": "1a2b3c4d"}}` and the full exception is
logged at `ERROR` under `[reference 1a2b3c4d]` — quote that to an operator.

### Docs Tools (2)

Read EDDI's own documentation over MCP **tools** — the counterpart to the `eddi://docs/*` **resources** below, and the pair the `toolsWhitelist: ["read_docs", "list_docs"]` example further down consumes. Tools and resources serve different clients: agentic MCP clients (EDDI's own included) consume `tools/list` and never call `resources/read`, so before these existed EDDI's docs were readable by a desktop client and not by any agent consuming EDDI's MCP server. Role set mirrors the REST docs endpoints exactly (any of the five roles). Both delegate to `DocsService`, so `eddi.docs.enabled=false` switches this surface off together with REST and the resources.

| Tool        | Description                                                                                                   |
| ----------- | ------------------------------------------------------------------------------------------------------------- |
| `list_docs` | List the documentation pages this deployment serves (one name per line, no `.md` suffix). Read this first — the runtime set is smaller than the repository's |
| `read_docs` | Read one page as markdown by name, e.g. `architecture`. Distinguishes an invalid name from an absent page      |

### Version Tool (1)

Which build of EDDI is running. Read live from the process, so it stays correct across an upgrade; the same answer as `GET /administration/version`. `eddi-admin` only.

| Tool               | Description                                                                                                       |
| ------------------ | ----------------------------------------------------------------------------------------------------------------- |
| `get_eddi_version` | Version and git commit of the running EDDI (`version`, `commit`, `commitShort`). `commit` is `unknown` if the build recorded none |

### Agent Test Tools (4)

Run golden-conversation regression suites against an agent version — see [Agent Testing](agent-testing.md). Same tier as `deploy_agent` (`eddi-admin`, `eddi-editor`), because a run deploys the version under test to the `test` environment.

| Tool                   | Description                                                                                                  |
| ---------------------- | ------------------------------------------------------------------------------------------------------------ |
| `run_agent_tests`      | Start a run (`suiteId`, `agentId`, optional versions, `keepConversations`). Returns the `RUNNING` record at once |
| `get_agent_test_run`   | Read a run: status, per-case / per-turn failed expectations with actual values, tool calls, cost              |
| `list_agent_test_runs` | An agent's runs, newest first                                                                                |
| `cancel_agent_test_run` | Stop a `RUNNING` run (`runId`): no further turn starts and its conversations are ended and deleted; it reads `CANCELLED` moments later. Only on the node executing the run |

### Rating Tools (4)

Read and triage what the people chatting thought of an agent — see [Conversation Ratings](conversation-ratings.md#analyzing-and-triaging-the-people-running-the-agent). Tier `eddi-admin`, `eddi-editor`, equal to the REST `/ratings` resource; `eddi-viewer` is refused, because a rating carries the rater's comment on a conversation the viewer could not open. The tools call the in-process rating resource, so its scoping applies to the MCP caller unchanged: an administrator sees every agent; anyone else must pass `agentId` and hold `EDIT` on it (`errorCode: AGENT_ID_REQUIRED` without it, `FORBIDDEN` without the permission); only administrators get the rater's `userId`. Refusals keep the REST codes as `errorCode` (`INVALID_PARAMETER`, `RATING_NOT_FOUND`, `RATING_CHANGED`, `NOTE_TOO_LONG`, `NOT_PERMITTED`). Export and bulk review stay REST-only.

| Tool                              | Description                                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `list_conversation_ratings`       | One page of ratings with the `/ratings` filters (`agentVersion`, `environment`, `from`/`to`, `minScore`/`maxScore`, `kind`, `tag`, `hasComment`, `reviewStatus`, `needsAttention`, `conversationId`), `sort` newest or lowest, `index`, `limit` ≤ 100 |
| `summarize_conversation_ratings`  | Aggregates (count, average, distribution, CSAT, thumbs-up rate, comments, top tags), `groupBy` none, day, week, agentVersion or channel |
| `count_ratings_needing_attention` | `{count}` of NEW ratings that are low (normalized ≤ 0.5) or carry a comment                                  |
| `review_conversation_rating`      | Mark one rating `REVIEWED` or reopen it as `NEW`, and/or set its review note. The reviewer is the caller's principal — there is no argument for it. A re-rating meanwhile answers `RATING_CHANGED` and changes nothing |

### HITL Tools (10)

Resolve Human-in-the-Loop approval gates over MCP — the counterpart to the REST HITL endpoints, at parity for both the regular (1:1) and group surfaces. Authorization mirrors REST exactly: the REST HITL role tier first (`eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-approver` — the `@RolesAllowed` of every REST HITL endpoint, so `eddi-viewer` is refused at the door here as there), then the shared `HitlAccessGuard` per conversation: owner / `eddi-admin` / `eddi-approver` for a decision (`resume_conversation`, `cancel_conversation`, `approve_group_phase`, `cancel_group_discussion`), while `get_group_approval_status` additionally admits the pending HUMAN member of an `AWAITING_HUMAN_INPUT` discussion (`requireGroupConversationReadAccess`) and `submit_group_human_input` admits only that pending member or an admin (`requireGroupHumanInputAccess` — the member speaking, not an approval); decisions are attributed server-side as `mcp:<principal>`. Mutating tools honour the `eddi.mcp.hitl.mutations.enabled` kill-switch and return structured errors (`errorCode` ∈ `NOT_FOUND | WRONG_STATE | PAUSE_CHANGED | FORBIDDEN | DISABLED | BAD_REQUEST | CONFLICT | INTERNAL`; `PAUSE_CHANGED` when a `pauseId` names a pause that is no longer current).

| Tool                              | Description                                                                                                                    |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `list_pending_approvals`          | List regular (1:1) conversations awaiting approval (owner-scoped; includes RULE and TOOL_CALL pauses)                          |
| `get_approval_status`             | Read a paused conversation's status; summary reports `pauseType`, `detail=full` returns the snapshot incl. any tool-call batch |
| `resume_conversation`             | Resume with APPROVED/REJECTED (case-insensitive); resolves both RULE and TOOL_CALL pauses                                      |
| `cancel_conversation`             | Cancel a paused or running conversation                                                                                        |
| `list_group_pending_approvals`    | List a group's conversations awaiting approval (owner-scoped)                                                                  |
| `list_all_group_pending_approvals`| Cross-group HITL inbox across all groups (owner-scoped)                                                                        |
| `get_group_approval_status`       | Read a paused group discussion's status (summary; `detail=full` returns the whole conversation)                               |
| `approve_group_phase`             | Approve/reject a paused phase, with optional `taskApprovals` JSON for TASK granularity and optional `pauseId` binding the decision to the pause reviewed; returns the resumed discussion |
| `submit_group_human_input`        | Submit a HUMAN member's response for the turn an `AWAITING_HUMAN_INPUT` discussion is waiting on (I6). Recorded as that member's transcript entry; the discussion resumes from the next speaker. Only the pending member's own principal (or an admin) may submit — this is the member **speaking**, not approving |
| `cancel_group_discussion`         | Cancel an in-progress or paused group discussion                                                                              |

See [HITL](hitl.md#mcp-surface) for the full authority model, the kill-switch, and REST-endpoint parity.

### Memory Tools (8)

| Tool                      | Description                                                                                          |
| ------------------------- | ---------------------------------------------------------------------------------------------------- |
| `list_user_memories`      | List all persistent memory entries for a user                                                        |
| `get_visible_memories`    | Get memories visible to a specific agent, considering self/group/global visibility scopes             |
| `search_user_memories`    | Search user memories by keyword across keys and values                                               |
| `get_memory_by_key`       | Get a specific memory entry by key for a user                                                        |
| `upsert_user_memory`      | Create or update a persistent memory entry for a user                                                |
| `delete_user_memory`      | Delete a specific memory entry by ID                                                                 |
| `delete_all_user_memories` | Delete a user's memory entries, keeping `_gdpr_` bookkeeping (use `delete_user_data` for Art. 17)   |
| `count_user_memories`     | Count total memory entries for a user                                                                |

See [User Memory](user-memory.md) for visibility scoping, recall order, and dream consolidation.

### GDPR Tools (2)

| Tool               | Description                                                                                                                      |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| `delete_user_data` | Cascade-delete all user data across all stores (GDPR Art. 17 Right to Erasure). Requires `confirmation='CONFIRM'`. Irreversible |
| `export_user_data` | Export all data for a user (GDPR Art. 15/20 Right of Access / Data Portability)                                                  |

See [GDPR / CCPA Compliance](gdpr-compliance.md) for data erasure, export, and retention details.

### Channel Integration Tools (5)

| Tool                            | Description                                                                                                     |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `list_channel_integrations`     | List all channel integrations with name, type, and target count                                                 |
| `read_channel_integration`      | Read a channel integration's full configuration                                                                 |
| `create_channel_integration`    | Create a new channel integration (Slack, Teams, etc.) with platform config and agent targets                    |
| `update_channel_integration`    | Update an existing channel integration                                                                          |
| `delete_channel_integration`    | Delete a channel integration (soft or permanent)                                                                |

See [Slack Integration](slack-integration.md) for Slack-specific setup and multi-agent thread discussions.

## MCP Resources

EDDI also exposes its documentation as MCP **resources**, allowing AI agents to browse and read the docs programmatically.

| Resource             | Description                                               |
| -------------------- | --------------------------------------------------------- |
| `eddi://docs/index`  | List all available documentation pages                    |
| `eddi://docs/{name}` | Read a specific doc (e.g., `eddi://docs/getting-started`) |

Configure the docs path with: `eddi.docs.path` (default: `docs/`, in Docker: `/deployments/docs`).

The resources are guarded by the same roles as the docs tools and REST (any of the five EDDI roles); a refusal is a JSON-RPC error with code `-32005` (security error), since a resource read has no `isError` result. Neither the resources nor the tools name the server's docs directory when it is missing or disabled.

### The same docs over REST

> **MCP resources do not reach an EDDI agent.** A resource is only usable by a client that asks for it, and EDDI's own MCP client never calls `resources/read` — it consumes *tools*. So `eddi://docs/*` made EDDI's documentation readable by a desktop MCP client and not by an agent running on EDDI, which is precisely backwards for an agent whose job is to explain the platform.

The same doc set is therefore served read-only over REST, where an agent generated from EDDI's OpenAPI spec picks it up as ordinary tools:

| Endpoint | Role | Returns |
| -------- | ---- | ------- |
| `GET /administration/docs` | any of `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-approver`, `eddi-viewer` | JSON array of page names, without the `.md` suffix |
| `GET /administration/docs/{name}` | same | The page's markdown source as `text/plain`; `404` if absent |

> **Roles are enumerated, not inherited.** A role check is a literal `hasRole`, in JAX-RS `@RolesAllowed` and in the MCP tools alike, so every tier lists each role it admits. The widest read tier is spelled out because these are published documentation pages.

Both surfaces delegate to `DocsService`, which owns the filesystem access and the path-traversal guard.

> **The runtime doc set is smaller than the repository's.** The container image copies only top-level `docs/*.md` (non-recursive, so nothing under `docs/agent-configs/` or `docs/templates/` is reachable) and then removes `changelog.md`, `code-review-standards.md`, `incident-response.md` and `SUMMARY.md`. Call the index and read from it — do not assume a particular page exists.

## Quick Start

### Client Configuration

EDDI uses **Streamable HTTP** transport at `http://localhost:7070/mcp`. How you connect depends on your client's transport support.

> The configurations below are for an instance with authentication **off**. If OIDC is enabled, each one additionally needs a bearer token — see [Connecting to an authenticated instance](#connecting-to-an-authenticated-instance).

#### Direct HTTP (Streamable HTTP clients)

Clients that natively support HTTP transport (e.g., IDE plugins, custom MCP clients) can connect directly:

```json
{
  "mcpServers": {
    "eddi": {
      "url": "http://localhost:7070/mcp"
    }
  }
}
```

#### Antigravity (Google)

Add EDDI as an MCP server in your Antigravity settings (`.gemini/config/settings.json` or workspace `.agents/settings.json`):

```json
{
  "mcpServers": {
    "eddi": {
      "serverUrl": "http://localhost:7070/mcp"
    }
  }
}
```

Antigravity connects natively via Streamable HTTP — no bridge required.

#### stdio Bridge (Claude Desktop, Cursor, Windsurf, etc.)

Many MCP clients — including Claude Desktop's `claude_desktop_config.json` — only support **stdio** transport (spawning a local subprocess). They cannot connect to HTTP endpoints directly.

Use [`mcp-remote`](https://github.com/geelen/mcp-remote) to bridge the gap. It runs as a local stdio process and proxies requests to EDDI's HTTP endpoint:

```json
{
  "mcpServers": {
    "eddi": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:7070/mcp"]
    }
  }
}
```

**Windows users** — if `npx` is not on your shell PATH, wrap via `cmd`:

```json
{
  "mcpServers": {
    "eddi": {
      "command": "cmd",
      "args": ["/c", "npx", "-y", "mcp-remote", "http://localhost:7070/mcp"]
    }
  }
}
```

> **What is `mcp-remote`?** An open-source npm package ([github.com/geelen/mcp-remote](https://github.com/geelen/mcp-remote)) that acts as an invisible bridge between stdio-only MCP clients and HTTP-based MCP servers. It handles protocol translation, session management, and authentication. Requires Node.js 18+.

### Claude Code plugin

Connecting gives an assistant EDDI's tools. It does not tell the assistant which tools to chain, which configuration mistakes deploy cleanly and then fail, or which actions need a person's yes. The [EDDI plugin for Claude Code](../integrations/claude-plugin/README.md) adds that as two skills: `eddi` for anyone who talks to EDDI's agents (discovery, conversations, group discussions, approvals) and `eddi-admin` for editors and administrators (create, change → test → promote, agent tests, debugging, operations). Install it with `/plugin marketplace add labsai/EDDI`, then `/plugin install eddi@eddi`. For anything version-specific the skills read this deployment's own docs through `list_docs` / `read_docs`. A build test fails if a skill names a tool that is not registered here.

### Example Workflow

```
1. list_agents → see deployed agents
2. create_conversation(agentId: "my-agent") → get conversationId
3. talk_to_agent(agentId: "my-agent", conversationId: "...", message: "Hello!") → get response
4. read_conversation_log(conversationId: "...") → see full history
```

### Discovering Agents by Purpose

```
1. discover_agents() → enriched list with intents per agent
2. list_agent_triggers() → see all intent→agent mappings
```

### Intent-Based Managed Chat

```
1. create_agent_trigger(config: {"intent":"support","agentDeployments":[{"agentId":"agent-123"}]})
2. chat_managed(intent: "support", userId: "user1", message: "Hello!") → auto-creates conversation
3. chat_managed(intent: "support", userId: "user1", message: "I need help") → reuses same conversation
```

### Inspecting Agent Configuration

```
1. list_agent_resources(agentId: "my-agent") → complete resource inventory in one call
2. read_resource(resourceType: "langchain", resourceId: "lc-456") → see LLM config details
```

### Modifying Resources + Cascade

```
1. read_resource("langchain", "lc-456") → get current config
2. update_resource("langchain", "lc-456", version: 1, config: {...}) → new version 2
3. apply_agent_changes(agentId, agentVersion, [{oldUri: "...?version=1", newUri: "...?version=2"}], redeploy: true)
```

### Debugging an Agent

```
1. read_agent_logs(agentId: "my-agent") → see pipeline errors, LLM timeouts
2. read_audit_trail(conversationId: "conv-123") → per-task execution details, LLM tokens, cost
```

### Scheduling a Cron Job

```
1. create_schedule(agentId: "my-agent", triggerType: "CRON", cron: "0 9 * * MON-FRI",
     message: "Daily morning check-in", name: "Weekday Morning Check")
   → { scheduleId: "sched-1", description: "At 09:00 on every weekday", nextFire: "..." }
2. list_schedules() → see all scheduled triggers with status
3. fire_schedule_now(scheduleId: "sched-1") → test immediately
4. read_schedule(scheduleId: "sched-1") → see full config + fire logs
```

### Setting Up a Heartbeat

```
1. create_schedule(agentId: "my-agent", triggerType: "HEARTBEAT", heartbeatIntervalSeconds: 300,
     name: "Health Heartbeat")
   → { scheduleId: "hb-1", description: "Every 5 minutes", conversationStrategy: "persistent" }
   # Heartbeats default to: persistent conversation, "heartbeat" message, drift-proof scheduling
2. read_schedule(scheduleId: "hb-1") → check next fire time and conversation ID
3. retry_failed_schedule(scheduleId: "hb-1") → requeue if dead-lettered
```

### Running a Multi-Agent Discussion

```
1. describe_discussion_styles → see all available styles with examples
2. create_group(name: "Architecture Review", memberAgentIds: "expert-1,expert-2",
     moderatorAgentId: "moderator", style: "PEER_REVIEW")
   → { groupId: "g1" }
3. discuss_with_group(groupId: "g1", question: "Should we use microservices?")
   → { transcript: [...], synthesizedAnswer: "Based on all perspectives..." }
4. list_group_conversations(groupId: "g1") → browse past discussions
```

---

## Tool Reference — Agent Discovery & Managed Conversations

EDDI provides **two tiers** of conversation management:

| Tier          | Tools                                   | Conversations                       | Use Case                                 |
| ------------- | --------------------------------------- | ----------------------------------- | ---------------------------------------- |
| **Low-level** | `create_conversation` + `talk_to_agent` | Multiple per user, manually managed | Custom apps, multi-conversation UIs      |
| **Managed**   | `chat_managed`                          | One per intent+userId, auto-created | Single-window chat, intent-based routing |

The managed tier relies on **agent triggers** — mappings from an _intent_ string to one or more agent deployments. Use the discovery and trigger tools below to configure and interact with this system.

### `discover_agents`

Discover deployed agents with their capabilities. Returns an enriched list of deployed agents, cross-referenced with intent mappings from agent triggers. This is the **best way to find agents by purpose**.

**Parameters:**

| Parameter     | Type   | Required | Default        | Description                                              |
| ------------- | ------ | -------- | -------------- | -------------------------------------------------------- |
| `filter`      | string | No       | `""`           | Filter agents by name (case-insensitive substring match) |
| `environment` | string | No       | `"production"` | Environment: `production` (default) or `test`            |

**Response:**

```json
{
  "count": 80,
  "agents": [
    {
      "agentId": "692f7fe8...",
      "name": "Bob Marley 2",
      "description": "gemini powered Agent",
      "version": 1,
      "status": "READY",
      "environment": "production",
      "intents": ["bob-marley-2-692f7fe8d6c14292d2b7f70c"]
    },
    {
      "agentId": "64513b3c...",
      "name": "Platform Operator",
      "description": "Agent that reads and operates this EDDI deployment...",
      "version": 110,
      "status": "READY",
      "environment": "production"
    }
  ]
}
```

> **Note:** The `intents` array only appears for agents that have agent triggers configured. Agents without triggers are still returned — they can be interacted with via `chat_with_agent` (low-level tier) but not via `chat_managed`.

---

### `chat_managed`

Send a message to an agent using **intent-based managed conversations**. Unlike `chat_with_agent` (which requires a agentId and creates multiple conversations), this tool uses an _intent_ to find the right agent and maintains **exactly one conversation per intent+userId** — like a single chat window.

The conversation is auto-created on first message and reused on subsequent calls. Requires an agent trigger to be configured for the intent (see `list_agent_triggers` / `create_agent_trigger`).

**Parameters:**

| Parameter     | Type   | Required | Description                                                              |
| ------------- | ------ | -------- | ------------------------------------------------------------------------ |
| `intent`      | string | **Yes**  | Intent that maps to an agent trigger. E.g. `"customer_support"`           |
| `userId`      | string | **Yes**  | User ID for conversation management (one conversation per intent+userId) |
| `message`     | string | **Yes**  | The user message to send                                                 |
| `environment` | string | No       | Environment: `production` (default) or `test`                            |

**Response:**

```json
{
  "environment": "production",
  "conversationId": "69bc8b93...",
  "agentId": "692f7fe8...",
  "userId": "user-123",
  "intent": "bob-marley-2-692f7fe8...",
  "actions": ["send_message", "unknown"],
  "conversationState": "READY",
  "response": {
    "conversationOutputs": [{
      "output": [{ "type": "text", "text": "Hello there! ..." }]
    }],
    "conversationSteps": [...]
  }
}
```

**Behavior:**

- **First call** with a new intent+userId: creates a new conversation and sends the message
- **Subsequent calls** with the same intent+userId: reuses the existing conversation (like continuing in the same chat window)
- Returns an error if no agent trigger is configured for the given intent

---

### `list_agent_triggers`

List all agent triggers (intent→agent mappings). Returns all configured intents with their agent deployments. Agent triggers enable intent-based conversation management via `chat_managed`.

**Parameters:** None.

**Response:**

```json
{
  "count": 48,
  "triggers": [
    {
      "intent": "customer_support",
      "agentDeployments": [
        {
          "environment": "production",
          "agentId": "6544db9b...",
          "initialContext": {}
        }
      ]
    }
  ]
}
```

> **Tip:** Each trigger can map to **multiple agent deployments** — useful for A/B testing or environment-specific routing.

---

### `create_agent_trigger`

Create an agent trigger that maps an intent to one or more agents. Once created, the intent can be used with `chat_managed` to talk to the agent.

**Parameters:**

| Parameter | Type          | Required | Description                                   |
| --------- | ------------- | -------- | --------------------------------------------- |
| `config`  | string (JSON) | **Yes**  | Full trigger configuration (see schema below) |

**Config schema:**

```json
{
  "intent": "customer_support",
  "agentDeployments": [
    {
      "agentId": "64513b3c...",
      "environment": "production",
      "initialContext": {
        "language": { "type": "string", "value": "en" }
      }
    }
  ]
}
```

| Field                               | Type   | Required | Description                                                                        |
| ----------------------------------- | ------ | -------- | ---------------------------------------------------------------------------------- |
| `intent`                            | string | **Yes**  | Unique intent identifier. Convention: `slug-agentId` (e.g. `support-agent-abc123`) |
| `agentDeployments`                  | array  | **Yes**  | List of agent deployments this intent routes to                                    |
| `agentDeployments[].agentId`        | string | **Yes**  | The agent ID to route messages to                                                  |
| `agentDeployments[].environment`    | string | No       | Deployment environment (default: `production`)                                     |
| `agentDeployments[].initialContext` | object | No       | Key-value pairs injected into the conversation context on creation                 |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "created" }
```

---

### `update_agent_trigger`

Update an existing agent trigger. Changes the agent deployments for a given intent (e.g., to point to a new agent version, add A/B routing, or change the initial context).

**Parameters:**

| Parameter | Type          | Required | Description                                                                |
| --------- | ------------- | -------- | -------------------------------------------------------------------------- |
| `intent`  | string        | **Yes**  | The intent to update                                                       |
| `config`  | string (JSON) | **Yes**  | Full updated trigger configuration (same schema as `create_agent_trigger`) |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "updated" }
```

---

### `delete_agent_trigger`

Delete an agent trigger for a given intent. After deletion, `chat_managed` calls with this intent will return an error. Existing conversations are **not** deleted — they become orphaned but can still be read.

**Parameters:**

| Parameter | Type   | Required | Description          |
| --------- | ------ | -------- | -------------------- |
| `intent`  | string | **Yes**  | The intent to delete |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "deleted" }
```

---

### End-to-End Example: Setting Up Managed Chat

```
# 1. Create an agent (using setup_agent or the Manager UI)
setup_agent(agentName: "Support Agent", systemPrompt: "You are a helpful support agent...", ...)
→ { agentId: "abc123", version: 1, status: "deployed" }

# 2. Create a trigger mapping an intent to this agent
create_agent_trigger(config: {
  "intent": "customer_support",
  "agentDeployments": [{ "agentId": "abc123", "environment": "production" }]
})

# 3. Chat using the intent — conversation auto-created
chat_managed(intent: "customer_support", userId: "user-1", message: "I need help with billing")
→ { conversationId: "conv-789", response: { output: "I'd be happy to help..." } }

# 4. Continue the same conversation (same conversationId reused)
chat_managed(intent: "customer_support", userId: "user-1", message: "Can you check order #1234?")
→ { conversationId: "conv-789", response: { output: "Let me look that up..." } }

# 5. Different user gets their own conversation
chat_managed(intent: "customer_support", userId: "user-2", message: "Hello")
→ { conversationId: "conv-999", response: { output: "Welcome! How can I help?" } }

# 6. Discover what's available
discover_agents(filter: "Support") → shows the agent with its intent
```

## Configuration

In `application.properties`:

```properties
# MCP Server — Streamable HTTP at /mcp
quarkus.mcp.server.http.root-path=/mcp

# Documentation path for MCP resources (default: docs/)
eddi.docs.path=docs

# EDDI's langchain4j agent tools (calculator, web scraper, ...) are not MCP tools
quarkus.mcp.server.support-langchain4j-annotations=false
```

> The namespace is `quarkus.mcp.server.*` with dots. The hyphenated
> `quarkus.mcp-server.*` is not a key the extension knows — setting it moves
> nothing and only logs an "Unrecognized configuration key" warning.

## Tool Filtering

EDDI uses a **whitelist-based `ToolFilter`** (`McpToolFilter.java`) to control which tools are exposed via MCP.

**Why?** EDDI's langchain4j integration registers internal agent tools (calculator, datetime, websearch, etc.) that are meant ONLY for agent pipeline execution — not for external MCP clients. The extension's discovery of langchain4j `@Tool` beans is switched off (`quarkus.mcp.server.support-langchain4j-annotations=false`), so those tools are not registered at all; the filter remains as a second line of defence. The `ToolFilter` SPI only sees a tool's *name* (not its declaring class or annotation type), so the whitelist is by name. It currently exposes all 94 intended tools — conversation, admin/resource/schedule/channel, setup, **build identity** (`McpVersionTools`), group, **HITL approvals** (`McpHitlTools`), **persistent user memory** (`McpMemoryTools`), **GDPR/CCPA** (`McpGdprTools`), **docs** (`McpDocTools`), **agent tests** (`McpAgentTestTools`) and **conversation ratings** (`McpRatingTools`).

To add a new MCP tool: add its name to the `MCP_TOOLS` set in `McpToolFilter.java`. A quarkus-MCP `@Tool` has no other invocation path, so a tool that is *not* whitelisted is unreachable dead code. `McpToolFilterTest.test_allMcpToolMethods_areWhitelisted()` auto-discovers every `@Tool` in the `engine.mcp` package and fails the build if any is missing from the whitelist — so forgetting this step is caught by CI.

## Authentication & Authorization

- The MCP endpoint inherits EDDI's existing OIDC/Keycloak authentication
- When auth is enabled (`quarkus.oidc.tenant-enabled=true`), MCP clients must provide valid tokens
- Authorization is enforced **in-code**, not via `@RolesAllowed`: each tool checks its [role tier](#role-mapping) (`requireAnyRole(identity, authEnabled, McpRoles.<TIER>)`; the tiers live in `McpRoles`), and the HITL tools check the **HITL** tier and then the shared `HitlAccessGuard` (per-conversation owner / `eddi-admin` / `eddi-approver`). A refusal is a tool error (`isError: true`, `errorCode: FORBIDDEN`). When `authorization.enabled=false` (the default dev posture) the role check is a no-op — production is guarded by `AuthStartupGuard`, which fails startup if OIDC is disabled.
- **Future**: Per-agent MCP access control via agent configuration for multi-tenant SaaS

### Connecting to an authenticated instance

The [Quick Start](#quick-start) configurations above assume an instance with authentication off. When `quarkus.oidc.tenant-enabled=true`, `/mcp` carries an explicit `authenticated` HTTP policy and every request needs a bearer token.

#### The client signs itself in (preferred)

EDDI advertises `/mcp` as an **OAuth 2.0 protected resource** ([RFC 9728](https://datatracker.ietf.org/doc/html/rfc9728)), which is what lets an MCP client obtain its own token and keep refreshing it — no shared credential, nothing for an operator to rotate. What ends that chain is not the 5-minute access token but the realm's **SSO session idle timeout** (Keycloak's default is 30 minutes): a client idle longer than that runs the browser flow again. The shipped realm removes that limit: `eddi-mcp` issues **offline tokens**, which outlive the SSO session and Keycloak restarts and expire only after **30 days without use** (`offlineSessionIdleTimeout`; set *Offline Session Max Limited* on the realm for a hard cap as well). An MCP client then signs in once and keeps refreshing. Keycloak issues an offline token only when three things hold, and each was verified against Keycloak 26.8.0 with this realm imported: (1) `offline_access` is a **Default** client scope of `eddi-mcp` — not *Optional*, because a client requests exactly the scopes the protected-resource document lists in `scopes_supported`, which is `openid`, so an optional scope is never asked for. Do not add `offline_access` to `quarkus.oidc.resource-metadata.scopes` instead: on a realm whose client lacks the scope, Keycloak fails the whole login with `invalid_scope`. (2) The `offline_access` role is in `eddi-mcp`'s **scope mapping** — the client has `fullScopeAllowed=false`, and without the mapping Keycloak answers *Offline tokens not allowed for the user or client*. (3) The **user** holds the `offline_access` role. Users created later get it from `default-roles-eddi`; the seed users carry explicit roles, so it is listed on each of them. The Manager's `eddi-frontend` client deliberately does not get offline tokens — they would sit in browser storage. An offline token is a 30-day credential on the client's disk: revoke one under *Users* → the user → *Consents* → `eddi-mcp` → *Revoke*. To opt out, remove `offline_access` from `eddi-mcp`'s default client scopes; MCP logins then end with the SSO session again, and raising the realm's `ssoSessionIdleTimeout` is the remaining lever.

A client that supports the MCP authorization flow needs only the URL:

```json
{ "mcpServers": { "eddi": { "url": "https://eddi.example.com/mcp" } } }
```

Behind that, the client: reads `WWW-Authenticate: Bearer resource_metadata="…"` from the 401; fetches `/.well-known/oauth-protected-resource/mcp`, which names the resource and your Keycloak realm; discovers the realm's endpoints; runs authorization code + PKCE in your browser; and then keeps its own tokens.

Deployment notes:

- **The advertised identifier's scheme is the request's scheme** — `http` on a plain-http instance, `https` over TLS. `quarkus.oidc.resource-metadata.force-https-scheme` defaults to `false` here: it used to be `true`, which made every plain-http instance with authentication on advertise `https://localhost:7070/mcp`, a URL nothing served, unless the deployment remembered to override it (`McpOAuthDiscoveryConfigTest` now proves the scheme follows the request). Behind a **TLS-terminating proxy** the request arrives as plain `http`, so either turn on forwarded-header handling (`quarkus.http.proxy.proxy-address-forwarding=true`, which also fixes every other absolute URL EDDI builds — see [httpcalls.md](httpcalls.md)), set the absolute resource URL in the next bullet, or set `force-https-scheme=true` yourself. The compose auth stacks and the k8s auth overlay still set it to `false` explicitly, which is now the default; the chart derives `eddi.oidc.resourceMetadata.forceHttpsScheme` from whether `ingress.tls` is configured, which is right for the chart's own ingress and wrong wherever something outside the chart terminates TLS — set the value explicitly there.
- **Advertise an absolute resource identifier in production.** `quarkus.oidc.resource-metadata.resource` defaults to the relative MCP root path, so the origin in the identifier comes from each request's `Host` header. The client binds its token to that identifier; behind anything that does not pin `Host` (a shared proxy, a cache that does not key on it) a crafted header makes EDDI advertise a resource on someone else's origin. Set `QUARKUS_OIDC_RESOURCE_METADATA_RESOURCE=https://eddi.example.com/mcp` (Helm: `eddi.oidc.resourceMetadata.resource`); the relative default is there so localhost and `kubectl port-forward` work without configuration.
- **Keycloak behind a TLS-terminating proxy must trust its forwarded headers** (`KC_PROXY_HEADERS=xforwarded`). The shipped stacks set `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, so Keycloak builds the token, JWKS and userinfo URLs in its discovery document from the incoming request — plain `http` behind the proxy — while the issuer and authorization URLs come from `KC_HOSTNAME` and stay `https`. MCP clients follow the discovery document and refuse to send credentials to an `http` token endpoint (Claude Code: *Refusing to send credentials to non-https token endpoint*). The chart sets it automatically when `eddi.oidc.publicUrl` is https (`keycloak.proxyHeaders`), and `gcp/provision-vm.sh --https` sets it; any other proxy in front of Keycloak needs it set by hand. The proxy must then **overwrite** every `X-Forwarded-*` header rather than pass a client's own through — `X-Forwarded-Host` and `-Port` set from the connection, `X-Forwarded-For` set to the client address instead of appended to — or a caller chooses the host in its own discovery response and the address Keycloak records for it. Check with `curl -s https://<keycloak>/realms/eddi/.well-known/openid-configuration` — every endpoint must be `https`.
- **The authorization server that is advertised** is `quarkus.oidc.token.issuer` when set, falling back to `quarkus.oidc.auth-server-url`. In the shipped compose and helm deployments the latter is the cluster-internal Keycloak address, so leave `QUARKUS_OIDC_TOKEN_ISSUER` pointing at the public URL.

**The realm ships the client this uses: `eddi-mcp`.** Public, authorization code + PKCE (`S256` required), no direct access grant, and carrying the same protocol mappers as `eddi-frontend`. That last part is not a detail — the realm defines no `roles` client scope, so **a client without the `realm-roles` mapper issues tokens that authenticate and then fail every tool with "requires role"**, because EDDI reads roles from `realm_access/roles`. If you provision your realm by hand, copy those mappers.

Its redirect URIs are `http://localhost:*` and `http://127.0.0.1:*`. Verified against Keycloak 26.8.0, the version every compose, Kubernetes and Helm stack and the auth E2E tier pin: both wildcard forms match a loopback callback on any port, PKCE is genuinely required (a request without `code_challenge_method` is refused with `Missing parameter: code_challenge_method`), the password grant is refused, and a non-loopback redirect is refused with `Invalid parameter: redirect_uri`. **Which callback URL your particular client uses is its own business and not something this repo can verify** — if yours is not a loopback URL, add it to `eddi-mcp` in the admin console.

- **Point your client at the client id.** Discovery names the authorization server, not which client to be, so each client has to be told:
  - **Claude Code** — `claude mcp add --transport http --client-id eddi-mcp --callback-port 8080 eddi https://eddi.example.com/mcp`. The callback port is worth fixing: without it Claude Code picks a random one, and while `eddi-mcp`'s redirect URIs are wildcards that accept any port, a realm hardened to a single redirect URI would not. **[ext]** Some Claude Code versions attempt dynamic registration even with a client id configured, and fail with "Incompatible auth server: does not support dynamic client registration" — if you hit that, the hand-pasted token below is the fallback.
  - **`mcp-remote`** — `--static-oauth-client-info '{"client_id":"eddi-mcp"}'`.
  - Other clients have their own setting, and some support only dynamic registration. A client that insists on registering itself (RFC 7591) cannot work against this realm as shipped, because dynamic registration carries no protocol mappers and would hit exactly the role-less-token failure above.
- **Claude Desktop connectors redirect to `https://claude.ai/api/mcp/auth_callback`**, not to loopback. That is deliberately *not* in the shipped list: it means the authorization response for your EDDI passes through a third party's endpoint, which is an operator's decision to make rather than a default to inherit. Add it to `eddi-mcp` in the Keycloak admin console if you want it.

> **Upgrading an existing realm: you have to add this client yourself.** Keycloak's
> `--import-realm` does **not** re-import into a realm that already exists, and both shipped
> auth stacks keep its database in a named volume — so a realm provisioned before this
> release keeps its old client list. The symptom is specific: discovery works, your client
> follows the document to Keycloak, and Keycloak answers `invalid_client`. `install.sh`
> does not add it either; its realm repair only restores `eddi-frontend`'s client scopes.
>
> Either re-provision the realm (in development, `docker compose -f docker-compose.yml -f
> docker-compose.auth.yml down -v` and start again — this deletes your Keycloak data), or create the client by hand
> from `eddi-mcp` in [`keycloak/eddi-realm.json`](../keycloak/eddi-realm.json). Creating it
> in the admin console takes four things: **public** client, **Standard flow** on with
> **Direct access grants** off, `pkce.code.challenge.method` = `S256` under Advanced, and the
> redirect URIs. Then copy the three protocol mappers from `eddi-frontend` — `realm-roles`
> above all, because **without it every token authenticates and every tool answers "requires
> role"**.
>
> **Optional: long-lived MCP logins on an existing realm.** Without these steps MCP logins
> still work; they just end with the SSO session. The import will not add them, so in this
> order: (1) make sure every user who connects over MCP holds the `offline_access` realm
> role (*Realm roles* → `default-roles-eddi` → *Add associated role* covers users created
> from now on; existing users with explicit roles need it assigned directly); (2) on
> `eddi-mcp`, *Client scopes* → `eddi-mcp-dedicated` → *Scope* → assign the realm role
> `offline_access`; (3) last, *Client scopes* → *Add client scope* → `offline_access` as
> **Default**. The order matters: the Default scope is what makes Keycloak issue an
> offline token, and once it is there a user or client without the role is refused with
> *Offline tokens not allowed for the user or client* — the login fails instead of falling
> back to an ordinary token.

#### Supplying a token by hand (fallback)

For a client with no OAuth support, or for a quick test, the token can be pasted in.

**1. Get a token.** No client in the shipped realm permits the direct access (password) grant any more — a public client with it enabled hands a token to anyone who can reach Keycloak and knows one password. For a quick test, turn it on temporarily for `eddi-frontend` (admin console → *Clients* → `eddi-frontend` → *Capability config* → *Direct access grants*), run the command below, and **turn it off again**:

```bash
read -rsp "Password for eddi: " KC_PASSWORD && echo
printf '%s' "$KC_PASSWORD" | curl -s \
  -d grant_type=password -d client_id=eddi-frontend -d username=eddi \
  --data-urlencode "password@-" \
  http://localhost:8180/realms/eddi/protocol/openid-connect/token
```

> The password is read without echo and reaches `curl` on stdin, so it lands in
> neither your shell history nor the process table. `--data-urlencode` encodes it,
> which a password with `&` or `+` in it needs.

**2a. Clients that speak Streamable HTTP and accept headers** (IDE plugins, Antigravity, custom clients):

```json
{
  "mcpServers": {
    "eddi": {
      "url": "http://localhost:7070/mcp",
      "headers": { "Authorization": "Bearer <access_token>" }
    }
  }
}
```

**2b. stdio-only clients** keep the `mcp-remote` bridge and pass the header through it. `mcp-remote` splits arguments on whitespace, so put the whole value in an environment variable:

```json
{
  "mcpServers": {
    "eddi": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:7070/mcp", "--header", "Authorization:${AUTH_HEADER}"],
      "env": { "AUTH_HEADER": "Bearer <access_token>" }
    }
  }
}
```

**Caveats, in the order they will bite you:**

- **The token expires.** The shipped realm does not override Keycloak's default access-token lifespan of five minutes, and a static header never refreshes. This recipe is for trying things out, not for running an assistant against EDDI all day.
- **There is no long-lived API key for `/mcp`.** The only api-key surface in EDDI is the `/v1` OpenAI-compatible adapter (`eddi.openai-compat.api-key`), which is a different protocol — see [Open WebUI integration](open-webui-integration.md).
- **Roles decide which tools work.** A token whose realm roles are missing authenticates fine and then fails every tool with "requires one of roles" — see [Role Mapping](#role-mapping) below.
- **`/mcp` cannot be opened selectively.** `eddi.mcp.allow-unauthenticated` only lets a *fully* unauthenticated deployment boot past `HighValueSurfaceGuard`; it does not exempt `/mcp` on an instance where authentication is on.

### Role Mapping

These are the **actual Keycloak role strings** the tools check (not aliases). Every tool belongs to one tier, and each tier admits the roles of the REST endpoints it mirrors — the same agent, the same rights, whichever door the caller uses. The exceptions are spelled out in the table: `eddi-viewer` is an MCP-only role with no REST counterpart, and a few admin-only tools are narrower than the REST store they touch.

| Tier | Roles admitted | Tools | REST counterpart (same `@RolesAllowed`) |
| ---- | -------------- | ----- | --------------------------------------- |
| **Docs** | `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-approver`, `eddi-viewer` | `list_docs`, `read_docs`, and the `eddi://docs/*` resources | `/administration/docs` |
| **Converse** — every tool ownership-guarded | `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-viewer` | `create_conversation`, `talk_to_agent`, `chat_with_agent`, `chat_managed`, `read_conversation`, `read_conversation_log`, `list_conversations`, `discover_agents`; the group discussions (`describe_discussion_styles`, `discuss_with_group`, `start_group_discussion`, `read_group_conversation`, `list_group_conversations`, `followup_with_member`, `continue_group_discussion`, `close_group_conversation`, `delete_group_conversation`); the memory reads (`list_user_memories`, `get_visible_memories`, `search_user_memories`, `get_memory_by_key`, `count_user_memories`) | `/agents/*` and `/groups/*/conversations` — admin, editor, user. `eddi-viewer` is the addition: the MCP read-and-converse role, which only ever reaches the caller's own conversations and memories. The memory reads also admit `eddi-editor`, which the REST memory store (`eddi-admin`, `eddi-user`) does not — for the caller's own `userId` only |
| **Observe** — ownership-guarded | `eddi-admin`, `eddi-viewer` | `read_audit_trail`, and `read_agent_logs` *with* a `conversationId` | `/auditstore`, `/administration/logs` — admin only. An audit entry carries the LLM request (system prompt, tool calls and their arguments), which `eddi-user` cannot read over REST, so this tier is not the conversation tier: next to the admin it admits only `eddi-viewer`, the MCP observer role that has always held these two tools. An editor who needs them is granted `eddi-viewer`. Under enforced workspaces `read_audit_trail` also replaces each entry's `llmDetail.compiledPrompt` (the agent's system message and prompt template) with a redaction marker unless the caller holds `VIEW` on the agent: owning a conversation with an agent shared as `USE` does not reveal its configuration |
| **Author** | `eddi-admin`, `eddi-editor` | `list_agents`, `list_agent_configs`, `get_agent`; deployment (`deploy_agent`, `undeploy_agent`, `upgrade_conversations`, `get_deployment_status`); agent, workflow and resource CRUD (`create_agent`, `update_agent`, `delete_agent`, `list_workflows`, `read_workflow`, `read_resource`, `create_resource`, `update_resource`, `delete_resource`, `list_agent_resources`, `apply_agent_changes`); triggers (`*_agent_trigger`); channels (`*_channel_integration`); group configuration (`list_groups`, `read_group`, `create_group`, `update_group`, `delete_group`, `create_group_from_template`, `list_group_templates`, `add_team_task`, `list_team_backlog`); agent tests (`run_agent_tests`, `get_agent_test_run`, `list_agent_test_runs`, `cancel_agent_test_run`); conversation ratings (`list_conversation_ratings`, `summarize_conversation_ratings`, `count_ratings_needing_attention`, `review_conversation_rating`) | the configuration stores, `/administration/{env}/deploy…`, `/agenttriggerstore`, `/channelstore`, `/groupstore`, `/agenttests`, `/ratings` |
| **Admin** | `eddi-admin` | `setup_agent`, `create_api_agent`, `get_eddi_version`; the schedule tools (`create_schedule`, `list_schedules`, `read_schedule`, `delete_schedule`, `fire_schedule_now`, `retry_failed_schedule`); memory writes (`upsert_user_memory`, `delete_user_memory`, `delete_all_user_memories`); GDPR (`delete_user_data`, `export_user_data`); `read_agent_logs` *without* a `conversationId` (the shared server log) | `/administration/agents/setup(-api)`, `/administration/gdpr`, `/administration/logs`. **Deliberately narrower than REST** for schedules and memory writes: these tools act on any user's schedule or memory directly, without the per-owner scoping the REST endpoints apply to editors and users |
| **HITL** | `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-approver` — then decided per conversation by `HitlAccessGuard`: the owner, `eddi-admin`, `eddi-approver` | `resume_conversation`, `cancel_conversation`, `get_approval_status`, `list_pending_approvals`, and the group counterparts | the REST HITL endpoints (`/{conversationId}/resume`, `/cancel`, `/approval-status`, `/pending-approvals` and the group `/approve`, `/cancel`, `/human-input`, pending listings) — see [HITL](hitl.md#who-may-decide). Until 6.6.0 these tools had no role tier at all, so an `eddi-viewer` could decide its own gated tool calls over MCP while REST refused it (security review 2026-10-15, L5) |

> **Higher roles include the read tiers.** A role check is a literal `hasRole`, so each tier enumerates its roles rather than relying on a hierarchy — which gives `eddi-admin ⊇ eddi-editor ⊇ eddi-viewer` for the conversation tools and `eddi-admin ⊇ eddi-viewer` for the Observe tier. An admin no longer needs `eddi-viewer` as well, and an editor needs it only for `read_audit_trail` and conversation-scoped `read_agent_logs` (up to 6.5.0 an account without `eddi-viewer` was refused all 27 read tools, `list_agents` included). The tiers are defined once, in `McpRoles`.

> **Changed after 6.5.0** to remove the places MCP and REST disagreed — see [Upgrading from 6.5](upgrading-from-6.5.md#mcp-role-tiers) for what an existing MCP client loses and gains: `setup_agent` and `create_api_agent` are admin-only (REST setup always was — provisioning writes API keys into the vault); deployment, agent/resource CRUD, triggers, channels and group configuration admit both `eddi-admin` and `eddi-editor` (as REST does); `get_agent`, `list_agents`, `list_agent_configs`, `list_team_backlog` and `list_group_templates` are no longer open to `eddi-viewer`, who cannot make those reads over REST either (`discover_agents` remains the viewer's way to find an agent); and `eddi-user` can now use the conversation and memory-read tools it can already use over REST.

## Sentiment Monitoring

Agents created with `enableSentimentAnalysis=true` (via `setup_agent` or `create_api_agent`) include sentiment data in every LLM response. The sentiment object includes: `score` (-1.0 to +1.0), `trend`, `emotions`, `intent`, `urgency`, `confidence`, and `topicTags`.

This data is stored in conversation memory and can be:

- Read via `read_conversation` (in the conversation snapshot)
- Aggregated for monitoring dashboards (Manager UI log panel)
- Used for alerting (e.g., negative sentiment spike triggers notification)

## Architecture

```
┌──────────────┐     ┌──────────────────────┐
│  MCP Client  │────▶│ quarkus-mcp-server   │
│ (Claude,IDE) │◀────│ Streamable HTTP /mcp │
└──────────────┘     └──────────┬───────────┘
                                │
                  ┌─────────────┼─────────────┐
                  ▼             ▼              ▼
         ┌────────────┐ ┌────────────┐ ┌────────────┐
         │ McpConv.   │ │ McpAdmin   │ │ McpSetup   │
         │   Tools    │ │   Tools    │ │   Tools    │
         └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
               │              │               │
         ┌─────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
         │ REST API   │ │ REST API   │ │ REST API   │
         │ endpoints  │ │ endpoints  │ │ + OpenAPI  │
         └────────────┘ └────────────┘ └────────────┘

         ┌──────────────────────────────────────────┐
         │         McpDocResources                   │
         │   @Resource / @ResourceTemplate           │
         │   eddi://docs/{name}  (filesystem I/O)    │
         └──────────────────────────────────────────┘
```

## MCP Client — Agents as MCP Consumers

In addition to acting as an MCP server, EDDI agents can also **consume external MCP servers** as tool providers. This enables agents to call tools exposed by other MCP-compatible services during conversations.

### Configuration

External MCP servers are configured as **`mcpcalls` workflow extensions** — a first-class, versioned configuration resource (the MCP equivalent of `httpcalls`). There is **no** inline MCP server array on the LLM task.

**Step 1 — create an `mcpcalls` configuration** (`POST /mcpcallsstore/mcpcalls`), one per MCP server:

```json
{
  "mcpServerUrl": "http://localhost:7070/mcp",
  "name": "eddi-docs",
  "transport": "http",
  "apiKey": "${vault:mcp-api-key}",
  "timeoutMs": 30000,
  "toolsWhitelist": ["read_docs", "list_docs"],
  "toolsBlacklist": []
}
```

| Field            | Type       | Required | Default   | Description                                                                                                              |
| ---------------- | ---------- | -------- | --------- | ------------------------------------------------------------------------------------------------------------------------ |
| `mcpServerUrl`   | string     | **Yes**  | —         | MCP server URL                                                                                                           |
| `name`           | string     | No       | —         | Human-readable name for logging                                                                                          |
| `transport`      | string     | No       | `"http"`  | Only Streamable HTTP is implemented; an unimplemented value is rejected as invalid configuration rather than silently substituted |
| `apiKey`         | string     | No       | —         | API key, sent as `Authorization: Bearer <key>`. Resolved through global variables and `${vault:key}` references, or `${caller:token}` to call as the chatting user (see below) |
| `timeoutMs`      | long       | No       | `30000`   | Connection and request timeout in milliseconds                                                                           |
| `toolsWhitelist` | string[]   | No       | —         | If non-empty, only these tool names are exposed (names as returned by the server's `tools/list`)                          |
| `toolsBlacklist` | string[]   | No       | —         | Tool names to exclude. Applied *after* the whitelist                                                                     |
| `mcpCalls`       | object[]   | No       | —         | Deterministic, action-triggered tool bindings (see *Pipeline mode* below). Omit for agent-mode-only servers               |
| `exposeResources` | boolean   | No       | `false`   | Opt-in bridge for the server's MCP **resources**: synthesizes `<name>_list_resources` and `<name>_read_resource` tools so the agent can list and read them (text capped at 64K chars, binary described, not returned). Independent of the whitelist/blacklist, which govern server-advertised names |

#### How `create_api_agent` builds a write tool's body

A generated `POST`/`PUT`/`PATCH` tool takes the **entire request body as a single
`requestBody` parameter**, whose description names the schema's properties, their
types, and which are required. The model writes the JSON itself.

It is worth knowing why, because the obvious alternative is worse. Decomposing the
schema into one parameter per property means every one becomes *required* (an
`ApiCall`'s parameter map has nowhere to record optionality), so a `PATCH` of one
field forces the model to restate all the others and a partial update silently
becomes a full overwrite. It also substitutes model-written values into JSON
unescaped, so a value containing a quote can break the body or add fields the
schema never declared.

The whole-body form matters most under [HITL approval](hitl.md): the approval card
shows tool **arguments**, so "what the approver sees is what gets sent" only holds
while the body is one of them.

**Which request bodies a tool can send.** The generator maps two media types:

| Operation consumes | Generated tool |
| --- | --- |
| `application/json` | `requestBody` carries the JSON document, as a string (above) |
| `text/plain` | `requestBody` carries the bare text, sent verbatim with `Content-Type: text/plain` — no JSON, quotes or key name. The parameter description is built from the spec's body description and example. EDDI's own `PUT /agentstore/agents/{id}/updateResourceUri` is this shape: its body is one URI |

An operation that offers both is sent the JSON form. The text is substituted as
*data*, never as a template: `{…}` or `${…}` inside it is sent as written, and a
credential reference typed into it is refused like one in a JSON body.

**Anything else is reported, not dropped.** An operation that *requires* an input
the generator cannot send — a request body of another type (`application/zip`,
`multipart/form-data`, form-urlencoded), or a required header or cookie parameter —
is skipped rather than generated as a tool that can only fail. The skipped
operations come back in the response as `resources.unusableEndpoints` (a list of
`"METHOD /path: reason"`), the operation is logged at `WARN`, and a spec in which
*nothing* is usable is rejected with those names in the error. An optional body of
an unsupported type is generated without the body. The Platform Operator's
activation uses this report to refuse an allow-list containing such an endpoint
(see [Architecture](architecture.md#case-study-the-platform-operator)).

#### Calling an MCP server as the chatting user

Set `apiKey` to `${caller:token}` and the tool call carries the identity of the
person chatting, instead of a standing service credential:

```json
{ "mcpServerUrl": "https://eddi.example/mcp", "apiKey": "${caller:token}" }
```

The same guarantees apply as for API call headers — released only to the
caller's origin or to this deployment's own address, fails closed rather than
sending a placeholder, never persisted. See
[`httpcalls.md`](httpcalls.md#calling-as-the-signed-in-user).

Two behaviours worth knowing, because they are deliberate:

- **Only tool calls carry the caller.** The `initialize` handshake and
  `tools/list` are sent unauthenticated, because the client is cached: a session
  opened with one user's token would be reused by everyone after them, and a
  tool list reflecting one user's permissions would be offered to the next. If
  your server requires authentication to *list* tools, use a static key.
- **Clients are cached per credential, not per URL.** Two agents pointing at the
  same server with different keys get separate clients. A caller-bound config
  still yields one shared client — the credential is applied per request, so
  there is no client per user.

A `${caller:token}` key with `eddi.caller-identity.enabled=false` is rejected as
invalid configuration when the server is validated, rather than failing on every
tool call.

**Step 2 — add an `mcpcalls` step to the agent's workflow**, before the LLM step:

```json
{
  "workflowSteps": [
    { "type": "eddi://ai.labs.parser",   "config": { "uri": "eddi://ai.labs.parser/parserstore/parsers/<id>?version=1" } },
    { "type": "eddi://ai.labs.behavior", "config": { "uri": "eddi://ai.labs.rules/rulestore/rulesets/<id>?version=1" } },
    { "type": "eddi://ai.labs.mcpcalls", "config": { "uri": "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/<id>?version=1" } },
    { "type": "eddi://ai.labs.llm",      "config": { "uri": "eddi://ai.labs.llm/llmstore/llms/<id>?version=1" } }
  ]
}
```

A workflow may contain any number of `mcpcalls` steps — one per MCP server.

### Two Modes, One Configuration

- **Agent mode** — `AgentOrchestrator.discoverMcpCallTools()` traverses the agent → workflow → every `mcpcalls` step at execution time, connects to each server, applies that config's whitelist/blacklist, and hands the surviving tools to the LLM. The LLM calls them reactively. Controlled by `enableMcpCallTools` on the LLM task (`langchain.json`), **default `true`** — no per-server opt-in is needed:

  ```json
  { "tasks": [ { "type": "anthropic", "enableMcpCallTools": false } ] }
  ```

- **Pipeline mode** — `McpCallsTask` (`eddi://ai.labs.mcpcalls`, pipeline position `Parser → Rules → HttpCalls → McpCalls → LLM → Output`) matches behavior-rule actions against `mcpCalls[].actions` and invokes the named tool deterministically, with **no LLM involved**. Only active when `mcpCalls` is non-empty.

Both modes read the same `mcpcalls` configuration; they are not mutually exclusive.

### Using `setup_agent` with MCP Servers

```
setup_agent(
  agentName: "My Agent",
  systemPrompt: "You are helpful",
  mcpServerUrls: "http://localhost:7070/mcp, https://tools.example.com/mcp",
  ...
)
```

The `mcpServerUrls` parameter accepts a comma-separated list of URLs. For each URL, `AgentSetupService` creates one `mcpcalls` configuration (`transport: "http"`, `timeoutMs: 30000`, no whitelist/blacklist, no `mcpCalls` bindings — i.e. agent-mode only) and inserts a matching `eddi://ai.labs.mcpcalls` step into the generated workflow ahead of the LLM step. Nothing is written inline into the LLM configuration.

### Architecture

```
┌──────────────┐     ┌──────────────────────┐
│  User sends  │────▶│       LlmTask         │
│   message    │     │  (EDDI pipeline)      │
└──────────────┘     └──────────┬────────────┘
                                │
                    ┌───────────┼───────────┐
                    ▼           ▼           ▼
           ┌──────────┐ ┌──────────┐ ┌──────────┐
           │ Built-in │ │  Custom  │ │   MCP    │
           │  Tools   │ │  Tools   │ │  Tools   │
           │(calc,dt) │ │(HttpCall)│ │(external)│
           └──────────┘ └──────────┘ └────┬─────┘
                                          │
                              ┌───────────┼───────────┐
                              ▼                       ▼
                    ┌──────────────┐       ┌──────────────┐
                    │ MCP Server 1 │       │ MCP Server 2 │
                    │ (EDDI docs)  │       │ (3rd party)  │
                    └──────────────┘       └──────────────┘
```

### Key Behaviors

- **Graceful degradation**: Failed MCP connections log warnings but never kill the pipeline
- **Connection caching**: `McpToolProviderManager` reuses connections across conversation turns
- **Budget/rate-limiting**: MCP tools are subject to the same `ToolExecutionService` controls as built-in tools
- **Vault references**: API keys support `${vault:key}` syntax via `SecretResolver`
- **Clean shutdown**: All MCP clients are closed on application shutdown via `@PreDestroy`

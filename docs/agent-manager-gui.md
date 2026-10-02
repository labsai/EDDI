# Agent Manager Dashboard

The EDDI Manager is the admin web UI for building, testing, deploying and operating EDDI agents. It is a React single-page application whose source lives in [`ui/manager`](https://github.com/labsai/EDDI/tree/main/ui/manager) of the EDDI repository; the Maven build compiles it into the EDDI jar, so every EDDI container image serves it with no separate deployment.

This page is a tour of what ships today: where each screen is, what it does, and which guide covers the feature behind it.

## Reaching the UIs

One EDDI instance serves every UI from its own port (7070 by default):

| URL | What it is |
| --- | --- |
| `http://localhost:7070/` | Sends a first-time visitor to the `/welcome` chooser. The choice made there (Manager or Workforce) is remembered in the browser, and later visits to `/` go straight to it |
| `/welcome` | The chooser between the Manager and the Workforce workspace |
| `/manage` | The Manager — everything on the rest of this page |
| `/workforce` | The Workforce workspace: a separate, simpler shell for running teams of agents (group conversations) |
| `/chat` | The standalone Chat UI for end users |
| `/q/swagger-ui` | The REST API explorer, linked from the Manager's sidebar |

The Manager talks to the backend that served it; there is nothing to configure in the browser. A sidebar switch moves between the Manager and Workforce shells.

### Frontend development

For work on the Manager itself, run the Vite dev server instead of rebuilding the jar:

```bash
cd ui/manager
npm ci
npm run dev
```

It listens on port 3000 (override with the `PORT` environment variable) and proxies the API paths to an EDDI backend on `http://localhost:7070`. If no backend answers at startup, the dev build falls back to an in-browser mock API (MSW), so the UI can be explored without a running EDDI. See [`ui/manager/AGENTS.md`](https://github.com/labsai/EDDI/blob/main/ui/manager/AGENTS.md) for the full contributor guide.

## Sign-in and roles

When OIDC is enabled on the backend (`QUARKUS_OIDC_TENANT_ENABLED=true`), the backend tells the Manager to sign in through Keycloak (`keycloak-js`, client `eddi-frontend`); the access token is refreshed before it expires. With authentication off, the Manager is open to anyone who can reach it. See [Security](security.md) for the Keycloak setup.

The sidebar is not filtered by role: every entry is visible to everyone, and the **backend** decides what each request may do. A screen you lack the role for shows a permission error (or, for Connections, falls back to your own linked accounts). The roles that matter most here:

| Role | Typical use in the Manager |
| --- | --- |
| `eddi-admin` | Everything, and required for the admin screens: secrets vault, connections, tenant quotas, GDPR, logs, audit trail, coordinator, orphan cleanup, and the agent setup wizard / Platform Operator activation (both call the admin-only `/administration/agents/setup` endpoints) |
| `eddi-editor` | Authoring agents, workflows and resources; global variables, schedules, import/export |
| `eddi-approver` | Deciding human-in-the-loop approvals across all conversations |
| `eddi-user` | Talking to agents and handling their own conversations and approvals |
| `eddi-viewer` | Little in the Manager: it is accepted by the MCP read tools and by only a few REST endpoints (documentation, usage, workspaces, resource sharing), not by the configuration stores, so most screens answer with a permission error |

There is no role hierarchy on the REST API: `eddi-admin` does not imply the others. See [Security](security.md) for the role model.

## Page layout

- **Sidebar** — grouped into Core, Build & Test, Monitor and Admin; sections collapse, and the whole sidebar collapses to icons. It also links to the OpenAPI explorer and to the documentation site, and holds a Help menu that restarts the guided tours.
- **Top bar** — platform health indicator, workspace notifications, the Platform Operator drawer, language and theme (light, dark, system) switchers, and the user menu (with **Linked accounts** and logout when signed in).
- **Command palette** — `Ctrl+K` / `Cmd+K` searches pages and agents and offers quick actions such as creating an agent or opening chat.
- **Languages** — English, German, French, Spanish, Arabic (right-to-left), Chinese, Thai, Japanese, Korean, Portuguese and Hindi.

## Core

### Dashboard (`/manage`)

Platform health at a glance (backend, coordinator connection, vault status), recent conversations, quick actions, and a discovery card for the Platform Operator until it is activated.

### Platform Operator (`/manage/operator`)

The Platform Operator is an assistant that inspects and operates **this** EDDI deployment through EDDI's own REST API. It is off by default; an admin activates it. Once active it is also available from the top bar drawer on every Manager screen.

**What it is.** A real EDDI agent, created through the API-agent setup (`setup-api`) from EDDI's OpenAPI spec. It appears in the Agents list with an *Operator* badge; editing or deleting it there breaks the operator screen. Its settings are stored as one JSON value in the `platform.operator` global variable.

**What it can do.** Its tools are an explicit allow-list of endpoints, defined in [`ui/manager/src/lib/operator/tool-scopes.ts`](https://github.com/labsai/EDDI/blob/main/ui/manager/src/lib/operator/tool-scopes.ts). An endpoint that is not on the list produces no tool at all. Two scopes are offered at activation:

- **Read-only** — inspect agents, workflows, groups and their extension configs; conversations and their status; deployment status; coordinator status; logs; quotas; schedules; knowledge bases and their ingestion status; the audit trail of an agent; and EDDI's bundled documentation.
- **Read & write** (the default) — additionally: create agents (standard or OpenAPI-backed), create groups, create and update workflows and workflow extensions (LLM, rules, output, property setter, dictionary, API calls, MCP calls), repoint one workflow step of an agent, deploy and undeploy, edit a descriptor's name and description, disable a runaway schedule, and start test conversations with other agents or groups.

**What it cannot do.** It has no `DELETE` endpoint of any kind, cannot replace a whole agent or group document (both carry their own approval gate), cannot create, enable or fire schedules, cannot read or write secrets, and cannot resume, undo or end conversations — including approving its own paused requests.

**How writes are controlled.** Every POST, PUT, PATCH and DELETE the operator makes pauses for human approval and waits indefinitely; reads run without a pause. The Manager shows the redacted request (and, for an update, a diff against the stored version) before you approve. The Manager's approval screens also refuse, rather than merely warn about, some requests outright: an LLM config that sets its own `toolApprovals` (that would replace the gate reviewing it), any change to the operator's own agent, and the operator test-driving itself. These refusals are made by the Manager, not by the backend: a decision submitted through another approval surface — `POST /agents/{conversationId}/resume`, the MCP `resume_conversation` tool, or Slack — is not checked against them, so decide the operator's pending requests in the Manager. Requests that grant further capability (for example an agent created with no approval gate, or a credential written in place of a `${vault:...}` reference) are flagged above the request. Approvals are covered in [Human-in-the-Loop](hitl.md).

**Activating it.** On the operator page, choose the model (provider, model, API key or vault reference, optional base URL), the environment the operator agent runs in, the capability scope, and how its tool calls authenticate:

- **Your identity** (`caller-identity`) — tool calls run as the signed-in user, with that user's permissions and audit trail. Required when authentication is enabled; *No credentials* is blocked in that case.
- **No credentials** — only for deployments with authentication off.

The **platform base URL** is the address EDDI can reach *itself* at, because the tools are called from inside the server, not from your browser. It is prefilled from `GET /administration/operator/self-url` (the `eddi.self.base-url` property, else loopback on the HTTP port); override it when EDDI must be addressed by a service name or through a proxy. You can review and edit the operator's instructions; a safety preamble that tells it to treat tool output as untrusted data is always prepended and is not editable.

Activation checks that the deployment exposes every allow-listed endpoint, builds and deploys the agent, reads the approval gate back from the stored document, and — for read & write — runs a harmless test write in the background to prove that writes really pause. **Reconfigure** replaces the agent with a new one; **Deactivate** undeploys it and keeps the configuration; **Delete operator** removes it and its resources. When a newer Manager ships a changed prompt or allow-list, the operator page shows an upgrade banner and the backend logs a warning at startup.

The operator can be asked to create a first agent from a description, as an alternative to the wizard below. See [Workspaces](workspaces.md#the-platform-operator) for how it behaves when workspaces are enforced.

### Agents (`/manage/agents`)

Search, sort and switch between card and table views. From here you can:

- **Create** — either a blank agent or the **Agent Setup Wizard** (`/manage/agents/wizard`). The wizard builds a complete, working agent in one step: a *Standard Agent* (an LLM with a system prompt, optional tools and quick replies) or an *API Agent* generated from an OpenAPI/Swagger spec. Pick provider and model, then *Create Only* or *Create & Deploy*.
- **Import** an agent from a ZIP and **export** one to a ZIP — see [Import/Export an Agent](import-export-an-agent.md).
- **Duplicate**, **delete**, and **share** an agent (sharing applies when [workspaces](workspaces.md) are enforced).

### Agent detail (`/manage/agentview/:id`)

One agent's configuration: its workflows (add, remove, update to the latest version), version picker, deploy and undeploy per environment (`production` and `test`, with options to end active conversations or undeploy earlier versions), and a chat button for any environment it is live in. It also holds the agent-level settings — human-in-the-loop approval rules for tool calls, persistent user memory and Dream consolidation, memory write guardrails, session forking, A2A exposure and declared capabilities, message signing, channel connectors, and conversation review. See [Deployment Management](deployment-management-of-agents.md), [Human-in-the-Loop](hitl.md), [User Memory](user-memory.md) and [A2A Protocol](a2a-protocol.md).

### Agent Studio (`/manage/studio/:agentId`)

A full-screen workspace for one agent: the pipeline drawn as a sequence of stages, an inline editor for the stage you click, and a chat panel with debugging next to it — so you can edit a step and test it without leaving the page.

### Workflows (`/manage/workflows`, `/manage/workflowview/:id`)

A workflow is the ordered pipeline of extensions an agent runs. The workflow editor is a drag-and-drop pipeline builder: add extensions (rules, LLM, API calls, MCP calls, property setter, output, parser, knowledge bases), reorder them, and open each one's editor. See [Extensions](extensions.md) and [Architecture](architecture.md).

### Groups (`/manage/groups`)

Multi-agent group conversations.

- **Group list** — search, create and delete groups.
- **Group Setup Wizard** (`/manage/groups/wizard`) — start from a template or from scratch, pick a discussion style, and fill each seat with an existing agent, a newly created one, or a nested group.
- **Group Templates** (`/manage/groups/templates`) — packaged, pre-tuned discussions such as an advisory board, a peer review panel, a risk assessment, independent estimates, a pro/con debate, a negotiation or a task force; you assign agents to the named roles.
- **Group detail** (`/manage/groups/:id`) — the configuration, a live discussion view, and its history.
- **Standing Team Workspace** (`/manage/groups/:id/workspace`) — a group's persistent task backlog and recurring cadences (cron schedules with per-run task and cost limits), plus per-member reliability and cost statistics.

See [Group Conversations](group-conversations.md).

### Channels (`/manage/channels`)

Channel integrations that connect agents (or a group) to Slack: bot token and signing secret (preferably as vault references), the channel ID, a default target and keyword-routed targets. See [Slack Integration](slack-integration.md).

### Capabilities (`/manage/capabilities`)

Find agents by the skills they declare, with a highest-confidence or all-matches strategy. See [Capability Matching](capability-match-guide.md).

## Build and test

### Resources (`/manage/resources`)

Every reusable configuration document, by type: rules, API calls, output sets, dictionaries, LLM, property setter, MCP calls, knowledge bases (RAG), prompt snippets and parsers. Each type has a form editor and a raw JSON (Monaco) editor; every save creates a new version. See the per-extension guides linked from [Extensions](extensions.md), plus [RAG](rag.md) and [Prompt Snippets](prompt-snippets-guide.md).

### Chat (`/manage/chat`)

Test-chat with any deployed agent: streamed responses with a live tool-activity view, file attachments, a secret-input mode for values that should not be stored in plain text, undo and redo of the last step, retry of a failed step, and conversation history. See [Attachments](attachments-guide.md).

### Triggers (`/manage/triggers`)

Map an intent to one or more agent deployments for managed, automatically routed conversations. See [Managed Agents](managed-agents.md).

## Monitor

- **Logs** (`/manage/logs`) — a live server log stream and a searchable history, filterable by level, agent, conversation and instance. See [Log Administration](log-administration.md).
- **Conversations** (`/manage/conversations`) — filter by agent, version and state, open a conversation's detail view (transcript, attachments, and any pending approval), and soft- or permanently delete. See [Conversations](conversations.md).
- **Active Conversations** (`/manage/conversations/monitoring`) — the in-flight conversations of one agent version; end a selection safely (paused conversations have their approvals cancelled) or purge ended conversations older than N days.
- **Coordinator** (`/manage/coordinator`) — conversation-processing statistics and the dead-letter queue, with replay, discard and purge. See [Coordinator Admin](coordinator-admin.md).
- **Approvals** (`/manage/approvals`) — the human-in-the-loop inbox for paused conversations and paused group phases: inspect what each pending tool call would do (arguments redacted), approve or reject per call, amend arguments, or cancel. Admins and approvers see every pending approval; other users see their own conversations'. The sidebar shows the pending count. See [Human-in-the-Loop](hitl.md).
- **Audit Trail** (`/manage/audit`) — the audit ledger by conversation or by agent: each pipeline task with its inputs, outputs, LLM details, tool calls, cost and timing, and its integrity verification. See [Audit Ledger](audit-ledger.md).

## Admin

- **Workspaces** (`/manage/workspaces`) — your spaces and the secrets and variables each one keeps; for an admin, whether workspaces are enforced, the token claim that carries teams, and where new work lands. Workspaces are off unless `eddi.workspaces.enabled=true`. See [Workspaces](workspaces.md).
- **Secrets** (`/manage/secrets`) — the encrypted vault per tenant: store, rotate and delete secrets, which agents reference as `${vault:key}`. Values can be written but are never shown again. See [Secrets Vault](secrets-vault.md).
- **Connections** (`/manage/connections`) — named outbound credentials (static keys, HTTP Basic, OAuth service accounts, per-user OAuth) that agents reference as `${connection:name}`. Managing them needs `eddi-admin`; every signed-in user can link their own accounts under **Linked accounts** (`/manage/linked-accounts`, in the user menu). See [Connections](connections.md).
- **Variables** (`/manage/variables`) — deployment-wide global variables, used as `{vars.key}` in prompts or `${vars:key}` in configs, with an option to include each in agent exports. Not for sensitive values. See [Global Variables](global-variables.md).
- **Quotas** (`/manage/quotas`) — per-tenant rate limits, conversation caps and monthly cost budgets, live usage, and counter reset. See [Tenant Quotas](tenant-quotas.md).
- **Schedules** (`/manage/schedules`) — cron and heartbeat schedules that trigger agents, their fire history, and failed or dead-lettered fires. See [Scheduling](scheduling.md).
- **User Data** (`/manage/userdata`) — per-user persistent memories, long-term properties and managed-conversation bindings, in tabs. See [User Memory](user-memory.md) and [Properties](properties.md).
- **Orphans** (`/manage/orphans`) — scan for extension configs no workflow or agent references, then purge them. Purging is blocked unless the last scan completed.
- **Sync** (`/manage/sync`) — connect to another EDDI instance, match its agents to local ones, preview the changes and sync. Synced changes are saved as new versions and are not deployed automatically. See [Agent Sync](agent-sync-guide.md).
- **Privacy** (`/manage/gdpr`) — for one user ID: export all stored data (Art. 15/20), erase it (Art. 17) with a per-category result, and restrict or resume processing (Art. 18). The page says explicitly when an export or an erasure is incomplete. See [GDPR Compliance](gdpr-compliance.md).
- **Updates** (`/manage/updates`) — checks GitHub, from your browser and only when you ask (or on every page load if you opt in), whether a newer EDDI release exists, and shows how to update.

## Workforce (`/workforce`)

The Workforce shell is aimed at people who run agent teams rather than configure them: a dashboard of teams, a wizard to assemble a new team (`/workforce/new`), a board per team with each member's thread, the team's settings and history, a chat page, and an analytics view. Teams are groups underneath, so everything in [Group Conversations](group-conversations.md) applies.

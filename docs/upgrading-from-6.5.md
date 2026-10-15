# Upgrading from EDDI 6.5

This guide is for an existing **EDDI 6.5.x deployment** moving to the next release. It
collects the changes that a working 6.5 client notices. It is written as the changes land, so
read it again before you upgrade. For an older deployment, start with
[Upgrading from 6.4](upgrading-from-6.4.md).

---

## Authorization defaults

These apply only with authorization on (`QUARKUS_OIDC_TENANT_ENABLED=true`). A 6.5 client that
held a token but no EDDI role, or that relied on an endpoint being open to any token, now gets
refused:

- **Metrics.** `/q/metrics` now needs the `eddi-admin` or `eddi-metrics` realm role
  (`eddi.metrics.roles-allowed`); in 6.5 any token was enough, so a scrape whose service account
  holds neither now gets **403**. Create an `eddi-metrics` realm role and give it to the
  scraper's service account ([Monitoring](monitoring/monitoring-guide.md)). As a stopgap,
  `EDDI_METRICS_HTTP_POLICY=authenticated` restores the 6.5 behaviour.
- **API docs.** `/openapi` and the Swagger UI need an EDDI role.
- **`/v1` (OpenAI-compatible API).** In `authenticated` mode it needs `eddi-admin`, `eddi-editor`
  or `eddi-user`.
- **Other role-gated endpoints.** `/a2a/agents` and the per-conversation `/conversationstore`
  operations need an EDDI role.
- **Descriptor listing.** A `type` that is not a configuration type now gets **400**.
- **Triggers.** Changing a trigger needs EDIT access; a `PUT` whose body names a different intent
  than the path gets **400**.
- **Conversations without an owner** are readable by administrators only.
- **Custom JAX-RS resources** deployed as extensions must carry a security annotation
  (`@RolesAllowed`, `@PermitAll`, …). Unannotated endpoints are now denied
  (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`).

---

## MCP server

### MCP role tiers

Up to 6.5 the MCP tools checked one role each, and those roles disagreed with REST: an editor
could provision agents over MCP that REST setup refuses it, could not deploy although REST lets
it, and a viewer could read agent configurations that REST refuses it. An account without
`eddi-viewer` was refused all 27 tools gated on it (`list_agents` and the conversation tools
among them), whatever else it held. Each tool now belongs to one
tier that lists every role it admits ([Role Mapping](mcp-server.md#role-mapping)).

What an existing MCP client loses, by role:

| Account holds only | Tools it can no longer call | Why |
| --- | --- | --- |
| `eddi-viewer` | `list_agents`, `list_agent_configs`, `get_agent`, `list_team_backlog`, `list_group_templates` | Configuration reads; REST refuses them to a viewer. Use `discover_agents` to find an agent |
| `eddi-editor` | `setup_agent`, `create_api_agent` | REST agent setup is admin-only: provisioning writes API keys into the vault |

What it gains:

- `eddi-admin` and `eddi-editor` no longer need `eddi-viewer` for the conversation, group
  discussion and memory-read tools. Exceptions: `read_audit_trail` and conversation-scoped
  `read_agent_logs` admit `eddi-admin` and `eddi-viewer` only (the REST audit and log endpoints
  are admin-only), so an editor that uses them keeps `eddi-viewer`.
- `eddi-editor` can deploy, undeploy and author agents, resources, triggers and channels, as
  over REST. `eddi-admin` can configure groups, which was editor-only.
- `eddi-user` can converse, run group discussions and read its own memories, as over REST.

**What to do:** nothing for a client whose account holds `eddi-admin`. Grant `eddi-admin` to an
editor account that provisions agents through `setup_agent`/`create_api_agent`, and
`eddi-editor` to a viewer account that reads agent configurations.

### Tool failures are `isError: true`

A refused or failed tool call used to come back as an ordinary result whose text was
`{"error": "..."}`, and a missing role came back as a JSON-RPC internal error naming
`io.quarkus.security.ForbiddenException`. Both are now a tool result with `isError: true`
carrying the same JSON body — a missing role with `errorCode: FORBIDDEN`. A client or model that
looked for the word `error` in a successful result keeps working; one that treated a JSON-RPC
error as "not permitted" should read `isError` and `errorCode` instead. See
[Argument contract](mcp-server.md#argument-contract).

### Optional arguments are optional

`tools/list` used to mark every argument required in 75 of the 84 tools, so a client that
omitted an "optional" argument was refused. Optional arguments are now optional and literal
defaults are published as the schema `default`. Clients that sent every argument keep working.
Two defaults changed meaning: `get_agent`, `read_workflow`, `read_resource` and
`list_agent_resources` without a `version` read the **latest** version (they read version 1),
and `create_group` with an unknown `style` is refused instead of becoming `ROUND_TABLE`.

# EDDI plugin for Claude Code

Two skills that teach Claude how to work with an EDDI server through EDDI's
[MCP tools](../../docs/mcp-server.md). The tools give Claude access to EDDI. The skills
tell it which tools to chain, which mistakes to avoid, and which actions need your yes
first.

| Skill | For | Covers |
| --- | --- | --- |
| [`eddi`](skills/eddi/SKILL.md) | Everyone with an EDDI account | Finding the right agent, talking to it without losing the conversation, group discussions, approvals that wait for you, your memories |
| [`eddi-admin`](skills/eddi-admin/SKILL.md) | `eddi-editor`, `eddi-admin` | Creating agents, the safe change → test → promote path, agent test suites, debugging, intents, schedules, channels, groups, user memory and GDPR |

The skills do not copy EDDI's documentation. For anything specific to a version, they
send Claude to the server's own `list_docs` / `read_docs`, which always match the running
release.

## Install

```
/plugin marketplace add labsai/EDDI
/plugin install eddi@eddi
```

The skills load as `eddi:eddi` and `eddi:eddi-admin`. Claude picks the right one from
what you ask.

## Connect to your EDDI server

The plugin contains no server address, because every deployment has its own. Add the
server once:

**Authentication off** (local development):

```
claude mcp add --transport http eddi http://localhost:7070/mcp
```

**Authentication on** (Keycloak). EDDI's realm ships a public client, `eddi-mcp`, for MCP
clients. Point Claude Code at it, and it signs you in through the browser and refreshes
its own token:

```
claude mcp add --transport http --client-id eddi-mcp --callback-port 8080 eddi https://eddi.example.com/mcp
```

What your account can do depends on its EDDI role. A token without roles signs in fine,
but every tool then fails with "requires one of roles". For a realm created before
`eddi-mcp` existed, for Claude Desktop, and for the manual-token fallback, see
[Connecting to an authenticated instance](../../docs/mcp-server.md#connecting-to-an-authenticated-instance).

## Keeping the skills honest

`SkillToolReferencesTest` reads every Markdown file in this plugin and fails the build if
it names an MCP tool EDDI does not register or an argument no tool takes, or if it tells
Claude to `read_docs` a page that the container image does not ship. A rename therefore
breaks the build until the skills follow. A **new** tool or a changed behaviour does not:
whoever adds one decides whether a skill should teach it (AGENTS.md §4.6).

`plugin.json` deliberately has no `version`. With one, installed copies stay on that
version until it is bumped. Without one they follow the repository, which suits skills
that have to match the server's current tools. `claude plugin validate` reports this as
a warning.

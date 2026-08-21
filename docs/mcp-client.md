# MCP Client — connecting EDDI to external MCP servers

> EDDI is an MCP **server** (see [MCP Server](mcp-server.md), 80+ tools) *and* an
> MCP **client**. This page is about the client half: pointing one of your agents
> at somebody else's MCP server so its tools become the agent's tools.

**Contents:** [Quick start](#quick-start) · [Configuration reference](#configuration-reference) ·
[Transports](#transports) · [stdio servers via a bridge sidecar](#stdio-servers-via-a-bridge-sidecar) ·
[Credentials](#credentials) · [What is governed](#what-is-governed) ·
[Approvals](#approvals) · [Troubleshooting](#troubleshooting)

---

## Quick start

An MCP client connection is a workflow extension, exactly like `httpcalls`. Create
an `mcpcalls` configuration:

```json
{
  "mcpServerUrl": "https://mcp.example.com/mcp",
  "name": "example",
  "transport": "http",
  "apiKey": "${vault:example-mcp-token}",
  "timeoutMs": 30000
}
```

…reference it as a workflow step (`eddi://ai.labs.mcpcalls`), and the agent's LLM
task picks its tools up automatically (`enableMcpCallTools`, default `true`).

To see what a server offers before wiring it up:

```bash
curl -X POST http://localhost:7070/mcpcallsstore/mcpcalls/discover-tools \
  -H 'Content-Type: application/json' \
  -H 'X-Mcp-Authorization: <server credential>' \
  -d '{"url": "https://mcp.example.com/mcp", "transport": "http"}'
```

> The credential goes in the **header**. The older
> `GET …/discover-tools?apiKey=…` form no longer accepts one: a credential in a
> URL is recorded by ingress, any reverse proxy, access logs, browser history and
> APM traces before EDDI ever sees it. The `GET` form still works for servers that
> need no authentication.

---

## Configuration reference

| Field | Type | Default | Purpose |
| --- | --- | --- | --- |
| `mcpServerUrl` | string | — | **Required.** `http`/`https` only. |
| `name` | string | — | Display name; appears in logs and failure reports. |
| `transport` | string | `http` | See [Transports](#transports). |
| `apiKey` | string | — | Sent as `Authorization: Bearer …`. Use a `${vault:…}` reference. |
| `timeoutMs` | number | `30000` | Per-operation timeout. |
| `toolsWhitelist` | list | — | If non-empty, only these server-advertised tool names are exposed. |
| `toolsBlacklist` | list | — | Server-advertised tool names to drop. |
| `exposeResources` | boolean | `false` | Synthesizes `<name>_list_resources` / `<name>_read_resource`. |

> **`toolsWhitelist` is not a security boundary.** It is context-window
> management. It governs names the *server* advertises, and it deliberately does
> not cover the resource bridge, whose two tool names are synthesized by EDDI. The
> security boundary is the credential you give the connection plus the
> [approval gate](#approvals).

---

## Transports

EDDI implements exactly one MCP transport: **StreamableHTTP**.

| Token | Status |
| --- | --- |
| `http`, `https`, `streamable-http`, `streamablehttp` | Supported. |
| `sse` | **Deprecated alias.** Accepted and served over StreamableHTTP, with a one-time warning per server. |
| `stdio` | Rejected. See below. |
| anything else | Rejected at write time with an actionable message. |

`sse` is accepted rather than rejected because it was once documented, so configs
carrying it exist. It is not rewritten on save — that would edit your document
behind your back; the runtime warning is the signal to change it.

### Why not `stdio`

Most vendor-shipped MCP servers are stdio binaries (`npx some-mcp-server`). EDDI
does not spawn them, and the reason is not that the transport is hard — langchain4j
ships a `StdioMcpTransport` and it would be a small change. It is that a
config-editable `command` array is **arbitrary code execution as the EDDI process
user**, driven by a configuration document. Combined with an MCP or REST surface
that can write that document, that is a remote-code-execution primitive with a
config editor in front of it.

The supported answer is a bridge.

---

## stdio servers via a bridge sidecar

Run the stdio server in its **own container**, with a bridge that speaks
StreamableHTTP on one side and stdio on the other
([`mcp-proxy`](https://github.com/sparfenyuk/mcp-proxy),
[`supergateway`](https://github.com/supercorp-ai/supergateway) and others do this).
EDDI then talks to it over the transport it already has.

```yaml
# docker-compose.mcp-sidecar.yml — see the repository root for the runnable file
services:
  filesystem-mcp:
    image: ghcr.io/sparfenyuk/mcp-proxy@sha256:<digest>   # pin a DIGEST, not a tag
    command:
      - "--port=8096"
      - "--host=0.0.0.0"
      - "--"
      - "npx"
      - "-y"
      - "@modelcontextprotocol/server-filesystem@2025.8.21"
      - "/data"
    user: "10001:10001"          # never root
    read_only: true
    cap_drop: ["ALL"]
    security_opt: ["no-new-privileges:true"]
    deploy:
      resources:
        limits: { cpus: "0.50", memory: 256M }
    networks: [mcp-internal]     # NOT the public network
    volumes:
      - ./mcp-data:/data:ro
```

and in the agent's `mcpcalls` config:

```json
{ "mcpServerUrl": "http://filesystem-mcp:8096/mcp", "name": "filesystem", "transport": "http" }
```

### What the sidecar does and does not buy

"Sidecar" is easy to over-read as "solved". Be precise about it:

* The MCP server binary **still executes**, and it still speaks to EDDI over a
  network channel. Container separation **bounds the blast radius**; it removes
  neither process-execution nor supply-chain risk. A malicious
  `npx some-mcp-server` is still malicious — just contained.
* What it does remove is *EDDI's* exposure: the EDDI process gains no
  code-execution surface, its runtime image gains no interpreter, and there is no
  process lifecycle code (spawn, reap, restart-on-crash, drain on shutdown) in the
  conversation engine.

So the container hardening above is not decoration. Each line is load-bearing:

| Control | Why |
| --- | --- |
| **Digest-pinned image** | The same rule EDDI applies to its own base image. A tag is mutable; a digest is the artifact you reviewed. |
| **Pinned server version** | `npx -y some-server` resolves *latest* at container start — a supply-chain change with no deploy. |
| **Non-root, `read_only`, `cap_drop: ALL`** | The bridge needs none of it. |
| **CPU/memory limits** | A runaway or hostile server must not starve the node. |
| **Isolated network** | The bridge must not be reachable by anything else on the pod network, and the server's own egress should be restricted to the provider it needs. |
| **Authentication on the bridge** | Without it, anything that can reach the port gets an unauthenticated tool server. Put a token on it and give EDDI the token via `apiKey`. |

A five-replica deployment runs five sidecars with five independent states. If the
server holds per-user state, that matters; if it is stateless, it does not.

---

## Credentials

`apiKey` is sent as `Authorization: Bearer <value>`. Three forms are resolved:

| Value | Resolves to |
| --- | --- |
| `${vault:name}` | A vault secret. **Use this.** |
| `${vars:name}` | A global variable — for non-secret values. |
| `${caller:token}` | The chatting user's own bearer token, released only to the **same origin** the caller addressed. |

A literal key is accepted and logs a warning: it sits in plaintext in MongoDB and
in any export that outruns scrubbing.

Rotating a vault secret now evicts cached MCP clients immediately. Before that,
the client cache was keyed on a hash of the *unresolved* reference and the
credential was resolved once per client, so a rotated secret kept presenting the
old value until restart.

---

## What is governed

Everything an MCP server sends you is third-party text that lands in your model's
context. Three layers apply, none of which you configure to get:

1. **Tool descriptions** are directive-redacted and length-capped
   (`eddi.mcp.tool-description.max-chars`, default 1024) before they become part of
   the model's tool definitions. Whitelisting operates on tool *names*, so an
   approved tool whose description later turns into an instruction would otherwise
   be ungoverned.
2. **Resource content and listings** (`exposeResources`) get the same treatment,
   plus an aggregate cap.
3. **Tool results** are wrapped in a provenance delimiter naming the tool and its
   source and stating that the content is data, not instructions; directive-shaped
   content in a result is redacted by default. Configure per LLM task:

```json
"toolResultGuardrails": {
  "enabled": true,
  "markProvenance": true,
  "directiveAction": "redact",
  "directiveAppliesToSources": ["mcp", "a2a", "http"],
  "exemptTools": []
}
```

`directiveAction` is `warn`, `redact` (default) or `block`. An unrecognised value
degrades to `warn` — a typo must not silently start blocking every tool result.

These are mitigations, not a boundary. A determined injection can talk past a
delimiter. What they buy is that the model is never asked to guess which part of
its context a third party wrote.

---

## Approvals

MCP tool calls participate in the [HITL tool-approval gate](hitl.md) like any
other tool source. A gated MCP call now shows its approver:

* the **target server URL** and the JSON-RPC method,
* the tool name and its arguments (credential-shaped values redacted),
* whether the call is authenticated,

and is pinned to a fingerprint that is re-checked immediately before execution. The
credential's *value* is deliberately excluded from that fingerprint: a token
refresh between approval and execution is routine, and hashing the live value would
make every approval of a credentialled call fail its own re-check.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `400` on save, transport rejected | Only StreamableHTTP is implemented — see [Transports](#transports). |
| Tools vanish after a while | Circuit breaker: 3 failures in 60s opens it for a cooldown. Check the server. |
| `Failed to connect to MCP server (…)` with no detail | Deliberate. The exception *message* routinely contains the resolved URL, and a URL with a templated credential in it is the credential. The full throwable is in the server log. |
| Two servers, one tool missing | Name collision. First writer wins, loudly; use `toolsBlacklist` on the loser to make the choice explicit. |
| A rotated key still fails | Confirm the config uses `${vault:…}` and not a literal — a literal is not invalidated because nothing knows it changed. |

---

## See also

- [MCP Server](mcp-server.md) — EDDI's own MCP surface, the other direction
- [HITL](hitl.md) — approval gates, including per-tool-call gating
- [httpcalls](httpcalls.md) — the REST equivalent, including `${caller:token}`
- [A2A Protocol](a2a-protocol.md) — agent-to-agent peers, governed the same way

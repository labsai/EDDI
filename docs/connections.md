# Connections — one credential model for every outbound call

> A **connection** describes *how to authenticate to one external system*. Any
> outbound configuration references it as `${connection:name}`, and EDDI resolves
> it to a credential **per request** — which is what lets the same model cover an
> org-wide API key and each end user's own OAuth grant.

**Contents:** [Why](#why) · [Quick start](#quick-start) · [The model](#the-model) ·
[Where a reference may appear](#where-a-reference-may-appear) ·
[Enabling connections](#enabling-connections) · [Per-user accounts](#per-user-accounts) ·
[Refresh, and what happens when it fails](#refresh-and-what-happens-when-it-fails) ·
[Security rules](#security-rules) · [Metrics](#metrics) · [Limitations](#limitations)

---

## Why

Before connections, EDDI had **five independent credential-resolution
implementations** — httpcalls, the MCP client, the A2A client, the
model/embedding/vector-store factories, and Slack — each with its own
`apiKey`-shaped field and its own `globalVariableResolver → secretResolver`
snippet. Adding OAuth to each separately is a five-times problem with five
refresh-concurrency bugs.

More concretely, three things were impossible:

| Want | Before |
| --- | --- |
| An org-wide Amplitude key + secret | Worked, but you had to vault a pre-base64-encoded blob because nothing encoded for you |
| Jira through Atlassian's hosted MCP server | Impossible — static Bearer only |
| Google Drive, each user seeing only their own files | Impossible — no per-user credential dimension existed |

---

## Quick start

### A static API key

```json
POST /connectionstore/connections
{
  "name": "amplitude",
  "description": "Org-wide analytics key",
  "authType": "STATIC",
  "binding": "SERVICE",
  "staticAuth": {
    "headerName": "Authorization",
    "valueTemplate": "Bearer ${vault:amplitude-key}"
  },
  "baseUrlAllowlist": ["https://amplitude.com"]
}
```

Then reference it from an httpcall **header**:

```json
"headers": { "Authorization": "${connection:amplitude}" }
```

### HTTP Basic

EDDI does the base64, which is the point of `BASIC` existing as a type — a
pre-encoded `user:pass` cannot be rotated field by field, cannot be read back to
check which account it belongs to, and hides a password inside something that
does not look like one.

```json
{
  "name": "jira",
  "authType": "BASIC",
  "binding": "SERVICE",
  "staticAuth": { "headerName": "Authorization", "username": "svc@example.com", "passwordRef": "${vault:jira-api-token}" },
  "baseUrlAllowlist": ["https://your-domain.atlassian.net"]
}
```

### An OAuth service account

```json
{
  "name": "atlassian-service",
  "authType": "OAUTH2_CLIENT_CREDENTIALS",
  "binding": "SERVICE",
  "oauth": {
    "tokenUrl": "https://auth.atlassian.com/oauth/token",
    "clientId": "…",
    "clientSecret": "${vault:atlassian-client-secret}",
    "scopes": ["read:jira-work"],
    "clientAuthMethod": "client_secret_basic"
  },
  "baseUrlAllowlist": ["https://api.atlassian.com"]
}
```

The first call mints the grant; there is no human step.

### Per-end-user OAuth

```json
{
  "name": "google-drive",
  "authType": "OAUTH2_AUTHORIZATION_CODE",
  "binding": "PER_USER",
  "oauth": {
    "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
    "tokenUrl": "https://oauth2.googleapis.com/token",
    "clientId": "…",
    "clientSecret": "${vault:google-client-secret}",
    "scopes": ["https://www.googleapis.com/auth/drive.readonly"],
    "extraAuthParams": { "access_type": "offline", "prompt": "consent" }
  },
  "baseUrlAllowlist": ["https://www.googleapis.com", "https://drive.googleapis.com"]
}
```

Each user links their own account once (see [below](#per-user-accounts)).

---

## The model

| Field | Purpose |
| --- | --- |
| `name` | What `${connection:name}` refers to |
| `authType` | `STATIC`, `BASIC`, `OAUTH2_CLIENT_CREDENTIALS`, `OAUTH2_AUTHORIZATION_CODE` |
| `binding` | `SERVICE` (one grant for everyone) or `PER_USER` (the caller's own) |
| `staticAuth` | Header name plus a reference-only value template |
| `oauth` | Endpoints, client id, a **vaulted** client secret, scopes |
| `baseUrlAllowlist` | The origins this credential may be sent to. **Required.** |
| `timeoutMs` | Token-endpoint timeout |

`binding` is the field that makes Amplitude and Google Drive the same system.
`PER_USER` is only legal with `OAUTH2_AUTHORIZATION_CODE` — a static key is the
same key for everybody however it is bound, and refusing that at write time is
better than pretending otherwise at runtime.

### Every secret is a reference

`clientSecret`, `passwordRef` and every interpolated segment of a `valueTemplate`
**must** be `${vault:…}` or `${vars:…}`. A literal is refused with a 400 that
names `POST /secretstore/secrets`.

This is not stylistic. A plaintext key in a connection document would sit outside
the vault, outside export scrubbing, and outside `VaultGrantChecker`'s
`${vault:}` scan — one field defeating three controls. `extraAuthParams` is
checked too, since an arbitrary string map is the obvious place to paste one.

Note also that the check is "the value **is** a reference", not "contains one":
`sk-live-abcdef${vault:unused}` is a literal key with a reference stapled on, and
it is refused.

---

## Where a reference may appear

**Headers only**, on the HTTP-templating paths — the same restriction set as
`${caller:token}`, for the same reasons.

| Placement | Result |
| --- | --- |
| httpcall **header** | ✅ Resolved per request |
| mcpcalls `apiKey` | ✅ Resolved per request (never on the discovery handshake — see below) |
| A2A `apiKey` | ✅ Resolved per call |
| httpcall path / query / body | ❌ Refused at build time |
| LLM, embedding, vector-store parameters | ❌ Refused — see [Limitations](#limitations) |

A credential in a URL or a query string is written to ingress logs, proxy logs
and browser history before the provider ever sees it. A credential in a body is
not one the provider will read. All three are refused rather than sent as literal
text and answered with an unexplained 401.

**MCP discovery is deliberately unauthenticated for connection-bound configs.**
The MCP client is cached, so a session established with one principal's
credential would be reused by everybody after them, and a tool list reflecting
one user's permissions would be offered to the next. Tool *calls* carry the
credential; `initialize` and `tools/list` do not. `${caller:token}` already drew
this line and connections follow it.

---

## Enabling connections

```properties
eddi.connections.enabled=true
eddi.connections.public-base-url=https://eddi.example.com
eddi.connections.credential-endpoint-allowlist=https://auth.atlassian.com,https://oauth2.googleapis.com
```

Disabled by default, matching the `openai-compat` precedent: a surface that
stores refresh tokens is one an operator turns on deliberately.

`ConnectionStartupGuard` refuses to boot on four states, each because the running
system would look fine afterwards:

| Refusal | Why |
| --- | --- |
| enabled with no `public-base-url` | It becomes the OAuth `redirect_uri`, which the provider matches **exactly**. Deriving it from an inbound request would let a `Host` header steer it. |
| `public-base-url` that is not a bare https origin | `startsWith("https://")` accepts a path, query, fragment and userinfo — each produces a redirect URI the provider will not match, and the failure surfaces as a user-facing OAuth error rather than a config problem. Dev and test also accept `http://localhost`. |
| a `PER_USER` connection with `authorization.enabled=false` | There is no verified identity, so anyone claiming `userId=alice` resolves Alice's tokens. |
| an OAuth connection with an inert vault | Grants are envelope-encrypted with the tenant DEK. This is the one place the `autoVaultSecret` degrade-to-plaintext pattern is unacceptable — these are refresh tokens. |

### Two allowlists, and why they are separate

`baseUrlAllowlist` (per connection) says where the **access token** may go.
`eddi.connections.credential-endpoint-allowlist` (per deployment) says where the
**client secret** may go — token, authorization and discovery endpoints.

They are separate because a client secret mints new access tokens, and because a
connection document must not be able to vouch for its own token endpoint: an
author who can edit one could otherwise point `tokenUrl` at a host they control
and receive the vault-resolved secret on the first refresh. Their origins also
routinely differ (`auth.atlassian.com` versus `api.atlassian.com`).

An empty credential-endpoint allowlist means **no OAuth connection can resolve**.
That is fail-closed on purpose: an unconfigured allowlist is far more likely than
an operator who meant "anywhere".

---

## Per-user accounts

```
POST /connections/{name}/authorize?returnTo=/manage/connections   → {"authorizationUrl": "…"}
GET  /connections/callback?code=…&state=…                          (the provider redirects here)
GET  /connections/mine                                             → your linked accounts
DELETE /connections/{name}/grant                                   → unlink
```

`authorize` is `@Authenticated` rather than role-gated: linking your own account
is not an administrative act. The principal comes from the verified identity —
never from a parameter — so there is no way to start a flow on somebody else's
behalf.

The **callback must be a permit path**. The provider redirects the user's browser
to it as a top-level GET with no bearer token, and
`quarkus.oidc.application-type=service` answers an unauthenticated request with a
401 rather than a login redirect. Its guard is the `state`:

* single-use, and **claimed by one conditional write that is the first thing the
  handler does** — validating and then marking consumed is a read-then-write, and
  two concurrent callbacks would both observe it unconsumed and both redeem the
  code;
* server-stored, not in memory, because behind a load balancer the redirect
  routinely lands on a different replica than the one that issued it;
* short-lived (10 minutes) and bound to tenant, connection and principal, so the
  callback never trusts a request parameter for identity.

**PKCE is mandatory**, not configurable. A public redirect endpoint without it is
an authorization-code interception vector, and no provider in scope lacks S256.

Unknown, expired and already-used states are answered **identically**. Telling
them apart is a state-guessing oracle, and none of the three is actionable by the
user beyond "start again".

---

## Refresh, and what happens when it fails

Two conversations hitting an expired grant at once both call the token endpoint.
With rotating refresh tokens — Google, Atlassian — the second call invalidates
the first and a user who did nothing wrong is silently logged out.

The fix is a claim taken **before** the network call:

1. **Claim** — one atomic conditional update on the grant row. Cross-replica gate.
2. The claimant refreshes; non-claimants poll for its result rather than
   refreshing blind.
3. **Write**, guarded by a version CAS, clearing the lease.

The ordering is the design. A version CAS alone is checked at *write* time, by
which point both replicas have already called the endpoint and the provider has
already rotated one token away — the CAS then dutifully serialises two writes,
one carrying a token that is already dead.

Failure semantics distinguish two cases that a naive implementation conflates:

| Provider says | EDDI does |
| --- | --- |
| `invalid_grant` / `invalid_client` / `unauthorized_client` | Marks the grant `REFRESH_FAILED`. The user must reconnect. |
| Timeout, 5xx, rate limit, connection refused | **Nothing.** The grant stays usable and the next request retries. |

Conflating them logs every user of a connection out during a five-minute provider
outage, and they come back to "reconnect required" for something that fixed
itself.

There is deliberately **no proactive refresh sweeper**. Lazy refresh with
single-flight is correct and simpler; add one only if telemetry shows cold-start
latency matters.

---

## Security rules

* **Only a reference is ever inherited, never a token.** Configs carry
  `${connection:name}`; the credential exists in memory for one outbound request.
* **Tokens are envelope-encrypted** with the vault's per-tenant DEK — the same
  key hierarchy as every other secret, so there is one key to rotate and one
  master key to protect rather than two.
* **Grants are never exported**, never returned by any REST endpoint, and never
  logged. `/connections/mine` returns connection name, status, scopes and expiry,
  enumerated explicitly rather than serialised from the entity.
* **Deleting a connection deletes its grants**, decided by re-reading the name
  rather than by the `permanent` flag — a soft delete already stops the name
  resolving, and leaving live refresh tokens at rest for a connection nobody can
  use is not a revocation.
* **`VaultGrantChecker` follows the hop.** A `${connection:name}` is an *indirect*
  vault reference: the connection document holds the `${vault:…}` client secret.
  Without following it an agent could use a credential it was never granted
  simply by naming somebody else's connection.
* **`PER_USER` fails closed twice** — at startup (the guard refuses the
  deployment) and per request (the resolver refuses without a verified
  principal). It never falls back to the service grant: sending the wrong
  authority is how one user reads another's data.
* **The token client goes through `SafeHttpClient`.** This is the one new
  outbound path, so it starts compliant. `Redirect.NEVER` matters more here than
  almost anywhere: a token request carries the client secret in an
  `Authorization` header, and a followed redirect would hand it to whatever host
  the 302 named.

---

## Metrics

Exposed at `/q/metrics`. **Tags are bounded categoricals only** — a connection
name is author-supplied and unbounded, so tagging with it would let a config
author mint Micrometer series without limit and publish provider names to anyone
who can read the endpoint.

| Metric | Tags |
| --- | --- |
| `connection.resolve.count` | `authType`, `binding`, `outcome` |
| `connection.resolve.time` | `authType`, `binding` |
| `connection.grant.missing.count` | `binding` — the per-user "not connected yet" signal |
| `connection.oauth.authorize.count` | `outcome`, `authType` |
| `connection.oauth.callback.count` | `outcome` (`success`, `bad_state`, `provider_error`, `exchange_failed`) |
| `connection.token.refresh.count` | `outcome` (including `invalid_grant` versus `transient`) |
| `connection.token.refresh.claim.count` | `outcome` (`claimed`, `awaited`, `lease_expired`) |

---

## Limitations

* **Language models, embedding models and vector stores do not accept
  `${connection:…}`** and refuse it with an explanatory error. A connection
  resolves to an HTTP *header*, while those builders need a bare credential, and
  there is no honest way to derive one — stripping a scheme prefix off a static
  template is a guess, and a guess that is wrong for one provider out of eleven
  produces an authentication failure with no visible cause. Those caches are also
  keyed on *unresolved* parameters by design. Use `${vault:…}`, which does
  everything a `SERVICE`-bound connection would there.
* **Slack channel integrations** still use their own `botToken` field.
* **Group conversations and `PER_USER`** — when a group agent acts inside a
  `GroupConversation`, whose token it should use is an open question; today the
  resolver refuses because the principal is not the human. Decide before relying
  on it.
* **No dynamic client registration** (RFC 7591). An admin registers the client
  once and stores the id and secret.
* **Revocation is local.** Deleting a grant stops EDDI resolving it; EDDI does
  not call the provider's revocation endpoint, so the token stays live at the
  provider until it expires.

---

## See also

- [Secrets Vault](secrets-vault.md) — where every credential actually lives
- [httpcalls](httpcalls.md) — including `${caller:token}`, the same-origin special case
- `mcpcalls` configurations — connecting to somebody else's MCP server, whose `apiKey` accepts a connection reference
- [HITL](hitl.md) — approving a connection-backed call before it runs

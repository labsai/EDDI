# Connections — one credential model for every outbound call

> A **connection** describes *how to authenticate to one external system*. Any
> outbound configuration references it as `${connection:name}`, and EDDI resolves
> it to a credential **per request** — which is what lets the same model cover an
> org-wide API key and each end user's own OAuth grant.

**Contents:** [Why](#why) · [Quick start](#quick-start) · [The model](#the-model) ·
[Where a reference may appear](#where-a-reference-may-appear) ·
[Enabling connections](#enabling-connections) · [Per-user accounts](#per-user-accounts) ·
[Refresh, and what happens when it fails](#refresh-and-what-happens-when-it-fails) ·
[Rotating the key that holds them](#rotating-the-key-that-holds-them) ·
[Export and import](#export-and-import) · [Security rules](#security-rules) · [Metrics](#metrics) ·
[Limitations](#limitations)

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

```http
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

### A credential the caller hands over

For a platform that has already authenticated the user and passes their own
credential inward on every request. EDDI stores nothing — the connection says only
which header the credential goes in and where it may be sent.

```json
{
  "name": "gnowbe",
  "authType": "STATIC",
  "binding": "CALLER_SUPPLIED",
  "staticAuth": { "headerName": "x-api-key" },
  "baseUrlAllowlist": ["https://api.gnowbe.com"]
}
```

Note the absence of `valueTemplate`: the caller supplies the value, so a stored one
is refused at save time rather than left to race theirs. `username` and
`passwordRef` are refused for the same reason, and `authType` must be `STATIC` —
there is nothing for EDDI to encode, exchange or refresh.

**The caller must be authenticated to EDDI.** The header is read only from a
request with a verified identity; an anonymous request has it dropped, with a
warning, so that an unauthenticated caller can never make EDDI spend a credential
on its behalf. With `authorization.enabled=false` (the shipped default) every
caller is anonymous, so a `CALLER_SUPPLIED` connection could save and then refuse
every call. It is therefore refused at the write boundary with a **400** naming
`authorization.enabled`, exactly as a `PER_USER` connection is, and a stored one
is reported by the startup guard.

The caller attaches it per request, once per connection:

```
X-EDDI-Connection-Credential: gnowbe key-id:secret
```

The connection name runs up to the first space; everything after it is the whole
header value to send, so a value containing spaces (`Bearer abc`) needs no escaping.

**Why choose this over a service key.** Authority, not convenience. An agent holding
one org-wide key can reach everything that key can, and only the agent's own
reasoning stands between a user and data they should not see. A caller-supplied
credential makes the target platform's authorization the boundary — the agent cannot
do what the user cannot do — without EDDI modelling that platform's permissions at
all.

**It is not `${caller:token}`.** That relays EDDI's *own* credential, and only ever
back to the origin the caller addressed. This carries a credential for a different
system entirely, to wherever `baseUrlAllowlist` permits. The same-origin rule on
`${caller:token}` stays exactly as strict as it is; do not read this as loosening it.

**It is not a client-supplied principal either.** EDDI derives no identity from the
credential and looks nothing up by it. The credential authenticates at the target or
it does not — which is why this binding needs no verified principal, and works in the
common topology where an integrating backend calls EDDI as one service account with
each end user's key attached.

**Fail closed, including on resume.** A request that carries no credential for the
connection is refused (`NO_CALLER_CREDENTIAL`, HTTP 400) rather than sent
unauthenticated. That includes
`POST /agents/{conversationId}/resume`: the credential lives for one request and does
not survive a HITL pause, so the system releasing an approved tool call must attach it
again. Where approvals surface in the integrating platform's own UI — and its backend
makes the resume call — this is the request it was already going to send.

**Withheld from discovery.** An MCP `initialize`/`tools/list` handshake or an A2A
agent-card fetch is cached and replayed for every conversation that follows, so a
`CALLER_SUPPLIED` connection resolves to nothing there. Whichever caller happened to
trigger the handshake would otherwise pin their credential, and their permissions,
onto everybody after them — the same rule `PER_USER` follows.

`binding` and `authType` constrain each other in **both** directions, and both
rules are enforced at save time. `PER_USER` requires
`OAUTH2_AUTHORIZATION_CODE`, because nothing else produces a grant per end user.
`OAUTH2_AUTHORIZATION_CODE` equally requires `PER_USER` — the flow files its
grant under whoever completed the consent screen, so a `SERVICE`-bound one would
look for a grant under a service principal that nothing can ever create. Since
`binding` defaults to `SERVICE`, omitting it here used to produce a connection
that saved, deployed, showed a working consent screen, and then failed every call
as "not connected". Use `OAUTH2_CLIENT_CREDENTIALS` for a service account.

---

## The model

| Field | Purpose |
| --- | --- |
| `name` | What `${connection:name}` refers to. Must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` — letters, digits, `.`, `_`, `-`, starting with a letter or digit, at most 64 characters, no surrounding whitespace. Refused otherwise, never trimmed: a space, `/`, `}` or `:` would make the name unreferenceable, and the `X-EDDI-Connection-Credential` header splits at the first space |
| `authType` | `STATIC`, `BASIC`, `OAUTH2_CLIENT_CREDENTIALS`, `OAUTH2_AUTHORIZATION_CODE` |
| `binding` | `SERVICE` (one grant for everyone), `PER_USER` (the caller's own, stored), or `CALLER_SUPPLIED` (the caller's own, handed over per request) |
| `allowUnverifiedPrincipal` | `PER_USER` only. Accept a user id EDDI never authenticated, on the grounds that a front proxy did. Default `false` — see [Whose identity counts](#whose-identity-counts) |
| `staticAuth` | Header name plus a reference-only value template |
| `oauth` | Endpoints, client id, a **vaulted** client secret, scopes |
| `baseUrlAllowlist` | The origins this credential may be sent to. **Required.** Bare origins (`scheme://host[:port]`). `http://` to a loopback host (`localhost`, `127.0.0.1`, `[::1]`) is always accepted. `http://` to any other host sends the credential across the network unencrypted, so it is **refused** unless the deployment sets `eddi.connections.allow-plaintext-remote-origins=true` — see [Plaintext origins](#plaintext-origins) |
| `timeoutMs` | Token-endpoint timeout in milliseconds, **1–60000**; refused outside that range at save time. Unset means the resolver's default. The token client applies its own lower ceiling at use so the refresh lease always outlasts the request |

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
checked too, since an arbitrary string map is the obvious place to paste one:
a credential-shaped **key** (`api_key`, `code_verifier`, …) is refused however it
is punctuated, a key EDDI composes itself (`redirect_uri`, `state`,
`code_challenge`, `code_challenge_method`, `client_id`, `response_type`,
`code_verifier`, `client_secret`) is refused case-insensitively, and every
**value** must be a literal of at most 512 characters with no `${…}` reference
and no credential-shaped prefix (`sk-`, `xoxb-`, `ghp_`, `AKIA`, `eyJ`, `Bearer `).

For `clientSecret` and `passwordRef` the check is "the value **is** a reference",
not "contains one". A `valueTemplate` may carry literal text around its references
— that is how `Bearer ${vault:k}` gets its scheme — so its literal text is checked
too, under three rules: every `${` must be a well-formed `${vault:…}` or
`${vars:…}` reference (an unknown prefix, an empty key, an unclosed brace or a key
over 256 characters is refused rather than treated as literal text); at least one
reference must be present; and each literal segment between or around the
references is at most **32 characters** with **no run of 12 or more key
characters** (`[A-Za-z0-9_-+/=.]`). `Bearer `, `Basic `, `token=` and `SSWS ` pass;
`sk-live-abcdef${vault:unused}` is a literal key with a reference stapled on, and
it is refused with a message that quotes the literal redacted to its first four
characters.

---

## Where a reference may appear

**Headers only**, on the HTTP-templating paths — the same restriction set as
`${caller:token}`, for the same reasons.

| Placement | Result |
| --- | --- |
| httpcall **header** | ✅ Resolved per request |
| mcpcalls `apiKey` | ✅ Resolved per request (on discovery too, unless `PER_USER` — see below) |
| A2A `apiKey` | ✅ Resolved per call (same discovery rule) |
| httpcall path / query / body | ❌ Refused at build time |
| LLM, embedding, vector-store parameters | ❌ Refused — see [Limitations](#limitations) |

A credential in a URL or a query string is written to ingress logs, proxy logs
and browser history before the provider ever sees it. A credential in a body is
not one the provider will read. All three are refused rather than sent as literal
text and answered with an unexplained 401.

### The value must be the reference, and nothing else

A connection supplies the **whole** header value — scheme included — so a value
that wraps text around the reference is refused with an error naming the header
and quoting the surrounding literal (redacted):

```text
"Authorization": "${connection:jira}"           ✅
"Authorization": "Bearer ${connection:jira}"    ❌ refused
```

Both halves of that used to be lost silently. Only the first reference in a value
was ever parsed and everything around it was dropped, so `Bearer ${connection:x}`
against an OAuth connection happened to work — the connection contributes its own
`Bearer ` — and the identical config against a `STATIC` connection holding a bare
token sent the token with no scheme at all. The 401 that came back named nothing.
Put the scheme in the connection's `valueTemplate`, where one connection's answer
is the same on every path that uses it.

The same rule applies to an mcpcalls or A2A `apiKey`, with the same message, so an
author who trips over it in one place reads the sentence they already know.

Two more header rules exist for the same reason — a credential must never move
without saying so:

* **The header must be named what the connection names.** A header referencing a
  connection whose `staticAuth.headerName` is `Authorization` must itself be
  called `Authorization`. Otherwise the config says `X-Jira-Auth` and the request
  carries `Authorization`, and the two disagree with nothing reporting it.
* **One credential per header name.** Two references resolving to the same header
  name, or a plain header colliding with a connection-owned one, are refused
  rather than left to iteration order — HTTP header names are case-insensitive and
  a later write replaces an earlier one silently.

**Discovery follows the binding.** The MCP `initialize` handshake and `tools/list`
— and the A2A agent-card fetch — run once per cached client and their result is
reused by every conversation that follows, so the rule is about *whose* credential
may pin a shared session, not about whether one is sent at all:

* **`SERVICE`** — the credential goes out. It is the same credential for everybody
  by definition, so there is nothing to leak and every reason to send it.
* **`PER_USER`** — nothing goes out, and the request is made unauthenticated with a
  warning naming the cause. One user's session, and their permissions, must not be
  handed to everybody after them. `${caller:token}` draws the same line.

Withholding on *every* binding is the tempting simplification and it is a
functional break rather than a safety measure: discovery goes out unauthenticated,
the server answers 401, and the agent silently has no tools at all with nothing
anywhere naming credentials. An unknown connection name still fails loudly on the
discovery path, because the alternative is another empty tool list with no
explanation.

---

## Enabling connections

```properties
eddi.connections.enabled=true
eddi.connections.public-base-url=https://eddi.example.com
eddi.connections.credential-endpoint-allowlist=https://auth.atlassian.com,https://oauth2.googleapis.com
```

Disabled by default, matching the `openai-compat` precedent: a surface that
stores refresh tokens is one an operator turns on deliberately.

`ConnectionStartupGuard` **refuses to boot** on two states, both properties of the
deployment rather than of any stored document:

| Refusal | Why |
| --- | --- |
| enabled with no `public-base-url` | It becomes the OAuth `redirect_uri`, which the provider matches **exactly**. Deriving it from an inbound request would let a `Host` header steer it. |
| `public-base-url` that is not a bare https origin | `startsWith("https://")` accepts a path, query, fragment and userinfo — each produces a redirect URI the provider will not match, and the failure surfaces as a user-facing OAuth error rather than a config problem. The scheme is compared case-insensitively. Dev and test also accept `http://localhost[:port]` and `http://127.0.0.1[:port]` — loopback only, and still a bare origin. |

### The states the guard reports rather than refuses

These are read from what is actually **stored**, because the dangerous state is
"somebody created this connection on a deployment that cannot honour it" and no
configuration property records that. Each is logged at ERROR and named on the
console; none of them stops the boot.

| Reported at boot | What happens at request time |
| --- | --- |
| a `PER_USER` connection with `authorization.enabled=false` | Every resolution of it is refused. There is no verified identity, so anyone claiming `userId=alice` would otherwise resolve Alice's tokens. |
| a `PER_USER` connection while `/v1` is enabled in api-key mode with `eddi.openai-compat.trust-user-headers=true` | Conversations opened through `/v1` carry a caller-supplied user id, so a holder of the shared api key can open a conversation as anyone. Those conversations are refused a `PER_USER` credential — see [Whose identity counts](#whose-identity-counts). |
| a `CALLER_SUPPLIED` connection with `authorization.enabled=false` | Every call through it is refused as `NO_CALLER_CREDENTIAL`: the credential header is read only from an authenticated caller, and with OIDC off every caller is anonymous. |
| an OAuth connection with an inert vault | Every grant it would store or read is refused. Grants are envelope-encrypted with the tenant DEK, and this is the one place the `autoVaultSecret` degrade-to-plaintext pattern is unacceptable — these are refresh tokens. |
| a first-release `OAUTH2_AUTHORIZATION_CODE` connection still bound to `SERVICE` | Validation runs on write only, so the document loads — and fails every call as "not connected", because the flow files its grant under the user who consented and a `SERVICE`-bound resolution looks under a principal nothing can create a grant for. Re-save it as `PER_USER`. |

A connection whose `baseUrlAllowlist` sends its credential over plaintext `http://`
to a non-loopback host is reported at **ERROR** while
`eddi.connections.allow-plaintext-remote-origins=false` (the default), because every
call through it to that origin is refused, and at **WARN** when the property is
`true` — see [Plaintext origins](#plaintext-origins).

**Reporting, not refusing, is deliberate**, and the reason is worth stating because
it looks like a weakened control and is not. Refusing meant that an administrator
saving one connection through the REST API — a live, permitted, single request —
left every replica in the cluster unable to boot from that moment on, including
the ones that had not restarted yet and so gave no warning. The next rolling
restart took the deployment down over a config document, fixable only by editing
the database.

Enforcement lives in the two places where it costs nothing and lands on someone
who can act:

* **The write boundary.** `POST /connectionstore/connections`,
  `PUT /connectionstore/connections/{id}` and the duplicate endpoint
  `POST /connectionstore/connections/{id}` answer **400** for a `PER_USER` or
  `CALLER_SUPPLIED` connection when `authorization.enabled=false`, **400** for
  an OAuth connection when the vault is inert, and **400** for a remote `http://`
  origin while `eddi.connections.allow-plaintext-remote-origins=false`. The
  administrator who wrote it is still looking at it.
* **Per request.** `ConnectionResolver` refuses, and never falls back to the service
  grant. Sending the wrong authority is how one user reads another's data.

The startup report still matters for a document that reached the store some other
way — an import, a direct database write, a downgrade — and for making the refusals
explicable rather than mysterious.

An empty `credential-endpoint-allowlist` is logged as a **warning** rather than an
error, because `STATIC` and `BASIC` connections are unaffected by it. What it costs
an OAuth connection is below.

### Plaintext origins

```properties
eddi.connections.allow-plaintext-remote-origins=false
```

A `baseUrlAllowlist` entry is where a connection's credential is delivered, so an
`http://` origin on anything but loopback puts that credential on the network
unencrypted. While the property is `false` — the default — such an origin is
refused in three places:

| Where | What happens |
| --- | --- |
| Save (`POST`/`PUT`, duplicate, archive import) | **400** naming the property; an import skips the connection with that reason |
| Each request (`ConnectionResolver`) | `TARGET_NOT_ALLOWED` naming the property, before any secret is resolved |
| Boot (`ConnectionStartupGuard`) | Reported at **ERROR** for every stored connection that has one |

Set it to `true` to accept a plaintext internal service deliberately; the origin is
then resolved as before and reported at **WARN** at save time and at boot.
`http://localhost`, `http://127.0.0.1` and `http://[::1]` are allowed either way —
they never leave the machine.

> **Upgrade note.** Earlier builds accepted a remote `http://` origin with a warning.
> After upgrading, **existing connections with a remote `http://` origin stop
> resolving** — every call to that origin is refused as `TARGET_NOT_ALLOWED`, and
> re-saving the connection answers 400 — until the origin is changed to `https://`
> or `eddi.connections.allow-plaintext-remote-origins=true` is set. The first boot
> names each affected connection at ERROR.

### Two allowlists, and why they are separate

`baseUrlAllowlist` (per connection) says where the **access token** may go.
`eddi.connections.credential-endpoint-allowlist` (per deployment) says where the
**client secret** may go — the token and authorization endpoints, and only those.
(RFC 9728 resource-metadata discovery is **not implemented**: `McpAuthChallengeParser`
can read a `WWW-Authenticate` challenge, but nothing fetches the metadata document
or selects an authorization server from it.)

They are separate because a client secret mints new access tokens, and because a
connection document must not be able to vouch for its own token endpoint: an
author who can edit one could otherwise point `tokenUrl` at a host they control
and receive the vault-resolved secret on the first refresh. Their origins also
routinely differ (`auth.atlassian.com` versus `api.atlassian.com`).

**A credential endpoint must be https.** `tokenUrl` and `authorizationUrl` are
refused at save time unless they use `https`, or plain `http` to a loopback host
(`localhost`, `127.0.0.1`, `[::1]`) where nothing crosses the network. The token
client applies the same rule again immediately before a request leaves the process,
answering `INVALID_CONFIGURATION` naming `oauth.tokenUrl` with nothing sent: the
allowlist below accepts `http://` origins, and a document that reached the store by
a direct database write never ran save-time validation, so the allowlist alone
would let a client secret travel in the clear.

An empty credential-endpoint allowlist means **no OAuth connection can resolve**.
That is fail-closed on purpose: an unconfigured allowlist is far more likely than
an operator who meant "anywhere".

**A listed origin may be on a private network.** The token request still goes
through `SafeHttpClient`, but the SSRF address check — which refuses loopback,
RFC 1918, link-local and cloud-metadata addresses — is not applied to the token
endpoint. The allowlist is a stricter rule than that check: an exact origin an
operator wrote down, not "anything public", and the operator who listed
`https://idp.corp.internal` is exactly the person entitled to point a client
secret at it. Scheme and host are still validated, redirects are still never
followed, and nothing else in EDDI gets the exemption — an httpcall to the same
host is still subject to `eddi.security.ssrf-protection`.

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
behalf. It is also checked in code and not only by the annotation, because
`@Authenticated` is a no-op when `authorization.enabled=false`; an anonymous
caller gets **403**, naming the reason.

What the four routes answer:

| Situation | Status |
| --- | --- |
| `eddi.connections.enabled=false` on `authorize`, `mine` or `disconnect` | **404**, with a body naming the setting to turn on |
| `eddi.connections.enabled=false` on the callback | **404**, empty — there is no caller to advise, only a browser the provider redirected |
| `authorize` or `disconnect` or `mine` without a verified identity | **403** |
| `authorize` for a connection that does not exist | **404** |
| `authorize` for a connection that is not `OAUTH2_AUTHORIZATION_CODE` | **400** — nothing else is linked by a user |
| `authorize` whose `authorizationUrl` or `tokenUrl` is not on the credential-endpoint allowlist | **400**, naming the origin and the setting |
| `authorize` succeeding | **200** with `{"authorizationUrl": …}` and a `Set-Cookie` binding the flow to this browser |
| the callback, in every outcome including failure | **303** back to `returnTo`, carrying `?connected=<name>` or `?error=<code>` |
| `disconnect` | **204**, whether or not a grant existed — whether a given user linked a given connection is not something to learn by probing |

The 404 for a disabled feature is the same answer the callback gives in that state:
one disabled feature that answers two different ways is a puzzle for whoever is
turning it on. It is also the status whose body actually arrives, since the error
mapper copies a message into the response only for 4xx — a 503 here delivered an
empty body and left the one sentence naming the setting in the server log.

### When a refusal escapes to a REST caller

A connection refusal reaching a REST boundary is answered with the status that
describes it, message included:

| Refusal | Status |
| --- | --- |
| `INVALID_CONFIGURATION`, `TARGET_NOT_ALLOWED`, `UNSUPPORTED_PLACEMENT` | **400** |
| `NOT_FOUND` — no connection of that name in this tenant | **404** |
| `NOT_CONNECTED`, `NO_VERIFIED_PRINCIPAL`, `GRANT_UNUSABLE` | **409** |
| `TOKEN_ENDPOINT_UNAVAILABLE` | **503** |

The three "you have not linked an account" reasons are **409** rather than 401 or
403 on purpose: the caller is authenticated and permitted, the resource simply is
not in a state that can serve the request yet, and the action that fixes it is
connecting or reconnecting rather than presenting a different token.

Copying the message through is safe here in a way it is not for a store failure —
these messages are written for the agent designer or end user who has to act on
them and never quote a credential. Without that, every escaping refusal was a bare
**500** with an empty body, and the sentence saying exactly what to fix ("add this
origin to `eddi.connections.credential-endpoint-allowlist`", "connect your account
first") reached the server log only. The person who has to act on it is the one
who never saw it.

### Whose identity counts

A `PER_USER` credential is released only to a conversation whose user id something
actually **authenticated**. A user id on its own does not say who asserted it, and
`authorization.enabled=true` is not proof that anybody did: the OpenAI-compatible
`/v1` adapter, in api-key mode with `eddi.openai-compat.trust-user-headers=true`
(the shipped default), believes a caller-supplied `X-OpenWebUI-User-Id` verbatim
once the shared key matches. A holder of that one key can open a conversation as
any user, and anything trusting the conversation's user id would hand them that
user's live SaaS tokens.

So a conversation records **how** its user id came to be, once, at creation:

| Provenance | Means |
| --- | --- |
| `VERIFIED` | The id matched the principal of the authenticated request that created the conversation. |
| `SELF_ASSERTED` | Nobody authenticated it — the caller asserted it, a trusted-proxy header supplied it, it was generated for an anonymous session, or the conversation predates provenance being recorded. |

`PER_USER` resolution requires `VERIFIED`. Anything else is refused, by name, with
the advice that applies: enable OIDC, make the connection `SERVICE`-bound, or opt
in below. A conversation spawned from inside a running turn — a sub-agent, a
delegate, a group member — inherits its parent's provenance, but **only for the
same user id**: inheriting a verification that was never about this subject is how
one user's grant becomes reachable from another's conversation.

**The opt-in.** Authenticating users at a front proxy is a legitimate deployment
rather than a mistake, so `allowUnverifiedPrincipal: true` on a connection accepts
a `SELF_ASSERTED` principal for that connection. What it costs, plainly: with it
on, anyone who can assert a user id to the fronting proxy resolves *that user's*
stored credentials, and nothing downstream re-checks the assertion — the id is the
entire authority for choosing whose refresh token to spend, so a proxy that lets a
caller pick the id it forwards hands out other people's live tokens.
It is default off and per connection rather than per deployment, so turning it on
is a decision about one provider's tokens — proxy identity can be good enough for a
calendar connection while the finance one still demands a principal EDDI verified
itself. It is refused at write time on any binding but `PER_USER`: a flag promising
to loosen an identity check, sitting where it does nothing, reads to the next person
as a posture already in force.

> **Migration.** Conversations that existed before provenance was recorded carry
> none, which counts as **not verified**. A user in a long-running conversation will
> be refused a `PER_USER` credential until that conversation is **started again**
> once. That polarity is the point rather than an oversight: the conversations this
> field exists to distrust are exactly the ones that predate it, and grandfathering
> them would leave the hole open on precisely the deployments that just closed it.
> Nothing else needs migrating — no grant is invalidated, no re-link is required.

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

The state is **not sufficient on its own**, and the reason is the attack people
usually have backwards. The state binds a principal — but on a hostile flow the
*attacker* chooses that principal. They start a link under their own EDDI
account, keep the state, and send the victim the provider's consent link built
around it. The victim consents with their own Google account, the callback files
the resulting tokens under the principal in the row — the attacker — and the
attacker reads the victim's mail on their next turn. Every field in the row is
exactly what it should be.

So `authorize` also issues a **nonce cookie** (`eddi_oauth_nonce_<state>`,
`HttpOnly`, `SameSite=Lax`, `Secure` whenever the public base URL is `https`,
scoped to `/connections`), and the callback refuses unless the request carries
the matching nonce for its state. Only the SHA-256 of the nonce is stored, so
database read access is not enough to complete a flow.

> **If your UI is on a different origin than EDDI**, the call to `authorize` must
> send and store credentials (`fetch(..., { credentials: 'include' })`, and CORS
> configured to allow them) or the browser will never hold the cookie and every
> link attempt will fail at the callback. EDDI-Manager is served from EDDI itself,
> so the default deployment needs no special handling.

**PKCE is mandatory**, not configurable. A public redirect endpoint without it is
an authorization-code interception vector, and no provider in scope lacks S256.

Unknown, expired and already-used states are answered **identically**, and so is
a missing or mismatched nonce cookie. Telling them apart is a state-guessing
oracle, and none of them is actionable by the user beyond "start again".

`DELETE /connections/{name}/grant` deletes **by name**, without requiring the
connection to still exist. The case that matters most is exactly the one where it
does not: an administrator deletes a connection while automatic grant cleanup is
failing, and the user would otherwise be left holding a live refresh token with
no way to revoke it. Unlinking must never be harder than linking was.

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

## Rotating the key that holds them

Grants are sealed with the tenant's DEK, so `POST /secretstore/secrets/{tenantId}/rotate-dek`
touches every linked account in the tenant. It is safe to run with users connected,
and the reason is that **rotation adds a key rather than replacing one**.

A tenant holds one DEK row per **generation**, and every sealed value records the
generation that sealed it. Rotation:

1. **Verifies** — opens every existing generation with the current master key, so a
   wrong `EDDI_VAULT_MASTER_KEY` is discovered before anything is written.
2. **Commits** — *inserts* the next generation. One statement, guarded by a unique
   key on `(tenant, generation)`, and the single atomic point in the whole
   operation. From here new values seal with the new key while every existing row
   still names a generation that exists and decrypts.
3. **Sweeps** — moves rows onto the new generation one at a time, each write guarded
   on the state the row was read in, so a refresh landing mid-sweep is never
   overwritten with a re-seal of the tokens it replaced. Handing back a refresh
   token the provider has already rotated away is how a sweep would log a user out.

**Old generations are never deleted.** That is what makes an interrupted sweep
harmless rather than fatal: a row the sweep did not reach still names a key that
opens it. Deleting the generation a row still names is the failure this design
exists to remove, so nothing in EDDI does it — pruning one is an operator decision
that requires knowing no row still names it.

A partial sweep is **reported, not hidden**. The REST call answers **500** with a
message saying that the new generation is active, that at least *N* sealed rows
still name an older one, that nothing is lost, and that the operation is safe to
**re-run** — a re-run picks up exactly the rows the previous one left. Each grant
gets two attempts against a concurrent writer; a row losing twice is a row being
refreshed continuously, and leaving it costs nothing.

The ordering is what this design is for. Re-sealing everything and then swapping
the key is the obvious shape and the dangerous one: any interruption between the
two steps leaves rows that neither key opens, so a routine, documented,
compliance-driven rotation silently disconnects every linked account in the tenant
and is discovered one `invalid_grant` at a time, days later, with no way back.
Refusing to rotate at all while grants exist is the other candidate and is worse
again — it makes a compliance control unusable from the moment the first user links
an account.

Grants join the sweep through the `SealedDataRotationParticipant` SPI, discovered
by CDI so that `ai.labs.eddi.secrets` stays a leaf package — anything else that
seals data joins by implementing it, and the vault does not learn about it.
[KEK rotation](secrets-vault.md#key-rotation) re-wraps **every** generation, not
just the newest, and leaves ciphertext untouched.

---

## Export and import

An agent archive carries the connections its configurations reference. Exporting an
agent scans every archived config for `${connection:name}` — an httpcall header, an
mcpcalls or A2A `apiKey`, wherever an author put one — and writes each referenced
connection's document into `connections/{connectionId}.connection.json`. A connection
nothing references is not exported, and neither is a reference to a tenant other than
the default.

**What travels is the document, and only the document**: name, `authType`, `binding`,
`staticAuth` or `oauth` block, `baseUrlAllowlist`. Every secret-bearing field in it is a
`${vault:…}` reference, so the target needs the same vault entries — exactly as it does
for any other exported config — and nothing resolved ever enters the archive. **Grants
are never exported.** Linked accounts stay where they were linked; a user links again on
the target.

On import, a connection is created only when the target holds **no connection of that
name**. An existing one is **never overwritten** — it is a live credential configuration,
possibly with linked accounts filed under that name — whatever the import strategy. The
create runs through the same gate as `POST /connectionstore/connections`: structural
validation, the deployment checks (`PER_USER` and `CALLER_SUPPLIED` need OIDC, OAuth needs
an active vault, a remote `http://` origin needs `eddi.connections.allow-plaintext-remote-origins`)
and the name-uniqueness lock. A document the deployment refuses is
**skipped with the reason logged**, not a failed import — the agent is still worth having,
and the refusal names what to fix. Skips of both kinds are counted in an
`X-Connections-Skipped` header on the import response.

## Security rules

### Who may do what

| Endpoint | Roles |
| --- | --- |
| `GET /connectionstore/connections/descriptors` — list connections | `eddi-admin`, `eddi-editor` |
| `GET /connectionstore/connections/{id}` — read one document | `eddi-admin`, `eddi-editor` |
| `POST`, `PUT`, `DELETE` and the duplicate `POST /{id}` | `eddi-admin` only |
| `GET /connectionstore/connections/jsonSchema` | `eddi-admin` only |
| `POST /connections/{name}/authorize`, `GET /connections/mine`, `DELETE /connections/{name}/grant` | any authenticated user — see [Per-user accounts](#per-user-accounts) |

Writes are admin-only because a connection is an egress channel plus a
credential — the same class of capability as a vault write. The two reads admit
an editor because an httpcall author cannot write `${connection:jira}` without
knowing that `jira` exists, and the Manager's picker needs the same list. Reading
is safe: a document carries only `${vault:…}` references, `clientId` is public by
definition, and every secret-bearing field is refused a literal at write time.

* **Only a reference is ever inherited, never a token.** Configs carry
  `${connection:name}`; the credential exists in memory for one outbound request.
* **Tokens are envelope-encrypted** with the vault's per-tenant DEK — the same
  key hierarchy as every other secret, so there is one key to rotate and one
  master key to protect rather than two. **DEK rotation carries them across**, and
  the shape of that is what makes a routine compliance rotation safe to run with
  users linked — see [Rotating the key that holds them](#rotating-the-key-that-holds-them).
* **Grants are never exported**, never returned by any REST endpoint, and never
  logged. `/connections/mine` returns connection name, status, scopes and expiry,
  enumerated explicitly rather than serialised from the entity.
* **A name is unique per tenant, case-sensitive.** `${connection:jira}` names
  one connection and must keep naming the same one. The store is a versioned
  document store with no unique index, so uniqueness is enforced on the write
  path: creates of one name are serialised inside a node — the document *and* its
  descriptor, which is what a name lookup reads, are both written before the lock
  is released, so a second create on the same node always sees the first — and
  after the write lands the store is asked again who holds the name. A create
  that finds another holder is rolled back, descriptor included, and answered
  **409**. What remains is replication lag between nodes: two replicas that each
  see the other both stand down, and both callers are told to retry — a wasted
  request, chosen over the alternative of two connections under one name.
* **Deleting a connection deletes its grants**, decided by re-reading the
  connection's `(tenant, name)` at its *current* version rather than by the
  `permanent` flag or the version in the request — a soft delete already stops the
  name resolving, and leaving live refresh tokens at rest for a connection nobody
  can use is not a revocation.
* **A connection cannot be renamed.** The name is both what `${connection:…}`
  refers to and what every grant is filed under, so a rename would orphan this
  connection's grants and hand them to whatever is created under the old name
  next — a fresh connection, possibly to a different provider, resolving other
  people's live refresh tokens on its first call. Create a new connection and let
  users link it.
* **A connection's `authType` and `binding` cannot change while it has linked
  accounts.** The rename rule protects the name a grant is filed under; this
  protects what the grant *is*. Re-saving a `PER_USER` authorization-code
  connection as `STATIC` or as `SERVICE`-bound client credentials would leave
  every user's refresh token at rest under a name the resolver never reads them
  for, and off a linked-accounts page the connection no longer has. `PUT` answers
  **409** naming the number of linked accounts and the two ways forward: each user
  unlinks with `DELETE /connections/{name}/grant`, or the administrator deletes the
  connection — which cascades to its grants — and creates the new one.
* **`VaultGrantChecker` follows the hop.** A `${connection:name}` is an *indirect*
  vault reference: the connection document holds the `${vault:…}` client secret.
  Without following it an agent could use a credential it was never granted
  simply by naming somebody else's connection.
* **`PER_USER` fails closed twice** — at the write boundary (creating one on a
  deployment without OIDC is a 400) and per request (the resolver refuses without a
  *verified* principal, not merely a present one — see
  [Whose identity counts](#whose-identity-counts)). It never falls back to the
  service grant: sending the wrong authority is how one user reads another's data.
  The startup guard *logs* rather than throwing, deliberately: a fatal check there
  meant one permitted REST write left every replica unable to boot, and the next
  rolling restart took the deployment down over a config document.
* **A `PER_USER` credential belongs to the conversation's owner**, not to whoever
  is driving the current request. The two are the same person on an ordinary turn
  and are *not* on a **HITL resume**: the resume request is made by the approver —
  often an administrator, by design — while the call being approved belongs to the
  user who asked for it. Both the user id and its provenance are therefore read
  from the **stored conversation**, never from the resuming request. That
  distinction exists because a resume proves who approved and says nothing about
  whose credentials the approved call may spend; reading the request identity there
  ran approved calls against the approver's SaaS account, and the approval did not
  mean what the approver was shown.
* **The token client goes through `SafeHttpClient`, and follows no redirect.**
  This is the one new outbound path, so it starts compliant — and it uses the
  client's no-redirect send deliberately. The ordinary `sendValidated` follows
  redirects itself on top of `Redirect.NEVER`, preserving method and body on a
  307/308, and checks the target only against the SSRF rules, never against the
  credential-endpoint allowlist. A token request carries the client secret in an
  `Authorization` header or the form body, and always a refresh token or a code
  plus verifier in the body, so a followed redirect would hand all of that to
  whatever host an allowlisted endpoint pointed at. Any 3xx from a token endpoint
  is therefore answered as `TOKEN_ENDPOINT_UNAVAILABLE` — the grant is untouched
  — with a message saying a token endpoint must not redirect.

---

## Metrics

Exposed at `/q/metrics`. **Tags are bounded categoricals only** — a connection
name is author-supplied and unbounded, so tagging with it would let a config
author mint Micrometer series without limit and publish provider names to anyone
who can read the endpoint.

| Metric | Tags | `outcome` values |
| --- | --- | --- |
| `eddi.connection.resolve.count` | `authType`, `binding`, `outcome` | `success`, plus the lower-cased refusal reason: `not_found`, `invalid_configuration`, `no_verified_principal`, `not_connected`, `no_caller_credential`, `grant_unusable`, `target_not_allowed`, `token_endpoint_unavailable` |
| `eddi.connection.resolve.time` | `authType`, `binding` | — |
| `eddi.connection.grant.missing.count` | `binding` | — (incremented alongside a `not_connected` resolve: the per-user "not connected yet" signal) |
| `eddi.connection.oauth.authorize.count` | `outcome`, `authType` | `issued` |
| `eddi.connection.oauth.callback.count` | `outcome`, `authType` | `success`, `bad_state`, `binding_mismatch`, `provider_error`, `exchange_failed` |
| `eddi.connection.token.refresh.count` | `outcome` | `success`, `minted`, `invalid_grant`, `transient` |
| `eddi.connection.token.refresh.claim.count` | `outcome` | `claimed`, `awaited`, `lease_released`, `lease_expired` |

All seven carry the `eddi.` prefix (scraped as `eddi_connection_…_total`), which is
what lets `MetricsDashboardCoverageTest` see them: the four OAuth and refresh
meters used to be registered unprefixed through a helper the guard could not read,
and were absent from the full dashboard and the metrics reference on a green
build.

Three of those are worth knowing by name:

* **`binding_mismatch`** on the callback means a state that was otherwise valid
  arrived **without the nonce cookie** that started the flow. That is either a link
  followed in a different browser or the confused-deputy attack described above, so
  it is counted separately even though the browser is told nothing that
  distinguishes it from `bad_state`.
* **`lease_released`** means a refresh claimant handed the lease back without
  writing a token — it failed transiently, or it died — so a waiter stopped polling
  and claimed instead. A rising count is a provider having a bad time, not a bug.
* **`eddi.connection.resolve.count`** is emitted with `authType=unknown` and
  `binding=unknown` when the *name* did not resolve, because at that point there is
  no connection to read either from. A deleted or misspelled connection therefore
  shows up rather than failing every turn behind a flat dashboard.

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
* **Group conversations and `PER_USER`** — whose token a group member should spend
  is an open question, and nothing in the group path answers it. A member
  conversation is opened under the group conversation's own `userId`, so it takes
  whatever provenance that moment can establish: `VERIFIED` when the member
  conversation is created on an authenticated caller's own thread or inherited from
  a parent turn for the same user, `SELF_ASSERTED` — and therefore refused — on an
  asynchronous or scheduled discussion, where nobody is present to verify anything.
  That is an accident of when the discussion starts rather than a decision about
  whose authority a debating agent carries. Decide before relying on it.
* **No dynamic client registration** (RFC 7591). An admin registers the client
  once and stores the id and secret.
* **Live sync does not carry connections.** Agent ZIP export and import do (see
  [Export and import](#export-and-import)); the instance-to-instance sync in
  [Agent Sync](agent-sync-guide.md) transfers workflows, extensions and snippets
  only, so a synced agent whose header reads `${connection:jira}` needs `jira`
  created on the target by hand or by a ZIP import.
* **Revocation is local.** Deleting a grant stops EDDI resolving it; EDDI does
  not call the provider's revocation endpoint, so the token stays live at the
  provider until it expires.

---

## See also

- [Secrets Vault](secrets-vault.md) — where every credential actually lives
- [httpcalls](httpcalls.md) — including `${caller:token}`, the same-origin special case
- `mcpcalls` configurations — connecting to somebody else's MCP server, whose `apiKey` accepts a connection reference
- [HITL](hitl.md) — approving a connection-backed call before it runs

## 🐛 fix(operator): review follow-ups on the self-URL fix — stricter origin, later retirement, factual tool errors (2026-09-19)

**Repo:** EDDI (`fix/operator-self-url`) — backend and `ui/manager/`, follow-up to the entry below (PR #795 review)

**What changed**

- **`eddi.self.base-url` must be a bare origin.** `SelfUrlResolver` now rejects a value
  with a path, query, fragment or userinfo (falls back to loopback, logged at ERROR).
  Every consumer appends an API path verbatim, so `https://eddi.internal/base` silently
  retargeted every call and `http://eddi:7070?tenant=x` turned every path into query
  content. The Manager's activation form applies the same rule by parsing with `URL`
  (`isOriginOnlyBaseUrl` in `lib/api/operator.ts`) instead of a character class that
  still admitted `?` and `user:pass@`.
- **Plain-HTTP, non-loopback self URL is warned about, not refused.** `${caller:token}`
  is released to that address, so the resolver logs a WARN at startup that the token will
  cross the network unencrypted unless a mesh protects it. Refusing it would break the
  in-cluster service-name case the override exists for.
- **The superseded operator is retired only after the replacement passes verification.**
  `useActivateOperator` used to undeploy and delete the predecessor before
  `verifyGateInstalled` / `enforceGateDryRun`; when either rolled the replacement back,
  the deployment had no operator at all. Retirement now runs last, and the failure path
  (`handBackToPredecessor`) never retires the predecessor. It asks the agent store
  whether the replacement still exists — via `GET /agentstore/agents/{id}/currentversion`
  (200 present, 404 absent; new `getAgentCurrentVersion` in `lib/api/agents.ts`), NOT the
  version-less `GET /agentstore/agents/{id}`, which a live 6.4 answers with 400 for an
  existing agent and an unknown id alike — rather than inferring it from the config
  variable (`resetOperator` deletes the agent before clearing the variable, so a failed
  clear leaves a config naming a deleted agent): gone means the predecessor's config is
  written back; still present — including a predecessor with no recorded version — means
  both are left and the error names both. Tests in `use-operator-supersede.test.tsx`,
  mutation-checked against the old ordering and the first version of the hand-back. Their
  agentstore mocks mirror the measured 6.4 behaviour (version-less GET → 400) and assert
  the exact URL the presence check calls: the first cut of this check used the
  version-less GET, and a mock that answered it with the document let it pass while it
  could only ever return "unknown" against a real backend.
- **Tool failure messages state facts, not reporting policy.** `HttpCallToolsProvider`
  no longer tells the model "report this to the administrator" or that a refused
  connection is "NOT a fault in the service" — a stopped listener refuses too. The
  connect-class message now says the request failed before any response and lists what
  to check (base URL reachable from the server, network path, listener); the SSRF
  message says no request was sent and that the base URL must pass the full SSRF policy.
- **Docs:** the SSRF guidance now says the self URL must pass the *whole* target policy
  (private and link-local are refused too, so an in-cluster name usually stays blocked);
  the `unresolved` self-URL case is documented in `httpcalls.md`, `hitl.md`, `AGENTS.md`
  and `ui/manager/AGENTS.md`; `eddi.self.base-url` and
  `eddi.caller-identity.self-release.enabled` are added to
  `configuration-reference.md` (CI's `ConfigurationReferenceCoverageTest` was red on
  their absence).
- **i18n:** corrected misspelled terms in the new `hi`, `ja`, `ko` and `th` strings.

**Design decisions:** the transport-security finding is answered with a startup warning
rather than a refusal, for the reason above; the tool message keeps its diagnostics in
the engine (every agent needs them) but drops the imperatives, which belong in an agent's
prompt.

---

## 🐛 fix(operator): the Platform Operator's self-URL, its replacement, and its error message (2026-09-18)

**Repo:** EDDI (`fix/operator-self-url`) — backend and `ui/manager/`, in one branch so the two halves are tested together

The Platform Operator was dead on arrival on a customer deployment. The agent
deployed, reported "Gate verified", and then failed **every** tool call — while telling
the admin that "the documentation service is currently unavailable" and that the refused
connection "indicates a problem with the platform's internal services". EDDI's health was
fine the whole time.

All 22 of the operator's api-call resources carried
`targetServerUrl: http://localhost:7080` — the origin the **browser** had used, over an
SSH tunnel, in front of a container listening on `:7070`. That address means
nothing inside the container. (`:7070` is confirmed by the container's own health check,
`curl -f http://localhost:7070/q/health`, which passes from inside.) Three separate
defects, each of which made the other two harder to find.

### Defect 1 — the self-URL came from the browser

`provisionOperator` sent `apiBaseUrl: window.location.origin`. That is right only when
nothing sits between the browser and EDDI; any tunnel, published-port remap, container
port remap or reverse proxy on another port produces a dead operator, silently.

**Where the fix belongs: the backend has to supply the address, and it did not.** The
browser cannot know it, and neither can any other client. Three mechanisms were weighed:

| Option | Verdict |
| --- | --- |
| Manager offers an explicit, pre-filled override | Necessary but **not sufficient alone** — pre-filled from what? |
| The operator's calls use a placeholder resolved server-side (`${self:baseUrl}`) | Rejected: a new resolver in the hot path of every api call, and it hides the value that the incident was diagnosed by *reading* |
| **EDDI exposes its own base URL; the Manager asks and pre-fills an editable field** | **Chosen** |

So: **`SelfUrlResolver`** (new, `ai.labs.eddi.engine.security`) answers
`eddi.self.base-url` when a deployment sets it, otherwise
`http://127.0.0.1:${quarkus.http.port}` — the port read from config, not assumed, and the
same address `RestInterfaceFactory` has always used for EDDI's internal loopback hop.
Loopback is correct behind a reverse proxy and on a remapped port *because* it ignores
both: a process reaches itself without going back out through whatever is in front of it.
The override exists for the cases where loopback genuinely is wrong — in-process TLS, a
service name a mesh requires. It is served by **`GET /administration/operator/self-url`**
(`IRestOperatorMetrics`, `eddi-admin`), a GET with no arguments so the answer cannot
depend on a `Host` header or an `X-Forwarded-*` chain a proxy rewrites.

**The part that would have turned one failure into another.** On an OIDC-protected
deployment the operator's tools authenticate with `${caller:token}`, and
`CallerIdentityResolver` releases that token **same-origin only**. Pointing the tools at
EDDI's own address makes them cross-origin by that rule — so the fix for defect 1 would
have produced a 401 on every call instead of a connect failure. `CallerIdentityResolver`
now also releases the token when the target is *this very process*
(`SelfUrlResolver.isSelf`). That is the same argument `LoopbackCallerAuthFilter` already
makes for the internal hop: the token is handed back to the process that issued the
request it came from. It is a **narrower** release than same-origin, not a wider one —
`SelfUrlResolver`'s value comes from deployment configuration only, never from an agent
config, a conversation or a request header, so no config can nominate itself. Counted
under its own `resolved_self` outcome tag rather than folded into `resolved`.

Manager side: `OperatorConfig` gains `apiBaseUrl` (optional — a config blob written before
this field has no key at all), `resolveOperatorApiBaseUrl` decides it (explicit value →
backend answer → browser origin *with a warning*, only on a backend that 404s the new
endpoint), and `provisionOperator` now **throws** rather than falling back: the silent
fallback is the defect. The value is persisted, not just sent, so the operator screen can
show the address the live tools call — the field the incident turned on was, until now,
nowhere on screen.

### Defect 2 — Reconfigure left the old operator deployed

Changing only the model produced **two** operators on staging, both `READY`, with the UI
silently talking to the new one. That cost real debugging time: the first repair was
applied to the agent that was no longer in use, and the symptom did not move.

Not a platform constraint — the two bots on that instance are versioned in place, and
`setup-api` creating a new agent id is a Manager consequence, not an EDDI one. The Manager
already *tried* to retire the predecessor; it failed for two reasons, both fixed:

1. `removeSupersededAgent` undeployed **without** `endAllActiveConversations`. The backend
   answers 409 while an agent still has active conversations, and the superseded
   operator's active conversation is almost always the admin's own operator chat — on the
   very screen the Reconfigure button lives on. So having *used* the operator was enough
   to make its replacement leave it deployed. `deactivateOperator` and `resetOperator`
   already pass the flag for exactly this reason.
2. The caller wrapped the whole retirement in a bare `catch {}`. A failed retirement now
   travels back as `ActivationOutcome.supersededWarning`, naming **both** agent ids, and
   the operator page shows it as a persistent destructive banner — not a toast, because
   the admin needs to still be able to read it when they start wondering why the operator
   is behaving oddly. (`activationError` was no use: it renders inside the activation form,
   which is already closed by then.)

The replacement is also explicit now rather than implied: the status panel shows the agent
id and the base URL it calls, and the pre-save warning says the current agent is
undeployed and deleted, names it, and says this screen will address the new one from then on.

### Defect 3 — the failure message pointed at the wrong thing

A transport failure surfaced to the model as the bare exception message —
`"Connection refused"` and nothing else. The model has no way to tell an unreachable
target from a broken dependency, so it guessed, and its guess sent the admin to check
EDDI's health.

`HttpCallToolsProvider.describeToolFailure` now recognises a connect-class failure
(`ConnectException`, `UnknownHostException`, `UnresolvedAddressException`,
`NoRouteToHostException`, connect timeouts — matched by type through the whole cause
chain, with a message-text fallback for clients that flatten it), names the method and the
address that was tried, states that this is a network failure reaching that address and
**not** a fault in the service behind it, and says the configured base URL must be one the
EDDI server can reach rather than one a browser uses. Every other failure keeps its own
message: telling the model to suspect the base URL on a 400 would misdirect in the other
direction.

**What may travel in that string.** It reaches the model and so, in paraphrase, the chat
surface. The URL goes in — it is the whole diagnostic value and it is configuration an
admin can already read. Headers do not. Neither does anything vault-resolved, which is why
the address is built from `targetServerUrl` plus the **configured** path rather than the
fully-resolved request URI: `ApiCallExecutor` resolves `${vault:…}` and global-variable
references into that URI, so it can legitimately hold a secret. The result is passed
through `SecretRedactionFilter` as a belt-and-braces measure against a base URL that
embeds credentials.

The Manager's activation canary gained the matching diagnosis: a connect-shaped tool
result now reports the configured base URL and says outright that this is not an EDDI
outage. It is checked *after* the auth check and allowed to win — an unreachable address is
the more actionable of the two, and a 401 cannot have happened if nothing connected.

### Files

**Backend:** `engine/security/SelfUrlResolver.java` (new),
`engine/api/model/OperatorSelfUrl.java` (new), `engine/api/IRestOperatorMetrics.java`,
`engine/rest/RestOperatorMetrics.java`, `engine/security/CallerIdentityResolver.java`,
`modules/llm/impl/HttpCallToolsProvider.java`, `resources/application.properties`
(documents `eddi.self.base-url`). Tests: `SelfUrlResolverTest`,
`CallerIdentitySelfOriginTest`, `HttpCallToolsProviderFailureMessageTest`,
`RestOperatorMetricsTest`.

**Manager (`ui/manager/src/`):** `lib/api/operator.ts`, `hooks/use-operator.ts`,
`components/operator/operator-activation.tsx`, `components/operator/operator-status.tsx`,
`pages/operator.tsx`, `test/mocks/handlers.ts` (one handler for the new endpoint),
`test/mocks/openapi-operations.json` (regenerated from this branch's own spec with
`OPENAPI_FILE=../../target/openapi/openapi.json npm run openapi:refresh` — one line
added, `GET /administration/operator/self-url`; no contract-test exemption needed), all
11 locales, plus tests in `lib/api/__tests__/operator.test.ts`,
`hooks/__tests__/use-operator-supersede.test.tsx` (new),
`components/operator/__tests__/operator-activation.test.tsx`,
`pages/__tests__/operator.test.tsx`, `pages/__tests__/operator-superseded.test.tsx` (new).

**How the halves line up.** The Manager calls `GET /administration/operator/self-url`
and reads `{ baseUrl, source }` — the `OperatorSelfUrl` record exactly, with `baseUrl`
typed nullable for the `unresolved` case. A 404 (a backend older than the endpoint)
reads as "cannot tell" and falls back, with a warning; every other error, a 401/403
included, propagates rather than being guessed past. The MSW handler answers the
loopback shape the backend produces by default, and the snapshot the contract test
checks it against was generated from this branch, so a drift in either direction fails.

### Mutation checks

Every fix was reverted and the pinning test confirmed red.

| Mutation | Test that failed |
| --- | --- |
| `provisionOperator` back to `currentOrigin()` | "targets the address EDDI can reach ITSELF at, not the browser's origin" |
| `resolveOperatorApiBaseUrl` prefers the browser origin | the five `resolveOperatorApiBaseUrl` cases |
| `requireApiBaseUrl` falls back to the origin | "refuses to provision without a resolved base URL" |
| `CallerIdentityResolver`'s `isSelf` branch removed | `resolvesForSelfWhenCallerOriginDiffers` |
| `SelfUrlResolver` hardcodes 7070 | `followsNonDefaultPort` |
| `SelfUrlResolver` ignores the configured override | the `eddi.self.base-url` cases |
| `selfUrl()` answers a constant | `selfUrlAnswersTheDeploymentsOwnAddress` |
| `describeToolFailure` back to `e.getMessage()` | `namesTheAddressThroughTheExecutor` |
| retirement drops `endAllActiveConversations` | "ends the superseded operator's conversations so its undeploy cannot 409" |
| `supersededWarning` forced to null | "reports a failed retirement instead of swallowing it" |
| canary drops the connection diagnosis | the three connect-diagnosis canary cases |

One of those mutations initially **survived**, and the fix for that is worth carrying
forward: the first cut of `HttpCallToolsProviderFailureMessageTest` only called
`describeToolFailure` directly, so reverting the *catch clause* to `e.getMessage()` left
the helper intact and all 11 cases green. The test now also drives the real executor
lambda `discover` builds (`Wiring`, with a mocked `IApiCallExecutor` that throws), which is
what actually pins what a failing tool call hands back to the model. A helper-level test
proves the helper; only the call site proves the behaviour.

One process note, because it cost half an hour twice: a mutation check must **not** be
undone with `git checkout -- <file>` while the fix is unstaged — that discards the fix
along with the mutation. Copy the file aside and copy it back. And copy it back with
`shutil.copy` rather than `copy2`: `copy2` preserves the backup's mtime, so the restored
source looks *older* than the `.class` Maven compiled from the mutated one, incremental
compilation skips it, and every later run keeps testing the mutation. That reads exactly
like a real regression.

### Independent review, and what changed because of it

A fresh reviewer that had not seen this work went through both halves adversarially.
Its security pass on the `CallerIdentityResolver` change found the value provenance
sound — `SelfUrlResolver` reads only `eddi.self.base-url` and `quarkus.http.port` at
construction; nothing writes config at runtime; no agent config, global variable, vault
reference, `Host` or `X-Forwarded-*` header reaches it — and the origin check sound
against look-alikes: `OriginMatcher` compares parsed `scheme://host:port` with no DNS, so
`https://`, `localhost`, `127.1`, `0.0.0.0`, `[::1]`, `[::ffff:127.0.0.1]`, another port
and `http://127.0.0.1:7070@evil.example` are all refused, and the check runs on the final
URI after template, global-variable and vault resolution, so nothing can alter the target
after it. Every finding was addressed:

| # | Finding | Resolution |
| --- | --- | --- |
| 1 | `describeToolFailure` claimed to redact URL credentials but `SecretRedactionFilter` only knows secret *shapes*; a plain `admin:hunter2@` went through, and the test passed only because it used an `sk-ant-` password | `stripUserInfo` removes `user:pass@` structurally from every URL in the message; new test with a plain password, mutation-checked |
| 2 | `AGENTS.md`, `docs/httpcalls.md`, `docs/mcp-server.md` still stated same-origin as absolute | All three updated |
| 3 | "Narrower than same-origin" was wrong: the self address bypasses the reverse proxy, so the reachable endpoints are what EDDI authorizes, not what the proxy also permits | Claim corrected in code and docs; new `eddi.caller-identity.self-release.enabled` (default true) for a deployment that relies on proxy rules too |
| 4 | With SSRF protection on, loopback is refused and the model got a raw "internal/local addresses" message | Not exempted — that would weaken SSRF protection for every agent. Documented instead, and the tool result now names SSRF protection and the remedy (`eddi.self.base-url` to a non-loopback address) |
| 5 | An identity with no captured origin became releasable to self (fail-closed to fail-open) | Kept fail-closed: `origin == null` never gets the self release; test added |
| 6 | The page banner for a failed retirement was untested | `operator-superseded.test.tsx` drives the page's own wiring |
| 7 | A stored address wins over the server's on reconfigure, and the "derived from HTTP port" note could sit under a different value | The form now says when the field differs from the server's current answer; the loopback note shows only when they match |
| 8 | An admin-typed trailing slash became `//path` in every tool | `normalizeBaseUrl` strips it before provisioning |
| 9 | Netty's connect timeout (a `ConnectException` subclass) read as "refused"; `SocketTimeoutException` also covers READ timeouts, where the service *is* at fault; the "unresolved" text fallback was too broad | Netty timeout matched first by name; `SocketTimeoutException` dropped; fallback narrowed to "unresolved address"; tests for both |
| 10 | A predecessor with no recorded version was skipped silently | Reported through `supersededWarning` like any other failed retirement |
| 11 | `quarkus.http.port=0` (random) answered a confident `:7070` | Now `source: unresolved`, `baseUrl: null`, `isSelf` false for everything; the Manager treats it as "cannot tell" |
| 12 | Some operator strings are not i18n keys | Declined: matches every existing `toolError` string in the file; `i18n:check` is green |
| 13 | `headersOnlyStillHolds` tested code this change never touched; the canary's "agent description" test used text none of the regexes matched | The first removed; the canary match is now anchored to the `{"error": …}` failure shape and the test uses a description containing the exact trigger phrases |

Every review fix was mutation-checked the same way: R1–R7 in the backend (null
origin, the switch, userinfo, the SSRF branch, Netty timeout, read timeout, random port)
and U1–U9 in `ui/manager` — each reverted, each failing its named test.

A second fresh review of the final branch, with the Manager in `ui/manager/`, found the
security pass sound again (provenance, look-alikes, the URI checked being the URI sent,
null-origin and opt-out wiring, no open redirect that would carry the header) and nine
smaller findings, all addressed: `docs/hitl.md` still carried the retracted "narrower"
claim; the form promised a browser-origin fallback on a 403/500 that activation would not
perform (now a distinct notice, and `retry: false` so it appears at once); an `unresolved`
answer fell back to the browser origin — the original defect's value — and now
refuses with a clear message, the fallback reserved for a genuine 404; `stripUserInfo`
stopped at the first `@`, leaking the tail of an un-encoded `p@ss` password; client
validation accepted a path or trailing text; the regression test resolved through the
helper's preset address instead of the backend (now `apiBaseUrl: null`); a note on
`quarkus.http.test-port`; the notice icon; and an `sk-ant-` test fixture replaced with
`sk-test-`. Mutation-checked: V1–V4, each failing its named test.

### Noted, not fixed

- **`HttpCallToolsProvider`'s sibling path.** `ApiCallsTask` (rule-based httpcalls, no LLM)
  has the same bare-message behaviour. Left alone: there is no model there to misdiagnose
  for a human, and the diagnostic belongs where a model paraphrases the error.
- **`activationError` is unreachable after a successful activation.** It renders only
  inside the activation form, which the success handler closes — so the existing
  write-probe failure path (`setActivationError(message)` in `pages/operator.tsx`) is
  visible only as its toast. Worked around here with a dedicated banner rather than
  fixed for both.
- **`secret-key-picker-reference-only.test.tsx` flakes under full-suite load** — it
  passed in isolation and on a clean tree, and passed on the full suite the second time.
  Timing, not a regression from this work.

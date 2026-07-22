# Platform Operator Agent — Design (v2, post critical review)

**Date:** 2026-07-03
**Branch:** `feat/model-cascade-ui` in `EDDI-Manager` (staying on current branch per request)
**Status:** Design approved; revised after an adversarial critical review that verified backend
contracts against `openapi.json`. This version supersedes v1.

## Problem

Configuring and operating EDDI today means clicking through the manager by hand, or wiring an
*external* local LLM to EDDI to drive it. There is no first-class, hosted "operator" you can turn
on inside the manager, point at a provider/model, and let it operate EDDI on your behalf through
chat.

Goal: a **Platform Operator** — an opt-in, admin-activated agent that operates EDDI via its own
tools (EDDI's REST API exposed as tools through its OpenAPI, which the backend builds with
`McpApiToolBuilder`). Off by default. An admin activates it, chooses a provider + model, supplies
a model key, and gets a curated-but-editable EDDI operator system prompt. Great UX is a first-class
requirement: guided activation, **visible tool activity**, clear status, reversible controls.

## What changed after critical review (read this first)

The v1 design had a load-bearing flaw and several inaccuracies, all verified against `openapi.json`:

1. **Identity/credentials (resolved).** v1 gave the operator a static `apiAuth` bearer to call
   EDDI's Keycloak-protected admin API. Keycloak tokens expire, so it would 401 within the hour;
   it also couldn't be least-privilege and collapsed audit to one synthetic principal. **Resolution
   (confirmed by the maintainer): EDDI runs the operator's tool calls under the *chatting user's*
   forwarded identity (caller-token pass-through).** No static operator credential. Authorization is
   bounded by the caller's real permissions (EDDI enforces per endpoint); audit is attributed to the
   real user; the token is always fresh (the manager already sends the user's bearer on the stream
   call). The operator is therefore **not** a standing over-privileged actor — a low-privilege user
   chatting with it only gets low-privilege reach.
2. **Contract bugs (fixed here).** setup-api requires `agentName` (not `name`);
   `getDeploymentStatus`/`undeployAgent` require a `version`; the deployment path is lowercase
   `deploymentstatus`; two v1 curated endpoints didn't exist. All corrected below.
3. **Assumptions (now VERIFIED live, 2026-07-03).** A spike against `localhost:7070` confirmed:
   the `endpoints` filter is real — `"METHOD /path"` comma-separated, matches spec templates
   **including `{param}`** paths, and scopes tools exactly (2 requested → `endpointCount: 2`);
   the **full 446 KB live spec is ingested** (HTTP 201, ~1.5s — no trimming); with no `apiAuth` the
   generated tools carry **empty headers** (no baked credential — consistent with runtime
   pass-through). See "Phase 0 — results" below. (The committed `openapi.json` is a stale 253 KB
   snapshot of the 446 KB live spec — validate the allow-list against the *fetched* spec.)
4. **Governance (clarified).** The manager has no RBAC and auth can be `none`, so "admin-only" cannot
   be enforced in the SPA. With caller-token pass-through this is far less severe: **activation
   itself is admin-gated by EDDI** (setup-api requires `eddi-admin`/`eddi-editor`), and usage is
   bounded by the caller. Writes still must not ship before the human-in-the-loop (HITL) approval
   branch; P1 builds the seam as an invariant.

## Backend contract (authoritative — verified against `openapi.json` + manager code)

- **Provision** — `createApiAgent` → `POST /administration/agents/setup-api` (`src/lib/api/agent-setup.ts`).
  Schema requires `agentName`, `systemPrompt`, `openApiSpec`. **v1 sent `name`; the shared
  `CreateApiAgentRequest` type and the mock encode the same bug** (`agent-setup.ts:22`,
  `handlers.ts:3452`). Fix the field to `agentName` (map in `createApiAgent`) and fix the mock.
  Fields used: `agentName`, `systemPrompt`, `openApiSpec` (EDDI's own spec content),
  `provider`/`model`/`apiKey` (the operator's LLM), `apiBaseUrl` (`window.location.origin`),
  `endpoints` (curated `METHOD /path` filter — **format unverified; see Phase 0**), `deploy: true`,
  `environment`. **`apiAuth` is intentionally left empty** (caller-token pass-through supplies auth).
- **Agent version** — setup-api's response carries no version. Resolve it after provisioning via
  `GET /agentstore/agents/{agentId}/currentversion` (returns a number; `agents.ts:175`). Persist it.
- **Deployment status** — `getDeploymentStatus(environment, agentId, version)` →
  `GET /administration/{env}/deploymentstatus/{agentId}?version=V` (lowercase; `agents.ts:261-269`).
  States `NOT_FOUND | IN_PROGRESS | READY | ERROR`.
- **Kill switch** — `undeployAgent(environment, agentId, version)` (`agents.ts:251-259`).
- **Config pointer** — one Global Variable (`src/lib/api/variables.ts`), `GlobalVariable = { key, value, description?, exportable? }`, key `^[a-zA-Z0-9_.-]+$`. **Store the whole operator config as one JSON blob** in `platform.operator` (atomic; v1's 7 separate vars were non-atomic).
- **Chat transport (reused)** — `startConversation(env, agentId)` then `sendMessageStreaming(env, agentId, conversationId, { input }, signal)` over `BearerEventSource` SSE (`src/lib/api/chat.ts`). SSE events: `token | task_start | task_complete | done | error`. The user's bearer is attached by `api-client` on every call — this is what carries the caller identity into pass-through.
- **Model credential** — EDDI Secrets Vault via `SecretKeyPicker` (`${vault:keyName}`). Only the **LLM** key is needed now (no EDDI credential).

## Architecture

```
Admin ── activates ──▶ Manager provisions EDDI agent (setup-api)
                        · agentName + curated operator systemPrompt
                        · openApiSpec = EDDI's own spec; endpoints = curated READ allow-list
                        · LLM = chosen provider/model/vault key ; apiAuth = EMPTY
                        · deploy:true → resolve currentversion → store JSON config blob
                        (setup-api is admin-scoped → only admins can activate)
User ── chats ──▶ EDDI operator agent (server-side, chosen model)
                        · tool calls to EDDI admin API run as THE CHATTING USER (pass-through)
                        · streams answer + task_start/task_complete tool activity (SSE)
Manager ── renders ──▶ chat + live tool-activity trace (ChatActivity) + status + kill switch
```

The manager's net-new surface: a single-blob config model, an activation flow (LLM only), an
operator screen (scoped chat **with tool-activity trace**), a dashboard discovery card, a curated
read allow-list, error/ERROR states, and i18n.

## Design

### Phase 0 — results (ran live 2026-07-03; residuals noted)

Ran against `localhost:7070` (dev instance, `auth=none`). Two throwaway API agents were created via
`setup-api` and deleted (cascade + permanent; removal verified).

- ✅ **`endpoints` filter scopes tools.** `"GET /a, GET /b"` → `endpointCount: 2`, exactly 2
  `apicalls` tools generated, grouped by OpenAPI tag. Matches `{param}` templates as written
  (`GET /agentstore/agents/{id}` → 1 tool). **Use the OpenAPI path templates verbatim.**
- ✅ **Full-spec ingestion.** The full **446 KB** live spec (`GET /openapi?format=json`,
  `JSON.stringify`) was accepted (HTTP 201, ~1.5s). **Pass the full spec; no trimming.**
- ✅ **`agentName` required** (schema `required: [agentName, systemPrompt, openApiSpec]`); `name` is
  the manager's bug. **`apiKey` presence is validated at creation** for cloud providers (validity is
  only checked at chat time — a placeholder key creates the agent).
- ✅ **No `apiAuth` → empty tool headers** (no baked credential); the generated apicall targets
  `apiBaseUrl` with `headers: {}`. Consistent with runtime caller-token pass-through.
- ✅ **Version** comes back in the response `resources.agentLocation` (`…?version=1`); `/currentversion`
  also works.
- ✅ **`setup-api` needs Keycloak `eddi-admin`** — admin-gated where auth is enabled (this box has
  `auth=none`, which is why the spike ran unauthenticated).

**Residuals (untestable on an `auth=none` box; do not block P1 UI work, verify before P2 writes):**
1. **Caller-token pass-through under real Keycloak** — maintainer-confirmed; the empty-header tool
   config is consistent with it, but exercise it on a Keycloak-enabled instance before writes.
2. **End-to-end chat** (SSE `task_start`/`task_complete` for this API agent + a grounded read answer)
   needs a valid LLM key — confirm during P1 dev with a real key.

### Data & config model

The operator **is** an EDDI agent (config lives in `agentstore`). The manager stores one atomic
pointer, `platform.operator`, whose value is `JSON.stringify(OperatorConfig)`:

```
OperatorConfig = {
  enabled: boolean;
  agentId: string | null;
  version: number | null;      // resolved via /currentversion after provisioning
  environment: string;         // where the operator agent is deployed
  provider: string;
  model: string;
  credentialKey: string | null;// vault key NAME of the LLM key (not the secret)
}
```

One GET (+`JSON.parse`, default on 404), one PUT, atomic. Activation is idempotent: if `agentId`
exists, update+redeploy the existing agent; else create. Deactivate = undeploy (with version) +
`enabled:false`. Reset = delete agent + delete the variable.

New modules: `src/lib/operator/tool-scopes.ts` (an **allow-list** of real GET endpoints — no
bind-then-subtract, no substring "danger" matching), `src/lib/operator/system-prompt.ts`
(`OPERATOR_SYSTEM_PROMPT` with a **non-editable safety preamble** + editable body),
`src/lib/api/operator.ts` (spec fetch, request build, version resolve, blob read/write),
`src/hooks/use-operator.ts`, `src/hooks/use-operator-chat.ts` (scoped store reusing the transport
**and handling `task_start`/`task_complete`**).

### Curated read tool set (allow-list, verified)

Bind exactly these real GET endpoints (each asserted to exist in the fetched spec by a test, so an
invented path fails CI). Includes **by-id reads and deploymentstatus** so the operator can actually
answer its starter prompts (descriptors alone can't diagnose an error):
`GET /agentstore/agents/descriptors`, `GET /agentstore/agents/{id}`,
`GET /workflowstore/workflows/descriptors`, `GET /groupstore/groups/descriptors`,
`GET /conversationstore/conversations`, `GET /conversationstore/conversations/{conversationId}`,
`GET /administration/{environment}/deploymentstatus/{agentId}`,
`GET /administration/coordinator/status`, `GET /administration/logs`,
`GET /administration/quotas`, `GET /auditstore/agent/{agentId}`.
(Alternatively, per YAGNI, bind **all** `GET` verbs from the spec — a read scope over read verbs is
inherently safe. Decide in Phase 0 based on tool-count/token cost.)

### Activation UX (LLM only — no EDDI credential)

Guided, mirrors `AgentWizardPage` patterns (`src/pages/agent-wizard.tsx`):
1. **Model** — provider select (`LLM_PROVIDERS`) + model combobox with suggestions (extract the
   inline `MODEL_SUGGESTIONS` map from `agent-wizard.tsx` into a shared module — DRY); base URL for
   local providers; **LLM key via `SecretKeyPicker`, required and validated** (blocks activation).
   Surface `useVaultHealth` "vault unavailable" state so the credential step isn't silently unusable.
2. **Prompt & review** — show/edit the operator prompt (safety preamble locked, body editable);
   review provider/model/environment; **Activate & deploy** with a live progress state
   (`provisioning → resolving version → deploying → running a canary read`).
3. **Post-deploy canary** — run one read (e.g. `GET /agentstore/agents/descriptors`) through the
   operator and show "operator can reach your platform" vs a clear "can't authenticate / no tools"
   error — never a bare READY badge that hides a broken operator.

There is no "capability scope" picker in P1 (only read-only exists). Show a reassuring
**"Read-only — inspects and explains your platform; cannot make changes"** statement instead.

### Operator screen

- Route `/manage/operator` in `src/app.tsx`; page `src/pages/operator.tsx`.
- Nav item **right after Dashboard** in the Core section (`sidebar.tsx:49`).
- **Inactive:** an inviting empty state + the activation flow.
- **Active:** split view — operator chat with a **live tool-activity trace** (reuse `ChatActivity`,
  fed by `task_start`/`task_complete` parsed into per-operator events; do **not** share the global
  debug store) + a status panel (model chip, `Read-only` chip, deployment status incl. **ERROR**
  handling with retry/reconfigure, prominent **kill switch**). Suggested starter prompts.
- **Error states:** config-fetch error branch, deployment `ERROR` branch, vault-down messaging.

### Dashboard integration (P1)

Pull the **inactive-state discovery card** into P1 (`src/pages/dashboard.tsx`, reuse `Card` +
`useOperatorConfig` — cheap single-blob read): an "Activate the Platform Operator" CTA. The
active-state quick-ask deep link is P2. (The user explicitly asked for dashboard integration; the
discovery card is what makes an off-by-default feature findable.)

### Security & governance (verified reality)

- **Caller-token pass-through** is the linchpin and it improves everything: the operator acts as the
  chatting user, so authorization is EDDI's per-endpoint enforcement, audit is per-user, and there is
  no long-lived platform credential to leak. Confirm empirically in Phase 0.
- **Prompt injection** is still the headline risk (the operator reads untrusted platform content).
  Mitigations, ranked honestly: (a) **read-only tool set** in P1 (nothing to abuse); (b) writes
  bounded by the caller's own authority; (c) a **non-editable safety preamble** treating tool output
  as untrusted data (defense-in-depth, *not* a control that justifies writes); (d) the kill switch.
- **No writes before HITL.** Build the approval **seam** in P1 as an invariant: `read_write` is
  literally unselectable unless an approval handler is registered. Reclassify create/update-agent,
  update-LLM-config, and create-schedule as **approval-required** (they can install attacker egress
  or persistence) — they are not "safe writes."
- **RBAC reality:** the manager can't enforce admin-only (`auth` can be `none`; `useHasRole` gates
  nothing; role extraction may read the wrong claim — `realm_access` vs the realm's `realm_roles`).
  Activation is nonetheless admin-gated **by EDDI** (setup-api scopes). Optional hardening: fix role
  extraction and hide the nav item for non-admins, but never rely on the SPA for enforcement.
- Mark the `platform.operator` variable **non-exportable**; it stores only non-secret pointers plus
  the LLM vault key *name*.

### i18n

New `operator.*` namespace + `nav.operator`, added to `en.json` and translated across the other 10
locales (`de, fr, es, ar, zh, th, ja, ko, pt, hi`).

## Phasing

- **Phase 0 — Spike (gate).** Verify endpoints-filter scoping, full-vs-trimmed spec, and caller-token
  pass-through against a live backend. No UI until green.
- **P1 — Activate + read/advise (with transparency).** Single-blob config, allow-list read tools
  (verified against the spec), activation flow (LLM only, validated, canary), operator screen with
  **live tool-activity trace**, dashboard discovery card, nav + route, error/ERROR states, kill
  switch, i18n. The approval seam exists as a no-op invariant.
- **P2 — Curated writes (only with HITL).** Populate write endpoints behind the approval handler;
  scope picker appears; reconfigure flow; active-state dashboard quick-ask. Writes are bounded by
  caller authority **and** gated by HITL.
- **P3 — Polish & forward-compat.** Cost/usage; command-palette + tour chapter; richer activity
  history; MCP-native tool path when EDDI ships an admin MCP server.

## Testing

- **Unit:** `tool-scopes` (allow-list only; **every endpoint exists in the fetched spec**);
  `operator.ts` (agentName mapping, request build, version resolve via `/currentversion`, single-blob
  read/write); `use-operator` (activate/reconfigure/deactivate idempotency, version threaded into
  status/undeploy). MSW handlers must reflect the **real** contract (`agentName`, lowercase
  `deploymentstatus`).
- **Component:** activation flow (LLM key required + validated; vault-down; canary result); operator
  screen (inactive/active/error/ERROR branches; kill switch); scoped chat **rendering tool activity**;
  dashboard discovery card.
- **E2E (Playwright `ui`):** activation → deployed → canary; a read query; deactivate.
- Gate: `typecheck` + `lint` + `test` green.

## Out of scope

- HITL write approval (separate branch — but P1 builds the seam), per-user operators, autonomy,
  a dedicated EDDI admin MCP server (P3+), and EDDI backend changes beyond what Phase 0 verifies.

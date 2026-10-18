## fix(security): the four leftovers of the 2026-10-17 security review (2026-10-18)

**Repo:** EDDI · **Branch:** `fix/security-review-leftovers`

The 2026-10-17 review was remediated in PRs #1079–#1094. Four items were left on purpose and
are closed here, one commit each.

### 1. Keycloak refuses the admin/admin fallback off loopback

[`docker-compose.auth.yml`](../../docker-compose.auth.yml) falls back to `admin`/`admin` for the
Keycloak bootstrap admin so the loopback quick start needs no `.env`. That was safe only while
`KEYCLOAK_BIND` stayed on loopback, and nothing enforced it. A new one-shot `keycloak-guard`
service, the same pattern as `docker-compose.yml`'s `bind-guard`, now exits 1 (and Keycloak,
which waits for it with `service_completed_successfully`, never starts) when `KEYCLOAK_BIND` is
not loopback while `KC_BOOTSTRAP_ADMIN_PASSWORD` is unset or `admin`. It reads both values from its
own environment with the defaults the keycloak service uses. It runs on the Keycloak image the overlay pulls anyway, so it adds no image to
either base stack (`docker-compose.postgres-only.yml`'s own guard avoids the mongo image for the
same reason). The installers and
`gcp/provision-vm.sh` already generate a password, so their paths pass unchanged.

- `ComposeStackTest` pins the wiring (dependency condition, same password expression, same bind
  variable as the published port) and runs the guard script itself through `sh` for five
  bind/password combinations; that test is skipped only where no POSIX `sh` is on `PATH`.
  `adminPasswordsAreOverridable` now checks that each admin-password variable appears at least
  once, since the guard repeats `KC_BOOTSTRAP_ADMIN_PASSWORD`.
- Verified by hand with `docker compose run keycloak-guard` against loopback, `0.0.0.0` with no
  password, with `admin`, with an empty one and with a real one.

### 4. `read_audit_trail` no longer shows a USE-only owner the agent's prompt

Finding: yes, it did. `LlmTask` writes `audit:compiled_prompt` as the compiled system message,
`---`, and the processed prompt template; `LifecycleManager.buildAuditEntry` copies it into
`AuditEntry.llmDetail.compiledPrompt`, and the MCP `read_audit_trail` tool returned entries as
stored to any `eddi-viewer` who owned the conversation. Under enforced workspaces an agent shared
with `USE` lets the recipient chat with it but not read its configuration, so the audit trail of
their own conversation was a way around that. (`/auditstore` over REST and the GDPR export are
`eddi-admin` only and unaffected.)

[`McpConversationTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java)
now replaces `compiledPrompt` with a redaction marker unless
`ResourceAccessGuard.hasAccess(agentId, VIEW)` holds. That answers OWN for an admin and whenever
workspaces are not enforced, so nothing changes there. VIEW on the agent rather than on each LLM
config: sharing materialises the agent's level onto its config graph (`ConfigGraphResolver`), so
it is the same decision without a graph walk per entry. Model name, token usage, cost and the
model's reply stay; the access lookup is made once per agent per call and skipped for entries
with no prompt. Tests in `McpConversationToolsOwnershipTest` pin both halves (USE-only owner gets
the marker and no prompt text; VIEW passes the entry through untouched), mutation-checked by
reverting the call.

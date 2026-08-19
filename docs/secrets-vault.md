# Secrets Vault

EDDI includes a built-in secrets vault for managing sensitive values like API keys, tokens, and passwords. Secrets are encrypted at rest, referenced via URI syntax, and automatically scrubbed from logs and API exports.

## Architecture

```text
┌─────────────────┐     ┌──────────────┐     ┌───────────────────┐
│  Configuration   │────>│ SecretResolver│────>│  VaultSecretProv.  │
│  (JSON configs)  │     │  (resolves    │     │  (envelope crypto  │
│  ${vault:..} │     │   at runtime) │     │   + persistence)   │
└─────────────────┘     └──────────────┘     └───────────────────┘
                                                       │
                                              ┌────────▼────────┐
                                              │  EnvelopeCrypto  │
                                              │  (AES-256-GCM +  │
                                              │   PBKDF2 KEK)    │
                                              └─────────────────┘
```

### Core Components

| Component                              | Package            | Purpose                                                         |
| -------------------------------------- | ------------------ | --------------------------------------------------------------- |
| `SecretReference`                      | `secrets.model`    | Value object: `tenantId/keyName` URI parsing                    |
| `EnvelopeCrypto`                       | `secrets.crypto`   | AES-256-GCM encryption with envelope key wrapping               |
| `ISecretProvider`                      | `secrets`          | SPI for reading/writing encrypted secrets                       |
| `VaultSecretProvider`                  | `secrets.impl`     | Production implementation with envelope crypto + persistence    |
| `SecretResolver`                       | `secrets`          | Resolves `${vault:...}` references to plaintext at runtime  |
| `IRestSecretStore` / `RestSecretStore` | `secrets.rest`     | JAX-RS endpoints for secret CRUD and key rotation               |
| `SecretScrubber`                       | `secrets.sanitize` | Removes `${vault:...}` references from export payloads      |
| `SecretRedactionFilter`                | `secrets.sanitize` | Regex-based log redaction for API keys, tokens, vault refs      |
| `ISecretPersistence`                   | `secrets.persist.` | DB abstraction (MongoDB default, PostgreSQL via profile)        |

## Secret References

Secrets are referenced in configuration JSON using the vault URI syntax:

**Short form** (uses `default` tenant):
```text
${vault:keyName}
```

**Full form** (explicit tenant):
```text
${vault:tenantId/keyName}
```

- **tenantId** — tenant namespace (e.g., `default`, `acme-corp`)
- **keyName** — the secret name (e.g., `openai-api-key`)

### Where Vault References Work

| Configuration Type                    | Fields Resolved                        |
| ------------------------------------- | -------------------------------------- |
| **HTTP Calls** (`httpcalls.json`)     | URL, headers, body, query parameters   |
| **LangChain** (`langchain.json`)      | `apiKey` and other model configuration |
| **Property Setter** (`property.json`) | Values with `scope: secret` auto-vault |

### Resolution Behavior

Vault references are resolved **at runtime** when the task executes, never stored as plaintext in conversation memory. The resolution flow:

1. Task reads configuration containing `${vault:...}` reference
2. `SecretResolver.resolveValue()` finds and replaces vault URIs
3. `VaultSecretProvider.resolve()` decrypts and returns the plaintext
4. Plaintext is used for the operation (e.g., HTTP call header)
5. **Plaintext is NOT stored in memory** — only the vault reference persists

**Caching:** Successfully resolved secrets are cached in a Caffeine cache (configurable TTL). Failed resolutions are **never cached**, ensuring newly created secrets resolve immediately without waiting for cache expiry.

## Agent Grants (`allowedAgents`)

Every stored secret carries an `allowedAgents` list — the agent IDs permitted to use it, or `["*"]` for all agents. It is checked **when an agent is deployed**, not when a secret is resolved. What a violation costs is set by [the enforcement mode](#modes) — blocked, logged, or not checked at all.

### Why deploy time, not resolution time

Blocking at resolution would fail in the middle of a live conversation, after the agent is already serving users, and the operator would learn about the misconfiguration from a broken turn. The deploy-time check runs once, before any user is affected: in `enforce` mode a misconfigured agent simply does not come up, and the reason is a single ERROR line.

The gate lives in `AgentFactory.deployAgent` — the one boundary every deployment path funnels through (REST administration, conversation-triggered deployment, the scheduled deployment poller). Placing it there rather than at each caller means it cannot be bypassed by reaching deployment through a different entry point.

### Modes

Configured with `eddi.vault.grant-enforcement`:

| Mode      | Behavior                                                            |
| --------- | ------------------------------------------------------------------- |
| `off`     | No check at all — the checker is never consulted                    |
| `warn`    | Violations logged at WARN, deployment proceeds                       |
| `enforce` | **Default.** Violations logged at ERROR, deployment is **blocked**   |

Two parsing rules, both deliberate:

- **An unrecognized value fails startup** rather than falling back to a default. `grant-enforcement=enforced` silently behaving as `warn` would turn one typo into a security control that is off while appearing on.
- **Absent or blank resolves to `enforce`**, the shipped default — never to something weaker. Turning enforcement down is always explicit.

### What counts as granted

The check answers "is this provably ungranted?", and anything short of proof is treated as granted. An agent is allowed when its `allowedAgents` list:

- contains the agent's ID, or
- contains the `*` wildcard, or
- is `null` or empty — an unset list means unrestricted, not "deny all"

Uncertainty likewise never becomes a violation: unreadable metadata, a disabled vault, or a secret that does not exist all resolve to *allowed*. A check that cannot run must not be able to take agents down.

### What is scanned

The agent document itself plus each workflow's LLM, HTTP-call, MCP-call, and RAG configurations are serialized and scanned for `${vault:...}` references. Each reference found is resolved to its secret metadata and tested against the deploying agent's ID.

### Upgrading to enforcement

On most deployments this control is inert, for two reasons worth confirming rather than assuming:

1. **No master key, no check.** With `eddi.vault.master-key` unset the vault is disabled and the checker reports no violations without looking at anything.
2. **Auto-vaulted keys are unrestricted.** Every key the setup wizard vaults is stored with `allowedAgents = ["*"]`.

The deployments that *are* affected have **both** a master key and a grant an operator has deliberately narrowed. There, enforcement is a behavior change with no warning phase — the first symptom is an agent refusing to deploy. Before enabling it, run once with `warn` and confirm the log is free of:

```text
references vault secret(s) it is not granted
```

Then set `enforce`. To widen a grant instead, add the agent ID to the secret's `allowedAgents` via the [REST API](#rest-api) — or remove the reference from the agent's configuration.

## Encryption

### Envelope Encryption

EDDI uses **envelope encryption** — each tenant gets its own random Data Encryption Key (DEK), which is itself encrypted by a Key Encryption Key (KEK) derived from the master password.

```text
Master Password → PBKDF2 (600,000 iterations) → KEK
                                                  │
Secret → tenant DEK → AES-256-GCM encrypt → ciphertext
                │
                └→ KEK wraps DEK → encrypted DEK
                        │
                        └→ stored: { encryptedDek, iv, ciphertext }
```

### Configuration

The vault requires a master key (KEK) to encrypt/decrypt secrets. If not set, the vault is **disabled** — all `${vault:...}` references pass through unresolved and a prominent warning is logged at startup.

#### Installer (Recommended)

The `install.sh` / `install.ps1` installer automatically generates a unique, cryptographically random vault master key during setup and stores it in `~/.eddi/.env`. No manual configuration is needed — the vault is **secure by default** for all installer-based deployments.

The installer offers two options during the "Security" wizard step:

1. **Auto-generate** (recommended) — creates a strong 32-character base64 key via `openssl rand`
2. **Custom passphrase** — enter your own passphrase (minimum 16 characters)

You can also provide a key non-interactively:

```bash
# Bash
bash install.sh --vault-key=your-strong-passphrase-here

# PowerShell
.\install.ps1 -VaultKey "your-strong-passphrase-here"
```

Re-running the installer preserves your existing key — it reads from `~/.eddi/.env` and never overwrites it.

#### Manual Configuration

For manual Docker Compose deployments or local development, set the master key using **one** of these methods (in priority order):

```bash
# 1. System property (highest priority)
./mvnw compile quarkus:dev -Deddi.vault.master-key=your-strong-passphrase

# 2. Environment variable (recommended for production)
export EDDI_VAULT_MASTER_KEY=your-strong-passphrase

# 3. .env file in project root (recommended for local dev — add to .gitignore!)
echo "EDDI_VAULT_MASTER_KEY=your-strong-passphrase" > .env

# 4. application.properties (dev profile only — safe to commit)
%dev.eddi.vault.master-key=dev-passphrase
```

Additional vault settings in `application.properties`:

```properties
# Cache for resolved secrets (avoids repeated decryption)
eddi.vault.cache-ttl-minutes=5
eddi.vault.cache-max-size=1000

# Whether an agent referencing a secret it is not granted may deploy:
# off | warn | enforce (default). See "Agent Grants" above.
eddi.vault.grant-enforcement=enforce

# Whether agent setup reuses a vault entry that already holds the same
# plaintext key: checksum (default) | never.
# See "Reusing one key across agents" below.
eddi.setup.vault-key-reuse=checksum
```

> **⚠️ Important:** The vault master key encrypts all stored API keys. If the master key is lost, all encrypted secrets become **permanently unrecoverable**. Back up your `~/.eddi/.env` file.

## Secret Input (Agent Conversations)

Agents can request secret input from users (e.g., API keys during setup). The flow works end-to-end across backend, chat UI, and Manager.

### Backend: PropertySetterTask + Conversation Scrubbing

When a property has `scope: secret`:

1. **PropertySetterTask** detects `scope == secret` on the property instruction
2. The raw value is immediately stored in the vault via `ISecretProvider.store()`
3. A vault reference (`${vault:...}`) replaces the plaintext in memory
4. The raw `input:initial` entry is scrubbed from the conversation step

When the **client flags input as secret** (via the `secretInput` context key):

1. `Conversation.isSecretInputFlagged()` checks for `{"secretInput": {"type": "string", "value": "true"}}` in the context map
2. `storeUserInputInMemory()` replaces the display value with `<secret input>` in conversation output
3. The actual plaintext still flows through lifecycle data so `PropertySetterTask` can vault it
4. The conversation log and API responses show `<secret input>` — **plaintext is never persisted**

### Output InputField Directive

To signal the chat UI to show a password field, use the `inputField` output type in your output configuration:

```json
{
  "type": "inputField",
  "subType": "password",
  "text": "Please enter your API key:"
}
```

### Chat UI: Password Fields + Secret Mode

Both **eddi-chat-ui** and the **EDDI-Manager chat panel** support secret input:

**Backend-driven password fields:**

- When the backend response contains an `inputField` output item with `subType: "password"`, the chat UI replaces the normal text input with a masked `<input type="password">` field
- An **eye toggle** button allows the user to reveal/hide the value
- After submission, the input reverts to the normal text field

**Proactive secret mode (client-initiated):**

- A 🔒/🔓 toggle button on the chat input lets users mark any input as secret
- When toggled ON, the input becomes a password field with eye toggle
- The `secretInput` context flag is sent to the backend, triggering output scrubbing in `Conversation.java`

**Security measures:**

- Chat UI state for secret values is **ephemeral** — cleared on submit or dialog close
- No secret values are stored in browser `localStorage` or `sessionStorage`
- `autoComplete="new-password"` prevents browser caching

### Example: collecting a key in a conversation

An agent that asks the user for an API key mid-conversation wires it up like this:

```json
// Output configuration — prompts with a password field
{
  "type": "inputField",
  "subType": "password",
  "text": "Please enter your API key:"
}

// Property setter — auto-vaults the input
{
  "name": "apiKey",
  "valueString": "{memory.current.input}",
  "scope": "secret"
}
```

The `scope: secret` instruction causes `PropertySetterTask` to store the API key in the vault and replace the memory value with a `${vault:...}` reference.

## Auto-Vaulting (Agent Setup)

When creating agents through the Manager's agent wizard, the Platform Operator, or the Setup API directly (`POST /administration/agents/setup` and `/setup-api`), API keys are **automatically stored in the vault**. You don't need to manually create vault entries.

### How It Works

1. User provides an API key during agent setup
2. `AgentSetupService.vaultApiKey()` resolves it against the vault (see *Reusing one key across agents* below)
3. A vault reference (`${vault:setup.<agent-name>.<timestamp>.apiKey}`, or the name you chose) is written to the LLM configuration
4. When the vault is enabled, the plaintext key is never persisted in MongoDB — only the vault reference is stored

### Reusing one key across agents

One provider key usually serves many agents, so setup avoids storing it many times. Three ways to say "use this key", in the order setup considers them:

| What you pass | What setup does |
| ------------- | --------------- |
| `vaultKeyName: "openai-prod"` (with or without `apiKey`) | Uses that entry. With `apiKey` it **creates** the entry under exactly that name; without one, the entry must already exist. Never overwrites an entry holding a different value — the request is rejected instead, because other agents may already point at it. Accepts the `${vault:openai-prod}` form too. |
| `apiKey: "${vault:openai-prod}"` | Used as-is, never re-vaulted. Surrounding whitespace is trimmed first, so a pasted reference still counts as one. If the key does not exist the setup still succeeds (you may vault it afterwards) but a warning is logged — the agent cannot resolve its credential until it does. |
| `apiKey: "sk-…"` (plaintext) | Reused if the vault already holds that exact value, otherwise stored under a generated name. |

Plaintext reuse is matched on the SHA-256 checksum the vault already stores per entry — nothing is decrypted to make the decision — and only entries with `allowedAgents` unset or `["*"]` are candidates, since referencing a narrowed grant from a new agent produces a config that [grant enforcement](#agent-grants-allowedagents) rejects at deploy time. When several entries match, the oldest wins, so repeated setups converge on one entry rather than depending on listing order.

Set `eddi.setup.vault-key-reuse=never` to switch plaintext reuse off and give every agent its own entry again — appropriate when two agents hold the same-valued key today but must be able to rotate independently. Neither setting affects the first two rows above: those are explicit caller decisions. Any other value fails startup, as `eddi.vault.grant-enforcement` does — a typo must not silently switch de-duplication off.

Every setup response carries **`apiKeyVaultReference`** — the reference the created agent's LLM config actually points at, whether this call vaulted the key or reused an entry that already held it. Pass it straight back as `vaultKeyName` (or as `apiKey`) on the next setup to put another agent on the same credential.

It is `null` in two cases: the provider needs no key at all (`ollama`, `jlama`, `bedrock`, `oracle-genai`, …), and the vault is disabled so the key was stored in plaintext — a plaintext key is a secret and is never echoed back in a response body.

A setup can also return **`resources.vaultWarning`**: the chosen key does not exist, or it is granted only to other agents. Neither fails the setup — see *Reusing one key across agents* above — but both end in an agent that was created and cannot use its credential, so both are reported rather than only logged.

`vaultKeyName` accepts three shapes:

| Shape | Parsed as |
| --- | --- |
| `openai-prod` | key `openai-prod` in the `default` tenant |
| `${vault:openai-prod}` | the same — the wrapper is unwrapped, not stored |
| `${vault:acme/openai-prod}` | key `openai-prod` in tenant `acme` |

The **tenant and key components** must each match `[a-zA-Z0-9._-]{1,128}` — the same charset the secrets REST API enforces on create. (The `${vault:…}` wrapper itself is of course not expected to match; it is stripped first.) The constraint is not cosmetic: the components are re-embedded into `${vault:<tenant>/<key>}`, where a `/` inside a *bare* name would re-parse as a tenant separator and a `}` would truncate the reference, leaving the agent pointing at a different secret or none.

Naming one key in `vaultKeyName` and a *different* one in `apiKey` is rejected rather than silently resolved in favour of either.

`vaultKeyName` is a REST-only field (`eddi-admin`). The MCP `setup_agent` / `create_api_agent` tools do not carry it — not to prevent reuse, which their `apiKey` already supports as a `${vault:...}` reference, but because the two things it *adds* (choosing the name of a newly created entry, and a value-must-match check on an existing one) are not needed to provision an agent and do not belong on the `eddi-editor` tier those tools are open to: name-squatting an entry an operator intends to create, and a per-request "does key X hold value V" oracle.

Naming, or pasting a reference to, a secret whose `allowedAgents` is narrowed is accepted — the legitimate flow is setup without deploy, widen the grant to the new agent's ID, deploy — but a brand-new agent cannot be on any existing grant list, so under `enforce` a `deploy: true` setup will end as `deployed: false`. The response says so up front in `resources.vaultWarning`. (Plaintext reuse simply skips such entries.)

### What rollback does with the vault

A setup that fails part-way rolls back the documents it created. It also removes the secret it vaulted — but only when nothing else can be referencing it: under `never` (unique per-agent names) it does; under `checksum` a freshly stored entry is a shared resource the moment a second setup with the same key runs, so it is left in place — a retry finds it by value and reuses it, and at worst it is one orphan. A caller-named entry is never rolled back for the same reason.

### Collision Prevention

A generated vault key is named `setup.<agent>.<timestamp>-<random>.apiKey`. The timestamp alone was not sufficient: two setups for agents with the same name landing in the same millisecond produced the same name, and `store` is an upsert, so one silently overwrote the other's credential. The random suffix is what makes the name unique; the timestamp is kept because it tells you when the entry was made.

A **caller-chosen** `vaultKeyName` has no such suffix, by design — that is the point of naming it. Creating one is read-then-write rather than a conditional insert, so two setups naming the same new key with different values can race; the loser is detected on read-back and fails before anything is created, but a write landing after that read is not caught (an atomic create-if-absent for the vault SPI is tracked in [issue #700](https://github.com/labsai/EDDI/issues/700)). Prefer creating a shared key through the secrets REST API first, then naming it.

### Graceful Degradation

When the vault is disabled (no `EDDI_VAULT_MASTER_KEY`), the setup service logs a warning and falls back to plaintext storage. This ensures agent setup works in local development without requiring vault configuration.

A request carrying `vaultKeyName` is the exception: it fails with a clear error instead. Naming a vault entry is a request for one specific shared secret, and quietly writing the key in plaintext is not a smaller version of that.

> **Production recommendation:** Always set `EDDI_VAULT_MASTER_KEY` in production. The installer does this automatically.

---

## REST API

### Endpoints

All endpoints are under the base path `/secretstore/secrets`. All endpoints require the `eddi-admin` role.

| Method   | Path                         | Description                                            |
| -------- | ---------------------------- | ------------------------------------------------------ |
| `PUT`    | `/{tenantId}/{keyName}`      | Store a secret (body = plaintext value)                |
| `DELETE` | `/{tenantId}/{keyName}`      | Delete a secret                                        |
| `GET`    | `/{tenantId}/{keyName}`      | Get secret **metadata only** (never returns plaintext) |
| `GET`    | `/{tenantId}`                | List all secrets for a tenant (metadata only)          |
| `GET`    | `/health`                    | Vault health check (provider status)                   |
| `POST`   | `/{tenantId}/rotate-dek`     | Rotate the tenant's Data Encryption Key                |
| `POST`   | `/admin/rotate-kek`          | Rotate the Master Key (KEK) — **TLS required**         |

> **⚠️ Important:** The `GET` endpoints return **metadata only** (`keyName`, `createdAt`, `lastAccessedAt`, `checksum`). Secret values are **write-only** — they can be stored and used by the engine but never retrieved via API.

### Response Examples

**`PUT /{tenantId}/{keyName}`** — returns the vault reference:

```json
{
  "reference": "${vault:apiKey}",
  "tenantId": "default",
  "keyName": "apiKey"
}
```

**`GET /{tenantId}`** — returns metadata list:

```json
[
  {
    "tenantId": "default",
    "keyName": "apiKey",
    "createdAt": "2026-03-15T10:30:00Z",
    "lastAccessedAt": "2026-03-16T14:00:00Z",
    "checksum": "a1b2c3d4..."
  }
]
```

**`GET /health`** — returns vault provider status:

```json
{
  "status": "UP",
  "provider": "VaultSecretProvider",
  "available": true
}
```

**`POST /{tenantId}/rotate-dek`** — rotates the tenant's DEK:

```json
{
  "tenantId": "default",
  "secretsReEncrypted": 5,
  "message": "DEK rotated successfully. 5 secrets re-encrypted."
}
```

**`POST /admin/rotate-kek`** — rotates the master key:

Request body:
```json
{
  "oldMasterKey": "current-master-key",
  "newMasterKey": "new-master-key-at-least-8-chars"
}
```

Response:
```json
{
  "deksReEncrypted": 3,
  "message": "KEK rotated successfully. 3 DEKs re-encrypted. IMPORTANT: Update the EDDI_VAULT_MASTER_KEY environment variable to the new key and restart."
}
```

> **⚠️ Warning:** The `rotate-kek` endpoint transmits master keys in the request body. Ensure TLS is enabled. After rotation, update `EDDI_VAULT_MASTER_KEY` and restart.

### Key Rotation

EDDI supports two levels of key rotation:

**DEK Rotation** (`POST /{tenantId}/rotate-dek`):
- Generates a new Data Encryption Key for the tenant
- Re-encrypts all secrets with the new DEK
- Does NOT require a restart
- Recommended: rotate periodically or after personnel changes

**KEK Rotation** (`POST /admin/rotate-kek`):
- Re-encrypts all tenant DEKs with a new master key
- Secret ciphertexts are NOT modified — only DEK wrappers change
- Requires an application restart with the new `EDDI_VAULT_MASTER_KEY` after rotation
- Both operations use a verify-then-commit pattern: all decryption is validated before any writes occur

### Input Validation

All path parameters (`tenantId`, `keyName`) are validated against `[a-zA-Z0-9._-]{1,128}` to prevent path traversal attacks.

## Observability

### Micrometer Metrics

The vault emits metrics under the `eddi.vault.*` namespace for Grafana/Prometheus monitoring:

#### SecretResolver Metrics

| Metric                      | Type    | Description                              |
| --------------------------- | ------- | ---------------------------------------- |
| `eddi.vault.cache.hits`     | Counter | Number of cache hits                     |
| `eddi.vault.cache.misses`   | Counter | Number of cache misses                   |
| `eddi.vault.resolve.errors` | Counter | Resolution failures (not-found, errors)  |
| `eddi.vault.resolve.time`   | Timer   | Duration of provider resolution calls    |

#### VaultSecretProvider Metrics

| Metric                       | Type    | Description                                |
| ---------------------------- | ------- | ------------------------------------------ |
| `eddi.vault.resolve.count`   | Counter | Total resolve operations                   |
| `eddi.vault.store.count`     | Counter | Total store operations                     |
| `eddi.vault.delete.count`    | Counter | Total delete operations                    |
| `eddi.vault.rotate.count`    | Counter | Total rotation operations (DEK + KEK)      |
| `eddi.vault.errors.count`    | Counter | Total error count (persistence + crypto)   |
| `eddi.vault.resolve.duration`| Timer   | Duration of resolve operations             |
| `eddi.vault.store.duration`  | Timer   | Duration of store operations               |

## Manager — Secrets Admin Page

The EDDI Manager includes a dedicated **Secrets Admin** page at `/manage/secrets` for managing vault entries through the UI.

### Features

- **Namespace filtering** — select tenant ID to scope the view
- **Secrets table** — displays `keyName`, `createdAt`, `lastAccessedAt`, and `checksum` (truncated)
- **Add Secret** — dialog with masked password input (eye toggle, `autoComplete="new-password"`)
- **Delete Secret** — confirmation dialog before permanent deletion
- **Vault Health** — live status badge showing vault online/offline state

### Security

- `autoComplete="off"` on key name input prevents browser caching
- `autoComplete="new-password"` on value input prevents browser caching
- React state is cleared immediately on dialog close or submission
- Secret values are **never displayed** — the API only returns metadata

## Security Measures

### Log Redaction

`SecretRedactionFilter` applies pre-compiled regex patterns to all log messages:

| Pattern                       | Replacement               | Example                                                 |
| ----------------------------- | ------------------------- | ------------------------------------------------------- |
| OpenAI keys (`sk-...`)        | `sk-<REDACTED>`           | `sk-abc123...` → `sk-<REDACTED>`                        |
| Anthropic keys (`sk-ant-...`) | `sk-ant-<REDACTED>`       | `sk-ant-api03-...` → `sk-ant-<REDACTED>`                |
| Bearer tokens                 | `Bearer <REDACTED>`       | `Bearer eyJhb...` → `Bearer <REDACTED>`                 |
| API key params                | `apikey=<REDACTED>`       | `apikey=secret123` → `apikey=<REDACTED>`                |
| Vault references              | `${vault:<REDACTED>}` | `${vault:t/key}` → `${vault:<REDACTED>}`        |

### Export Sanitization

`SecretScrubber` removes vault references from agent export (backup) payloads, replacing them with `<SECRET_REMOVED>`. This prevents secrets from leaking when agents are shared or exported.

### Memory Protection

- **HTTP headers**: Sensitive headers (`Authorization`, `X-Api-Key`, etc.) are scrubbed before storing HTTP request details in conversation memory
- **Property values**: Secret-scoped properties store only vault references, never plaintext
- **User input**: When `scope == secret`, the raw `input:initial` is removed from the conversation step

### Persistence Error Handling

Both MongoDB and PostgreSQL persistence implementations wrap all database exceptions in `PersistenceException` (unchecked). This ensures:
- Consistent error handling across database backends
- No silent failures — all persistence errors surface to the caller
- Clear error messages with context (tenant ID, key name, operation)

## Testing

~100 tests across backend and frontend:

### Backend (~80 tests)

| Test Class                    | Tests | Coverage                                                                                           |
| ----------------------------- | ----- | -------------------------------------------------------------------------------------------------- |
| `SecretVaultIntegrationTest`  | 22    | Full round-trip, negative caching, DEK/KEK rotation, metrics, exceptions                          |
| `VaultSecretProviderTest`     | 11    | Store, resolve, delete, metadata, list, unavailable states                                         |
| `SecretResolverTest`          | 10    | Single/multiple/nested resolution, caching, passthrough, auto-vault keys                           |
| `RestSecretStoreTest`         | 22    | All endpoints, validation, error codes, vault unavailable, rotation                                |
| `EnvelopeCryptoTest`          | 9     | Encrypt/decrypt, key rotation, wrong key, tampering, large payloads                                |
| `SecretRedactionFilterTest`   | 6     | All 5 regex patterns, null/empty, safe messages                                                    |
| `SecretScrubberTest`          | 4     | Nested object scrubbing, preservation of non-secret fields                                         |
| `SecretReferenceTest`         | 6+    | Parsing, equality, hash, invalid references                                                        |
| `ConversationSecretInputTest` | 5     | Secret context scrubbing, normal passthrough, false flag, empty context, output vs. lifecycle data |

### Frontend (17 tests)

| Test File                       | Tests | Coverage                                                                                                                |
| ------------------------------- | ----- | ----------------------------------------------------------------------------------------------------------------------- |
| `secrets.test.tsx` (Manager)    | 12    | Page render, tenant inputs, vault health, create dialog (password, autocomplete, eye toggle), delete confirmation       |
| `chat-store.test.tsx` (Chat UI) | 5     | `SET_INPUT_FIELD`, `CLEAR_INPUT_FIELD`, `TOGGLE_SECRET_MODE`, `CLEAR_MESSAGES` reset, initial defaults                  |

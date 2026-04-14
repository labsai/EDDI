# Secrets Vault

EDDI includes a built-in secrets vault for managing sensitive values like API keys, tokens, and passwords. Secrets are encrypted at rest, referenced via URI syntax, and automatically scrubbed from logs and API exports.

## Architecture

```
┌─────────────────┐     ┌──────────────┐     ┌───────────────────┐
│  Configuration   │────>│ SecretResolver│────>│  VaultSecretProv.  │
│  (JSON configs)  │     │  (resolves    │     │  (envelope crypto  │
│  ${eddivault:..} │     │   at runtime) │     │   + persistence)   │
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
| `SecretResolver`                       | `secrets`          | Resolves `${eddivault:...}` references to plaintext at runtime  |
| `IRestSecretStore` / `RestSecretStore` | `secrets.rest`     | JAX-RS endpoints for secret CRUD and key rotation               |
| `SecretScrubber`                       | `secrets.sanitize` | Removes `${eddivault:...}` references from export payloads      |
| `SecretRedactionFilter`                | `secrets.sanitize` | Regex-based log redaction for API keys, tokens, vault refs      |
| `ISecretPersistence`                   | `secrets.persist.` | DB abstraction (MongoDB default, PostgreSQL via profile)        |

## Secret References

Secrets are referenced in configuration JSON using the vault URI syntax:

**Short form** (uses `default` tenant):
```
${eddivault:keyName}
```

**Full form** (explicit tenant):
```
${eddivault:tenantId/keyName}
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

1. Task reads configuration containing `${eddivault:...}` reference
2. `SecretResolver.resolveValue()` finds and replaces vault URIs
3. `VaultSecretProvider.resolve()` decrypts and returns the plaintext
4. Plaintext is used for the operation (e.g., HTTP call header)
5. **Plaintext is NOT stored in memory** — only the vault reference persists

**Caching:** Successfully resolved secrets are cached in a Caffeine cache (configurable TTL). Failed resolutions are **never cached**, ensuring newly created secrets resolve immediately without waiting for cache expiry.

## Encryption

### Envelope Encryption

EDDI uses **envelope encryption** — each tenant gets its own random Data Encryption Key (DEK), which is itself encrypted by a Key Encryption Key (KEK) derived from the master password.

```
Master Password → PBKDF2 (600,000 iterations) → KEK
                                                  │
Secret → tenant DEK → AES-256-GCM encrypt → ciphertext
                │
                └→ KEK wraps DEK → encrypted DEK
                        │
                        └→ stored: { encryptedDek, iv, ciphertext }
```

### Configuration

The vault requires a master key (KEK) to encrypt/decrypt secrets. If not set, the vault is **disabled** — all `${eddivault:...}` references pass through unresolved and a prominent warning is logged at startup.

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
```

> **⚠️ Important:** The vault master key encrypts all stored API keys. If the master key is lost, all encrypted secrets become **permanently unrecoverable**. Back up your `~/.eddi/.env` file.

## Secret Input (Agent Conversations)

Agents can request secret input from users (e.g., API keys during setup). The flow works end-to-end across backend, chat UI, and Manager.

### Backend: PropertySetterTask + Conversation Scrubbing

When a property has `scope: secret`:

1. **PropertySetterTask** detects `scope == secret` on the property instruction
2. The raw value is immediately stored in the vault via `ISecretProvider.store()`
3. A vault reference (`${eddivault:...}`) replaces the plaintext in memory
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

### Agent Father Example

The default Agent Father agent demonstrates vault integration during API key setup:

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

The `scope: secret` instruction causes `PropertySetterTask` to store the API key in the vault and replace the memory value with a `${eddivault:...}` reference.

## Auto-Vaulting (Agent Setup)

When creating agents through the **Agent Father** wizard or the Setup API, API keys are **automatically stored in the vault**. You don't need to manually create vault entries.

### How It Works

1. User provides an API key during agent setup
2. `AgentSetupService.vaultApiKey()` stores the key in the vault
3. A vault reference (`${eddivault:setup.<agent-name>.<timestamp>.apiKey}`) is written to the LLM configuration
4. When the vault is enabled, the plaintext key is never persisted in MongoDB — only the vault reference is stored

### Collision Prevention

Each vault key includes an epoch-millisecond timestamp suffix. This prevents key collisions when two agents share the same name — each gets a unique vault entry.

### Graceful Degradation

When the vault is disabled (no `EDDI_VAULT_MASTER_KEY`), the setup service logs a warning and falls back to plaintext storage. This ensures the Agent Father wizard works in local development without requiring vault configuration.

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
  "reference": "${eddivault:apiKey}",
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
| Vault references              | `${eddivault:<REDACTED>}` | `${eddivault:t/key}` → `${eddivault:<REDACTED>}`        |

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

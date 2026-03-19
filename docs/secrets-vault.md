# Secrets Vault

EDDI includes a built-in secrets vault for managing sensitive values like API keys, tokens, and passwords. Secrets are encrypted at rest, referenced via URI syntax, and automatically scrubbed from logs and API exports.

## Architecture

```
┌─────────────────┐     ┌──────────────┐     ┌───────────────────┐
│  Configuration   │────>│ SecretResolver│────>│  ISecretProvider  │
│  (JSON configs)  │     │  (resolves    │     │  (reads encrypted │
│  ${eddivault:..} │     │   at runtime) │     │   storage)        │
└─────────────────┘     └──────────────┘     └───────────────────┘
                                                       │
                                              ┌────────▼────────┐
                                              │  EnvelopeCrypto  │
                                              │  (AES-256-GCM +  │
                                              │   PBKDF2 KEK)    │
                                              └─────────────────┘
```

### Core Components

| Component | Package | Purpose |
|---|---|---|
| `SecretReference` | `secrets.model` | Value object: `namespace/scope/key` URI parsing |
| `EnvelopeCrypto` | `secrets.crypto` | AES-256-GCM encryption with envelope key wrapping |
| `ISecretProvider` | `secrets` | Interface for reading/writing encrypted secrets |
| `DatabaseSecretProvider` | `secrets` | MongoDB/PostgreSQL implementation of `ISecretProvider` |
| `SecretResolver` | `secrets` | Resolves `${eddivault:path}` references to plaintext at runtime |
| `IRestSecretStore` / `RestSecretStore` | `secrets.rest` | JAX-RS endpoints for secret CRUD |
| `SecretScrubber` | `secrets.sanitize` | Removes `${eddivault:...}` references from export payloads |
| `SecretRedactionFilter` | `secrets.sanitize` | Regex-based log redaction for API keys, tokens, vault refs |

## Secret References

Secrets are referenced in configuration JSON using the vault URI syntax:

```
${eddivault:namespace/scope/key}
```

- **namespace** — tenant or organizational delimiter (e.g., `default`)
- **scope** — bot or feature scope (e.g., `bot-123`)
- **key** — the secret name (e.g., `openai-api-key`)

### Where Vault References Work

| Configuration Type | Fields Resolved |
|---|---|
| **HTTP Calls** (`httpcalls.json`) | URL, headers, body, query parameters |
| **LangChain** (`langchain.json`) | `apiKey` and other model configuration |
| **Property Setter** (`property.json`) | Values with `scope: secret` auto-vault |

### Resolution Behavior

Vault references are resolved **at runtime** when the task executes, never stored as plaintext in conversation memory. The resolution flow:

1. Task reads configuration containing `${eddivault:...}` reference
2. `SecretResolver.resolveValue()` finds and replaces vault URIs
3. `ISecretProvider.getSecret()` decrypts and returns the plaintext
4. Plaintext is used for the operation (e.g., HTTP call header)
5. **Plaintext is NOT stored in memory** — only the vault reference persists

## Encryption

### Envelope Encryption

EDDI uses **envelope encryption** — each secret gets its own random Data Encryption Key (DEK), which is itself encrypted by a Key Encryption Key (KEK) derived from the master password.

```
Master Password → PBKDF2 (600,000 iterations) → KEK
                                                  │
Secret → random DEK → AES-256-GCM encrypt → ciphertext
                │
                └→ KEK wraps DEK → encrypted DEK
                        │
                        └→ stored: { encryptedDek, iv, salt, ciphertext }
```

### Configuration

The vault requires a master key (KEK) to encrypt/decrypt secrets. If not set, the vault is **disabled** — all `${eddivault:...}` references pass through unresolved and a prominent warning is logged at startup.

Set the master key using **one** of these methods (in priority order):

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

> **⚠️ Important:** The master key must be set via environment variable or secure configuration. If the master key is lost, all encrypted secrets become unrecoverable.

## Secret Input (Bot Conversations)

Bots can request secret input from users (e.g., API keys during setup). The flow works end-to-end across backend, chat UI, and Manager.

### Backend: PropertySetterTask + Conversation Scrubbing

When a property has `scope: secret`:

1. **PropertySetterTask** detects `scope == secret` on the property instruction
2. The raw value is immediately stored in the vault via `ISecretProvider.storeSecret()`
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

### Bot Father Example

The default Bot Father bot demonstrates vault integration during API key setup:

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
  "valueString": "[[${memory.current.input}]]",
  "scope": "secret"
}
```

The `scope: secret` instruction causes `PropertySetterTask` to store the API key in the vault and replace the memory value with a `${eddivault:...}` reference.

## REST API

### Endpoints

All endpoints are under the base path `/secretstore/secrets`.

| Method | Path | Description |
|---|---|---|
| `PUT` | `/{tenantId}/{botId}/{keyName}` | Store a secret (body = plaintext value) |
| `DELETE` | `/{tenantId}/{botId}/{keyName}` | Delete a secret |
| `GET` | `/{tenantId}/{botId}/{keyName}` | Get secret **metadata only** (never returns plaintext) |
| `GET` | `/{tenantId}/{botId}` | List all secrets for a tenant+bot (metadata only) |
| `GET` | `/health` | Vault health check (provider status) |

> **⚠️ Important:** The `GET` endpoints return **metadata only** (`keyName`, `createdAt`, `lastAccessedAt`, `checksum`). Secret values are **write-only** — they can be stored and used by the engine but never retrieved via API.

### Response Examples

**`PUT /{tenantId}/{botId}/{keyName}`** — returns the vault reference:
```json
{
  "reference": "${eddivault:default.bot1.apiKey}",
  "tenantId": "default",
  "botId": "bot1",
  "keyName": "apiKey"
}
```

**`GET /{tenantId}/{botId}`** — returns metadata list:
```json
[
  {
    "tenantId": "default",
    "botId": "bot1",
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
  "provider": "DatabaseSecretProvider",
  "available": true
}
```

### Input Validation

All path parameters (`tenantId`, `botId`, `keyName`) are validated against `[a-zA-Z0-9._-]{1,128}` to prevent path traversal attacks.

## Manager — Secrets Admin Page

The EDDI Manager includes a dedicated **Secrets Admin** page at `/manage/secrets` for managing vault entries through the UI.

### Features

- **Namespace filtering** — select tenant ID and bot ID to scope the view
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

| Pattern | Replacement | Example |
|---|---|---|
| OpenAI keys (`sk-...`) | `sk-<REDACTED>` | `sk-abc123...` → `sk-<REDACTED>` |
| Anthropic keys (`sk-ant-...`) | `sk-ant-<REDACTED>` | `sk-ant-api03-...` → `sk-ant-<REDACTED>` |
| Bearer tokens | `Bearer <REDACTED>` | `Bearer eyJhb...` → `Bearer <REDACTED>` |
| API key params | `apikey=<REDACTED>` | `apikey=secret123` → `apikey=<REDACTED>` |
| Vault references | `${eddivault:<REDACTED>}` | `${eddivault:ns/scope/key}` → `${eddivault:<REDACTED>}` |

### Export Sanitization

`SecretScrubber` removes vault references from bot export (backup) payloads, replacing them with `<SECRET_REMOVED>`. This prevents secrets from leaking when bots are shared or exported.

### Memory Protection

- **HTTP headers**: Sensitive headers (`Authorization`, `X-Api-Key`, etc.) are scrubbed before storing HTTP request details in conversation memory
- **Property values**: Secret-scoped properties store only vault references, never plaintext
- **User input**: When `scope == secret`, the raw `input:initial` is removed from the conversation step

## Testing

54 tests across 8 test classes:

### Backend (37 tests)

| Test Class | Tests | Coverage |
|---|---|---|
| `EnvelopeCryptoTest` | 9 | Encrypt/decrypt, key rotation, wrong key, tampering, large payloads |
| `SecretResolverTest` | 7 | Single/multiple/nested resolution, no-ops, missing secrets |
| `SecretRedactionFilterTest` | 6 | All 5 regex patterns, null/empty, safe messages |
| `SecretScrubberTest` | 4 | Nested object scrubbing, preservation of non-secret fields |
| `SecretReferenceTest` | 6 | Parsing, equality, hash, invalid references |
| `ConversationSecretInputTest` | 5 | Secret context scrubbing, normal passthrough, false flag, empty context, output vs. lifecycle data |

### Frontend (17 tests)

| Test File | Tests | Coverage |
|---|---|---|
| `secrets.test.tsx` (Manager) | 12 | Page render, tenant/bot inputs, vault health, create dialog (password, autocomplete, eye toggle), delete confirmation |
| `chat-store.test.tsx` (Chat UI) | 5 | `SET_INPUT_FIELD`, `CLEAR_INPUT_FIELD`, `TOGGLE_SECRET_MODE`, `CLEAR_MESSAGES` reset, initial defaults |

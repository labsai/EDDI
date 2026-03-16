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

```properties
# Master encryption key (REQUIRED for vault to function)
eddi.secrets.master-key=your-strong-master-key

# Algorithm parameters (defaults shown)
# PBKDF2: 600,000 iterations, SHA-256
# AES: 256-bit GCM, 12-byte IV, 16-byte salt
```

> **⚠️ Important:** The master key must be set via environment variable or secure configuration. If the master key is lost, all encrypted secrets become unrecoverable.

## Secret Input (Bot Conversations)

Bots can request secret input from users (e.g., API keys during setup). When a property has `scope: secret`:

1. **PropertySetterTask** detects `scope == secret` on the property instruction
2. The raw value is immediately stored in the vault via `ISecretProvider.storeSecret()`
3. A vault reference (`${eddivault:...}`) replaces the plaintext in memory
4. The raw `input:initial` entry is scrubbed from the conversation step

### Output InputField Directive

To signal the chat UI to show a password field, use the `inputField` output type:

```json
{
  "type": "inputField",
  "subType": "password",
  "text": "Please enter your API key:"
}
```

> **Note:** Chat UI password field support is a separate implementation (see remaining work).

## REST API

### Endpoints

| Method | Path | Description |
|---|---|---|
| `PUT` | `/secretstore/secrets/{id}` | Create or update a secret |
| `GET` | `/secretstore/secrets/{id}` | Read a secret (returns decrypted value) |
| `DELETE` | `/secretstore/secrets/{id}` | Delete a secret |

### Input Validation

All path parameters are validated against `[a-zA-Z0-9._-]{1,128}` to prevent path traversal attacks.

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

32 unit tests across 5 test classes:

| Test Class | Tests | Coverage |
|---|---|---|
| `EnvelopeCryptoTest` | 9 | Encrypt/decrypt, key rotation, wrong key, tampering, large payloads |
| `SecretResolverTest` | 7 | Single/multiple/nested resolution, no-ops, missing secrets |
| `SecretRedactionFilterTest` | 6 | All 5 regex patterns, null/empty, safe messages |
| `SecretScrubberTest` | 4 | Nested object scrubbing, preservation of non-secret fields |
| `SecretReferenceTest` | 6 | Parsing, equality, hash, invalid references |

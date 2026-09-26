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
| `ISecretProvider`                      | `secrets`          | SPI for reading/writing encrypted secrets, plus `seal`/`unseal` |
| `SealedDataRotationParticipant`        | `secrets`          | SPI joining DEK rotation's sweep — implemented by anything else that stores `seal`-ed data |
| `VaultSecretProvider`                  | `secrets.impl`     | Production implementation with envelope crypto + persistence    |
| `SecretResolver`                       | `secrets`          | Resolves `${vault:...}` references to plaintext at runtime  |
| `IRestSecretStore` / `RestSecretStore` | `secrets.rest`     | JAX-RS endpoints for secret CRUD and key rotation               |
| `SecretScrubber`                       | `secrets.sanitize` | Removes plaintext secrets from export payloads (leaves `${vault:...}` references intact) |
| `SecretRedactionFilter`                | `secrets.sanitize` | Regex-based log redaction for API keys and tokens                |
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

In an HTTP call, a vault reference is resolved **only where the configuration wrote it**: in the
template of that URL, header, body or query parameter, or as the value of a property the template
names that EDDI itself auto-vaulted (`Bearer {properties.apiKey}` holding `${vault:<agentId>.apiKey}`).
The same
applies to `${eddivault:…}`, `${connection:…}` and `${caller:…}`. A reference that arrives through
conversation data — user input, a model reply, an API response, client context — refuses the call
instead of being resolved, with an error naming the field. Grants are checked at deploy time, so
without this rule a user could have a template substitute any secret of the tenant.

This holds through `${vars:…}` as well. A global variable may itself hold a vault or connection
reference, so a `${vars:…}` the configuration wrote still resolves through to the secret, while one
that arrived through conversation data refuses the call — the check runs again after variable
expansion, against the configured template expanded the same way.

The auto-vaulted-property case rests on **provenance, not on what the value looks like**. A
`scope: secret` instruction stores its vault reference as an ordinary conversation property, so the
string `${vault:<agentId>.apiKey}` is one anything that can write a property could produce — a
`valueString` of `{memory.current.input}` and a user who types it, a model reply, an API response
copied into a property. The property therefore carries an `autoVaulted` marker, written by the
auto-vaulting code and by nothing else, and the reference is resolved only when that marker is
present. On top of it the reference must still name this agent and the property the template reads,
**under this conversation's own tenant**.

A property with no marker is refused, which includes one stored in a conversation that began before
this marker existed: an unmarked property and one written from conversation data are the same thing
on disk, and accepting the pair would leave the case the marker exists to close open. Re-running the
`scope: secret` instruction — having the user supply the secret again, or starting a new
conversation — marks it.

A configured reference that **cannot** be resolved — no such secret, the provider failed, or the
vault is disabled — also refuses the call, naming the field and the reference. The literal
`${vault:name}` is never sent as a credential: it would come back as the API's own "invalid key",
with nothing naming the cause.

The plaintext EDDI substitutes is redacted by value from everything it records about the request:
the request record in conversation memory, the HITL approval preview and the request log line. The
log line is built from the request's raw components and redacted *before* it is shortened, so a
secret carrying a newline, or one longer than the log's body limit, cannot survive the formatting.

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

Then set `enforce`. To widen a grant instead, see below — or remove the reference from the agent's configuration.

### Changing a grant without the secret's value

Use **`PUT /secretstore/secrets/{tenantId}/{keyName}/grant`**. It replaces `allowedAgents` (and optionally the description) and **never touches the encrypted value** — no plaintext is accepted, and none is required:

```bash
curl -X PUT "$EDDI/secretstore/secrets/default/llm-api-key/grant" \
  -H 'Content-Type: application/json' \
  -d '{"allowedAgents": ["0123456789abcdef01234567", "89abcdef0123456789abcdef"]}'
```

This exists because `PUT /{tenantId}/{keyName}` — the other way to write `allowedAgents` — requires the plaintext value, and **by the time you need to widen a grant the plaintext is gone**, which is the point of having vaulted it. Before this endpoint, adding one agent to a list meant recovering the value from a backup or rotating the key, and the path of least resistance was to grant `["*"]` to everything — throwing away the only control that limits a secret's blast radius. The endpoint is on the same `eddi-admin` role as every other vault endpoint.

Three properties are worth knowing:

- **`allowedAgents` is required and must not be empty.** Unlike the store endpoint, an omitted list is a `400` rather than a silent default to `["*"]`, and so is `[]` — which every other layer reads as "unrestricted": on an edit, a field missing from a JSON body, or a list filtered down to nothing, must not be able to open a narrowed secret to every agent. Send `["*"]` to mean "all agents". A list that mixes the wildcard with agent IDs, such as `["*", "someAgent"]`, is stored as plain `["*"]`, because that is what it already means to the deploy-time check — so a grant never *reads* narrower than it behaves.
- **Concurrent edits can be refused instead of lost.** Every edit replaces the whole list, so two operators editing from what they each loaded would otherwise overwrite each other without either finding out — the later write quietly reinstating an agent the earlier one removed. Send the list you loaded as `expectedAllowedAgents` and the write is applied only while the grant is still that list (compared as a set; every spelling of the wildcard is equal). Otherwise the answer is **409** with the grant as it now stands, and nothing is written. A dry run checks the precondition too. Omitting the field keeps the old unconditional behaviour.
- **Absent means null.** Like every EDDI response, fields whose value is null are omitted — a secret that has never been rotated has no `lastRotatedAt` in the response, rather than `"lastRotatedAt": null`.
- **It is not a rotation.** `createdAt` and `lastRotatedAt` keep their values and the checksum is unchanged; all three are echoed back so you can see that for yourself. The `SecretResolver` cache is deliberately *not* invalidated — the plaintext cannot have changed, and the grant check reads metadata from the store on every call, so the new grant is in force for the very next deployment either way.
- **Narrowing a grant is reported, not silently applied.** The response lists every *deployed* agent that references the secret and is no longer granted it:

```json
{
  "reference": "${vault:llm-api-key}",
  "tenantId": "default",
  "keyName": "llm-api-key",
  "dryRun": false,
  "allowedAgents": ["0123456789abcdef01234567"],
  "previousAllowedAgents": ["0123456789abcdef01234567", "89abcdef0123456789abcdef"],
  "grantsAllAgents": false,
  "description": "LLM provider key",
  "createdAt": "2026-03-15T10:30:00Z",
  "agentsLosingAccessScope": "this-node",
  "agentsLosingAccess": [
    { "agentId": "89abcdef0123456789abcdef", "agentVersion": 3, "environment": "production" }
  ],
  "warning": "1 deployed agent(s) reference this secret and are not on the new grant list. They keep running — the grant is checked when an agent is deployed, not when a secret is resolved — but under eddi.vault.grant-enforcement=enforce their next deployment will be REFUSED. …"
}
```

  Those agents are **not** broken now: the check is a deploy-time gate, so they keep resolving the secret until they are redeployed. What breaks is their *next* deployment, possibly weeks later on a restart nobody connects to the grant edit — which is exactly why it is reported at the moment of the change. Add `?dryRun=true` to get this answer **without writing anything**; that is how the Manager shows the warning before you commit rather than after. Every deployed *version* is considered, not only the latest, since an older version can be the one serving. The list covers the agents deployed on the node you are talking to — the response says so in `agentsLosingAccessScope: "this-node"` — so on a cluster it is indicative rather than exhaustive.

**In the Manager UI:** *Secrets* → the **Allowed Agents** column, or the **Access** action on a row. Agents are picked from a searchable list of the ones that exist (a raw ID can still be typed, for an agent not created yet), and a change that would strip access from a deployed agent shows the warning above and has to be acknowledged before it can be saved.

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
                        └→ stored: { encryptedDek, iv, ciphertext, dekId }
```

`dekId` is `<tenantId>#g<generation>` — readable on purpose, so `default#g3` in a
database row tells an operator exactly what it means. A tenant holds one DEK row per
generation because [rotation adds one rather than replacing the key](#dek-rotation-is-additive--it-adds-a-generation),
and ciphertext therefore has to say which key sealed it. A value written before
generations existed carries no generation and reads as generation 1.

**Ciphertexts are bound to their rows.** A secret is sealed with AES-GCM associated
data naming its tenant and key name, and a wrapped DEK with associated data naming
its tenant and generation, so a ciphertext copied into another row by someone with
write access to the database fails authentication instead of decrypting as that
row's value. Bound values carry an `a1:` prefix; values written before this carry
none and keep decrypting without associated data, so nothing has to be migrated.
A DEK rotation re-seals a tenant's secrets in the bound form, and a KEK rotation
re-wraps DEKs in it. System values (such as the pinned audit key) are bound to
their name the same way. OAuth connection grants, sealed through `seal()`, are not
bound yet.

**The per-deployment salt is created once, by whichever replica gets there first.**
It is written with an insert-if-absent and every other replica adopts the winner, so
two replicas booting against an empty database cannot each derive their KEK from a
different salt. A salt that cannot be read fails the start: falling back to the
legacy salt on a deployment that has a random one derives the wrong KEK.

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

> **⚠️ Important:** The vault master key encrypts all stored API keys. If the master key is lost, all encrypted secrets become **permanently unrecoverable**. Back up your `~/.eddi/.env` file. If the key has already changed and the old one is unavailable, follow [Lost master key](#lost-master-key): adopt the new key with `POST /secretstore/secrets/admin/adopt-master-key?confirm=true`, then `POST /secretstore/secrets/{tenantId}/reset` clears each tenant's unrecoverable entries so it can start fresh.

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

When the **client sends a credential as context** — for example the caller's token for a
downstream API — it marks that context entry `"secret": true`. The value works for that one
turn and is replaced by `<secret context>` in everything that outlives it. See
[Passing Context Information → Secret Context Values](passing-context-information.md#secret-context-values).

### Output InputField Directive

To signal the chat UI to show a password field, use the `inputField` output type in your output configuration:

```json
{
  "type": "inputField",
  "subType": "password",
  "label": "API Key",
  "placeholder": "Paste your API key here"
}
```

The explanatory sentence ("Please enter your API key:") belongs in a sibling `text` output item in the same action's output set — the `inputField` item only controls how the input is rendered.

### Chat UI: Password Fields + Secret Mode

Both the **Chat UI** (`ui/chat`) and the **Manager chat panel** (`ui/manager`) support secret input:

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
// (pair it with a sibling `text` item that explains what to enter)
{
  "type": "inputField",
  "subType": "password",
  "label": "API Key",
  "placeholder": "Paste your API key here"
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
3. A vault reference (`${vault:setup.<agent-name>.<timestamp>-<random>.apiKey}`, or the name you chose) is written to the LLM configuration
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
| `PUT`    | `/{tenantId}/{keyName}`      | Store a secret (JSON body: `{"value": …, "description": …, "allowedAgents": …}`) |
| `PUT`    | `/{tenantId}/{keyName}/grant` | Replace `allowedAgents` (and optionally the description) **without** the value — see [Changing a grant](#changing-a-grant-without-the-secrets-value). `?dryRun=true` previews the impact without writing |
| `DELETE` | `/{tenantId}/{keyName}`      | Delete a secret                                        |
| `GET`    | `/{tenantId}/{keyName}`      | Get secret **metadata only** (never returns plaintext) |
| `GET`    | `/{tenantId}`                | List all secrets for a tenant (metadata only)          |
| `GET`    | `/health`                    | Vault health check (provider status)                   |
| `POST`   | `/{tenantId}/rotate-dek`     | Install the tenant's next DEK generation and sweep rows onto it |
| `POST`   | `/admin/rotate-kek`          | Rotate the Master Key (KEK) — **TLS required**         |
| `POST`   | `/{tenantId}/reset`          | Delete **ALL** secrets and the DEK for a tenant — destructive; use when the master key changed and the old key is unavailable |
| `POST`   | `/admin/adopt-master-key?confirm=true` | Make the configured master key the vault's after the previous one was **lost** — never during an unfinished rotation. Lists the tenants that still need a reset |

> **⚠️ Important:** The `GET` endpoints return **metadata only** (`keyName`, `createdAt`, `lastAccessedAt`, `checksum`). Secret values are **write-only** — they can be stored and used by the engine but never retrieved via API.

### Response Examples

**`PUT /{tenantId}/{keyName}`** — the request body carries the plaintext value, an optional description, and the optional agent grant list. On a **create**, an omitted `allowedAgents` defaults to `["*"]`. On an **update** — rotating the value — an omitted `allowedAgents` or `description` keeps what is stored: a rotation used to reset a narrowed grant to `["*"]` and wipe the description whenever the client did not restate them.

```json
{
  "value": "sk-...",
  "description": "OpenAI production key",
  "allowedAgents": ["*"]
}
```

It returns the vault reference:

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

**`POST /{tenantId}/reset`** — deletes every secret and the DEK for the tenant:

```json
{
  "tenantId": "default",
  "secretsDeleted": 5,
  "message": "Vault reset for tenant 'default'. 5 secret(s) deleted, DEK removed. The next secret store operation will generate a fresh DEK with the current master key."
}
```

**`POST /{tenantId}/rotate-dek`** — installs the tenant's next DEK generation and
sweeps existing rows onto it:

```json
{
  "tenantId": "default",
  "secretsReEncrypted": 5,
  "message": "DEK rotated successfully. 5 secrets re-encrypted."
}
```

If the sweep does not finish, the call answers **500** and says so — the new
generation is still active, nothing is lost, and re-running finishes the migration:

```json
{
  "error": "DEK rotation failed: DEK rotation for tenant 'default': generation 3 is now the active key and every new value is sealed with it, but at least 2 sealed row(s) still name an older generation. Nothing is lost — those rows still decrypt with the generation they name, which has not been deleted — and the operation is safe to re-run to finish the migration."
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

EDDI supports two levels of key rotation.

#### DEK rotation is additive — it adds a generation

`POST /{tenantId}/rotate-dek` does **not** replace the tenant's key. A tenant holds
one DEK row per **generation**, and every ciphertext — a stored secret, and any
other sealed value written through a `SealedDataRotationParticipant`, such as an
[OAuth connection grant](connections.md#rotating-the-key-that-holds-them) — records
the generation that sealed it. Reading opens a value with the generation
it names, not with whichever one is newest.

Rotation runs three phases, and only the middle one is irreversible:

1. **Verify** — every existing generation is opened with the current KEK, so a wrong
   `EDDI_VAULT_MASTER_KEY` is discovered before anything is written. Every
   generation, not just the newest: the sweep below has to open the older ones, and
   finding that out mid-sweep is a discovery that belongs before the commit point.
2. **Commit** — the next generation is *inserted*. One statement, guarded by a
   unique key on `(tenant, generation)`, so two racing rotations produce one winner
   and one clean refusal. From here new values seal with the new key while every
   existing row still names a generation that exists and decrypts.
3. **Sweep** — rows move onto the new generation one at a time, each write guarded on
   the state the row was read in, so a `store` that landed in between is never
   overwritten with a re-seal of the value it replaced.

**Old generations are never deleted.** That is precisely what makes a half-finished
sweep harmless: a row the sweep did not reach still names a key that opens it.
Deleting the generation a row still names is the one action that would make a
partly swept tenant unreadable, so nothing here does it — and a resolve that finds
a named generation missing says so by name rather than failing as a decryption
error one layer down.

**A partial sweep is reported, and re-running finishes it.** The endpoint answers
**500** with a message stating that the new generation is active and every new value
seals with it, that at least *N* sealed rows still name an older generation, that
nothing is lost, and that the operation is safe to re-run. Re-running picks up
exactly the rows the previous run left. Rows are swept individually so that one
secret nobody can open does not strand the rest of the tenant on an older
generation for every future rotation as well.

- Does NOT require a restart
- Recommended: rotate periodically or after personnel changes

#### KEK rotation

`POST /admin/rotate-kek` re-encrypts all tenant DEKs with a new master key:

- **Every generation of every tenant is re-wrapped**, not just the newest. A tenant
  part-way through a DEK sweep still has rows depending on an older generation, and
  leaving one behind on the old KEK is exactly the orphaned-key failure generations
  exist to prevent.
- Secret ciphertexts are NOT modified — only DEK wrappers change
- Requires restarting **every** replica with the new `EDDI_VAULT_MASTER_KEY` after
  rotation. Until then, the other replicas cannot open the re-wrapped DEKs and refuse
  to create new ones (see below) rather than wrap them under the retired key.

It cannot be made atomic, so it is ordered to be **re-run with the same two keys**
wherever it stops:

1. **New salt first.** A deployment still on the legacy salt migrates to a random one
   during the rotation, and that salt is persisted as *pending* before anything is
   wrapped under it. It used to exist only in memory until the end, so a failure
   half-way left DEKs wrapped under a KEK nobody could derive again.
2. **Verify** — every DEK must open with the old KEK *or the new one*; the second is
   what an interrupted run leaves behind. A DEK that opens with neither stops the
   rotation before anything is written.
3. **Announce** — the vault's KEK check value is switched to the new KEK *before* any
   DEK is re-wrapped. A replica still on the old master key checks it before wrapping
   a new DEK and refuses, so no new tenant's key is wrapped under a KEK that is on its
   way out.
4. **Re-wrap** each DEK, guarded on the wrapping it was read with, then sweep again
   for a DEK another replica created in the meantime.
5. **Promote** the pending salt.

A failure after step 2 answers with how many DEKs are already on the new key and a
statement that re-running completes the rotation. A replica restarted part-way
through with the new master key opens DEKs under either KEK and wraps new ones under
the new one. A DEK it cannot open — one the rotation did not reach, still under the
previous key — fails with a message saying to re-run the rotation, and does **not**
offer a tenant reset while a salt migration is pending: one re-run recovers it.

If a legacy-salt migration was interrupted after step 3 and the nodes were restarted
with the new key, everything keeps working and the unfinished rotation is easy to
forget. A later rotation from that key to another still succeeds when every DEK was
reached (DEKs under the pending salt are recognised). If some DEK was not reached, it
is still under the key before that, and the rotation refuses with a message saying
to finish the earlier rotation first — from the previous key to the current one.

A node that is about to wrap a new DEK checks the KEK check value both before and
after inserting it. A node that stalled across a rotation's announcement takes the
DEK it just inserted back out, before anything is sealed with it, and fails the
request. Otherwise a stale replica could leave a DEK — and, during a DEK rotation,
every secret swept onto it — under a KEK nobody runs any more.

#### Lost master key

The KEK check value records which KEK the vault uses, and every node refuses to wrap
a new DEK under any other. That is what protects a rotation from replicas still on
the retired key. From a node's point of view, though, a replica that simply has not
been restarted with a rotated key looks exactly like an operator who lost the old
key and configured a new one. After a lost key, every new secret for every tenant is
refused until the operator decides which case applies. That decision cannot be made
automatically.

When the previous master key is **gone for good**:

1. Start EDDI with the new `EDDI_VAULT_MASTER_KEY`.
2. `POST /secretstore/secrets/admin/adopt-master-key?confirm=true`. This does three
   things:
   - re-announces the check value with the configured key;
   - if the reserved `__eddi-system` tenant's DEKs no longer open, discards them and
     every sealed system value (the audit ledger pins a new key — see
     [audit-ledger.md](audit-ledger.md#signing-keys-and-rotation));
   - answers with `tenantsNeedingReset`, the tenants whose DEKs the key cannot open.
3. `POST /secretstore/secrets/{tenantId}/reset` for each of them, then store the
   secrets again.

Never call it while a KEK rotation is merely unfinished, or because one replica was
not restarted: in both cases the previous key still exists and `rotate-kek`
recovers everything. Adopting the wrong key makes every other replica refuse new
DEKs instead.

One case needs no call. A vault that holds no DEKs at all — started once with one
key, restarted with another before anything was stored — adopts the configured key
at startup, because nothing can be stranded.

#### Resetting a tenant

`POST /{tenantId}/reset` deletes every secret and every DEK generation of the tenant,
and — before the DEKs go — everything else sealed with them, such as the tenant's
[OAuth connection grants](connections.md); their users reconnect. Left in place those
values were not merely unreadable: the tenant's next DEK is generation 1 again, with
the same `dekId`, so every later read opened them with the wrong key and failed
authentication on every request. If discarding them fails, the reset stops with the
DEKs still in place and can be re-run. The reserved tenant `__eddi-system`, which
seals EDDI's own system values (the audit ledger's pinned key), cannot be reset.

#### Schema

Generations need one column and one index, and both are created on boot. Nothing to
run by hand on either backend:

| Backend | On boot |
| --- | --- |
| PostgreSQL | Adds `generation INTEGER NOT NULL DEFAULT 1` to `secret_vault_deks`, drops the column-level `UNIQUE (tenant_id)` constraint, and creates a unique index on `(tenant_id, generation)`. The old constraint is the blocker: while it stands a tenant cannot hold a second generation, so rotation has nowhere to write. It is an index rather than a constraint because only an index can be declared `IF NOT EXISTS`, and Postgres accepts one as an `ON CONFLICT` target just the same. |
| MongoDB | Backfills `generation` on existing DEK documents, *then* drops the legacy unique-on-`tenantId` index and creates a unique compound index on `(tenantId, generation)`. That order matters — the backfill runs first so every pre-generation document has a generation to be indexed on. |

A row written before generations existed reads as generation 1, which is what lets
every already-stored ciphertext keep working with no migration of the ciphertext
itself. During a rolling upgrade the two spellings coexist: a document with no
`generation` field and a document holding `generation: 1` both match generation 1.

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
| `eddi.vault.grant.update.count` | Counter | Successful grant edits (`PUT …/grant`) — counted apart from stores so a spike in widening is visible |
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

> A bare `${vault:...}` reference is deliberately **not** redacted — it is a pointer, not a secret, and hiding it removes exactly the information an approver needs to judge a request. A reference followed by extra value material (`${vault:k}SECRET-TAIL`) **is** redacted as a whole.

### Export Sanitization

`SecretScrubber` removes plaintext secrets from agent export (backup) payloads — detected by field name, by credentials embedded in a URL, and by Shannon entropy — replacing each with `${vault:REDACTED}`. This prevents secrets from leaking when agents are shared or exported. Existing `${vault:...}` / `${eddivault:...}` references are deliberately left intact: a reference is a pointer to a secret, not the secret itself.

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

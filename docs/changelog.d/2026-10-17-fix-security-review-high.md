## 🔒 fix(vault): wizard-vaulted keys carry a destination list; discovery probes resolve no vault reference (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-high`) — security review 2026-10-17, finding **H1**

### What changed and why

Every key the setup wizard vaulted was stored with `allowedAgents ["*"]` and no
`allowedHosts`. An `eddi-editor` can read the LLM configuration holding the
`${vault:…}` reference, so they could copy it, set `baseUrl` to their own host,
deploy, send one message, and receive the key (reproduced live). The MCP
`discover-tools` probe was a second path needing no agent: it resolved a
`${vault:…}` reference from the `X-Mcp-Authorization` header and sent the
plaintext to the URL in the request body.

- **`AgentSetupService`** stores every secret it creates (the LLM `apiKey`; the
  `apiAuth` on `/setup-api`) with `allowedHosts`: the host of the base URL the
  request names, else the provider's own endpoint (new `SetupSecretHosts` —
  OpenAI, Anthropic, Gemini, Mistral, Hugging Face, jlama's model download, and
  the catalog of named OpenAI-compatible providers). For `apiAuth` it is the host
  of each generated httpcall's `targetServerUrl`.
- **New `allowedHosts` field** on `SetupAgentRequest` and `CreateApiAgentRequest`
  (appended last, positional constructors kept): an explicit list overrides the
  derived one, `["*"]` stores the secret open. Under
  `eddi.vault.grant-enforcement=enforce` an open secret is never created
  silently — when nothing can be derived and the request does not say `["*"]`,
  setup returns a 400 before writing anything. `warn`/`off` store it open with a
  `[SECURITY]` warning. Not exposed on the MCP setup tools.
- **Checksum reuse** now reuses an entry only when it is restricted to the same
  hosts, so a key an earlier release vaulted open is not handed to new agents.
- **`ISecretProvider.storeIfAbsent`** gained an `allowedHosts` overload, written
  in the same atomic insert by `VaultSecretProvider` (the default refuses a list
  rather than store the secret open).
- **`RestMcpCallsStore.discoverTools`** refuses a `${vault:…}`/`${eddivault:…}`
  or `${vars:…}` value in `X-Mcp-Authorization` with a 400 instead of resolving
  it.
- **Platform Operator** prompt: never set `allowedHosts`; ask for the endpoint
  when a setup is refused for an unknown destination. Revision 17 → 18.
- Docs: `docs/secrets-vault.md` ("Where a wizard key may be sent"),
  `docs/mcp-client.md`.

### Design decisions

- **Derive, don't ask.** The wizard knows where the key is going. A required
  field would break every existing caller; a derived list closes the hole for
  all of them. The list only constrains a configuration that *names* an
  endpoint; one without uses the provider's built-in endpoint and is not checked
  (`ChatModelRegistry.ENDPOINT_KEYS`), so a slightly incomplete default list
  cannot break an agent.
- **Existing entries are not migrated.** A `vaultKeyName` or `${vault:…}`
  reference the admin names is used as it is. Keys already vaulted open stay
  open until an admin narrows them via `PUT …/grant`.
- **`allowedHosts ["*"]` is wizard-request syntax only.** The secrets REST API
  still rejects `*` in `allowedHosts`, and still accepts a create without the
  field. Refusing that under enforce too would be a breaking change for the
  Manager's Secrets page, so it is left as a follow-up.

### Tests

`AgentSetupAllowedHostsTest` (new, 11): derived and requested lists, enforce
refusal, explicit wildcard, warn mode, reuse of open versus restricted entries,
the named-key insert. `RestMcpCallsStoreTest`: a reference in the header is
refused before the manager is called, and a literal still probes.
`VaultSecretProviderAllowedHostsTest`: `storeIfAbsent` persists the list.
`McpSetupToolsTest` now asserts the end-to-end `["api.openai.com"]`. Reverting
the store argument or the probe check fails these tests (mutation-checked).

### Not done here

- `POST /apicallstore/apicalls/discover-endpoints` was not checked for the same
  probe pattern.
- The review's defence-in-depth items: requiring `eddi-admin` to save a
  non-default `baseUrl` next to a vault reference the caller did not create, and
  a Manager warning badge for "unrestricted destination".

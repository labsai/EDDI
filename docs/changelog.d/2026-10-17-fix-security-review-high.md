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
  when a setup is refused for an unknown destination. Revision 17 → 18 on this
  branch; `main` had moved to 18 in parallel, so the merge landed it as 19.
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
  Manager's Secrets page, so it is left as a follow-up. *Superseded by
  `2026-10-17-fix-security-review-h1-leftovers.md`: a REST create under
  enforce now requires `allowedHosts`, with `["*"]` as the explicit opt-out.*

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

## 🔒 fix(ssrf): the metadata guard refuses a host java.net.URI cannot read (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-high`) — security review 2026-10-17, finding **H2**

### What changed and why

`UrlValidationUtils.rejectCloudMetadataTarget` is the only check on the
httpCalls path while `eddi.security.ssrf-protection.enabled=false` (the
default). It read the host with `java.net.URI` and returned quietly when that
host was null. `URI` gives a null host for any registry-style authority: an
underscore (`x_y.attacker.com`), a percent-encoded host, or an IP literal with a
trailing dot. The Vert.x client (`HttpClientWrapper`) parses the same string
with `java.net.URL`, gets a host, resolves it and connects. An editor-written
`targetServerUrl` naming such a host, with an A record of `169.254.169.254`,
therefore reached the cloud metadata service.

- **`rejectCloudMetadataTarget`** now throws for an unparseable URL, and for an
  `http(s)` URL whose `URI` host is null or blank. When `java.net.URL` reads a
  different host, that host is checked as well. A URL with another scheme is
  still left to the caller.
- **`HttpClientModule.requireNotMetadataHop`** refuses a redirect hop with no
  host instead of following it. A hop with an unreadable host is refused through
  the same check.
- Docs: `docs/security.md`.

### Behaviour change

An httpCall, MCP server or A2A peer whose host contains an underscore (for
example a Docker service name such as `my_api`) is now refused even with SSRF
protection off. Give it a hyphenated alias. The JDK `HttpClient` used by every
other path already refused such URLs.

### Tests

`UrlValidationUtilsTest.unreadableHostIsRefused` covers `x_y.example`,
`169.254.169.254.`, `169%2e254%2e169%2e254`, a single-slash `http:/` and an
unparseable URL. Its resolver answers "public" for everything, so only the new
rule can refuse them. `HttpClientModuleTest` covers the same hosts as redirect
hops, plus a hop with no host. The old assertion that an unparseable URL "is
left to the caller" was inverted. Reverting the null-host throw fails both
classes (mutation-checked).

## 🔒 fix(llm): linear JSON fence stripping; cap the recall tool's query (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-high`) — security review 2026-10-17, finding **H3**

### What changed and why

`ModelOutputParser.stripFence` ran every reply of a `convertToObject` LLM task
(and every `FormatRetryRunner` retry) through a regex with three quantifiers
competing for one whitespace run. A reply of three backticks, N spaces and a
letter cost O(N³): 462 ms at 1,000 spaces, 3.7 s at 2,000, about an hour at
16,000. That CPU is spent on the turn thread, and any chat user can trigger it
by prompt injection, as can a hostile model endpoint.

- **`stripFence`** is now `startsWith` / `endsWith` / `substring`, plus an
  ASCII case-insensitive check for the `json` tag. It returns the same result as
  the old regex for every input: the regex's leading `[ \t]*\r?\n?` and trailing
  `\s*` only moved whitespace that the `trim()` removed anyway.
- **`ConversationRecallTool`** reads only the first 500 characters of the
  model-written `query` (`MAX_QUERY_LENGTH`). Its range pattern was quadratic
  over a long digit run (10.9 s at 40,000 characters). A null query now means
  "the last summarized turns" instead of a `NullPointerException`.

### Tests

`ModelOutputParserFenceTest` (new, 24) keeps the old regex as an oracle and
compares it with the new code on 23 fixtures, including non-ASCII look-alikes of
`json` and a vertical tab. It also runs the 50,000-space input through
`stripFence` and through `parse()`, each within 100 ms. `ConversationRecallToolTest`
adds a 40,000-digit query (100 ms budget), a turn range followed by a 40,000-char
tail, and a null query. Removing the cap fails the budget test at 10.4 s. The
fence budget was not mutation-run: the old regex needs hours at 50,000 spaces.

### Not done here

The L14 alternation-loop regexes from the same review are untouched.

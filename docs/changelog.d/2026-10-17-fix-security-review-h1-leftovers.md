## 🔒 fix(vault): a secret create says where the secret may go; discover-endpoints pinned as never resolving (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-h1-leftovers`, built on `fix/security-review-2026-10-17-high`). Covers the two **H1** leftovers from the 2026-10-17 security review.

### What changed and why

**1. `POST /apicallstore/apicalls/discover-endpoints`: checked, no fix needed.**
The question was whether it resolves a `${vault:…}` or `${vars:…}` value from the
request and sends it to a URL the caller picked, the way `discover-tools` did.
It does not. `authHeaderRef` must already be a reference (a literal is a 400),
and `McpApiToolBuilder.parseAndBuild` copies it verbatim into the generated
calls' `Authorization` header. The spec fetch (`OpenApiSpecFetcher`) sends only
`Accept` and `User-Agent`. The reference is resolved later, when an agent makes
the call, and the secret's grant and `allowedHosts` are checked at that point. A new
test in `RestApiCallsStoreBranchTest` pins this: discovery from an inline spec
whose server is `https://attacker.example` returns both references exactly as
written.

**2. A secret create needs `allowedHosts` under `enforce`. This is a breaking change.**
`allowedAgents` limits which agents may reference a secret, but any editor can
write a config that sends a referenced secret to a URL they choose. So
a secret created without `allowedHosts` went wherever the next config edit
pointed it. Under `eddi.vault.grant-enforcement=enforce` (the default), a create
through `PUT /secretstore/secrets/{tenantId}/{keyName}` or
`PUT /spacestore/secrets/{key}` now gets a **400** when `allowedHosts` is omitted or
`[]`. `["*"]` (alone, never as a pattern) is the explicit opt-out and is
stored as the empty list. `warn` and `off` keep the old behaviour, and a rotation may
still omit the list. The user agreed to the break before it was made.

- `VaultGrantRequests.requireHostsOnCreate` is the host-side twin of
  `requireGrantOnCreate`, shared by both endpoints.
- `VaultHostAllowlist` gains `ANY_HOST`, `isExplicitlyUnrestricted` and
  `requestedHosts` (which maps `["*"]` to `[]`, and otherwise works like
  `validatedHosts`). `SetupSecretHosts` now delegates to it rather than keeping
  its own copy. The grant endpoint also accepts `["*"]` and treats it like `[]`,
  which clears the restriction.
- Space secrets could not carry `allowedHosts` at all. `SecretWrite` gains the
  field, and a 3-argument constructor is kept. `SpaceSecret` now returns the field.
- **Manager:** there is a shared `AllowedHostsField` with two choices, *Only these
  hosts* or *Any host*. It is used in Add Secret, in the secret picker's inline
  create, and on the Workspaces page, and the submit button stays disabled until
  one is chosen. The picker fills in the host from the config's URL
  for the MCP server URL, the A2A agent URL and the LLM `baseUrl`/provider
  default. The Workspaces page create now also sends `allowedAgents: ["*"]`: it
  sent no grant at all, so it already failed under `enforce`. Re-saving an existing space key sends no
  grant and no hosts, so the stored lists are kept. The MSW handlers model the `enforce` rules.
  The Operator prompt tells an admin a new secret needs its hosts. The operator revision is bumped.
- `seed-demo-agent.sh` sent neither list and failed under `enforce`. It now sends both,
  with the host taken from `EDDI_DEMO_LLM_TYPE`, or from
  `EDDI_DEMO_LLM_ALLOWED_HOST` when that is set (wired through
  `docker-compose.openwebui.yml` and `.env.example`). The cluster demo and
  `AuditAndSecurityIT` now send both lists too.
- Docs: `secrets-vault.md` (new *Required on a create* section), `workspaces.md`,
  `troubleshooting.md`, `upgrading-from-6.4.md` §7.1.

### Verification

- `./mvnw test` on `*Secret*Test,*Vault*Test,*Space*Test,AgentSetup*Test,SetupSecretHosts*Test,McpSetupToolsTest,ComposeStackTest,RestApiCallsStore*Test`:
  6114 run, and one fails: `RestApiCallsStoreBranchTest.arbitraryPasswordInTheSpecUrlIsNotEchoed`.
  That test resolves a `.test` host over the network and fails locally with
  `UncheckedIOException`. This change does not touch it.
- Mutation check: with `requireHostsOnCreate` removed from both endpoints,
  `createWithoutHostsRefused`, `createWithEmptyHostsRefused` and
  `SpaceResourcesTest.createNeedsAllowedHosts` fail.
- Manager: `npm run typecheck`, `lint` and `i18n:check` pass, and so do the targeted vitest
  files (secrets, picker, workspaces, use-secrets, MSW handlers, i18n,
  operator-revision). The full vitest suite and the Playwright run are left to CI.

### Decisions

```decision-log
| 2026-10-17 | A secret create under grant-enforcement=enforce requires allowedHosts; ["*"] is the explicit opt-out, [] is refused | allowedAgents limits which agent references a secret, not where it is sent; every editor can choose the destination (security review 2026-10-17, H1) | A [SECURITY] warning only, which keeps scripts working but leaves the default open; requiring hosts in every mode, which overrides the warn/off opt-out |
```

### Folded in when this branch was synced with `main` (2026-10-19)

#1079 merged as a squash while this branch still carried its earlier commits,
and `main` had moved on, so the sync merge resolves the overlap (operator
revision 22 after later syncs past #1091 and #1078, `main`'s dialog sizing, `nameSeg` encoding in `space-resources.ts`)
and carries the fixes below:

- **`main` was red** (#1066 against #1093: the response's cost sum converted
  every step that #1093 had made lazy). This branch fixed it first, but #1081
  merged the same fix (`conversationCost` reads a lazy step list's total from
  the live steps), so the second sync takes `main`'s version and drops the
  duplicate.
- **`/setup-api` apiAuth was locked to the LLM host** by an explicit
  `allowedHosts` (#1079): the request's list names where the LLM key may go, so it
  no longer applies to `apiAuth`, which keeps the generated calls' target hosts;
  only an explicit `["*"]` carries over (`AgentSetupService.apiAuthRequestedHosts`).
- **Timing limits** in `ModelOutputParserFenceTest` and `ConversationRecallToolTest`
  rose from 100 ms to 1 s: the cubic case they guard against takes seconds, and
  100 ms was a flake on a loaded CI runner.
- **Workspaces → space secret Save** stays disabled until the secret list has
  loaded: while it was loading or had failed, a save went out as a create with an
  open agent grant and could widen a key someone had narrowed.
- **The #1079 fragment** now says its decision about REST creates is superseded
  here, and that its operator revision landed as 19.

## 📘 docs: correct what the 2026-10-02 review found wrong, and add a REST reference, troubleshooting and release-notes index (2026-10-02)

**Repo:** EDDI · **Branch:** `docs/review-corrections`

The documentation pass of the 2026-10-02 review (§5). Every corrected claim was
checked against the code, and every copy-pasteable command on the touched
install, tutorial and reference pages was run against a jar built from this
branch.

### Install paths that did not boot

- [`getting-started.md`](../getting-started.md): the manual `docker run labsai/eddi`
  exited at startup — `AuthStartupGuard` and `HighValueSurfaceGuard` refuse a
  production boot without OIDC unless opted out. The command now sets the three
  `EDDI_*_ALLOW_UNAUTHENTICATED` flags the compose file sets, binds to
  `127.0.0.1`, names the Mongo container the image's default connection string
  expects, and carries a local-only warning. Dev mode does not build the Manager,
  which the page now says.
- [`setup-eddi-on-aws-with-mongodb-atlas.md`](../setup-eddi-on-aws-with-mongodb-atlas.md):
  no `JAVA_OPTS_APPEND` override (it replaced the image's own flags), the Mongo
  URI is fixed, and `MONGODB_CONNECTIONSTRING` and `EDDI_VAULT_MASTER_KEY` come
  from Secrets Manager. [`redhat-openshift.md`](../redhat-openshift.md): the quick
  start has a MongoDB and binds to loopback; the OIDC client is `eddi-backend`;
  the `redhat-certify` and OpenSSF-level claims say only what the repository shows.
  [`docker.md`](../docker.md): `mongo:7.0.14`.
- [`security.md`](../security.md): EDDI does not "run open" — the two startup
  guards are described.

### Examples that did not compile or misbehaved

- [`developer-quickstart.md`](../developer-quickstart.md): the Java snippets now
  use the real API (`Property`, `new Data<>`, `getId`/`getType`, null-checked
  `getLatestData`); a custom task needs a bootstrap module registering it in
  `@LifecycleExtensions`; the rule fragments are wrapped in `behaviorGroups`;
  the context example no longer expects a start-call context on a later turn
  (shown live: it renders blank).
- [`creating-your-first-agent/`](../creating-your-first-agent/README.md): the two
  GitBook-era tutorials (numbering 1, 4, 5, 6, `packages`, `RuleChild.values`,
  the legacy inline property setter, a Postman list, "Your Welcome") are folded
  into one tutorial that was run end to end.
- [`conversations.md`](../conversations.md): the redo cache **is** cleared by the
  next message (shown live); the page said the opposite.
- [`architecture.md`](../architecture.md): no native image is shipped; Jackson,
  not JSON-B; the vault DEK is per tenant; `logSizeLimit`, not `sendConversation`;
  a Dream schedule needs no `message`; the ToC covers every section and the
  Summary closes the page; unsourced performance figures removed.
- [`conversation-memory.md`](../conversation-memory.md) (only conversation *state*
  is cached, for 30 s; the real init flow and stored document; the `secret`
  scope), [`memory-policy.md`](../memory-policy.md) (`task_failed_<lifecycle task id>`;
  the 200-character cap excludes the prefix), [`managed-agents.md`](../managed-agents.md)
  (`undoAvailable`/`redoAvailable`, not `redoCacheSize`; the list-all trigger
  endpoint), [`langchain.md`](../langchain.md), [`how-to....md`](../how-to....md),
  [`prompt-snippets-guide.md`](../prompt-snippets-guide.md),
  [`capability-match-guide.md`](../capability-match-guide.md) (the real group
  tools instead of `createGroupConversation`),
  [`code-review-standards.md`](../code-review-standards.md) (two required checks).

### `PUT`, not `POST`, stores a secret — in the docs and in the product

[`agent-sync-guide.md`](../agent-sync-guide.md), [`connections.md`](../connections.md)
and seven validation messages in `ConnectionConfiguration` and
`RestApiCallsStore` told admins to `POST /secretstore/secrets`, which answers
405. They now name `PUT /secretstore/secrets/{tenantId}/{keyName}`; new tests in
`ConnectionConfigurationValidationTest` and `RestApiCallsStoreBranchTest` pin it.

### New and rewritten pages

- [`rest-api-reference.md`](../rest-api-reference.md): every conversation endpoint
  and the streaming SSE event contract (`task_start`, `task_complete`,
  `task_failed`, `token`, `tool_call`, `cascade_*`, `done`, `error` with its
  `code`s), with captured requests and responses. Before, the contract existed
  only in `ui/chat/AGENTS.md`.
- [`troubleshooting.md`](../troubleshooting.md): startup guards, datastores, LLM
  providers, HITL, groups, vault, connections, scheduling, GDPR, deployment and
  auth, each with the exact message the code emits.
- [`release-notes.md`](../release-notes.md): an index of 6.0–6.5 pointing at the
  GitHub release notes, the upgrade guides and the changelog archive.
- [`agent-manager-gui.md`](../agent-manager-gui.md): rewritten for the Manager
  that ships, including the Platform Operator; [`README.md`](../README.md) loses
  its overpromising headings and its quick start no longer assumes a compose file.
- [`configuration-reference.md`](../configuration-reference.md): a "settings
  operators set first" section — MongoDB and PostgreSQL connection settings, OIDC,
  CORS, body size, bind address, `QUARKUS_CONFIG_LOCATIONS`.

```decision-log
| 2026-10-02 | The two "first agent" tutorials are folded into one page (creating-your-first-agent/README.md) and the sub-pages are deleted | Both repeated the same steps with different mistakes; one tested page is cheaper to keep correct than two. The anchors other pages link to are directory links and still resolve | Correcting both pages in place (two copies of the same steps to keep in sync) |
| 2026-10-02 | Doc examples keep `labsai/eddi:6.4.0` / `EDDI_VERSION=6.4.0` although 6.5.0 is tagged | ReleaseVersionSourceTest ties every pointer to helm/eddi/Chart.yaml's appVersion, still 6.4.0 until the post-release PR lands; bump-version.py moves them all together | Writing 6.5.0 now (fails the build until the chart moves) |
```

## 🐛 fix(configs,datastore): agent findings for the configuration stores and the datastore (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-configs-datastore`)

Verified the 56 CodeRabbit "agent findings" for `configs/**`, `datastore/mongo`, `datastore/postgres` and
`ui/BrandedOpenApiRoute` against `main`. Many had been closed since the scans, mostly by #1191
(`87f98b7b3`), #1190, #1175, #1173 and #1161. Verdicts: 14 fixed here, 29 already fixed, 5 accepted risk,
8 needing a decision, 0 false positives (two of the already-fixed ones left a gap that is fixed here too).

### What changed and why

**Reference checks.**
- `RestWorkflowStore.addReference` now needs only a host, not a scheme. A scheme-relative
  `//ai.labs.output/outputstore/outputsets/<id>` was skipped, but the engine resolves a step by host and
  id and loaded it anyway.
- A parser document's own `config.uri` is now one of the references `RestParserStore` checks. The
  standalone parse endpoint follows it (`InputParserTask.loadParserDocument`) and uses that parser's
  dictionaries.
- `RestAction` and `RestExpression` (the Manager's action/expression helpers) check VIEW on every rule
  set, API-call set, output set and dictionary they read, not just on the workflow. They pick a store by
  step type and read whatever id `config.uri` spells, relative ones included. A resource the caller
  cannot read contributes nothing.

**Artifact validators (group conversations).**
- A JSON-schema validator is refused at save, and not run at write, when its evaluation is unbounded
  (`ArtifactSchemaComplexity`). The library evaluates every `$ref` separately and reads no clock.
  Measured: 20 `$defs` that each `allOf` the previous one twice took 2 s to save and 3.6 s per artifact
  write, doubling with each further level. A self-`allOf` threw a `StackOverflowError` out of the tool
  call, and two routes to the same property doubled the work per nesting level. The check refuses:
  - an expansion beyond 2000 subschemas;
  - recursion that comes back at the same content location;
  - two recursive routes that do not start with differently named properties.

  Trees and binary trees still pass. A nested `$id` is refused rather than resolved by guesswork.
- `REGEX` validators and schema `pattern`s are run through `BoundedRegex.excessiveRepetition`, the scan
  agent tests already use. A counted repetition of an empty match (`(?:(?:){99999}){99999}`) spins
  without reading input, so the match deadline never saw it.

**JSON Patch.** `ConfigJsonPatcher` masks credentials and judges their relocation by the store's own
read rules. Before, it used the generic redaction rules, while `RestLlmStore`'s read leaves
`tasks[].toolCacheScopes` as it is. A key copied to `toolCacheScopes.apiKey` was accepted as "still
hidden" and then returned in plain text. The LLM and API-call stores pass their `NOT_CONFIGURATION` skip
paths through a new `RestVersionInfo.patch` overload.

**Knowledge bases.**
- A 6.5.0 name-layout knowledge base that changes its `storeType`, or the server it connects to, moves
  to the id layout. The server parameters are `host`, `port`, `database`, `databaseName`,
  `connectionString`, `serverUrl`, `baseUrl` and `tenantName`. On the new backend or server the same
  name maps to that one's default store, which can belong to another legacy knowledge base with the
  same name.
- The standalone endpoints (search, ingest, documents, chunks) apply the deployment rule: the caller
  must belong to every space tenant the configuration's `${vars:<tenant>/…}` / `${vault:<tenant>/…}`
  references name, with variables expanded and connections followed. They build the model and store on
  the spot with the server's authority, and no deployment ever checked them. This adds
  `SpaceReferenceGuard.requireMayUse` and `VaultGrantChecker.referencedTenantsOf`. Docs:
  [RAG](../rag.md).

**Connections.** `ConnectionStore.readByName` reads the live version of each connection. Before, it
read the version the descriptor names. The response filter advances that descriptor only after
`updateConnection` has invalidated the registry, so a lookup in between cached the previous
`allowedHosts` for the registry's one-minute TTL.

**Datastore.** `GridFsAttachmentStore` counts quota usage, and enumerates a conversation's blobs for
erasure, on the primary, like `MongoScheduleStore`'s erasure reads. The client reads `nearest`, so a
lagging secondary undercounted admitted uploads and left committed blobs out of an erasure.

**API description.** `quarkus.http.auth.permission.api-docs.paths` now lists `/openapi.json`, `.yaml`,
`.yml` and their `/q/` forms. Neither `/openapi` nor `/openapi/*` matches them, so the role-gated
document was served to any authenticated token through a suffix URL. Docs:
[security](../security.md), [configuration reference](../configuration-reference.md).

### Design decisions

- **Schema complexity is bounded statically.** networknt offers no hook that runs during evaluation, so
  a budget cannot be enforced while the schema runs. The analysis follows only references inside the
  document. References to other documents are left to the loader, which loads nothing but the bundled
  meta-schemas.
- **Standalone RAG reuses the deployment rule** (`SpaceReferenceGuard`) instead of a second
  tenant-membership check.

### Needs a decision (not changed)

- Descriptor `PATCH` against sharing: the full descriptor is written back, guarded only by the resource
  version, so a concurrent revocation can be undone. Needs a descriptor revision or field-level writes
  on both backends.
- Mongo authorization reads use `nearest`. Moving descriptor reads to the primary affects every
  descriptor read.
- Slack approval records are keyed by the integration's name, not its id.
- A non-admin `duplicateLlm` (or a deep workflow copy) carries plaintext credentials the copier cannot
  read.
- The knowledge-base collision check does not see locations used by older, still-pinned versions.
- Uploads are not fenced against a concurrent conversation erasure (MongoDB and PostgreSQL).

### Follow-up: review findings on the parse path and the connection lookup

- **Standalone parse checks a parser document's `config.uri` per request.** Parser documents saved
  before the save-time check can still name another owner's parser, and `RestSemanticParser` loaded
  them unchecked. The cache entry now records the parser documents `config.uri` named when it was
  built, and every request requires `VIEW` on each of them on the request thread, hit or miss, with the
  same rule `RestParserStore` applies on save. The cache stays shared and the document is still read
  once per cached parser. The parser is now resolved on the request thread instead of the pool thread,
  so that the check has the caller's identity. Not done: a one-off scan or migration of stored legacy
  parsers, and the same per-request check for dictionary references in `extensions`.
- **The connection lookup reads from the primary.** `IResourceStorage.primaryReads()` returns a view
  served by the authoritative copy: on MongoDB the same collections with `ReadPreference.primary()`,
  on PostgreSQL the storage itself. `ConnectionStore` resolves the current version and fetches the
  connection through it, so a lagging secondary cannot hand the registry a connection whose
  `allowedHosts` was just narrowed. Every other store keeps `nearest`. Authorization descriptor reads
  are unchanged and remain the open decision above.

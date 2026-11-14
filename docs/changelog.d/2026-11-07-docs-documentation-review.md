## Documentation review: docs, root and UI guides, inline Javadoc (2026-11-07)

**Repo:** EDDI · **Branch:** `docs/documentation-review`

Checked every line of the 100 pages under `docs/` (not `archive/` or the changelog), the root guides (`AGENTS.md`, `README.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `PRIVACY.md`, `SECURITY.md`) and the `ui/chat` / `ui/manager` guides against the code, `pom.xml`, `application.properties`, the workflows, the Dockerfile and `helm/`. Swept the Javadoc, OpenAPI text and tool descriptions of `src/main/java` for comments that contradict the code. Wrong claims were fixed in place, and missing config fields, endpoints, defaults and limits were added where a page already covers the feature.

**Notable corrections**

- MCP tool counts: 108 tools across 16 classes, 354 arguments of which 145 required (was 96 / 303 / 129 in `AGENTS.md`, `README.md`, [MCP server](../mcp-server.md), [MCP client](../mcp-client.md)).
- Building the image: `-Dquarkus.container-image.build=true` looks for a `Dockerfile.jvm` that no longer exists. The docs and the `mise` `docker-build` task now run `docker build -f src/main/docker/Dockerfile`, the same as CI.
- The Dev UI is at `/q/dev-ui` on Quarkus 3.
- [LLM parameters](../llm-parameters.md): the generator (`scripts/gen-llm-parameters-doc.py`) now renders each rule's `when`/`unless` condition and writes boolean defaults as `true`/`false`. Before this, conditional rejections read as unconditional.
- [Behavior rules](../behavior-rules.md): the "Welcome" API example could never fire. `tagMatches` reads `agent:tags`, which nothing writes, and the page now warns about it.
- CI and release pages: when Trivy, Gitleaks, CodeQL, the SBOM and fuzzing actually run; release candidates do not move `latest`; tags are not signed; the SBOM is a workflow artifact, not a release asset.
- Javadoc: broken `{@link}`/`@see` targets fixed, Javadoc blocks that had drifted off their members moved back, stale "package"/"LangChain"/`SafeHttpClient`/`@DefaultBean` wording updated, and MCP tool descriptions now say "workflow".
- `ConfigurationReferenceCoverageTest` now allows `EDDI_BACKEND_URL`, the Manager dev-server proxy variable that is now documented.

**Found, not fixed (code issues)**

- `DeploymentContextCondition.tagMatches` can never match: no code writes `agent:tags`.
- `DreamConfig.batchSize` / `maxUsersPerRun` and `ScheduleConfiguration.allowSelfScheduling` / `tenantId` are stored but never read.
- `IParserExtensionProvider` casts `lookupIfKnown` to `String`, so a JSON boolean would throw; it is also missing from the correction providers' `ExtensionDescriptor`.
- `helm/eddi/values.yaml` still says `nats.buildProfileImage` stopped being read with chart "< 2.5"; it was 2.7.0. Left alone, because a change under `helm/` needs a chart version bump.

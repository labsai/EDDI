## ✨ feat(operator): the operator can look up the running EDDI version and git commit (2026-10-13)

**Repo:** EDDI (`feat/operator-build-version`)

### What

An operator should always know which EDDI build it runs on, including after the
deployment is upgraded. The Platform Operator's system prompt is written at activation and
its tools are generated from the OpenAPI spec, so nothing it was given can say what is
running *now*. It now has a live lookup:

- `BuildInfo` (`engine/runtime`) is the single source: version from
  `systemRuntime.projectVersion`, commit from `eddi.build.commit` (env `EDDI_BUILD_COMMIT`).
  Only a hex string is accepted as a commit; anything else reports `unknown`, because the
  value reaches an LLM through the tool.
- `GET /administration/version` (`IRestBuildInfo`, `eddi-admin`) returns
  `{version, commit, commitShort}`.
- `get_eddi_version` MCP tool (`McpVersionTools`, `eddi-admin`), added to the `McpToolFilter`
  whitelist and `McpToolFilterCoverageTest`.
- An `EDDI <version> (commit <sha>)` startup log line. (A Micrometer info gauge was left out:
  `MetricsDashboardCoverageTest` requires every meter on the generated dashboard and in
  `docs/metrics.md`, which is more than this change warrants.)
- Dockerfile: `ARG EDDI_COMMIT` -> `ENV EDDI_BUILD_COMMIT` and the
  `org.opencontainers.image.revision` label. `ci.yml` passes `--build-arg EDDI_COMMIT=${{ github.sha }}`.
- Manager: `GET /administration/version` added to the operator's `READ_ENDPOINTS`, and one line
  in its system prompt telling it to call the tool instead of answering from memory.

### Design decisions

- **Commit via Docker build arg, not a Maven git plugin.** The jar stays free of per-commit
  stamps (see `docs/build-reproducibility.md`) and no new build dependency is introduced.
  The cost: a jar run outside the image reports `commit: unknown` unless `EDDI_BUILD_COMMIT` is set.
- **Live endpoint rather than text in the operator's prompt.** An upgrade replaces the image;
  it does not rewrite the stored operator agent, so anything baked in at activation goes stale.
- Raising the Manager's backend floor: operator activation now requires an EDDI that serves
  `/administration/version`. Manager and backend ship in one jar, so this only matters for a
  Manager pointed at an older backend.

### Not done

- Manager vitest was not run locally (no `node_modules` in this checkout); CI runs it. Backend:
  `BuildInfoTest`, `McpToolFilterCoverageTest`, `ImportStyleTest` pass.

### Also in this change

- `ui/manager/src/test/mocks/openapi-operations.json` refreshed from the `OpenAPI Snapshot` job's
  artifact, and `operator-revision.json` bumped to revision 9 (the operator's prompt and allow-list
  changed, which is what makes an operator update reach existing deployments).

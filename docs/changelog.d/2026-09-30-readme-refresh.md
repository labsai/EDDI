## 📝 docs(readme): bring the README up to what 6.5.0 ships (2026-09-30)

**Repo:** EDDI (`docs/650-readme`)

### Why

The README was last refreshed around 6.4.0. Since then the RAG ingestion pipeline (web crawler,
file uploads, scheduled sources, the Manager panel), agent version following, LLM telemetry, MCP
OAuth sign-in, editable vault grants and a round of fail-closed security changes landed, and
several existing claims had drifted from the code.

### What changed

- **Added** (each checked against the code or the page it links): discussion overview, agent
  version following, the OpenAI Chat Completions row in the standards table, MCP OAuth sign-in and
  the MCP client, connections, caller identity, secret context values, editable vault grants,
  workspaces and sharing, the restricted template engine, memory guardrails, the `secret` property
  scope, RAG crawler / upload / scheduled sources, LLM telemetry meters and span, global variables,
  the Manager approvals page, the Workforce workspace and the `/manage` / `/workforce` / `/chat`
  entry points, the loopback-by-default compose bind, the
  `ollama-nvidia`, `mcp-sidecar` and `openwebui` compose files, `mise.toml`, an upgrade callout
  under *Updating*, and ten rows in the documentation table.
- **Corrected**: embedding providers 7 → 8 (Gemini was missing; `EmbeddingModelFactory`),
  memory visibility `global`/`agent`/`group` → `self`/`group`/`global` (`Property.Visibility`),
  prompt-snippet syntax `{{snippets.x}}` → `{snippets.x}` (Qute), GDPR erasure "across 6 stores"
  → every store (the cascade now has well over a dozen steps), MCP circuit breaker "60s cooldown"
  → "3 failures within 60 s" (`McpToolProviderManager`), `install.sh --full` selects PostgreSQL.
- **Removed**: the ZAP DAST line from the CI security gates. `ci.yml` removed that job; its
  comment says not to cite DAST until it is rebuilt.

### Decisions

- No concrete image tag is written, so the README stays out of the release-pointer sweep until the
  6.5.0 image exists.
- The compose bind-address variable for EDDI is not named in the README: `ConfigurationReferenceCoverageTest` treats every documented `EDDI_*` name as a property unless its allow-list of compose variables lists it, and that list does not include this one yet. The upgrade guide words it the same way.
- Screenshots are unchanged: none of the new surfaces has one yet, and a caption must not
  describe a picture that does not show it.

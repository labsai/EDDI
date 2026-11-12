## 🐛 fix(health): an import refused for its agent writes no snippet or connection first (2026-11-12)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Review findings on PR #1389, about running `HealthConfigValidator` on ZIP import.

- **Validation runs before the first write of any kind.** `RestImportService.unpackAndImportAgent` used to import
  the archive's snippets and connections first and only then validate the agent (HITL config, continuation,
  `healthConfig`). Those resources are global and live the moment they are written, so a `merge` archive with a
  changed snippet and an invalid `healthConfig` updated the live snippet before the archive was refused. Running
  conversations could pick up that content, and putting it back took another versioned write, which can itself
  fail. The new `validateAgentFiles` reads the agent file and runs all three checks before `importSnippets`. The
  checks inside the per-agent loop are gone, since they would only repeat it. New test:
  `RestImportServiceSnippetResilienceTest.refusedHealthConfigWritesNoSnippet`.
- **The docs now describe both import paths accurately.** [`docs/agent-config-authoring.md`](../agent-config-authoring.md)
  said every ZIP import and sync was refused with a 400 before writing. That holds for `create` and `merge`. An
  `upgrade` import or a sync checks only when it updates the agent, after the workflows and extensions are
  written: the request then fails with a generic error, and what was written stays.

### What's next

The `upgrade` and sync paths could check the settings they adopt before writing anything. Not done here.

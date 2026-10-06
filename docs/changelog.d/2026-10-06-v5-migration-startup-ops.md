## 5.x migration: background properties migration, progress logging, operations guidance (2026-10-06)

Repo: EDDI, branch `fix/v5-migration-startup-ops`.

**Why.** A production 5.x to 6.x upgrade blocked HTTP for minutes at startup (liveness failed), logged
nothing during long migration steps, and printed the database connection string into the container
log.

**What changed.**
- `PropertiesMigrationService` no longer runs from a `StartupEvent` observer on the main thread. Its
  public `runIfNeeded()` is called by `AgentDeploymentManagement` on the scheduler thread, right after
  the V6 rename migration and before the readiness callback and the first deployment. Readiness
  therefore stays DOWN until it finishes, liveness stays UP, and no agent (so no conversation, so no
  property load) exists before it completes. A failure is logged and retried on next start, as before.
- New `ai.labs.eddi.utils.ProgressLogger`: at most one INFO line per interval (10 s), driven by
  `advance()` in per-document loops or by a daemon `startHeartbeat()` around a single long
  `updateMany`. Wired into `V6RenameMigration` (agents, workflow/descriptor/other document loops,
  triggers, and the four server-side `updateMany` steps including the conversation step reshape) and
  `PropertiesMigrationService`. Tested with a fake clock.
- Docs: new section 9 in `upgrading-from-5x.md` (health checks and start period, rollback, memory,
  vault for former property keys, never-ended conversation clean-up, rehearsal, connection string), a
  "Secrets in the Container Environment" section in `security.md`, and the AWS setup page now uses
  `MONGODB_CONNECTIONSTRING` from a secret and the liveness endpoint.

**Decisions.**
- The UBI `run-java.sh` logs the exec line unconditionally and its logging helpers have no off switch,
  so no Dockerfile environment variable can silence it; the image is unchanged and the guidance is
  documentation. A custom logging module via `JBOSS_CONTAINER_UTIL_LOGGING_MODULE` would work but
  replaces base-image code, so it was not done.
- The migration is injected into `AgentDeploymentManagement` as a field `Instance`, not a constructor
  argument, to keep the constructor stable for its many tests.

**Follow-up.** The end-inactive conversation endpoint is described generically in the upgrade guide
and should get a literal path once its PR is merged.

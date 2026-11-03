## Log capture starts at boot; unauthenticated reminder is opt-in (2026-11-03)

**Repo:** EDDI, branch `fix/log-capture-at-startup`

**Defect.** `/administration/logs` and `/administration/logs/history` returned nothing on a production instance that had been serving conversations for hours; capture began at the first request to the log API. `BoundedLogStore` was a plain `@ApplicationScoped` bean that registered itself with `LogCaptureFilter` in `@PostConstruct`, but it was only injected by `RestLogAdmin` and `McpConversationTools`, so it was created on first use. Until then `LogCaptureFilter` (which forwards only when a store is registered) fed nothing to the ring buffer, the SSE tail or the DB persistence, and every startup warning was lost from EDDI's own log view after each restart. Console output was never affected.

**Fix.**
- `BoundedLogStore` is now `@Startup`, so the ring buffer, DB flush scheduler and shutdown flush run from application start.
- `LogCaptureFilter` holds records logged before a store exists in a bounded pre-start buffer (1000, newest kept, drops counted) and replays them in order, plus a WARN naming any dropped count, when `setStore` is called. Only redacted text is buffered; a record whose redaction fails is never buffered raw and is still stripped for the console. Console output is unchanged. Javadoc "Bootstrap Safety" updated.
- Docs: `docs/log-administration.md` states capture starts at boot.

**Unauthenticated reminder.** `AuthStartupGuard` logged a WARN `REMINDER: OIDC is DISABLED` every hour for deployments that knowingly set `eddi.security.allow-unauthenticated=true`. It now logs the `[SECURITY]` ERROR once at startup (wording and level unchanged) and the repeat is opt-in through `eddi.security.unauthenticated-reminder-interval` (default `off`; a duration such as `1h` enables it; repeats closer than 5 minutes are throttled). Documented in `application.properties`, `docs/configuration-reference.md`, `docs/security.md`, `docs/troubleshooting.md`, `docs/docker.md`, `docs/architecture.md`.

**Tests.** `LogCaptureFilterPreStartTest` (eager store, ordered replay with MDC, bounded buffer, redaction, fail-closed, live path) and extended `AuthStartupGuardTest`.

**Brand-leak E2E.** With capture running from boot, the Logs page now shows startup records that name the engine, which `brand-leak.fullstack.spec.ts` flagged. Log message and stacktrace text is server output, not product chrome: `LogRow` (Logs page) and the live log viewer mark it `data-log-content`, and the spec's snapshot skips that text and its attributes while still scanning the rest of the page. `docs/white-labeling.md` "Not branded" notes it.

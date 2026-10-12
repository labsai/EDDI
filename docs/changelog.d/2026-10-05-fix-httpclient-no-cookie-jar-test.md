## Regression test: outbound httpcalls client keeps no cookie jar (2026-10-05)

**Repo/branch:** EDDI, `fix/httpclient-no-cookie-jar`

**What changed**
- The shared Vert.x client behind every agent's httpcalls was already switched from an application-scoped `WebClientSession` (cookie store, replayed `Set-Cookie` across agents, users and tenants) to a plain `WebClient` in #862. No production code changed here.
- Added `HttpClientModuleCookieTest`: the real producer against a loopback server proves a `Set-Cookie` from one call is not sent on the next call to the same host. Mutation-checked by restoring `WebClientSession.create(...)`, which fails the test.
- Documented the behaviour in `docs/httpcalls.md` ("Cookies are not kept between calls"). Corrected a stale comment in `ApiCallExecutorBranchCoverageTest` that still described a cookie-aware session.

**Behaviour note:** httpcalls never persisted cookies across calls after #862; an API that relied on a session cookie between two calls must carry the credential explicitly (header or property). Nothing in the tests, `docs/agent-configs/` or `docs/httpcalls.md` relies on session cookies.

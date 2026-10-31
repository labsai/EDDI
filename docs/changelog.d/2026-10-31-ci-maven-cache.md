## 🔧 ci: warm one complete Maven cache and retry Central rate limits (2026-10-31)

**Repo:** EDDI (`chore/ci-maven-cache`)

### What changed and why

A pull request's Build & Test job failed with an HTTP 429 from Maven Central while resolving
`surefire-junit-platform`, before a single test ran. Every Java job used `setup-java` with `cache: maven`, which keys
one shared cache on the `pom.xml` hash: whichever job finished first saved it and nothing ever updated it. A
compile-only job (CodeQL) could therefore save a repository without the surefire/failsafe providers or the other
test-time plugins, and every later job fetched those from Central, concurrently with every other open PR.

- **New `Maven Cache` job** (`.github/workflows/ci.yml`) is the only writer of `~/.m2/repository`. It resolves the
  dependencies and plugins, runs `package` once with tests skipped (formatter, Checkstyle, JaCoCo, Quarkus and its
  `*-deployment` jars), forces the surefire and failsafe JUnit providers, and fetches the SBOM and license plugins,
  then saves under `maven-v1-<os>-<hash of every pom.xml and maven-wrapper.properties>`. When that exact key already
  exists it does a lookup-only check and stops, so a docs-only PR pays seconds. A miss starts from the nearest older
  cache. It is `continue-on-error`, so a failed warm-up never blocks the required checks.
- **Build & Test, Integration Tests, CodeQL Analysis, Generate SBOM and Build Image** `need` it and only
  `actions/cache/restore` the same key (with the prefix as fallback); none saves. `cache: maven` is gone from them. Job
  names are unchanged.
- **`.mvn/maven.config`** sets Maven Resolver's HTTP retry: `aether.transport.http.retryHandler.count=8`,
  `.interval=10000`, `.intervalMax=300000`, `.serviceUnavailable=429,503`. A 429 or 503 is retried, the server's
  `Retry-After` is obeyed when sent, and a wait above five minutes fails the request instead of stalling the build.

### Design decisions

- The warm-up is ungated: CodeQL runs on every PR (Scorecard), so a gated warm-up would leave it uncached. The exact-key
  lookup keeps that cheap.
- Caches written on `main` are visible to every PR, caches written on a PR only to that PR, which is why the fallback
  prefix exists.
- The Node archive pin in Build Image is unchanged; it now rides in the same restored repository.
- `codeql.yml` and `loadtest-db-ops.yml` still use `cache: maven` (separate workflows, not in this change).

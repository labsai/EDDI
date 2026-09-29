## ⬆️ chore(deps): every stable patch/minor upgrade, backend and UIs (2026-09-28)

**Repo:** EDDI (`chore/deps-minor-upgrades`)

### What changed

Every dependency and tool moves to its newest stable release within the same major (for `0.x`
packages, within the same minor). Majors are deliberately left to follow-up branches, one per area.

**Backend (`pom.xml`)**

| Artifact | From → To | Note |
|---|---|---|
| Quarkus platform BOM | 3.39.4 → **3.39.5** | `3.40.0` exists only as `CR1` for the platform BOM; the `io.quarkus:*` `3.40.0` the versions report lists are core artifacts the BOM manages, not a platform release |
| `reactor-netty-http` | 1.2.8 → **1.3.7** | The CVE pin moves to the 1.3 line: its pom depends on `reactor-core` 3.8.7, which is exactly what the Quarkus 3.39.5 BOM manages, whereas 1.2.x is built against 3.7.x |
| bcprov-lts8on | 2.73.12.1 → **2.73.13** | |
| classgraph | 4.8.194 → **4.8.196** | |
| swagger-annotations | 2.2.54 → **2.2.55** | `BuildQualityGatesTest` pins it; its constant and Javadoc moved with it |
| swagger-parser | 2.1.47 → **2.1.48** | |
| jnats | 2.26.2 → **2.26.3** | |
| surefire / failsafe | 3.5.6 → **3.6.0** | GA (3.6.0-M1 was skipped last time) |
| Maven (wrapper + `mise.toml`) | 3.9.12 → **3.9.16** | New `distributionSha256Sum`, computed from the zip after checking it against Maven Central's published SHA-512 |
| Node (`node.version` + `mise.toml`) | 22.23.2 → **22.23.3** | |

**Rule for anything Quarkus manages or integrates with: take the version Quarkus has, not a newer one.** The versions report offered Jackson 2.22.3, but the Quarkus 3.39.5 BOM manages the Jackson family at 2.22.2, so Jackson stays at 2.22.2. Everything else above is either not in the Quarkus BOM (bcprov-lts8on, classgraph, swagger, jnats, the Maven plugins and wrapper) or matches it (`reactor-core` 3.8.7). Mockito and Caffeine are Quarkus-BOM-managed and move with the platform.

**Held back on purpose:** langchain4j `1.20.0` → `1.20.2`, for two reasons.

- **Quarkus:** the Quarkus 3.39.5 platform ships langchain4j **1.19.3**, through
  `quarkus-langchain4j-bom`. EDDI is already ahead of it at 1.20.0, and by the rule above it moves
  no further until Quarkus does.
- **OCI GenAI:** `langchain4j-community-oci-genai` was never released past `1.20.0-beta30`, and the
  pom forbids splitting the two langchain4j lines, since a split surfaces as a runtime
  `NoSuchMethodError`.

**Manager (`ui/manager`)**: React / React DOM 19.3, `@tanstack/react-query` 5.104,
`react-router-dom` 7.18.4, dompurify 3.4.16, tailwind-merge 3.7, typescript-eslint 8.70,
`@playwright/test` 1.63, `@testing-library/*`, `eslint-plugin-react-refresh` 0.5.7, and the React
and Node type packages.

`eslint-plugin-react-refresh` 0.5.7 now catches a re-exported constant it missed before:
`discussion-transcript.tsx` exported `STYLE_THEME` for `group-detail.tsx`, which disables Fast
Refresh for that component file. The constant moved to
[`discussion-style-theme.ts`](../../ui/manager/src/components/groups/discussion-style-theme.ts),
imported by both.

**Chat (`ui/chat`)**: TypeScript `~5.7` → `~5.9.3` (the Manager's version), katex 0.18.9,
vitest 5.0.2, and type packages. `npm update` also raised a few floors to the versions already
installed.

Both lockfiles were written by npm inside a Linux `node:22.23.3` container: npm on Windows prunes
`@tailwindcss/oxide-wasm32-wasi`'s children and breaks CI's `npm ci`. The four entries are still
present. The Manager's `npm update` hit an npm bug with its `overrides` block
(`Cannot read properties of null (reading 'edgesOut')`), so its bumps are explicit `npm install`s.

### Verification

- **`./mvnw clean test`:** 18,043 tests ran.
  - 12 failures and 210 errors, all "Unable to establish loopback connection" or Netty "failed to
    create a child event loop". That is this machine's environmental baseline for tests that bind
    loopback sockets.
  - Plus the swagger-annotations constant, since fixed.
  - The Azure / `reactor-netty` path cannot be exercised here, so CI is the real check.
- **Guard tests** (`BuildQualityGatesTest`, `ReleaseVersionSourceTest`, `DeploymentManifestsTest`,
  `ImportStyleTest`, `ComposeStackTest`): pass, apart from the three `DeploymentManifestsTest`
  PowerShell tests that fail identically on `origin/main` here.
- **Manager:** lint, typecheck, `i18n:check`, and `vitest run --coverage` (426 files, 6,858 tests,
  no unhandled errors) all pass, as does `vite build`.
- **Chat:** typecheck, vitest (15 files, 278 tests) and build pass.

### Next

The majors, as their own branches: MCP server 2 / testcontainers 2 / vert.x 5; the JSON libraries,
bson4jackson 3 and langchain4j 1.20.2; Node 24 / frontend-maven-plugin 2 / JDK patch; and the UI
toolchain and runtime majors.

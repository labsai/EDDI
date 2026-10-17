## 📦 chore(deps): dependency refresh before the 6.6.0 release (2026-10-17)

**Repo:** EDDI (`chore/release-6.6.0-deps`)

### What changed and why

A last pass over every pinned dependency before tagging 6.6.0. `pom.xml` already
says `6.6.0` (the 6.5.0 post-release PR set it), so this changes no project
version, only libraries and the Node toolchain.

- **Maven.** Every version literal in `pom.xml` was checked against Maven
  Central. Dependabot had already brought Quarkus (3.40.1), langchain4j
  (1.22.0 / 1.22.0-beta32), the Jackson overrides and the plugins to their
  latest releases. The only patch or minor still open was **classgraph
  4.8.196 → 4.8.199**.
- **Node 24.21.0 → 24.22.0** (latest 24 LTS patch), moved in all the places
  `BuildQualityGatesTest` keeps equal: `pom.xml` `node.version`, `ci.yml`
  `NODE_VERSION` with `NODE_SHA256_LINUX_X64` (the linux-x64 `.tar.gz` line of
  the release's `SHASUMS256.txt`), and `mise.toml`. Both UI READMEs name the
  pinned version and were updated to match.
- **Manager and Chat UI.** `npm update --save` inside the existing semver
  ranges, run with Node 24.22.0 / npm 11.19.1. This includes React 19.3.1,
  Vite 8.3.4, Vitest 5.0.5 (covers the open Dependabot vitest-group PRs),
  eslint 10.13, typescript-eslint 8.71.2, Playwright 1.64.1, Radix UI minors,
  TanStack Query, i18next / react-i18next, lucide-react 1.59 (Manager),
  zustand and the fontsource packages. The Manager lockfile also deduplicates
  about 30 nested entries. The earlier advisory overrides still apply: the Chat UI
  ships a single `katex` 0.19.0 and the Manager resolves `postcss-selector-parser`
  7.1.6.

### Deliberately not updated (majors that need their own migration)

| Dependency | Current → available | Why not now |
|---|---|---|
| `com.networknt:json-schema-validator` | 1.5.9 → 3.0.8 | Rewritten API; the shared-artifact validators need porting |
| `de.undercouch:bson4jackson` | 2.18.0 → 3.x | Built on Jackson 3, which the `ban-jackson3` enforcer rule blocks |
| `com.github.victools:jsonschema-*` | 4.38.0 → 5.0.0 | Also built on Jackson 3 (`tools.jackson`), so the same enforcer rule blocks it |
| `io.github.classgraph:classgraph` | → 5.x / 6.x | Major release; patch line taken instead |
| `io.projectreactor.netty:reactor-netty-http` | 1.2.18 → 1.3.x | Needs Netty 4.2; Quarkus still manages 4.1 (see the pom comment) |
| `typescript` | 5.9 → 7.0 | Native compiler port; its own PR |
| `msw` (Manager) | 2.15 → 3.x | Major release; the mock layer needs migrating |
| `lucide-react` (Chat UI) | 0.577 → 1.x | Major release with renamed icons |
| `@types/node` | 24 → 26 | The runtime is Node 24; the types follow it |

### Verification

`./mvnw clean compile`; Chat UI lint, typecheck, all tests and build; Manager
typecheck, lint, `i18n:check`, build and the full vitest run (592 files, 8551
tests); `BuildQualityGatesTest` and the repo-wide guard tests (243 tests, three
failures in `DeploymentManifestsTest`'s PowerShell `create-secrets.ps1` stub
tests, which fail the same way on an untouched `origin/main` checkout on the
Windows machine used, so they are environmental). The full backend unit suite,
the integration tests and the E2E jobs were left to CI.

**Files:** [`pom.xml`](../../pom.xml), [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml),
[`mise.toml`](../../mise.toml), [`ui/manager/package.json`](../../ui/manager/package.json),
[`ui/chat/package.json`](../../ui/chat/package.json), both `package-lock.json` files,
both UI READMEs.

## ⬆️ chore(deps): Node 24 LTS, frontend-maven-plugin 2 and Temurin 25.0.4.1 (2026-09-28)

**Repo:** EDDI (`chore/deps-major-toolchain`, on top of `chore/deps-minor-upgrades`)

### What changed

The build-toolchain majors deferred from the patch/minor branch, one commit each, plus one Manager
test fix that Node 24 required.

| Tool | From → To | Where |
|---|---|---|
| Node | 22.23.3 → **24.21.0** (LTS "Krypton") | `pom.xml` `node.version`, `mise.toml`, all eight `setup-node` steps in `ci.yml`, and the docs that name the vendored Node (`AGENTS.md`, `README.md`, both UI READMEs) |
| frontend-maven-plugin | 1.15.1 → **2.0.2** | `pom.xml` |
| JDK (mise) | `temurin-25.0.3+9.0.LTS` → **`temurin-25.0.4+101.0.LTS`** | `mise.toml` |

**Node 24.** Node 22 leaves LTS on 2027-04-30; Node 24 is supported until 2028-04-30. CI used
to take `node-version: 22`, the newest 22.x, while the pom pinned an exact version. Every
`setup-node` step now names `24.21.0`, so the UI jobs, the Build Image job's Maven build and
`mise` run the same Node. The UI packages' own floor is unchanged at 22.18, so a contributor still
on Node 22 can run `npm run dev`. No `package.json` changed: neither UI declares `engines`, and
`@types/node` belongs to the UI-majors branch.

**frontend-maven-plugin 2.** 2.0.0 is a dependency-and-baseline release: it needs Java 17 and
Maven 3.6, and its `install-node-and-npm` and `npm` mojos did not change between 1.15.1 and
2.0.2. The executions stay as they were. It also fixes archive extraction on Windows under
Java 25. One visible change: 2.0.2 logs a child process's stderr at `ERROR`, so npm and Vite
warnings now appear as `[ERROR]` lines in a successful build. The pom comment warns about this.

**JDK.** Adoptium's newest GA for 25 is `jdk-25.0.4.1+1`. mise names it `25.0.4+101.0.LTS` (its
semver form folds the fourth component into the build number, the same for every `.1+1` Temurin
release in its catalogue). The identifier was read from mise's own metadata
(`mise-java.jdx.dev/jvm/ga/{linux,windows,macosx}/…json`), which lists it on every platform. mise
itself was not installed here, so the pin was not exercised by a `mise install`.

### Verification

- `./mvnw compile` after each commit.
- `./mvnw package -DskipTests` twice: with Node 24 on plugin 1.15.1, then on plugin 2.0.2. Both
  installed `v24.21.0` into `ui/node`, ran `npm ci` and `npm run build` for both UIs, and ended
  `BUILD SUCCESS`. Under Node 24's npm 11.19, `npm ci` warns that `esbuild` and `msw` install
  scripts are "not yet covered by allowScripts". They still run: the `msw` postinstall rewrote
  `mockServiceWorker.js`, which was restored afterwards.
- **Chat, in a Linux `node:24.21.0` container:** typecheck and vitest pass (15 files, 278 tests).
- **Manager, in a Linux `node:24.21.0` container:** lint, typecheck and `i18n:check` pass. In the
  full vitest run, 422 of 423 files passed. Three more files never started because a vitest worker
  timed out, the container flakiness seen before; all three pass when run alone. The one failing
  file is Node 24 itself, fixed in this branch (below).
- **Guard tests:** `ImportStyleTest`, `BuildQualityGatesTest`, `ChangelogRotationTest`,
  `ChangelogFragmentTest`, `ReleaseVersionSourceTest`, `DocumentationLinksTest`,
  `DeploymentManifestsTest`, `ComposeStackTest` and `JlamaRuntimeFlagsTest` all pass. The
  exception is the three `DeploymentManifestsTest` PowerShell-generator tests, which fail on this
  machine regardless of the change.

### The one test Node 24 broke

`resource-detail-rag-upload-source.test.tsx` failed both of its upload tests on 24.21.0 and passed
on 22.23.3. The cause: Node 24's fetch (undici 7) brand-checks `Blob` and `File`, where Node 22's
accepted anything shaped like one, and jsdom replaces the global `File`. That has two effects:

- The dropped jsdom `File` reaches the MSW handler with an empty body.
- `request.formData()` then fails undici's own assertion on the `File` it builds from the global.

The product code is unaffected, because a browser has only one `File`. The handlers now read the
first part's field name and content type from the raw multipart body, which survive on every Node,
and tell the PDF refusal apart by its content type (commit `d09d1b120a`). Mutation-checked: an
impossible content type in that match fails the test.

```regression-note
| 2026-09-28 | Manager RAG upload tests failed under Node 24 | undici 7 brand-checks File/Blob; jsdom's global File loses its bytes in FormData and breaks request.formData() | Assert on the multipart part's name and content type, parsed from the raw body | d09d1b120a |
```

### Existing checkouts: a Node install per version

The first integration run of all the dependency branches together failed in `npm ci` with
`Class extends value undefined is not a constructor or null`. It only fails on a checkout that
already had a `ui/node` from Node 22, which is every developer's; fresh clones and CI are fine.
frontend-maven-plugin replaces the node binary over an existing install but unpacks the new npm on
top of the old one without clearing it, and the mixed npm tree crashes on start. `ui/node` is
gitignored, so `./mvnw clean` does not remove it either.

`installDirectory` in [`pom.xml`](../../pom.xml) is now `ui/node/${node.version}`: every Node
version gets its own directory, so a bump can never mix installs, and the old one is simply never
used again. Verified by `./mvnw package` on the checkout that had failed: it installed v24.21.0 into
`ui/node/v24.21.0/node`, left the old files alone, and built both UIs.

### Next

The UI toolchain and runtime majors (including `@types/node` 24) stay on their own branch.

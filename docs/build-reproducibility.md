# Build Reproducibility

This document describes what is and is not guaranteed about EDDI builds.

## What EDDI guarantees today

| Property | Status |
|----------|--------|
| **Deterministic dependency resolution** — the same commit resolves the same dependency set, byte-identical, on any machine | ✅ Guaranteed |
| **Deterministic toolchain** — same JDK, same Maven, same base image | ✅ Guaranteed |
| **Verifiable provenance** — you can prove a published image came from a specific commit of this repository | ✅ Guaranteed |
| **Bit-for-bit reproducible artifacts** — two builds of the same commit produce byte-identical JARs | ❌ Not yet — see [Bit-for-bit reproducibility](#bit-for-bit-reproducibility) |

The distinction matters. Deterministic dependency resolution means nobody can slip a different library into your build; it does **not** mean you can rebuild `eddi-6.3.0.jar` and compare its SHA-256 against ours. Verify published artifacts via the [signature and attestation](#verify-a-published-image), not by rebuilding.

## Build System

EDDI uses **Apache Maven** with the Maven Wrapper (`mvnw`) to ensure all developers and CI use the same Maven version regardless of local installation.

```bash
./mvnw clean verify -B -DskipITs
```

The `-B` (batch mode) flag ensures non-interactive, deterministic output.

## Dependency Pinning

All dependencies are pinned to exact versions in `pom.xml`:

- **Direct dependencies** — explicit `<version>` tags, no ranges
- **Quarkus BOM** — `quarkus-bom` imported in `<dependencyManagement>` pins all Quarkus transitive deps
- **Plugin versions** — all build plugins have explicit version tags
- **Maven Wrapper** — `.mvn/wrapper/maven-wrapper.properties` pins the Maven version

No version range anywhere means dependency resolution is a pure function of the commit.

## CI Build Environment

Builds run on GitHub Actions with deterministic configuration:

| Component | Pinning Strategy |
|-----------|-----------------|
| Java | `temurin` distribution, version `25`, via `actions/setup-java` |
| Maven | Wrapper (`mvnw`) — version in `.mvn/wrapper/` |
| Docker base image | Pinned by SHA256 digest in `src/main/docker/Dockerfile` |
| CI runner | `ubuntu-latest` (GitHub-managed) |
| Action versions | Pinned by commit SHA in workflow files |

## Bit-for-bit reproducibility

**Not currently achieved.** `project.build.outputTimestamp` is not set in `pom.xml`, so Maven stamps every JAR entry with the wall-clock time of the build. Two builds of the same commit therefore differ in every archive header, and their checksums never match — regardless of whether the compiled bytecode is identical.

Enabling it is a single property, per the [Maven Reproducible Builds guide](https://maven.apache.org/guides/mini/guide-reproducible-builds.html):

```xml
<properties>
  <project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp>
</properties>
```

With that set, Maven normalises archive entry timestamps, file ordering and file modes across `maven-jar-plugin`, `maven-source-plugin` and friends, and a rebuild of the same commit produces a byte-identical JAR. Verification then becomes:

```bash
./mvnw clean package -DskipTests -B
sha256sum target/quarkus-app/quarkus-run.jar
# compare against the same command run on another machine / in CI
```

Until that property lands, do not describe EDDI builds as "reproducible" without qualification — say **deterministic dependency resolution** and point at the signature/attestation chain below for artifact verification.

## Verification

### Verify a published image

Published Docker images are signed with [Sigstore Cosign](https://docs.sigstore.dev/) (keyless OIDC) and carry a [SLSA](https://slsa.dev/) build provenance attestation. This is the supported way to establish that an image came from this repository's CI:

```bash
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/labsai/EDDI/\.github/workflows/ci\.yml@refs/(heads/main|tags/.+)$' \
  labsai/eddi:6.3.0
```

The identity regexp is the load-bearing part: it pins the signature to the `ci.yml` workflow on `main` or a tag, so a signature produced by any other workflow, branch, or repository fails verification.

Once verified, resolve and pin the digest in your deployment manifests (`k8s/base/eddi-deployment.yaml`, or `eddi.image.digest` in the Helm chart) so the kubelet can never pull different bits under the same tag:

```bash
crane digest labsai/eddi:6.3.0
```

### Verify the build provenance

```bash
gh attestation verify oci://docker.io/labsai/eddi:6.3.0 --repo labsai/EDDI
```

### SBOM

CI builds on `main` generate a [CycloneDX](https://cyclonedx.org/) Software Bill of Materials. The SBOM is uploaded as a CI artifact and can be downloaded from the GitHub Actions run summary.

## Known Limitations

- **Timestamp variance** — build timestamps are embedded in `META-INF/MANIFEST.MF` and in every JAR entry header. This is the reason bit-for-bit reproducibility is not yet claimed; see above for the fix.
- **OS-level differences** — line endings and filesystem ordering may differ between Windows and Linux builds. Compiled bytecode is unaffected, but archive layout can be.

# Build Reproducibility

This document describes how EDDI builds are reproducible and verifiable.

## Build System

EDDI uses **Apache Maven** with the Maven Wrapper (`mvnw`) to ensure all developers and CI use the same Maven version regardless of local installation.

```bash
# Reproducible build command
./mvnw clean verify -B -DskipITs
```

The `-B` (batch mode) flag ensures non-interactive, deterministic output.

## Dependency Pinning

All dependencies are pinned to exact versions in `pom.xml`:

- **Direct dependencies** — explicit `<version>` tags, no ranges
- **Quarkus BOM** — `quarkus-bom` imported in `<dependencyManagement>` pins all Quarkus transitive deps
- **Plugin versions** — all build plugins have explicit version tags
- **Maven Wrapper** — `.mvn/wrapper/maven-wrapper.properties` pins the Maven version

## CI Build Environment

Builds run on GitHub Actions with deterministic configuration:

| Component | Pinning Strategy |
|-----------|-----------------|
| Java | `temurin` distribution, version `25`, via `actions/setup-java` |
| Maven | Wrapper (`mvnw`) — version in `.mvn/wrapper/` |
| Docker base image | Pinned by SHA256 digest in `Dockerfile` |
| CI runner | `ubuntu-latest` (GitHub-managed) |
| Action versions | Pinned by commit SHA in workflow files |

## Verification

### Verify a local build matches CI

```bash
# Build locally
./mvnw clean verify -B -DskipITs

# The resulting JAR in target/ should produce the same class files
# as the CI-built artifact (modulo timestamps in META-INF)
```

### Verify Docker image authenticity

Published Docker images are signed with [Sigstore Cosign](https://docs.sigstore.dev/) (keyless OIDC):

```bash
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/labsai/EDDI/\.github/workflows/ci\.yml@refs/(heads/main|tags/.+)$' \
  labsai/eddi:latest
```

### SBOM

CI builds on `main` generate a [CycloneDX](https://cyclonedx.org/) Software Bill of Materials (SBOM). The SBOM is uploaded as a CI artifact and can be downloaded from the GitHub Actions run summary.

## Known Limitations

- **Timestamp variance** — `META-INF/MANIFEST.MF` contains build timestamps that differ between builds. This does not affect functional reproducibility.
- **OS-level differences** — Line endings and file ordering may differ between Windows and Linux builds, but compiled output is identical.

# Red Hat Enterprise Linux & OpenShift Support

## Platform Support

EDDI is built on and fully supports **Red Hat Enterprise Linux (RHEL)**. The production container image is based exclusively on Red Hat content:

- **Base OS**: [Red Hat Universal Base Image 9 (UBI 9)](https://catalog.redhat.com/software/base-images) — a freely redistributable subset of RHEL 9, binary-compatible with RHEL 9 and supported by Red Hat when run on RHEL or OpenShift.
- **Runtime**: OpenJDK 25 from the official Red Hat UBI 9 OpenJDK runtime image (`ubi9/openjdk-25-runtime`).
- **Architecture**: `linux/amd64` (x86_64).
- **Non-root execution**: Runs as UID `185` (the default `jboss` user from the UBI base image) — containers never run as root.

EDDI is delivered as an OCI-compliant Docker container image and runs on any platform that supports OCI containers, including:

| Platform | Support Level |
|---|---|
| **Red Hat Enterprise Linux 9** | ✅ Primary — UBI 9 base image, Red Hat-certified |
| **Red Hat OpenShift 4.12+** | ✅ Certified — listed in the [Red Hat Ecosystem Catalog](https://catalog.redhat.com/) |
| **Docker** (any Linux, macOS, Windows) | ✅ Full support — standard OCI container |
| **Kubernetes** (any distribution) | ✅ Full support — standard OCI container |
| **Podman** | ✅ Full support — OCI-compliant runtime |

> **Note**: Because EDDI ships as a standard OCI container image built on Red Hat UBI 9, it is inherently compatible with RHEL 9 and any RHEL-based platform. No host-level OS dependencies are required beyond a container runtime.

All EDDI releases are continuously validated against Red Hat certification requirements via automated [preflight checks](https://github.com/redhat-openshift-ecosystem/openshift-preflight) in CI/CD.

---

## Red Hat Ecosystem Catalog

EDDI is listed in the [Red Hat Ecosystem Catalog](https://catalog.redhat.com/) as a certified container image, and is available on [Docker Hub](https://hub.docker.com/r/labsai/eddi):

🔗 **[hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)**

---

## Container Certification

The EDDI container image is certified by Red Hat / IBM for use on OpenShift. Certification is automated via the [`redhat-certify.yml`](../.github/workflows/redhat-certify.yml) GitHub Actions workflow.

### Certification Compliance

| Requirement | Implementation |
|---|---|
| **Base image** | `registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24` (pinned by SHA256 digest) |
| **Non-root execution** | Runs as UID `185` — the default `jboss` user |
| **Licenses** | Auto-generated `/licenses` directory containing `THIRD-PARTY.txt` and downloaded license texts |
| **Required labels** | `name`, `vendor`, `version`, `release`, `summary`, `description` |
| **OpenShift labels** | `io.k8s.display-name`, `io.k8s.description`, `io.openshift.tags` |
| **Health check** | Docker-native `HEALTHCHECK` on `/q/health/ready` |
| **Security scanning** | Trivy image scan in CI blocks push on OS-level CVEs |

### Automated Certification Workflow

The certification release process is fully automated:

1. **Build** — `mvnw clean package -Plicense-gen` builds the application and auto-generates license files via the [MojoHaus license-maven-plugin](https://www.mojohaus.org/license-maven-plugin/)
2. **Docker build** — Builds the image with Red Hat certification labels (parameterized via `--build-arg`)
3. **Push** — Pushes to Docker Hub (or Quay.io when configured)
4. **Preflight** — Runs the [Red Hat preflight tool](https://github.com/redhat-openshift-ecosystem/openshift-preflight) to validate certification requirements
5. **Submit** — Optionally submits results to Red Hat Partner Connect for review

To trigger a certification release, go to **Actions → Red Hat Certification Release → Run workflow** and provide:

- `version` — EDDI version (e.g., `6.0.2`)
- `release` — Incremental release number (e.g., `1`, `2`, `3`)
- `submit` — Whether to submit results to Red Hat (`true`/`false`)
- `registry` — Target registry (`docker.io` or `quay.io`)

### Preflight Quality Gate

Every push to `main` or release tag that produces a Docker image is validated by a **preflight check** in CI. Pull requests also run a preflight dry-run. This catches certification regressions before they reach production (e.g., missing labels, license issues, prohibited packages).

### Required GitHub Secrets

| Secret | Purpose |
|---|---|
| `REDHAT_API_TOKEN` | Pyxis API token from Red Hat Partner Connect |
| `REDHAT_CERT_PROJECT_ID` | Certification project ID |
| `DOCKER_USERNAME` | Docker Hub username |
| `DOCKER_PASSWORD` | Docker Hub password |
| `QUAY_USERNAME` | Quay.io robot account (optional, for Quay.io publishing) |
| `QUAY_PASSWORD` | Quay.io password (optional) |

---

## License Automation

Third-party licenses are generated on-demand using the `license-gen` Maven profile:

```bash
./mvnw package -Plicense-gen -DskipTests
```

This generates:

| File | Contents |
|---|---|
| `licenses/THIRD-PARTY.txt` | All runtime dependencies with their license names |
| `licenses/third-party/` | Downloaded license text files for each dependency |
| `licenses/licenses.xml` | Machine-readable license index |

The profile is **not activated during normal dev builds** to keep them fast. CI workflows (`redhat-certify.yml`, `ci.yml`) activate it automatically.

These files are **not committed to git** — they're generated fresh and accurate in every Docker image build.

---

## EDDI Operator for OpenShift

[![Docker Repository on Quay](https://quay.io/repository/labsai/eddi-operator/status)](https://quay.io/repository/labsai/eddi-operator)

### Prerequisites

- OpenShift 4.12+ deployment
- Block storage (preferably with a storage class)

### Installing from OperatorHub

1. Navigate to **Operators → OperatorHub** in the OpenShift Admin console
2. Search for "EDDI" and select the operator
3. Click **Install** — leave defaults (All Namespaces, Update Channel `alpha`, Approval Strategy `Automatic`)
4. Click **Subscribe**

### Creating an EDDI Instance

After installation, go to **Installed Operators → EDDI** and create a new instance:

```yaml
apiVersion: labs.ai/v1alpha1
kind: Eddi
metadata:
  name: eddi
spec:
  size: 1
  mongodb:
    environment: prod
    storageclass_name: managed-nfs-storage
    storage_size: 20G
```

The operator creates a route automatically. With the CR above, the route would be:
`eddi-route-$NAMESPACE.apps.ocp.example.com`

> **Note**: The EDDI operator is being updated for v6 to support both MongoDB and PostgreSQL storage backends. Stay tuned for the updated operator release.

---

## Docker Image Details

| Property | Value |
|---|---|
| **Image** | `docker.io/labsai/eddi` |
| **Base** | `registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24` |
| **Digest pinning** | SHA256 digest for supply-chain integrity (OpenSSF Silver) |
| **User** | `185` (non-root) |
| **Port** | `7070` |
| **Health endpoint** | `GET /q/health/ready` |
| **Java** | OpenJDK 25 (Red Hat build) |
| **Framework** | Quarkus 3.34.x |

### Quick Start

```bash
docker pull labsai/eddi:latest
docker run -i --rm -p 7070:7070 labsai/eddi
```

For production deployments with MongoDB:

```bash
docker run -d \
  -p 7070:7070 \
  -e QUARKUS_MONGODB_CONNECTION_STRING=mongodb://mongo:27017 \
  labsai/eddi:6.0.2
```

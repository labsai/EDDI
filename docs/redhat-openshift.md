# Red Hat Enterprise Linux & OpenShift Support

## Platform Support

EDDI is built on and fully supports **Red Hat Enterprise Linux (RHEL)**. The production container image is based exclusively on Red Hat content:

- **Base OS**: [Red Hat Universal Base Image 10 (UBI 10)](https://catalog.redhat.com/software/base-images) — a freely redistributable subset of RHEL 10, binary-compatible with RHEL 10 and supported by Red Hat on OpenShift and on **RHEL 9 or newer** hosts. A RHEL 8 host is not supported for a UBI 10 image — see the host compatibility note below.
- **Runtime**: OpenJDK 25 from the official Red Hat UBI 10 OpenJDK runtime image (`ubi10/openjdk-25-runtime`).
- **Architecture**: `linux/amd64` (x86_64), **x86-64-v3 or newer**. RHEL 10 raises the microarchitecture floor, so the host CPU must support AVX2, BMI2 and FMA — Intel Haswell (2013) and AMD Excavator (2015) onward. Every current cloud instance type clears this; a pre-2013 bare-metal host does not, and the container refuses to start rather than failing later.
- **Non-root execution**: Runs as UID `185` (the default `jboss` user from the UBI base image) — containers never run as root.

EDDI is delivered as an OCI-compliant Docker container image and runs on any platform that supports OCI containers, including:

| Platform                               | Support Level                                                                         |
| -------------------------------------- | ------------------------------------------------------------------------------------- |
| **Red Hat Enterprise Linux 10**        | ✅ Primary — UBI 10 base image                                                         |
| **Red Hat Enterprise Linux 9**         | ✅ Supported — mismatched majors, see the host compatibility note below               |
| **Red Hat Enterprise Linux 8**         | ❌ Not supported for a UBI 10 image — pin an EDDI release built on UBI 9              |
| **Red Hat OpenShift 4.12+**            | ✅ Supported — each stable release is submitted for Red Hat container certification   |
| **Docker** (any Linux, macOS, Windows) | ✅ Full support — standard OCI container                                              |
| **Kubernetes** (any distribution)      | ✅ Full support — standard OCI container                                              |
| **Podman**                             | ✅ Full support — OCI-compliant runtime                                               |

> **Note**: Because EDDI ships as a standard OCI container image built on Red Hat UBI 10, it runs on any host with a container runtime and an x86-64-v3 CPU — the image carries its own userspace. That is a statement about whether it *runs*, not about Red Hat support: for a supported RHEL deployment the host must also be RHEL 9 or newer, per the note below.

> **Running on a RHEL 9 host**: supported. Red Hat's [container compatibility matrix](https://access.redhat.com/support/policy/rhel-container-compatibility) lists a UBI 10 image on a RHEL 9 host as **Supported** — a container supplies its own userspace, so the host only has to be new enough. Because the majors do not match, the usual conditions for a mismatched pair apply: the workload must run unprivileged, must not interact directly with kernel-version-specific interfaces (`ioctl`, `/proc`, `/sys`, routing, iptables, nftables, eBPF), and the image's RHEL version must stay within its supported lifecycle. EDDI satisfies these — it runs as UID `185` and touches nothing below the JVM. One support consequence is worth planning for: Red Hat may ask that a reported issue be reproduced in a fully compatible configuration, meaning on a RHEL 10 host, before it is investigated. A RHEL 8 host is the one combination Red Hat marks unsupported for a UBI 10 image.

Every image CI builds is checked against Red Hat's container certification requirements with the [preflight tool](https://github.com/redhat-openshift-ecosystem/openshift-preflight): a dry-run on pull requests, and a check of the pushed image on `main` and on tags.

---

## Red Hat Ecosystem Catalog

EDDI has a container certification project with Red Hat Partner Connect. Every stable release is published to Red Hat's hosted certification registry and its preflight results are submitted to Red Hat (see [Automated Certification Workflow](#automated-certification-workflow)); once Red Hat accepts a submission, that version is served from `registry.connect.redhat.com` and shown in the [Red Hat Ecosystem Catalog](https://catalog.redhat.com/). The primary distribution is [Docker Hub](https://hub.docker.com/r/labsai/eddi):

🔗 **[hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)**

---

## Container Certification

The EDDI container image is submitted to Red Hat for container certification for use on OpenShift. The submission is automated by the [`redhat-certify.yml`](../.github/workflows/redhat-certify.yml) GitHub Actions workflow, which `ci.yml` calls on every stable release tag.

### Certification Compliance

| Requirement            | Implementation                                                                                 |
| ---------------------- | ---------------------------------------------------------------------------------------------- |
| **Base image**         | `registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24` (pinned by SHA256 digest)           |
| **Non-root execution** | Runs as UID `185` — the default `jboss` user                                                   |
| **Licenses**           | Auto-generated `/licenses` directory containing `THIRD-PARTY.txt` and downloaded license texts |
| **Required labels**    | `name`, `vendor`, `version`, `release`, `summary`, `description`                               |
| **OpenShift labels**   | `io.k8s.display-name`, `io.k8s.description`, `io.openshift.tags`                               |
| **Health check**       | Docker-native `HEALTHCHECK` on `/q/health/ready`                                               |
| **Security scanning**  | Trivy image scan in CI blocks push on OS-level CVEs                                            |

### Automated Certification Workflow

EDDI is distributed on **two registries**: Docker Hub (`labsai/eddi`, published by `ci.yml` when a release tag is pushed) and Red Hat's catalog (published by this workflow, per release, after the fact). The certification project uses Red Hat's **hosted registry**: the certified image lives in `quay.io/redhat-isv-containers/<project-id>` and Red Hat serves it to customers via `registry.connect.redhat.com`.

The workflow certifies the image that was **already released** — it never rebuilds. A rebuild would have a different digest, would not be covered by the release's cosign signature or SLSA attestation, and would put bytes in Red Hat's catalog that differ from what Docker Hub users pull. Instead it:

1. **Pull** — Pulls the released `docker.io/labsai/eddi:<version>` and records its registry digest
2. **Verify** — Checks the Red Hat labels and the `/licenses` directory inside the pulled image
3. **Publish** — Retags to `quay.io/redhat-isv-containers/<project-id>` as `<version>` and `<version>-<release>` (a retag reuses the manifest, so the hosted tags carry the *same digest* as the release — asserted after pushing) 
4. **Preflight** — Runs the [Red Hat preflight tool](https://github.com/redhat-openshift-ecosystem/openshift-preflight) against the hosted `<version>-<release>` coordinate
5. **Submit** — Submits the results to Red Hat Partner Connect for review (`submit: true`, the default)

`ci.yml`'s `redhat-publish` job runs this automatically for every stable release tag (`X.Y.Z`, no pre-release suffix) once the image has passed its smoke test, with `release: 1` and `submit: true`. To re-submit a version by hand, go to **Actions → Red Hat Certification Release → Run workflow** and provide:

- `version` — EDDI version (e.g., `6.4.0`) — must already be released on Docker Hub
- `release` — Incremental release number (`2`, `3`, … for a re-submission; the automatic run uses `1`)
- `submit` — Whether to submit results to Red Hat (`true`/`false`)

### Preflight Quality Gate

Every push to `main` or release tag that produces a Docker image is validated by a **preflight check** in CI (`preflight-push`). Pull requests that change code also run a preflight dry-run against the image CI built (`preflight-check`). This catches certification regressions before they reach production (e.g., missing labels, license issues, prohibited packages).

### Required GitHub Secrets

| Secret                     | Purpose                                                                                     |
| -------------------------- | ------------------------------------------------------------------------------------------- |
| `REDHAT_API_TOKEN`         | Pyxis API token from Red Hat Partner Connect                                                 |
| `REDHAT_CERT_PROJECT_ID`   | Certification project ID (also names the hosted repository)                                  |
| `REDHAT_REGISTRY_USERNAME` | The project's registry robot user — shown with the key on the project's **Registry key** page |
| `REDHAT_REGISTRY_KEY`      | The project's registry key (the robot account's password)                                    |
| `DOCKER_USERNAME`          | Docker Hub username (used by `ci.yml`, not by certification)                                 |
| `DOCKER_PASSWORD`          | Docker Hub password (used by `ci.yml`, not by certification)                                 |
| `QUAY_USERNAME`          | Quay.io robot account (optional, for Quay.io publishing) |
| `QUAY_PASSWORD`          | Quay.io password (optional)                              |

---

## License Automation

Third-party licenses are generated on-demand using the `license-gen` Maven profile:

```bash
./mvnw package -Plicense-gen -DskipTests
```

This generates:

| File                       | Contents                                          |
| -------------------------- | ------------------------------------------------- |
| `licenses/THIRD-PARTY.txt` | All runtime dependencies with their license names |
| `licenses/third-party/`    | Downloaded license text files for each dependency |
| `licenses/licenses.xml`    | Machine-readable license index                    |

The profile is **not activated during normal dev builds** to keep them fast. `ci.yml` activates it in the build that produces the Docker image. `redhat-certify.yml` never builds anything: it certifies the image `ci.yml` already released, `/licenses` included.

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

| Property            | Value                                                     |
| ------------------- | --------------------------------------------------------- |
| **Image**           | `docker.io/labsai/eddi`                                   |
| **Base**            | `registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24` |
| **Digest pinning**  | Base image pinned by SHA256 digest (OpenSSF Scorecard Pinned-Dependencies check) |
| **User**            | `185` (non-root)                                          |
| **Port**            | `7070`                                                    |
| **Health endpoint** | `GET /q/health/ready`                                     |
| **Java**            | OpenJDK 25 (Red Hat build)                                |
| **Framework**       | Quarkus (version pinned in `pom.xml`)                     |

### Quick Start

EDDI needs a database. This starts MongoDB on a private Docker network (no published port) and EDDI next to it, published on `127.0.0.1` only, so the unauthenticated instance is not reachable from other machines:

```bash
docker network create eddi
docker run -d --name mongodb --network eddi mongo:7.0.14
docker run -i --rm --network eddi -p 127.0.0.1:7070:7070 \
  -e MONGODB_CONNECTIONSTRING='mongodb://mongodb:27017/eddi?retryWrites=true&w=majority' \
  -e EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true \
  -e EDDI_MCP_ALLOW_UNAUTHENTICATED=true \
  -e EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true \
  labsai/eddi:6.4.0
```

Open `http://localhost:7070`. Clean up with `docker rm -f mongodb` and `docker network rm eddi`.

> The container runs in production launch mode, where `AuthStartupGuard` and `HighValueSurfaceGuard` refuse to boot while OIDC is disabled. Either enable OIDC (`QUARKUS_OIDC_TENANT_ENABLED=true` plus a configured realm) or pass the three opt-outs above — without one of the two, startup fails and the container never serves traffic. The opt-outs leave every endpoint, `/mcp` and `/secretstore` open to anyone who can reach the port, which is why the port above is published on loopback only. Never combine them with `-p 7070:7070` (all interfaces) or an OpenShift route.

For production deployments, enable OIDC rather than the unauthenticated opt-outs, point EDDI at an authenticated MongoDB and set a vault master key. `eddi-backend` is the bearer-only client the shipped Keycloak realm defines and the default of `quarkus.oidc.client-id`; access tokens must carry it as their audience:

```bash
docker run -d \
  -p 7070:7070 \
  -e MONGODB_CONNECTIONSTRING="$MONGODB_CONNECTIONSTRING" \
  -e EDDI_VAULT_MASTER_KEY="$EDDI_VAULT_MASTER_KEY" \
  -e QUARKUS_OIDC_TENANT_ENABLED=true \
  -e QUARKUS_OIDC_AUTH_SERVER_URL='https://keycloak.example.com/realms/eddi' \
  -e QUARKUS_OIDC_CLIENT_ID=eddi-backend \
  labsai/eddi:6.4.0
```

Supply the connection string (for example `mongodb://<user>:<password>@<host>:27017/eddi?authSource=admin`) and the master key from your secret store rather than typing them on the command line; on OpenShift, inject them from a `Secret` with `secretKeyRef`. See [Security](security.md) for the realm and roles and [Secrets Vault](secrets-vault.md) for the master key.

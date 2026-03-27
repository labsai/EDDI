# RedHat OpenShift

E.D.D.I is enterprise certified to run on RedHat OpenShift and is offered with support on the Red Hat Marketplace: [https://marketplace.redhat.com/en-us/products/labsai](https://marketplace.redhat.com/en-us/products/labsai)

## Container Certification

The EDDI container image is certified by Red Hat / IBM for use on OpenShift. Certification is automated via the `redhat-certify.yml` GitHub Actions workflow.

### What's Certified

- **Base image**: `registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24` (Red Hat UBI 9)
- **Non-root execution**: Runs as user `185` (not root)
- **Licenses**: Auto-generated `/licenses` directory containing `THIRD-PARTY.txt` and downloaded license texts for all runtime dependencies
- **Required labels**: `name`, `vendor`, `version`, `release`, `summary`, `description` plus OpenShift labels (`io.k8s.display-name`, `io.openshift.tags`)

### Automated Certification Workflow

The certification release process is fully automated:

1. **Build** — `mvnw clean package` builds the app and auto-generates license files via the `license-maven-plugin`
2. **Docker build** — Builds the image with Red Hat certification labels (parameterized via `--build-arg`)
3. **Push** — Pushes to Docker Hub (or Quay.io when configured)
4. **Preflight** — Runs the [Red Hat preflight tool](https://github.com/redhat-openshift-ecosystem/openshift-preflight) to validate certification requirements
5. **Submit** — Optionally submits results to Red Hat Partner Connect for review

To trigger a certification release, go to **Actions → Red Hat Certification Release → Run workflow** and provide:
- `version` — EDDI version (e.g., `6.0.0`)
- `release` — Incremental release number (e.g., `1`, `2`, `3`)
- `submit` — Whether to submit results to Red Hat (`true`/`false`)
- `registry` — Target registry (`docker.io` or `quay.io`)

### Required GitHub Secrets

| Secret | Purpose |
|---|---|
| `REDHAT_API_TOKEN` | Pyxis API token from Red Hat Partner Connect |
| `REDHAT_CERT_PROJECT_ID` | Certification project ID |
| `DOCKER_USERNAME` | Docker Hub username |
| `DOCKER_PASSWORD` | Docker Hub password |
| `QUAY_USERNAME` | Quay.io robot account (optional, for Quay.io publishing) |
| `QUAY_PASSWORD` | Quay.io password (optional) |

### License Automation

Licenses are automatically generated during every Maven build using the [MojoHaus license-maven-plugin](https://www.mojohaus.org/license-maven-plugin/):

- `licenses/THIRD-PARTY.txt` — Lists all runtime dependencies with their license names
- `licenses/third-party/` — Downloaded license text files for each dependency
- `licenses/licenses.xml` — Machine-readable license index

These files are regenerated every build and are **not committed to git** — they're always fresh and accurate in the Docker image.

### Preflight Quality Gate

Every pull request to `main` runs a **preflight dry-run** as part of CI. This catches certification regressions before they're merged (e.g., missing labels, license issues, prohibited packages).

## EDDI Operator

[![Docker Repository on Quay](https://quay.io/repository/labsai/eddi-operator/status)](https://quay.io/repository/labsai/eddi-operator)

### Usage

#### OpenShift Setup

**Prerequisites**

* OpenShift 4.3+ Deployment
* Block Storage (preferably with storage class)

**Installing the Operator from the RedHat Marketplace**

1. Head to the Operator section in the Admin Overview and go to the OperatorHub
2. Choose which version of the EDDI Operator to use (Marketplace or normal)
3. Click install and leave the defaults (All Namespaces, Update Channel alpha and Approval Strategy Automatic)
4. Click subscribe

**Using the operator**

After the installation of the operator, go to the installed Operators menu point and click on the first EDDI menu on top and create a new Instance. Below is a minimal CustomResource. The storageclass\_name has to be changed to the name of an existing StorageClass, the environment variable will be added as a label to the mongoDB deployment.

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

The operator will create a route automatically so you can access the EDDI admin panel. Per default the route will take the name of the CR. With the CR from above the route would look like this: `eddi-route-$NAMESPACE.apps.ocp.example.com` ($NAMESPACE will be the name of the project where the CR was created.)

> **Note:** The EDDI operator is being updated for v6 to support both MongoDB and PostgreSQL storage backends. Stay tuned for the updated operator release.

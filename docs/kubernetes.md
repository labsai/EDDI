# Kubernetes Deployment

EDDI runs natively on Kubernetes. This guide covers deployment options from a simple quickstart to production-grade configurations.

## Prerequisites

- **Kubernetes cluster** (1.26+) — minikube, kind, GKE, EKS, AKS, or any conformant cluster
- **kubectl** configured to access your cluster
- **Helm 3** (optional, for Helm chart deployment)

## Quick Start (5 minutes)

### Option A: Single-file manifest

Deploy EDDI + MongoDB with one command:

```bash
kubectl apply -f https://raw.githubusercontent.com/labsai/EDDI/main/k8s/quickstart.yaml
```

The EDDI pod now waits in `ContainerCreating` — it mounts the `eddi-secrets`
Secret, which no manifest creates. That is deliberate: a Secret shipped in the
manifest would be re-applied on every `kubectl apply` and would overwrite your
vault master key, making everything already encrypted with it undecryptable.
Generate and store the key, and the pod starts by itself:

```bash
set -euo pipefail

# EDDI reads its secrets from a mounted properties FILE rather than environment
# variables, so the Secret holds exactly one key:
# "application-secrets.properties".
#
# mktemp gives an unpredictable name created 0600, so it cannot be pre-created
# or symlinked by another local user.
secrets_file=$(mktemp "${TMPDIR:-/tmp}/eddi-secrets.XXXXXX")
trap 'shred -u "$secrets_file" 2>/dev/null || rm -f "$secrets_file"' EXIT

key=$(openssl rand -base64 24)
[ -n "$key" ] || { echo "vault key generation failed" >&2; exit 1; }
printf 'eddi.vault.master-key=%s\n' "$key" > "$secrets_file"

# The key name must be application-secrets.properties — that is the filename the
# Deployment mounts. Passing the temp path bare would name the key after it.
#
# --dry-run=client | kubectl apply makes this idempotent: re-running it updates
# the Secret instead of failing with AlreadyExists.
kubectl create secret generic eddi-secrets \
  --namespace=eddi \
  --from-file=application-secrets.properties="$secrets_file" \
  --dry-run=client -o yaml | kubectl apply -f -

# Only needed if EDDI was already running with a different key
# kubectl rollout restart deployment/eddi -n eddi

# Access EDDI
kubectl port-forward svc/eddi 7070:7070 -n eddi
```

Open [http://localhost:7070](http://localhost:7070).

### Option B: Using the helper script

The Secret must exist **before** the first apply — `kubectl apply -k` never
creates or reconciles it, so the pod cannot mount it otherwise:

```bash
# Clone the repo
git clone https://github.com/labsai/EDDI.git && cd EDDI

# Generate vault key + create K8s secret
bash k8s/create-secrets.sh

# Deploy with MongoDB
kubectl apply -k k8s/overlays/mongodb/
```

PowerShell:
```powershell
.\k8s\create-secrets.ps1
kubectl apply -k k8s\overlays\mongodb\
```

The script generates a **new** master key, so it stops if `eddi-secrets` already
exists rather than replacing what is there — re-running it would leave every
secret encrypted under the old key unrecoverable. Pass `--force` (`-Force` in
PowerShell) only when you mean to rotate.

### Option C: Helm

```bash
helm install eddi ./helm/eddi \
  --set eddi.vaultMasterKey="$(openssl rand -base64 24)" \
  --namespace eddi --create-namespace
```

## Deployment Options

EDDI provides modular overlays (Kustomize) and Helm values for different deployment profiles:

### Database Backend

| Backend | Kustomize | Helm |
|---|---|---|
| **MongoDB** (default) | `kubectl apply -k k8s/overlays/mongodb/` | `--set mongodb.enabled=true` |
| **PostgreSQL** | `kubectl apply -k k8s/overlays/postgres/` | `--set postgres.enabled=true --set mongodb.enabled=false --set eddi.datastoreType=postgres` |

### Optional Components

Everything under `k8s/overlays/` except `mongodb/` and `postgres/` is a kustomize
**Component**. Components have no resource set of their own — `kubectl apply -k`
on one does not work by design — and are composed with a database overlay.

| Component | Description | Helm Values |
|---|---|---|
| **Keycloak Auth** | OIDC authentication | `--set keycloak.enabled=true --set eddi.oidc.enabled=true --set eddi.oidc.publicUrl=http://localhost:8080 --set keycloak.adminPassword=…` |
| **NATS JetStream** | Durable, ordered messaging | ⚠️ needs an image built with `-Dquarkus.profile=nats` — see [Durable Messaging](#durable-messaging-production) |
| **Monitoring** | Prometheus + Grafana | — (Kustomize only: `k8s/overlays/monitoring/`) |
| **Ingress** | External HTTPS access | `--set ingress.enabled=true --set ingress.hosts[0].host=eddi.example.com` |
| **Production** | PDB, NetworkPolicy | `--set podDisruptionBudget.enabled=true --set networkPolicy.enabled=true` |

> **Manager UI**: there is nothing to enable. EDDI serves it from its own
> Service at `/manage`.

### Composing Kustomize Overlays

Kustomize takes **one directory** as input. Combine the pieces with a
`kustomization.yaml` that lists the standalone overlay under `resources:` and the
components under `components:`:

```yaml
# my-deployment/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: eddi
resources:
  - ../k8s/overlays/mongodb        # standalone: base + MongoDB
components:
  - ../k8s/overlays/auth           # Keycloak, realm import, OIDC ConfigMap keys
  - ../k8s/overlays/monitoring     # Prometheus + Grafana
```

Two rules decide whether this works:

- `resources:` may name a **directory** outside your root — kustomize builds it as
  its own kustomization root — but never a loose **file** outside your root. That
  is a hard `file ... is not in or below ...` error.
- A component's patches are applied into **your** resource set, so they reach
  `eddi-config` and the `eddi` Deployment. The same patches inside a
  `kind: Kustomization` referenced under `resources:` are built in isolation
  first, match nothing, and are dropped — with no error and exit code 0.

Ready-made examples are provided in `k8s/examples/`:

```bash
# Create the vault Secret once, before the first apply
bash k8s/create-secrets.sh

# MongoDB + Keycloak auth + Monitoring
kubectl apply -k k8s/examples/mongodb-full/

# PostgreSQL + Production hardening (PDB, NetworkPolicy, resource limits)
kubectl apply -k k8s/examples/postgres-ha/
```

## Architecture on Kubernetes

```
┌──────────────────────────────────────────────┐
│                  Ingress                      │
│            (nginx / traefik)                  │
└──────────────┬───────────────────────────────┘
               │
     ┌─────────▼──────────┐
     │    EDDI Service     │
     │   (ClusterIP:7070)  │
     └─────────┬──────────┘
               │
    ┌──────────▼──────────┐    ┌─────────────┐
    │  EDDI Deployment     │───▶│  MongoDB    │
    │  (labsai/eddi:6.3.0) │    │ StatefulSet │
    │                      │    └─────────────┘
    │  replicas: 1         │    ┌─────────────┐
    │  (single-writer)     │───▶│ PostgreSQL  │
    └──────────────────────┘    │ StatefulSet │
               │                └─────────────┘
    ┌──────────▼──────────┐
    │   NATS JetStream     │  (optional, durable ordering)
    │   StatefulSet        │
    └──────────────────────┘
```

## Security

### Vault Master Key

The vault master key encrypts all stored API keys and secrets. **If you lose this key, encrypted secrets are unrecoverable.**

Three ways to manage it:

1. **Helper script** (recommended for initial setup):
   ```bash
   bash k8s/create-secrets.sh
   ```

2. **Manual kubectl** — the Secret holds one key, `application-secrets.properties`,
   which the Deployment mounts as a file and loads via `QUARKUS_CONFIG_LOCATIONS`:
   ```bash
   set -euo pipefail

   # mktemp gives an unpredictable name created 0600, so another local user
   # cannot pre-create the path or point it at a symlink.
   secrets_file=$(mktemp "${TMPDIR:-/tmp}/eddi-secrets.XXXXXX")
   trap 'shred -u "$secrets_file" 2>/dev/null || rm -f "$secrets_file"' EXIT

   # Fail closed: an empty key would create a Secret that silently leaves the
   # vault inert and secrets in plaintext.
   key=$(openssl rand -base64 24)
   [ -n "$key" ] || { echo "vault key generation failed" >&2; exit 1; }
   printf 'eddi.vault.master-key=%s\n' "$key" > "$secrets_file"

   # The Secret key must be named application-secrets.properties — that is the
   # filename the Deployment mounts. A bare temp path would name it otherwise.
   #
   # --dry-run=client | kubectl apply makes this idempotent: re-running it
   # updates the Secret instead of failing with AlreadyExists.
   kubectl create secret generic eddi-secrets \
     --namespace=eddi \
     --from-file=application-secrets.properties="$secrets_file" \
     --dry-run=client -o yaml | kubectl apply -f -
   ```

3. **External secrets** (production): Use [External Secrets Operator](https://external-secrets.io/) to sync from AWS Secrets Manager, HashiCorp Vault, Azure Key Vault, etc.

> **No manifest ever writes this Secret.** `k8s/base/eddi-secret.yaml.example` is
> a commented template that kustomize does not include, and `k8s/quickstart.yaml`
> ships no Secret at all. A reconciled Secret would replace a live vault master
> key with a placeholder on the next apply, and every secret encrypted under the
> old key would be permanently undecryptable.

### Authentication (Keycloak)

Both delivery paths ship the `eddi` realm and import it into Keycloak on **first
boot** — `k8s/overlays/auth/eddi-realm.json` for Kustomize,
`helm/eddi/files/eddi-realm.json` for Helm. Both are cluster-calibrated copies of
`keycloak/eddi-realm.json`, which is calibrated for docker-compose: it allows
redirects to `localhost` only and names a login theme that only the compose file
mounts.

Realm import is **one-shot**. It seeds an empty database and is skipped once the
realm exists, so later edits to the JSON do not reach a running Keycloak — change
those in the admin console. Keycloak keeps that database on a PVC, so a restart
no longer wipes it.

Four settings must all name the URL the **browser** uses for Keycloak — none of
them can be derived, because the in-cluster Service name does not resolve in a
browser and the Ingress fronts only EDDI:

| Setting | Kustomize | Helm |
|---|---|---|
| Keycloak's own hostname (token issuer) | `KC_HOSTNAME` in `keycloak-statefulset.yaml` | `eddi.oidc.publicUrl` |
| URL handed to the Manager SPA | `EDDI_KEYCLOAK_PUBLIC_URL` patch | `eddi.oidc.publicUrl` |
| Issuer EDDI validates tokens against | `QUARKUS_OIDC_TOKEN_ISSUER` patch | derived from `eddi.oidc.publicUrl` |
| Realm `redirectUris` / `webOrigins` | edit `eddi-realm.json` | `keycloak.publicOrigin` |

The shipped defaults cover `kubectl port-forward svc/keycloak 8080:8080`. Behind
an Ingress, a realm that does not list your host answers
`Invalid parameter: redirect_uri` and login cannot complete.

#### Upgrading an existing Keycloak install

> ⚠️ **Kustomize: delete the old Deployment before applying this version.**
>
> ```bash
> kubectl delete deployment keycloak -n eddi
> ```
>
> Keycloak used to be a `Deployment` named `keycloak`; it is now a `StatefulSet`
> under the **same name and the same pod labels**. `kubectl apply -k` never
> prunes, so on an existing install the old Deployment's ReplicaSet keeps
> running and **both** pods match the one `keycloak` Service selector — one with
> the imported `eddi` realm, one without. Requests round-robin between them, so
> `/realms/eddi` 404s on roughly half of them and logins fail intermittently,
> with nothing in `kubectl get` to explain it. Run the delete before the first
> apply, or immediately after if you have already applied.
>
> Helm needs no manual step: `helm upgrade` removes resources that left the
> release. The Deployment→StatefulSet change still replaces the pod, and because
> the old Deployment had no volume there is no Keycloak state to carry over —
> the new pod imports the realm into an empty database, and anything configured
> by hand in the old admin console is gone. Export it first if you need it.

### Pod Security

EDDI runs as non-root user (UID 185) and is compatible with `restricted` Pod Security Standards:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 185
  runAsGroup: 185
```

### Network Policy

The production overlay includes a `NetworkPolicy` that restricts EDDI to:
- **Ingress**: HTTP port 7070 from within the namespace + Ingress controllers
- **Egress**: Database (MongoDB/PG), NATS, Keycloak, DNS, and external HTTPS (port 443 for LLM APIs)

## Scaling

### Single Replica (default)

Default configuration uses in-memory messaging — suitable for development and low-traffic deployments.

### Durable Messaging (production)

EDDI runs at **exactly one replica**. It serialises the turns of a conversation with
a JVM-local lock, so a second replica silently drops turns — the Helm chart refuses
to render with `eddi.replicas` above 1 or `autoscaling.enabled=true`, and every
shipped manifest pins `replicas: 1`. NATS JetStream is a durable ordering and
dead-lettering primitive, not a scale-out enabler: the Callable still executes in
the JVM that published it. Scale **vertically** via `eddi.resources`.

> ⚠️ **NATS needs a purpose-built image.** `NatsConversationCoordinator` is gated
> on `@IfBuildProfile("nats")` — a *build-time* switch — and the published
> `labsai/eddi` image is built without it. No Java code reads
> `eddi.messaging.type` at runtime either, so setting it does not swap the
> coordinator: you get a JetStream StatefulSet with a PVC that EDDI never
> connects to, and in-memory queues anyway. The Helm chart now refuses to render
> `eddi.messagingType` other than `in-memory` unless you also set
> `nats.buildProfileImage=true` to confirm you built the image yourself with
> `-Dquarkus.profile=nats`; the Kustomize component carries the same warning.

**Kustomize** — production hardening with PostgreSQL (still one replica):
```bash
kubectl apply -k k8s/examples/postgres-ha/
```

Add NATS on top only with a `-Dquarkus.profile=nats` image, by listing
`- ../../overlays/nats` under that example's `components:`.

**Helm** — with an image you built with `-Dquarkus.profile=nats`:
```bash
helm install eddi ./helm/eddi \
  --set eddi.image.repository=your-registry/eddi-nats \
  --set nats.enabled=true \
  --set nats.buildProfileImage=true \
  --set eddi.messagingType=nats \
  --namespace eddi --create-namespace
```

## Monitoring

EDDI exposes Prometheus metrics at `/q/metrics`. The EDDI Deployment includes Prometheus scrape annotations by default:

```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "7070"
  prometheus.io/path: "/q/metrics"
```

Deploy the monitoring stack with Kustomize. The Helm chart ships **no**
Prometheus or Grafana templates and no longer offers `monitoring.*` values — they
used to exist and render nothing, which read as success:

```bash
# Kustomize (with MongoDB + Auth + Monitoring)
kubectl apply -k k8s/examples/mongodb-full/

# Or list `- ../../overlays/monitoring` under `components:` in your own
# kustomization (it is a Component; `kubectl apply -k` on it directly fails)

# Access Grafana — the Prometheus datasource is provisioned for you
kubectl port-forward svc/grafana 3000:3000 -n eddi
# Open http://localhost:3000 (admin/admin)
```

## Health Checks

EDDI provides three probe endpoints:

| Endpoint | Probe Type | Purpose |
|---|---|---|
| `/q/health/live` | Liveness | Process is alive |
| `/q/health/ready` | Readiness + Startup | DB connected, ready for traffic |
| `/q/metrics` | — | Prometheus metrics |

## File Structure

```
k8s/
├── base/                    # Core EDDI manifests
├── overlays/
│   ├── mongodb/             # MongoDB backend (standalone)
│   ├── postgres/            # PostgreSQL backend (standalone)
│   ├── nats/                # NATS JetStream (component)
│   ├── auth/                # Keycloak + realm import (component)
│   ├── monitoring/          # Prometheus + Grafana (component)
│   ├── ingress/             # Ingress resource (component)
│   └── production/          # PDB, NetworkPolicy, resource limits (component;
│                            #   eddi-hpa.yaml is an intentionally disabled template)
├── examples/
│   ├── mongodb-full/        # MongoDB + Auth + Monitoring
│   └── postgres-ha/         # PostgreSQL + Production hardening
├── create-secrets.sh        # Vault key generator (bash)
├── create-secrets.ps1       # Vault key generator (PowerShell)
└── quickstart.yaml          # All-in-one manifest

helm/
└── eddi/                    # Helm chart
    ├── Chart.yaml
    ├── values.yaml
    ├── files/               # eddi-realm.json, imported by Keycloak on first boot
    └── templates/
```

> **Note**: Overlays marked **(standalone)** include the base and can be applied directly with `kubectl apply -k`. Overlays marked **(component)** are `kind: Component` — they have no resource set of their own, so `kubectl apply -k` on one fails by design; list them under `components:` in a kustomization that includes a standalone overlay. See [Composing Kustomize Overlays](#composing-kustomize-overlays).

> **Note**: `k8s/base/eddi-secret.yaml.example` is a template, not a resource. The
> `eddi-secrets` Secret is created out-of-band (`bash k8s/create-secrets.sh`) so
> that no re-apply can overwrite a live vault master key.

## Troubleshooting

### EDDI pod stuck in ContainerCreating

This is the most likely first-run symptom. `kubectl describe pod` shows:

```
MountVolume.SetUp failed for volume "secrets" :
  secret "eddi-secrets" not found
```

No shipped manifest creates `eddi-secrets` — a reconciled Secret would overwrite
a live vault master key on the next `kubectl apply`. Create it out-of-band and
the pod starts on its own, with no restart needed:

```bash
bash k8s/create-secrets.sh            # PowerShell: .\k8s\create-secrets.ps1
kubectl get pods -n eddi -w
```

If `create-secrets` refuses because `eddi-secrets` already exists, that is the
guard against replacing a live key — the Secret is there and the mount failure
is something else (wrong namespace, or the key inside it is not named
`application-secrets.properties`). Check with
`kubectl get secret eddi-secrets -n eddi -o jsonpath='{.data}'`.

### EDDI pod stuck in CrashLoopBackOff

Check if the database is reachable:
```bash
kubectl logs -n eddi deployment/eddi
kubectl get pods -n eddi
```

Common causes:
- MongoDB/PostgreSQL not yet ready (wait for StatefulSet pod)
- Incorrect connection string in ConfigMap
- Volume claims pending (check `kubectl get pvc -n eddi`)

### EDDI starts but readiness probe fails

Check the health endpoint:
```bash
kubectl exec -n eddi deployment/eddi -- curl -s localhost:7070/q/health/ready
```

### Vault key issues

On a fresh install a missing key can no longer produce a running pod — the
Deployment mounts `eddi-secrets` and the pod stays in `ContainerCreating` until
the Secret exists (see above). "vault master key not set" therefore means the
Secret exists but its `application-secrets.properties` payload has an empty or
absent `eddi.vault.master-key` — for example a Secret created by hand from the
old `k8s/base/eddi-secret.yaml` template. Replace it and restart:

```bash
bash k8s/create-secrets.sh --force    # PowerShell: .\k8s\create-secrets.ps1 -Force
kubectl rollout restart deployment/eddi -n eddi
```

⚠️ `--force` installs a **new** key. Anything already encrypted under the old
one becomes unrecoverable, so use it only when there is nothing to lose — which
is exactly the case when the key was empty.

### PVC stuck in Pending

If PVCs aren't provisioning, check your StorageClass:
```bash
kubectl get sc                    # List available StorageClasses
kubectl get pvc -n eddi           # Check PVC status
kubectl describe pvc -n eddi      # See events / errors
```

If your cluster doesn't have a default StorageClass, uncomment `storageClassName` in the StatefulSet manifests.

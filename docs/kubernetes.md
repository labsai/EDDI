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

Then generate and store a vault master key:

```bash
# Generate the secret. EDDI reads its secrets from a mounted properties FILE
# rather than environment variables, so the Secret holds exactly one key:
# "application-secrets.properties".
kubectl delete secret eddi-secrets -n eddi --ignore-not-found
# umask first: the file holds the master key, and the default mode is
# world-readable on most images. The trap removes it even if kubectl fails.
( umask 077
  trap 'shred -u /tmp/application-secrets.properties 2>/dev/null' EXIT
  printf 'eddi.vault.master-key=%s\n' "$(openssl rand -base64 24)" \
    > /tmp/application-secrets.properties
  kubectl create secret generic eddi-secrets \
    --namespace=eddi \
    --from-file=/tmp/application-secrets.properties )

# Restart EDDI to pick up the key
kubectl rollout restart deployment/eddi -n eddi

# Access EDDI
kubectl port-forward svc/eddi 7070:7070 -n eddi
```

Open [http://localhost:7070](http://localhost:7070).

### Option B: Using the helper script

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

The component overlays (auth, nats, monitoring, etc.) are designed to be **composed** with a database overlay. They do not include the base EDDI manifests on their own.

| Component | Description | Helm Values |
|---|---|---|
| **Keycloak Auth** | OIDC authentication | `--set keycloak.enabled=true --set eddi.oidc.enabled=true` |
| **NATS JetStream** | Durable, ordered messaging | `--set nats.enabled=true --set eddi.messagingType=nats` |
| **Manager UI** | Configuration dashboard | `--set manager.enabled=true` |
| **Monitoring** | Prometheus + Grafana | — (Kustomize only: `k8s/overlays/monitoring/`) |
| **Ingress** | External HTTPS access | `--set ingress.enabled=true --set ingress.hosts[0].host=eddi.example.com` |
| **Production** | PDB, NetworkPolicy | `--set podDisruptionBudget.enabled=true --set networkPolicy.enabled=true` |

### Composing Kustomize Overlays

Kustomize takes **one directory** as input. To combine components, create a `kustomization.yaml` that references multiple overlays:

```yaml
# my-deployment/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: eddi
resources:
  - ../k8s/overlays/mongodb                    # Base + MongoDB
  - ../k8s/overlays/auth/keycloak-deployment.yaml  # Keycloak
  - ../k8s/overlays/manager/manager-deployment.yaml # Manager UI
patches:
  - target: { kind: ConfigMap, name: eddi-config }
    patch: |
      - op: replace
        path: /data/QUARKUS_OIDC_TENANT_ENABLED
        value: "true"
```

Ready-made examples are provided in `k8s/examples/`:

```bash
# MongoDB + Auth + Monitoring + Manager
kubectl apply -k k8s/examples/mongodb-full/

# PostgreSQL + NATS + Production hardening
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
    │  (labsai/eddi:latest) │    │ StatefulSet │
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
   printf 'eddi.vault.master-key=%s\n' "$(openssl rand -base64 24)" \
     > /tmp/application-secrets.properties
   kubectl create secret generic eddi-secrets \
     --namespace=eddi \
     --from-file=/tmp/application-secrets.properties
   shred -u /tmp/application-secrets.properties
   ```

3. **External secrets** (production): Use [External Secrets Operator](https://external-secrets.io/) to sync from AWS Secrets Manager, HashiCorp Vault, Azure Key Vault, etc.

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

**Kustomize:**
```bash
# Use the ready-made HA example (PostgreSQL + NATS, still one replica)
kubectl apply -k k8s/examples/postgres-ha/
```

**Helm:**
```bash
helm install eddi ./helm/eddi \
  --set nats.enabled=true \
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

Deploy the monitoring stack with Kustomize. The Helm chart does **not** ship
Prometheus or Grafana templates yet, so `monitoring.*` values render nothing:

```bash
# Kustomize (with MongoDB + Auth + Monitoring)
kubectl apply -k k8s/examples/mongodb-full/

# Or the monitoring component on its own overlay: k8s/overlays/monitoring/

# Access Grafana
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
│   ├── auth/                # Keycloak authentication (component)
│   ├── monitoring/          # Prometheus + Grafana (component)
│   ├── manager/             # Manager UI (component)
│   ├── ingress/             # Ingress resource (component)
│   └── production/          # HPA, PDB, NetworkPolicy (component)
├── examples/
│   ├── mongodb-full/        # MongoDB + Auth + Monitoring + Manager
│   └── postgres-ha/         # PostgreSQL + NATS + Production
├── create-secrets.sh        # Vault key generator (bash)
├── create-secrets.ps1       # Vault key generator (PowerShell)
└── quickstart.yaml          # All-in-one manifest

helm/
└── eddi/                    # Helm chart
    ├── Chart.yaml
    ├── values.yaml
    └── templates/
```

> **Note**: Overlays marked **(standalone)** include the base and can be applied directly with `kubectl apply -k`. Overlays marked **(component)** must be composed with a standalone overlay — see [Composing Kustomize Overlays](#composing-kustomize-overlays).

## Troubleshooting

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

If you see "vault master key not set" warnings, create the secret:
```bash
bash k8s/create-secrets.sh
kubectl rollout restart deployment/eddi -n eddi
```

### PVC stuck in Pending

If PVCs aren't provisioning, check your StorageClass:
```bash
kubectl get sc                    # List available StorageClasses
kubectl get pvc -n eddi           # Check PVC status
kubectl describe pvc -n eddi      # See events / errors
```

If your cluster doesn't have a default StorageClass, uncomment `storageClassName` in the StatefulSet manifests.

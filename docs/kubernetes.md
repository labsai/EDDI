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
# Generate the secret
kubectl create secret generic eddi-secrets \
  --namespace=eddi \
  --from-literal=EDDI_VAULT_MASTER_KEY="$(openssl rand -base64 24)" \
  --dry-run=client -o yaml | kubectl apply -f -

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
| **NATS JetStream** | Durable messaging for multi-replica | `--set nats.enabled=true --set eddi.messagingType=nats` |
| **Manager UI** | Configuration dashboard | `--set manager.enabled=true` |
| **Monitoring** | Prometheus + Grafana | `--set monitoring.prometheus.enabled=true` |
| **Ingress** | External HTTPS access | `--set ingress.enabled=true --set ingress.hosts[0].host=eddi.example.com` |
| **Production** | HPA, PDB, NetworkPolicy | `--set autoscaling.enabled=true --set podDisruptionBudget.enabled=true` |

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
    │  (labsai/eddi:6)     │    │ StatefulSet │
    │                      │    └─────────────┘
    │  replicas: 1-10      │    ┌─────────────┐
    │  (HPA auto-scales)   │───▶│ PostgreSQL  │
    └──────────────────────┘    │ StatefulSet │
               │                └─────────────┘
    ┌──────────▼──────────┐
    │   NATS JetStream     │  (optional, for multi-replica)
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

2. **Manual kubectl**:
   ```bash
   kubectl create secret generic eddi-secrets \
     --namespace=eddi \
     --from-literal=EDDI_VAULT_MASTER_KEY="$(openssl rand -base64 24)"
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

### Multi-Replica (production)

For horizontal scaling, enable NATS JetStream for durable message ordering:

**Kustomize:**
```bash
# Use the ready-made HA example
kubectl apply -k k8s/examples/postgres-ha/
```

**Helm:**
```bash
helm install eddi ./helm/eddi \
  --set eddi.replicas=2 \
  --set nats.enabled=true \
  --set eddi.messagingType=nats \
  --set autoscaling.enabled=true \
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

Deploy the monitoring stack using the full example or Helm:

```bash
# Kustomize (with MongoDB + Auth + Monitoring)
kubectl apply -k k8s/examples/mongodb-full/

# Helm
helm install eddi ./helm/eddi \
  --set monitoring.prometheus.enabled=true \
  --set monitoring.grafana.enabled=true

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

# Release Signing & Verification

> **Audience:** Users, operators, and security auditors who need to verify the integrity of EDDI releases.

## What Is Signed?

EDDI's primary release artifacts are **Docker images** published to [Docker Hub: `labsai/eddi`](https://hub.docker.com/r/labsai/eddi). Starting with v6.0.0, every image pushed by the CI/CD pipeline is **cryptographically signed** using [Sigstore cosign](https://github.com/sigstore/cosign) with keyless OIDC signing.

This includes all images pushed after signing was enabled:
- Every build pushed from `main` (e.g., `labsai/eddi:6.0.0-b42`)
- Every release candidate (e.g., `labsai/eddi:6.0.0-RC2`)
- Every general availability release (e.g., `labsai/eddi:6.0.0`)
- The `latest` tag (updated on release tag pushes)

> **Note:** Images published before v6.0.0 are not signed. Signature verification only applies to images built after this feature was enabled.

---

## How Signing Works

EDDI uses **keyless signing** — there are no long-lived private keys to manage or protect:

1. The GitHub Actions CI pipeline builds and pushes the Docker image
2. GitHub provides an **OIDC identity token** proving the workflow identity
3. **Fulcio** (Sigstore's certificate authority) issues a short-lived certificate based on that identity
4. **cosign** signs the image using the ephemeral certificate
5. The signature is stored as an **OCI artifact** alongside the image in Docker Hub
6. The signing event is recorded in the **Rekor** public transparency log

```
GitHub Actions OIDC Token
        │
        ▼
   ┌─────────┐     ┌────────────┐
   │  Fulcio  │────▶│  Ephemeral │
   │   (CA)   │     │   Cert     │
   └─────────┘     └─────┬──────┘
                         │
                         ▼
                  ┌──────────────┐
                  │  cosign sign │──▶ Signature stored in Docker Hub
                  └──────┬───────┘
                         │
                         ▼
                  ┌──────────────┐
                  │    Rekor     │──▶ Transparency log entry
                  │  (public)    │
                  └──────────────┘
```

### Security Properties

| Property | How it's achieved |
|---|---|
| **No private key exposure** | Ephemeral keys exist only in runner memory for milliseconds — never stored anywhere |
| **Tamper evidence** | Signatures are recorded in the immutable Rekor transparency log |
| **Identity binding** | The signature proves the image was built by the `labsai/EDDI` GitHub Actions workflow |
| **Private key not on distribution site** | Docker Hub only stores the signature and public certificate, never a private key |

---

## How to Verify

### Prerequisites

Install cosign:

```bash
# macOS
brew install cosign

# Linux (download binary)
curl -LO https://github.com/sigstore/cosign/releases/latest/download/cosign-linux-amd64
chmod +x cosign-linux-amd64
sudo mv cosign-linux-amd64 /usr/local/bin/cosign

# Or download from https://github.com/sigstore/cosign/releases
```

### Verify an Image

```bash
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "^https://github\.com/labsai/EDDI/\.github/workflows/ci\.yml@refs/(heads/main|tags/.+)$" \
  labsai/eddi:6.0.0
```

Replace `6.0.0` with any tag you want to verify (`latest`, `6.0.0-RC2`, `6.0.0-b42`, etc.).

**Successful output** will show the verified certificate chain and Rekor log entry:

```
Verification for docker.io/labsai/eddi:6.0.0 --
The following checks were performed on each of these signatures:
  - The cosign claims were validated
  - Existence of the claims in the transparency log was verified offline
  - The code-signing certificate was verified using trusted certificate authority
```

### Verify by Digest (Recommended)

For maximum security, verify by image digest instead of tag:

```bash
# Get the digest
docker pull labsai/eddi:6.0.0
DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' labsai/eddi:6.0.0)

# Verify the digest
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "^https://github\.com/labsai/EDDI/\.github/workflows/ci\.yml@refs/(heads/main|tags/.+)$" \
  $DIGEST
```

### Inspect the Transparency Log

Every signature is publicly recorded in [Rekor](https://rekor.sigstore.dev/). When you run `cosign verify`, the output includes the Rekor log index. You can also inspect the full transparency log entry for a signed image:

```bash
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "^https://github\.com/labsai/EDDI/\.github/workflows/ci\.yml@refs/(heads/main|tags/.+)$" \
  --output-text \
  labsai/eddi:6.0.0
```

This outputs the full certificate chain and Rekor log entry as JSON.

---

## Git Tag Signing

For version tags in the Git repository (e.g., `v6.0.0`, `v6.0.0-RC2`), maintainers sign tags using GPG or SSH keys:

```bash
# Create a signed tag
git tag -s v6.0.0 -m "Release 6.0.0"
git push origin v6.0.0

# Verify a signed tag
git tag -v v6.0.0
```

> **Note:** The primary release integrity guarantee is provided by the Docker image signing described above. Git tag signing provides an additional layer of assurance that the tag was created by an authorized maintainer.

---

## Related Documentation

- [Release & Versioning Strategy](release-versioning.md) — Docker tags, branching model, how to release
- [Security Policy](../SECURITY.md) — Vulnerability reporting, scope, security practices
- [CI/CD Pipeline](../.github/workflows/ci.yml) — The signing implementation

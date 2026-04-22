# Security Policy

## Supported Versions

| Version | Supported              |
| ------- | ---------------------- |
| 6.0.x   | ✅ Active development  |
| 5.6.x   | 🔒 Security fixes only |
| < 5.6   | ❌ End of life         |

## Reporting a Vulnerability

**Please do NOT report security vulnerabilities through public GitHub issues.**

Instead, please report them privately via email:

📧 **security@labs.ai**

### What to Include

- Description of the vulnerability
- Steps to reproduce the issue
- Potential impact assessment
- Any suggested fix (optional but appreciated)

### Response Timeline

| Stage              | Timeline                                                             |
| ------------------ | -------------------------------------------------------------------- |
| **Acknowledgment** | Within 48 hours                                                      |
| **Initial triage** | Within 7 days                                                        |
| **Status update**  | Every 14 days until resolved                                         |
| **Fix release**    | Depends on severity (critical: ASAP, high: 30 days, medium: 90 days) |

## Disclosure Policy

We follow **coordinated disclosure**:

1. You report the vulnerability privately
2. We acknowledge and begin working on a fix
3. We release the fix and publish a security advisory
4. You may publish your findings after the fix is released

We will credit you in the security advisory unless you prefer to remain anonymous.

## Scope

### In Scope

- EDDI core application (`labsai/eddi` Docker image)
- MCP server implementation
- REST API endpoints
- Authentication and authorization mechanisms
- Official Docker images on Docker Hub
- Secrets vault implementation
- SSRF protection mechanisms

### Out of Scope

- Third-party LLM API vulnerabilities (OpenAI, Anthropic, etc.)
- User configuration errors (e.g., running without authentication)
- Vulnerabilities in dependencies (report upstream; we monitor via Dependaagent)
- Social engineering attacks
- Denial of service via expected API usage

## Security Best Practices for Contributors

- Never commit API keys, tokens, or passwords
- Use Vault references (`${vault:key-name}`) for sensitive configuration
- All external URL access must use `UrlValidationUtils.validateUrl()`
- No `eval()`, `ScriptEngine`, or dynamic code execution
- No `@JsonTypeInfo(use=Id.CLASS)` for untrusted payloads
- Read the [Security documentation](docs/security.md) before contributing security-sensitive code

## Release Integrity

All Docker image releases are **cryptographically signed** using [Sigstore cosign](https://github.com/sigstore/cosign) with keyless OIDC signing. Users can verify any image was built by the official CI pipeline:

```bash
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "^https://github\.com/labsai/EDDI/\.github/workflows/ci\.yml@refs/(heads/main|tags/.+)$" \
  labsai/eddi:latest
```

For full details, see [Release Signing & Verification](docs/release-signing.md).

## Security-Related Documentation

- [Security Architecture](docs/security.md) — SSRF protection, sandboxed evaluation, tool hardening
- [Secrets Vault](docs/secrets-vault.md) — Secure secret storage and retrieval
- [Project Philosophy — Pillar 4](docs/project-philosophy.md) — Security as Architecture

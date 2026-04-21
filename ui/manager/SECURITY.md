# Security Policy

## Supported Versions

| Version | Supported              |
| ------- | ---------------------- |
| 6.0.x   | ✅ Active development  |
| < 6.0   | ❌ End of life         |

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

- EDDI Manager frontend application
- Authentication flows (Keycloak integration)
- API communication layer (`ApiClient`)
- Any XSS, CSRF, or injection vectors in the UI

### Out of Scope

- EDDI backend vulnerabilities (report to [EDDI SECURITY.md](https://github.com/labsai/EDDI/blob/main/SECURITY.md))
- Third-party LLM API vulnerabilities (OpenAI, Anthropic, etc.)
- User configuration errors
- Vulnerabilities in dependencies (report upstream; we monitor via Dependabot)

## Security Best Practices for Contributors

- Never commit API keys, tokens, or passwords
- All user-generated content rendered via `react-markdown` is sanitized with DOMPurify
- Never use `dangerouslySetInnerHTML` without sanitization
- All API calls go through `ApiClient` which handles auth token injection

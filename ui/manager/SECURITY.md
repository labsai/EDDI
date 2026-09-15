# Security Policy

## Supported Versions

| Version | Supported              |
| ------- | ---------------------- |
| 6.3.x   | ✅ Active development  |
| 6.1–6.2 | ⚠️ Security fixes only |
| < 6.1   | ❌ End of life         |

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
- Vulnerabilities in dependencies (report upstream; we monitor via [Renovate](renovate.json))

### Known accepted `npm audit` findings

> **`react-router` — GHSA-qwww-vcr4-c8h2 (high, "RSC Mode CSRF Bypass")**
>
> **Do not "fix" this by downgrading.** `npm audit fix --force` proposes
> `react-router-dom@7.11.0`, which falls back inside the range of
> GHSA-wrjc-x8rr-h8h6 (open redirect via backslash in `<Link>`/`useNavigate`,
> leading to XSS) — an advisory that *does* apply to a client-side SPA.
>
> The RSC advisory requires React Server Components mode. This app is a pure
> client SPA: `BrowserRouter` in `src/main.tsx`, declarative `<Routes>` only, no
> `createBrowserRouter`, no data-router loaders or actions, no server runtime and
> no `@react-router/*` server packages. The vulnerable code path is not reachable
> here, so we accept this finding.
>
> **The real fix is a v8 migration, not a version bump.** The advisory covers
> `7.12.0 – 8.2.0`, so `react-router@8.3.0` is patched — but `react-router-dom`
> was discontinued at `7.18.2` (v8 consolidates everything into `react-router`).
> Adopting 8.3.0 therefore means repointing every `react-router-dom` import at
> `react-router` plus whatever else v8 changed, which is why this branch stayed on
> `react-router-dom@7.18.2`, the newest release of the package we actually depend
> on. Track the v8 migration separately; it removes this allowlist entry.
>
> Re-evaluate if this app ever adopts the data router or any server rendering.

The remaining `npm audit` highs are dev-only toolchain transitives (`eslint`,
`@vitest/coverage-v8` → `glob`/`minimatch`/`brace-expansion`) and are not shipped
in the built asset.

## Security Best Practices for Contributors

- Never commit API keys, tokens, or passwords
- Agent and user content is rendered through `react-markdown` **without `rehype-raw`**, so raw HTML stays escaped. The one opt-in HTML path (`agent-response-card.tsx`) sanitizes with DOMPurify first
- Never use `dangerouslySetInnerHTML` without sanitizing or HTML-escaping the input. Agent output is attacker-influenceable via prompt injection, so treat it as untrusted
- Prefer `ApiClient` for API calls (it injects the auth token). The raw-`fetch` call sites that exist for SSE, binary bodies and `text/plain` must spread `api.getAuthHeader()` explicitly

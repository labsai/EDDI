## 🐛 fix(openai-compat): the /v1 shared key works with OIDC on (2026-11-09)

**Repo:** EDDI (`fix/v1-shared-key-under-oidc`)

### What changed and why

Security review finding B7. With `quarkus.oidc.tenant-enabled=true` (for example `docker-compose.auth.yml`) and the default `eddi.openai-compat.http-policy=permit`, every `/v1` request carrying the shared key (`Authorization: Bearer sk-…`) got `401` with a `WWW-Authenticate` challenge from the OIDC layer, and `OpenAiAuthFilter` never ran. Requests without an `Authorization` header did reach the filter. Open WebUI cannot mint per-user OIDC tokens, so Keycloak + Open WebUI, the main production setup, could not work, although [the docs](../open-webui-integration.md) said it did.

**Cause.** `/v1/*` has its own `permit` permission so the key can reach the filter, but Quarkus authenticates proactively. Read from the Quarkus 3.40.1.1 sources (the version `pom.xml` pins): `HttpSecurityRecorder.AuthenticationHandler` runs `HttpAuthenticator.attemptAuthentication` before any permission policy is consulted, and an `AuthenticationFailedException` from it goes to `DefaultAuthFailureHandler`, which sends the challenge and a `401` whatever the policy says. `OidcAuthenticationMechanism` hands the bearer value to its bearer mechanism, which cannot verify `sk-…` as a token and throws. Without a header it returns no identity instead, which is why only that case got through.

**Fix.** A new `OpenAiPermitModeAuthMechanism` (an `HttpAuthenticationMechanism` bean). `HttpSecurityConfiguration.getMechanisms` sorts mechanisms by descending priority, and `HttpAuthenticator.createSecurityIdentity` stops at the first one that returns an identity when inclusive authentication is off. The new mechanism ranks one above `quarkus.oidc.priority` (default 1001) and, for a `/v1` request in permit mode, returns the anonymous identity. That is the identity `HttpSecurityRecorder` assigns to a request that carried no credential at all. OIDC is never asked about the key, the `permit` policy admits the request, and `OpenAiAuthFilter` checks the key: the right key is served, a wrong one or a JWT gets the adapter's `401 invalid_api_key`. The mechanism verifies nothing and grants nothing; anyone can be anonymous by leaving the header out.

It fails closed. It returns no identity, and OIDC runs as before, unless all of these hold: the adapter is enabled; `eddi.openai-compat.http-policy` **and** the effective `quarkus.http.auth.permission.openai-compat.policy` are both `permit`; `quarkus.http.auth.inclusive` is off; the method is one the permission lists; and both path forms Quarkus matches permissions against (the router's `normalizedPath()` and `HttpSecurityUtils.normalizePath` of it) are `/v1` or below it. With `http-policy=authenticated` nothing changes: OIDC validates the JWT and the filter checks the role. No other path's authentication changes, and proactive authentication stays on.

`OpenAiStartupGuard` now warns at startup when OIDC is on, the adapter is in key mode, and the mechanism cannot be active (inclusive authentication, an overridden `/v1` permission, or an unknown `http-policy`). In those cases every shared-key request would still get the OIDC `401`.

### Design decisions

- **Not an OIDC tenant.** A `TenantConfigResolver` returning a disabled tenant for `/v1`, or a named disabled tenant chosen by a `TenantResolver`, would also stop the verification (`OidcAuthenticationMechanism.authenticate` returns no identity for a disabled tenant). But in quarkus-oidc either one changes how the *default* tenant starts. `TenantContextFactory.createTenantContext` silently disables a default tenant with no `auth-server-url` once a `TenantConfigResolver` bean or a named tenant exists, where that is a startup failure today. A named static tenant is also selected by `DefaultStaticTenantResolver` for any request path with a segment equal to its id.
- **Not a per-path `auth-mechanism`.** It would need a config value that switches between this mechanism and `bearer` with `http-policy`, and a mechanism that names a credential transport.
- **The key is not checked in the mechanism.** The filter already checks it, in constant time, and answers in the OpenAI error envelope. A second check would mean two places to keep in step.

### Tests

- `OpenAiPermitModeAuthMechanismTest`: activation (each fail-closed condition on its own), path and method matching including disagreeing normalizations, the anonymous identity, no challenge, priority above OIDC, a chain that reproduces `HttpAuthenticator`'s ordering and shows the OIDC mechanism is never called for a `/v1` key request, and a pin on the shipped `openai-compat` permission in `application.properties`.
- `OpenAiStartupGuardTest`: the new warning condition.
- The Keycloak Auth E2E tier (`ui/manager/docker-compose.integration-keycloak.yml`) now enables the adapter with a shared key. [`e2e/auth/openai-compat.spec.ts`](../../ui/manager/e2e/auth/openai-compat.spec.ts) covers: the key is served on `/v1/models`; a full chat completion runs end to end; a wrong key, no key and a Keycloak JWT each get the adapter's `401 invalid_api_key` with no challenge; the key on a non-`/v1` path still gets OIDC's `401`. These are the regression tests against a real OIDC provider.

**Files:** [`OpenAiPermitModeAuthMechanism.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiPermitModeAuthMechanism.java), [`OpenAiStartupGuard.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiStartupGuard.java), [`OpenAiAuthFilter.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiAuthFilter.java) (Javadoc), [`application.properties`](../../src/main/resources/application.properties) (comments), [`open-webui-integration.md`](../open-webui-integration.md), the two test classes, the E2E spec and compose file.

The Platform Operator needs no change: neither its prompt nor its allow-list covers the `/v1` adapter, which is deployment configuration.

```decision-log
| 2026-11-09 | Keep OIDC off /v1 in permit mode with a higher-priority HttpAuthenticationMechanism that returns the anonymous identity | Review B7: proactive auth handed the shared key to OIDC, which answered 401 before the permit policy or OpenAiAuthFilter ran | A disabled OIDC tenant for /v1 via TenantConfigResolver/TenantResolver (changes default-tenant startup and path-segment tenant selection); a per-path auth-mechanism; disabling proactive auth globally |
```

```regression-note
| 2026-11-09 | Every /v1 request with the shared key got 401 + WWW-Authenticate once OIDC was on | The /v1/* permit permission does not stop proactive authentication: the OIDC bearer mechanism verified the opaque key and failed before the policy was consulted | OpenAiPermitModeAuthMechanism ranks above OIDC and returns the anonymous identity for /v1 in permit mode | fix/v1-shared-key-under-oidc |
```

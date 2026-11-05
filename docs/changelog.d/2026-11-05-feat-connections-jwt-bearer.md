## ✨ feat(connections): signed-JWT credentials — OAUTH2_JWT_BEARER and private_key_jwt (2026-11-05)

**Repo:** EDDI (`feat/connections-jwt-bearer`) — slice C1 of the Google Workspace plan

### What changed and why

A service account that proves itself with a private key instead of a client secret could not be
expressed as a connection: Google service accounts (RFC 7523 JWT bearer grant) and Entra apps
registered with a certificate (`private_key_jwt`) both need EDDI to sign an assertion.

- **`AuthType.OAUTH2_JWT_BEARER`** (new; `isOAuth()` true, new `isServiceAccount()` shared with
  `OAUTH2_CLIENT_CREDENTIALS`). `SERVICE` binding only. `oauth.tokenUrl`, `oauth.privateKey`
  (vault reference), optional `clientId` (issuer), `privateKeyId` (`kid`), `audience` (defaults to
  the token URL), `scopes`. The assertion is RS256 via the JDK `Signature` — no new dependency —
  with `iss`, `sub = iss`, `aud`, `scope`, `iat`, `nbf`, a unique `jti` and a five-minute `exp`.
- **`oauth.clientAuthMethod: "private_key_jwt"`** on `OAUTH2_CLIENT_CREDENTIALS` and
  `OAUTH2_AUTHORIZATION_CODE`: `privateKey` replaces `clientSecret`, and every token request
  (mint, code exchange, refresh) carries a freshly signed `client_assertion` (`aud` = token URL,
  unique `jti`). Optional `certificateThumbprint` (hex or base64url) in `x5t#S256` (default) or
  `x5t`, chosen by `certificateThumbprintHeader`; the digest length must match the header. The
  default stays `client_secret_basic`. The existing `clientAuthMethod` field was extended rather
  than a second `clientAuth` field added, so one field says how the client authenticates.
- **`SigningKey`** (new, `connections/oauth`) parses a PKCS#8 PEM or a whole Google service-account
  JSON key file from ONE vault entry (`private_key`, `client_email`, `private_key_id`; `token_uri`
  deliberately ignored). Refuses PKCS#1, encrypted keys, non-RSA keys and RSA below 2048 bits with
  fixed messages and no exception cause, because a JSON parser's message quotes the key.
  `toString()` is redacted. **`JwtAssertions`** (new) builds and signs both assertions.
- **`OAuthTokenClient.jwtBearer`**; client authentication factored into `authenticateClient`;
  the token-URL allowlist and https checks factored into `requireUsableTokenEndpoint` and run
  before anything is signed. **`OAuthTokenService`** mints JWT bearer grants through the same
  cache/single-flight as `client_credentials` and re-mints on expiry or `REFRESH_FAILED`;
  `OAuthConfig.clientCredentialReference(AuthType)` picks `privateKey` or `clientSecret` for the
  service and the authorization callback alike.
- **Validation** (`ConnectionConfiguration`): `privateKey` reference-only; a field the chosen flow
  would ignore (`clientSecret` beside a key, `authorizationUrl`/`extraAuthParams`/thumbprint on JWT
  bearer, `audience` outside it) is refused; identifiers are bounded literals.
  `RestConnectionStore` applies the vars→vault check to `oauth.privateKey`;
  `ConnectionReadRedactor` redacts a legacy literal `privateKey` for non-admins.
- **Manager**: the new auth type in the chooser, labels and badges; private-key, thumbprint and
  audience fields; and **connection templates as data** — JSON files under
  `ui/manager/src/lib/connection-presets/` (Google service account, Entra app with a certificate)
  offered by the New connection dialog. New i18n keys are translated for `de`; other locales carry
  the English text until translated.
- **Docs**: `docs/connections.md` — two new quick-start sections, model and redaction updates, a
  security rule on private keys.

### Decisions

- Assertion lifetime five minutes, not Google's one-hour maximum: it is presented once,
  immediately, and an intercepted one is replayable until it expires.
- A JSON key file is parsed when used, not at save: resolving a secret only to inspect it is a use
  of it. Save time checks only that `privateKey` is a vault reference.
- The Platform Operator was not changed: it has no connection tools or connection section (writes
  are admin-only by design), and its one mail line naming `OAUTH2_CLIENT_CREDENTIALS` stays
  accurate — a Google service account cannot log into a mailbox without domain-wide delegation.

### Next

C2 (domain-wide delegation: `sub` = a Workspace user) and C5 (keyless signing) build on
`JwtAssertions`.

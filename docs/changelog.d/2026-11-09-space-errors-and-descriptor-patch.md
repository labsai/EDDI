## 🐛 fix(spaces): a space id that matches none of the caller's spaces no longer says "not a member" (2026-11-09)

**Repo:** EDDI (`fix/space-errors-and-descriptor-patch`)

### What changed and why

External security review, findings B9 and B10 — both message-only. The checks
themselves are unchanged: still exact, still fail-closed.

- **B9 — `?space=` on `/spacestore/**`.** Space ids are opaque and may contain
  `%` (group `a|b` is `team:a%7Cb`). Sent raw, the id is decoded once by the
  container and arrives as `team:a|b`, which matches none of the caller's
  spaces — and the 403 told a member "You are not a member of that space." It
  now quotes the id as received (sanitised, capped at 200 characters) and says
  to send the id exactly as `GET /workspaces` lists it, URL-encoded with
  `encodeURIComponent` — what the Manager already does.
- **B10 — `X-EDDI-Space`.** The header is compared exactly and
  case-sensitively (Keycloak group names are case-sensitive), so
  `TEAM:engineering` or `team:engineering/` is refused — correctly — with
  "You are not a member of the space …". The refusal now says the value
  matches none of the caller's spaces, that the comparison is exact and
  case-sensitive with only surrounding whitespace ignored, and points at
  `GET /workspaces`.

Both texts live in one new class, `SpaceRefusals`. Neither lists the caller's
spaces: `GET /workspaces` already does, and an error body ends up in client
logs. The OpenAPI 403 descriptions on `IRestSpaceResources` say the same.
`docs/workspaces.md` documents the exact header match and the encoding of
`space`.

The Platform Operator quotes neither message (checked
`ui/manager/src/lib/operator/`), so its prompt and allow-list are unchanged.

**Files:**
[`SpaceRefusals.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/SpaceRefusals.java),
[`SpaceHeaderFilter.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/SpaceHeaderFilter.java),
[`RestSpaceResources.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/rest/RestSpaceResources.java),
[`IRestSpaceResources.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/rest/IRestSpaceResources.java),
[`SpaceHeaderTest.java`](../../src/test/java/ai/labs/eddi/engine/security/spaces/SpaceHeaderTest.java),
[`SpaceResourcesTest.java`](../../src/test/java/ai/labs/eddi/engine/security/spaces/SpaceResourcesTest.java),
[`workspaces.md`](../workspaces.md)

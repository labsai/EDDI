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

## 🐛 fix(descriptors): PATCH refuses a document that sets anything but name and description (2026-11-09)

**Repo:** EDDI (`fix/space-errors-and-descriptor-patch`)

### What changed and why

External security review, PATCH side note. `PATCH /descriptorstore/descriptors/{id}`
applies `name` and `description` only, and answered 204 to a document that also
set `visibility`, `spaceId`, `grants` or anything else — so a client making an
agent private was told it had, while its visibility stayed as it was. It now
answers **400** and writes nothing. The message names every unsupported field
and where it is changed instead: visibility, space, owner and grants under
`/descriptorstore/descriptors/{id}/shares/…`; `deleted` by a DELETE on the
resource's own store; `resource`, the timestamps, `originId`, `syncedVersion`
and `accessIndex` are maintained by EDDI. A DELETE patch is held to the same
rule, since it would not clear those fields either.

**How "set" is detected.** A declared field counts when it is non-null —
`deleted`, a primitive, when it is `true`; none of the others has a
deserialisation default, and every in-process caller builds its patch from an
empty `DocumentDescriptor`. A key the model does not declare at all (the
review's `resourceVisibility`) was dropped by the REST mapper's
`FAIL_ON_UNKNOWN_PROPERTIES=false`, so `DocumentDescriptor` gained a
`@JsonAnySetter` that records such keys — names only, capped at 100, never
serialised or stored, values still discarded — exposed as `unmodeledFields()`.
Stored descriptors with legacy fields load exactly as before. Undeclared keys
are echoed quoted, sanitised, shortened to 64 characters and at most ten
(`N more` for the rest). `callerLevel` and `ownerName` are read-only, and Jackson
hands such a property to the any-setter rather than dropping it; they are
skipped there, so a client echoing them is not refused for it.

**Caller audit.** Every PATCH of the descriptor store sends name and/or
description only: the Manager's `updateDescriptor` (nine call sites, the operator marker among them), MCP
`create_agent` / `update_agent`, `AgentSetupService`, and
`src/main/docker/seed-demo-agent.sh`. The Chat UI sends none, and the Platform
Operator's prompt says nothing about this body — its write canary renames the
operator, a name-only patch — so neither the prompt nor the allow-list changes.
`updateDescriptor`'s parameter type also allowed a `resources` key no caller
used and the server never had; it is gone. Unknown keys elsewhere in EDDI are
unaffected: the recording is on this model only, and only this endpoint reads it.

**Files:**
[`DocumentDescriptor.java`](../../src/main/java/ai/labs/eddi/configs/descriptors/model/DocumentDescriptor.java),
[`RestDocumentDescriptorStore.java`](../../src/main/java/ai/labs/eddi/configs/descriptors/rest/RestDocumentDescriptorStore.java),
[`IRestDocumentDescriptorStore.java`](../../src/main/java/ai/labs/eddi/configs/descriptors/IRestDocumentDescriptorStore.java),
[`RestDocumentDescriptorStoreTest.java`](../../src/test/java/ai/labs/eddi/configs/descriptors/rest/RestDocumentDescriptorStoreTest.java),
[`descriptors.ts`](../../ui/manager/src/lib/api/descriptors.ts),
[`developer-quickstart.md`](../developer-quickstart.md)

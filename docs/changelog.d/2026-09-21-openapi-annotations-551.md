## 📝 docs(openapi): annotate the user-conversation store (2026-09-21)

**Repo:** EDDI (`chore/openapi-annotations-551`, PR #552)

### What changed and why

`IRestUserConversationStore` was the last REST interface in
`engine/triggermanagement` with no OpenAPI metadata at all: its three operations
appeared in the generated document as bare paths, with no summary and no
description. All three now carry an `@Operation`.

The `POST` additionally gained `@Consumes(MediaType.APPLICATION_JSON)`. It takes a
`UserConversation` body, and without that annotation the generated document leaves
the request-body media type unspecified — 35 of the repository's 41 `@POST`
interfaces already declare it, so this was an omission rather than a choice.
`RestUserConversationStoreTest` grew a contract test for it, next to the existing
one that pins the `eddi-admin` role gate.

The other files this branch started with — `IRestHtmlChatResource`,
`ILogoutEndpoint` and `OpenApiConfig` — reached `main` from other work in the
meantime, so the branch no longer carries them.

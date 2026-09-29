## 🔒 fix(engine): authorize managed-agent calls before creating conversations (2026-09-27)

**Repo:** EDDI (`fix/managed-agent-auth-first`)

### What changed and why

`GET` and `POST /agents/managed/{intent}/{userId}` (`loadConversationMemory`,
`sayWithinContext`) resolved the caller's managed conversation first and checked
the caller afterwards. Resolving it has side effects: when no conversation exists
it starts one in the engine and stores a `UserConversation`, and when the existing
one has ended it deletes that `UserConversation` and creates a replacement. Only
then did `checkUserAuthIfApplicable` throw `UnauthorizedException` for an
anonymous caller (OIDC on, non-production environment). An anonymous request
could therefore create or replace a conversation and still get a 401.

That ordering is why the Manager's `ApiClient` stopped replaying 401'd writes in
PR #855 (`fix/manager-shell`): its retry assumed a 401 meant the handler never
ran, and this endpoint was the counterexample. The client-side guard stays; this
is the server-side fix.

`initUserConversation` now authorizes before each side effect:

- **No conversation yet:** the deployment is picked from the intent's trigger
  first (`selectAgentDeployment`), the caller is checked against that
  deployment's environment, and only then is the conversation started, with
  that same deployment. The random pick among a trigger's deployments happens
  once, so the check and the start cannot disagree on the environment.
- **Ended conversation:** the same, before the delete. The replacement's
  environment decides, not the ended conversation's, because the replacement
  is what the request acts on. That matches what the old code eventually checked.
- **Live conversation:** checked against its stored environment. Only reads (the
  `UserConversation`, the engine's conversation state) come first.
- A final check on the conversation actually returned still runs. It also
  covers a conversation that a concurrent request stored first (the
  `ResourceAlreadyExists` fallback).

`sayWithinContext`'s generic `catch (Exception)` turned the
`UnauthorizedException` into an opaque **500**. It now calls
`response.resume(e)`, which RESTEasy Reactive routes through the exception
mappers (`AsyncResponseImpl.resume(Throwable)` calls `handleException`). So the
POST answers **401**, exactly as the GET's throw does.

`endCurrentConversation`, `undo`/`redo` and `isUndoAvailable`/`isRedoAvailable`
already read, then checked, then acted, so they are unchanged.

### Behaviour for authorized callers

Authorized callers see the same calls, the same creation order (delete, engine
start, stored `UserConversation`) and the same responses. One call moved: when an
ended conversation is replaced, the trigger is now read *before* the delete
rather than after it. So a missing trigger now fails without deleting the ended
`UserConversation`, where before it deleted it and then failed. The error itself
is the same: the store's not-found exception for the GET, the opaque 500 for
the POST. Every later request fails the same way either way.

### Residual

- **Race path:** if a concurrent request stores its conversation between this
  request's start and store, this request's engine conversation is orphaned
  (as before), and the final check runs against the other request's environment.
  A 401 there follows an engine start. This needs a trigger with mixed
  environments and a concurrent anonymous request, and the stored
  `UserConversation` is untouched.
- The HTTP layer normally rejects anonymous callers first. `IRestAgentManagement`
  is `@RolesAllowed` and the catch-all path policy is `authenticated`, so this
  in-method gate is defence in depth. It is reached when
  `authorization.enabled` or the path policy has been loosened.

### Tests

`RestAgentManagementExtendedTest` has a new nested class, "Authorization before
side effects", with 20 cases. Anonymous callers with OIDC on and a
non-production environment get a 401 with no `createUserConversation`,
`deleteUserConversation` or engine start, for both methods, with no existing
conversation and with an ended one. Authorized callers are parameterized over
authenticated/test, anonymous/production and OIDC-off, for both methods and
both the create and replace paths, and each is checked for call order. The
missing-trigger error path is also covered. The mutation check was run three
ways. Removing the two pre-creation checks fails the 4 create/replace
rejection tests. Removing the `resume(e)` branch fails the 3 POST rejection
tests. The original class fails 5, and every authorized-path test passes
against it.

**Files:** [`RestAgentManagement.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestAgentManagementExtendedTest.java`](../../src/test/java/ai/labs/eddi/engine/internal/RestAgentManagementExtendedTest.java),
[`security.md`](../security.md) (RestAgentManagement Gate: the check now runs before any side effect)

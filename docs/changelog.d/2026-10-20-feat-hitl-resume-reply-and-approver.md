## feat(hitl): opt-in reply and unverified approver on resume (2026-10-20)

**Repo:** EDDI (`feat/hitl-resume-reply-and-approver`)

### What changed and why

- **Reply from resume.** `POST /agents/{conversationId}/resume` accepts the `say` query parameters (`returnCurrentStepOnly`, `returnDetailed`, `returningFields`). With any of them the request waits for the resumed turn (`AsyncResponse`, `agentTimeoutInSeconds`, 408 on timeout, `Retry-After` as on `say`) and returns it through the same snapshot conversion `say` uses, so secret context stays a placeholder. Without them the answer is the empty 200 it always was. `IConversationService.resumeConversation` gained a six-argument form; the three-argument form delegates with `(false, true, [])`.
- **Approver without authentication.** With no authenticated principal, a body `decidedBy` is recorded as `unverified:<value>` (trimmed, 128 characters, `[A-Za-z0-9._@:+-]` only). It is a claim by the caller, not an identity. An authenticated principal still wins and the body is ignored.
- **Tool-call path test.** `ResumeSecretContextApprovedCallTest` drives the real resume loop with the real httpcall provider and `ApiCallExecutor` resolution (HTTP client recorded): a pinned call using `Bearer {context.userToken}` runs with the real header when the token is re-supplied, is refused as before without it, and is still refused when the URI or a body value changed.

Files: [`RestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java), [`IRestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/api/IRestAgentEngine.java), [`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java), [`hitl.md`](../hitl.md).

### Design decisions

- The endpoint is converted to `AsyncResponse` rather than blocking a worker: it is the same shape and timeout contract as `say`, and the no-flag path resumes the async response immediately.
- Presence of any `say` flag, not a particular value, is what asks for the reply, so `returnCurrentStepOnly=false` returns the whole conversation as it does on `say`.
- **Follow-up fixes.** `unverified:` is now a `ReservedPrincipals` prefix (no token may carry it). The resumed reply runs `recordTurnOutcome` like `say`, so an ERROR turn carries `error` and `Retry-After`. A waiting client is always answered: a resume dropped before it ran (cancelled/ended, or refused by the coordinator) is delivered as `onSkipped` (410 for ENDED, else 409, one mapping shared with `say`), and a resume that throws without rendering is delivered as the ERROR snapshot.

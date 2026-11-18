## ✨ feat(security): the caller's team subjects travel with the turn (2026-11-18)

**Repo:** EDDI (`feat/caller-identity-team-subjects`) — work package WP-5b.0 of the PDF-documents plan, the prerequisite of every team-library tool.

### What changed and why

Inside a conversation turn there was no source for "the current token's teams". `CallerIdentity` carried token, user id, origin, connection credentials and the admin flag, and `ResourceAccessGuard.currentTeamSubjects()` needs a live request, which a tool on a worker or virtual thread does not have. The one in-turn caller, `AgentOpsToolsProvider.creatorTeams()`, caught the failure and fell back to an empty list.

- **`CallerIdentity.teamSubjects`** — a new sixth component: the team subjects (`team:<group path>`) on the token of the request that captured the identity, personal space excluded, in token order. Defensively copied; `null`, blank and duplicate entries are dropped; never `null`. The five-argument constructor stays and delegates with `List.of()`, so every existing construction site compiles unchanged.
- **One derivation.** The filter `ResourceAccessGuard.currentTeamSubjects()` used moved to a static `CallerSpaces.teamSubjectsOf(CallerSpaces)`. The guard delegates to it, and `CallerIdentityContext.capture()` fills the snapshot with it from `SpaceContext.current()`.
- **Captured at the request boundary, its own failure boundary.** `CallerIdentityContext` gets `SpaceContext` through a new three-argument `@Inject` constructor; the two-argument constructor remains as a test seam that captures no teams. A groups claim that cannot be read costs the caller their teams, not their token.
- **Carried by `propagate`.** The whole record already travelled, so the teams reach the tool-execution thread with no change at the call sites.
- **An approver's teams stay out.** `withApprover` now binds the approver through `CallerIdentity.withoutTeamSubjects()`, so `callAsApprover` runs an approved call with the approver's token but without their teams. A resume by somebody other than the owner already runs with no caller, so with no teams.
- **No leak through `toString`.** `CallerIdentity.toString()` is overridden: the token is redacted, and connection credentials and team subjects are counted rather than listed. The generated record `toString` printed the bearer token. A grep of `CallerIdentity` usages found no serialization, persistence or audit write of the record.
- **Docs.** [Security → Caller Identity Forwarding](../security.md#caller-identity-forwarding--calleridentityresolver) and [Workspaces → Team membership inside a conversation](../workspaces.md#team-membership-inside-a-conversation).

### Design decisions

- **A snapshot, not a lookup.** Taken once from the request that started the turn, never refreshed mid-turn, never persisted. A membership change applies from the caller's next request.
- **Empty means "no team", and team features fail closed.** Schedules, team cadences and non-owner HITL resumes have no request token behind them and get an empty list. The user directory's last-seen `DirectoryUser.teams` is deliberately not a fallback.
- **Stripped centrally.** The approver's teams are removed where the approver is bound, not at the `ConversationHitlService` call site, so no future caller of `withApprover` can reintroduce them.

### Tests

`CallerIdentityTest` (constructors, null and defensive copy, order and junk, `withoutTeamSubjects`, `toString`) and `CallerIdentityContextTeamSubjectsTest`. The acceptance test captures two team subjects through the real `capture()` and `SpaceContext`, runs a tool through `ToolExecutionService` with a timeout bound — so on its virtual-thread executor behind `CallerIdentityContext.propagate` — and reads `CallerIdentityContext.current().teamSubjects()` there. The other cases cover anonymous, token-less and unreadable-claim captures, `propagate(Runnable)`, and the approver path. Mutation-checked: making `propagate` drop the teams fails the two propagation tests; binding the approver with their teams fails the approver test; capturing `List.of()` fails the capture and acceptance tests. Every existing `CallerIdentity` test passes unedited.

### What's next

WP-5b.1 (owner `TEAM`, `LibraryAccessCheck`) reads `CallerIdentityContext.current().teamSubjects()` inside a turn. `AgentOpsToolsProvider.creatorTeams()` still asks the request-scoped guard and gets an empty list off the request thread; switching it to the snapshot is a separate behaviour change.

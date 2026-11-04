## 🐛 fix(groups): a caller without access to a group gets 403 from the workspace endpoints, not 500 (2026-11-03)

**Repo:** EDDI (`fix/group-workspace-forbidden`)

### What changed and why

`RestGroupWorkspace` (`readWorkspace`, `readBacklog`, `addBacklogTask`, `addCadence`, `deleteCadence`) calls `ResourceAccessGuard.requireAccess`
inside a `try` whose last `catch (Exception e)` logs and answers 500. The guard denies with `io.quarkus.security.ForbiddenException`, so a
caller without access to the group received a 500 and an error log line instead of 403. Each of the five methods now catches
`ForbiddenException` ahead of the generic catch and rethrows it, so Quarkus maps it to 403.

`RestGroupWorkspaceTest` gains `everyEndpoint_accessDenied_propagatesForbidden`, which makes the guard throw the real Quarkus type for each of the
five endpoints. Mutation-checked: with the new catch removed the test fails because the endpoints answer 500 instead of throwing.

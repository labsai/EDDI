## fix(security): refuse templated hosts without SSRF protection, and only admins may write `${caller:token}` (2026-10-15)

**Repo:** EDDI (`fix/ssrf-default-caller-token`; backend, docs)

### What changed and why

The two High findings of the 2026-10-15 security review.

**H1 — templated httpCall hosts with SSRF protection off.** `eddi.security.ssrf-protection.enabled` defaults to `false`, and an httpCall's authority may be a Qute template whose rendered value keeps dots and a port. A property is routinely captured from user input, so `https://{properties.apiHost}` let the chatting user steer a call to `127.0.0.1:7070` (EDDI's own admin API) or any RFC1918 address; the review reproduced the loopback reach live.

- **A templated scheme, host or port is refused while protection is off.** `ApiCallExecutor.rejectTemplatedAuthority` checks the URL before rendering (a `{` in the authority that is not part of a `${vars:…}`/`${vault:…}` reference); `ApiCallsTask.configure` calls it for `targetServerUrl` and every full-URL call path, so the agent fails to deploy with a `WorkflowConfigurationException` naming the flag, and `buildRequest` applies the same check per turn, which also covers httpcall tools. With protection on, templated hosts work as before and the rendered host is validated.
- **`eddi.security.ssrf-protection.allowed-hosts`.** Comma-separated exact `host` or `host:port` entries (case-insensitive, no wildcards, a bare host matches any port). With protection on, a listed host skips the private-address check but never the cloud-metadata check, and redirects stay off. Lets a self-hoster turn protection on and keep named internal APIs. httpCalls only; MCP and A2A are unchanged (review finding M2 covers them).
- **Default kept `false`, with a production WARN.** The rule-based reference agent and the integration tests target `http://localhost:7070`, so defaulting to `true` would break them. `AuthStartupGuard.ssrfDiagnostic()` logs a `[SECURITY]` WARN at production startup while protection is off, naming the flag and the allow-list.

**H2 — `${caller:token}` as a confused deputy.** `CallerIdentityResolver` releases the bearer token of whoever is chatting to the caller's origin or EDDI's own address, without regard to who authored the config. Editors may save `${caller:token}` and deploy, so an editor-authored call to `/administration/…` or `/secretstore/…` ran with the privileges of the next admin who chatted with the agent.

- **New `CallerTokenAuthorPolicy`.** With `authorization.enabled=true`, storing a document that contains `${caller:token}` (or `{caller:token}`) requires one of `eddi.caller-identity.author-roles` (default `eddi-admin`); anyone else gets a 403 with a JSON `error` naming the property. It scans the serialized document, so it covers httpcall headers, MCP `apiKey` and anything later added, without tracking fields. `${caller:userId}` stays open to editors.
- **Released only from the configuration as written.** `ApiCallExecutor` refuses a header whose `${caller:token}` arrives through a `${vars:…}` or `${vault:…}` value (`GlobalVariableResolver` allows a variable whose whole value is a reference). The save-time scan cannot see through a variable, and a variable can be edited after the config was saved, so this is enforced at execution. The MCP `apiKey` path never resolves a caller reference produced by a variable, so it needed no change.
- **Applied at every write.** `RestApiCallsStore` and `RestLlmStore` create/update/patch/duplicate, `RestMcpCallsStore` create/update/patch/duplicate, `RestImportService.createResourceDirect` (ZIP create/merge), and `UpgradeExecutor.createExtension` (upgrade and instance sync); merge and upgrade updates go through the REST stores. The check throws rather than returning a response, because the import treats a non-200 update as "create it directly instead". An import refusal fails the whole import with the 403 and rolls it back.
- **Docs.** [`security.md`](../security.md#ssrf-protection--urlvalidationutils), [`configuration-reference.md`](../configuration-reference.md), [`httpcalls.md`](../httpcalls.md#who-may-write-callertoken) ("Who may write `${caller:token}`"), [`upgrading-from-6.5.md`](../upgrading-from-6.5.md).

### Design decisions

- **Refuse the templated authority, not the flag default.** Defaulting protection on breaks every configured internal target at once; refusing only the template closes the path the end user controls and leaves editor-written literals, which the WARN now surfaces.
- **Check at write time, not at release time.** The resolver knows the chatting user, not the author; the write is the only point where the author is known.
- **Whole-document text scan.** One rule for every config type, no field list to keep in step. A literal `{caller:token}` in, say, a prompt is refused for editors too; acceptable for a credential reference.
- **Platform Operator unchanged.** Its prompt does not discuss caller identity or SSRF, and it is provisioned by an admin, who passes the check.

### Known limitations

- A merge import by a non-author is refused before its first write when it would replace a stored version containing `${caller:token}` (`CallerTokenAuthorPolicy.requireMayRestore`): its rollback restores through the same stores, which would refuse that version and leave the import's content in place.
- An upgrade or instance sync reports a refusal as that resource failing rather than a 403, like every other per-resource failure there: an upgrade is not transactional, and aborting would leave earlier resources written.
- With protection off, editor-written literal internal targets remain reachable by design; turn protection on and use the allow-list to close that.

```decision-log
| 2026-10-15 | Templated httpCall hosts are refused while eddi.security.ssrf-protection.enabled=false; allowed-hosts list added | A conversation-controlled host reached loopback and private APIs | Defaulting protection to true (breaks the localhost reference agent and every configured internal API) |
| 2026-10-15 | Only eddi.caller-identity.author-roles (default eddi-admin) may store a config containing ${caller:token} | An editor-authored call spent the chatting admin's token on EDDI's admin API | Disabling self-release by default (breaks the Platform Operator); HITL-gating every caller-token call (a prompt per tool call) |
```

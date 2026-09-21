## 🧹 chore: replace a customer name used in examples and tests with a generic one (2026-09-18)

**Repo:** EDDI (`chore/genericise-customer-examples`); mirrored on the archived EDDI-Manager repo's
branch of the same name

A worked connection example, a planning section, two changelog entries, one Javadoc and the
`CALLER_SUPPLIED` test fixtures named a real integrating customer. They now use generic values
throughout: connection `acme`, host `https://api.example.com` (RFC 2606), header `X-Acme-Key`, and
prose that describes the requirement ("an integration that hands EDDI the end user's own API key")
rather than who had it.

- **Docs:** `docs/connections.md`, `planning/saas-connectors-plan.md` §5.5, one line of this file,
  and the `CALLER_SUPPLIED` entry in the `docs/changelog/2026-08.md` archive (wording only, structure
  untouched).
- **Code:** the `RequestRedactor` Javadoc only.
- **Tests:** `ConnectionConfigurationValidationTest`, `RestConnectionStoreWriteGuardTest`,
  `ConnectionResolverTest`, `ConnectionStartupGuardTest`, `CallerIdentityContextTest`,
  `ApiCallExecutorConnectionHeaderTest`, and under `ui/manager/` the connection tests, the MSW
  fixture and `HANDOFF.md`. The replacement is used consistently in setup and assertion; no behaviour
  changed, and the same tests pass before and after.

The removal is from the tree only. Git history is not rewritten — that would need a force-push to
`main`.

---

  connection on `X-Amp-Id` or a `CALLER_SUPPLIED` one on `X-Acme-Key` matched none, so the live

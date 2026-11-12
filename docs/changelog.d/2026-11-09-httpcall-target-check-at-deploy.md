## 🐛 fix(httpcalls): a literal target the SSRF check refuses fails at deploy, not on the first turn (2026-11-09)

**Repo:** EDDI (`fix/httpcall-target-check-at-deploy`)

### What changed and why

External security review, finding B1 (real, low). With
`eddi.security.ssrf-protection.enabled=true`, an httpcall whose literal
`targetServerUrl` the SSRF check refuses (`http://127.0.0.1:7070/...`) saved
with 201, deployed with 200, and only failed when the first conversation
started: `ApiCallExecutor.buildRequest` threw and `POST /agents/{id}/start`
answered a bare 500. At deploy, `ApiCallsTask.configure` only refused templated
hosts.

- **One decision, two callers.** The SSRF block of `buildRequest` is extracted
  unchanged into `ApiCallExecutor.checkOutboundTarget(URI, HostResolver)`;
  `buildRequest` calls it with the JDK resolver, so runtime behaviour is the
  same. The new `IApiCallExecutor.rejectRefusedLiteralTarget` runs it at deploy
  on the scheme and authority of `targetServerUrl` and of every absolute
  `request.path` (`ApiCallsTask.rejectRefusedTargets`, formerly
  `rejectTemplatedHosts`). A refusal is a `WorkflowConfigurationException`
  naming the host, the `host:port` entry to add to
  `eddi.security.ssrf-protection.allowed-hosts`, and the alternative of a
  public address. A cloud-metadata target fails to deploy whatever the flag,
  with the metadata message (the allow-list cannot admit it, so the advice
  would be wrong).
- **Skipped at deploy:** a templated host (still handled by
  `rejectTemplatedAuthority`) and a `${vars:…}`/`${vault:…}` host — both are
  only known per call, where the same check runs.
- **DNS:** a host that does not resolve at deploy is logged, not refused. The
  execution path still refuses it with protection on; but a failed lookup at
  deploy is as likely a transient resolver problem (agents auto-deployed at
  startup) as a wrong name, and an unresolvable name cannot be reached anyway.
  A blocked name (`localhost`, `*.internal`, `*.local`) is refused without a
  lookup, as at execution.
- **DNS lookups are bounded.** The check resolves the host (deploy and, as
  before, every call), and `InetAddress.getAllByName` has no timeout: a
  black-holed name server would hold the deploying or turn thread for the OS
  default. Both now go through `BoundedHostResolver` (small bounded daemon pool,
  deadline `eddi.security.ssrf-protection.dns-timeout`, default `5s`). A timeout
  or a full pool is an `UnknownHostException`, i.e. "does not resolve": refused
  at execution with protection on, logged at deploy, never allowed. No default
  of any security flag changes. New property documented in
  `configuration-reference.md`; `BoundedHostResolverTest` covers fast, slow,
  failing and saturated.
- **Upgrade note** added to `upgrading-from-6.5.md` (Changes after 6.7.0).
- **No self exception for httpcalls — documented, not changed.** The
  `application.properties` comment on `eddi.self.base-url` claimed loopback was
  always refused with protection on; it now says that `OutboundTargetPolicy`
  targets accept the self URL while httpcalls need the host on the allow-list.
  `OutboundTargetPolicy`'s Javadoc no longer says it applies the flag "the way
  ApiCallExecutor" does without naming that difference, and the LLM httpcall
  tool's refusal message (`HttpCallToolsProvider.describeToolFailure`) now
  names the allow-list as well as `eddi.self.base-url`.

The Platform Operator needed no change: its prompt and allow-list say nothing
about SSRF, and a refused deploy surfaces through the deployment status it
already reads.

**Files:** [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`IApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/IApiCallExecutor.java),
[`BoundedHostResolver.java`](../../src/main/java/ai/labs/eddi/engine/net/BoundedHostResolver.java),
[`ApiCallsTask.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallsTask.java),
[`OutboundTargetPolicy.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/OutboundTargetPolicy.java),
[`HttpCallToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/HttpCallToolsProvider.java),
[`application.properties`](../../src/main/resources/application.properties),
[`security.md`](../security.md),
[`httpcalls.md`](../httpcalls.md),
[`configuration-reference.md`](../configuration-reference.md),
[`ApiCallTargetDeployCheckTest.java`](../../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallTargetDeployCheckTest.java),
[`ApiCallsTaskTest.java`](../../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallsTaskTest.java)

```decision-log
| 2026-11-09 | httpcalls keep no self-URL exception under SSRF protection; a literal target the check refuses fails at deploy, an unresolvable one only logs | Review B1: refused targets deployed fine and failed the first turn with a 500 | Adding the OutboundTargetPolicy self exception to httpcalls (widens what a config editor can reach); refusing deploy on a DNS failure (a transient resolver outage would take agents down) |
```

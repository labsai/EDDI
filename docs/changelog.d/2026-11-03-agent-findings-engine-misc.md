## Agent findings, engine misc: audit erasure barrier, scrubbed audit payloads, MCP schedule floor (2026-11-03)

**Repo:** EDDI. **Branch:** `fix/agent-findings-engine-misc`.

40 CodeRabbit agent findings in `engine/{mcp,memory,a2a,alerts,audit,httpclient,mail,ratings,agenttests}` were traced against the current code. Most were already closed by #1173, #1187, #1188, #1193 and #1198; four real gaps are fixed here, plus one extra item carried over from the llm-impl sibling branch.

**Fixed**

- `AuditLedgerService.stopInFlightWorkByHash` (the peer's reply to the cluster `gdpr-stop`) now waits for a flush that is already committing before it answers. The erasing node sweeps the stored audit rows as soon as every node replied, so a batch drained before the peer marked the user could previously commit its raw entries after the sweep.
- `TurnAuditBuffer` scrubs the buffered audit payloads with the shared deep, JSON-aware `SecretValueScrubber.scrubDeep` instead of its own string/number/map/list walk. A secret inside a payload object, or inside a JSON string in its escaped form (a quote, backslash or newline in the secret), no longer survives into the persisted audit entry.
- MCP `create_schedule` refuses heartbeat and cron intervals below `eddi.schedule.min-interval-seconds` (default 60), as the REST schedule store already did; one MCP call could otherwise create a schedule firing agent turns faster than the deployment's floor.
- OpenAPI discovery from a remote `specUrl` refuses a downloaded document whose `$ref` carries an explicit non-http(s) scheme (`file:`, `classpath:`, a drive path or UNC path). Relative refs stay: they resolve against the document's URL.
- `CompatibilityRules.containsTemplate` strips configuration-reference mentions with a linear forward scan, not a regex that went quadratic on a value of unclosed `{vault:` openers (same approach as `LlmTask.escapeConfigReferenceMentions` on the llm-impl branch).

**Verified, no change needed** — `SelfUngatingGuard.refusalFromArguments` already checks the JSON-decoded keys and string values (`argumentHaystack`, #1192), so a `\uXXXX`-escaped id or key in tool arguments is caught; `SelfUngatingGuardProtectionTest` pins it.

**Open decisions** (not implemented; they change a contract or need a redesign): a cap on active alert rules per agent and a per-event delivery budget; routing OpenAPI external `$ref` fetches through the guarded fetcher (or refusing external refs); a durable deletion barrier between conversation purge and in-flight dependent writes.

Verdicts for the 40 findings: 4 FIXED, 28 ALREADY_FIXED (two of them on the sibling `fix/agent-findings-engine-internal` branch), 1 FALSE_POSITIVE, 3 ACCEPTED_RISK, 4 NEEDS_DECISION.

**Review follow-up (PR #1257)**

- `CronParser.computeMinIntervalSeconds` skips calendar dates a zone does not contain (Pacific/Apia lost 2011-12-30), which used to yield a gap of 0 and reject a valid daily schedule on REST, MCP and RAG ingestion alike.
- Embedded JSON in scrubbed strings is read with decimals as `BigDecimal`: unrelated decimals keep their text (`1.00` stays `1.00`) and a secret number is matched by value.
- A downloaded OpenAPI spec is checked on its decoded tree only (prose and YAML comments that mention `$ref: file:` no longer refuse it) and a `file:`/`jar:`/`classpath:` schema `$id` is refused, since relative refs resolve against it.
- The MCP schedule-floor error tells operators to lower, not raise, `eddi.schedule.min-interval-seconds`.

## fix(alerts): review findings on the alerts backend — https-only webhooks, GDPR export, digest healing, bulk acknowledge (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138; Manager follow-ups on `feat/alerts-manager`, PR #1139)

### What changed and why

- **Webhook URLs must be https.** `AlertRuleValidator` refuses a plain `http` URL at save time; `AlertSenders` checks again at delivery, which is the only place a `${vault:…}` URL is known — one that resolves to `http` (or to no host) is not delivered to. STRICT SSRF and no redirects are unchanged. Docs: [Alerts → Delivery](../alerts.md#delivery).
- **GDPR export** (`AlertGdprParticipant.export` returned `null`): it now lists the rules the person created or last edited (id, name, created/updated time, `author` and/or `lastEditor`) and the events they acknowledged (id, rule name, source, agent, fired and acknowledged time), at most 10,000 of each, and no other person's identity or event content. New store reads `IAlertRuleStore.findByAuthor` and `IAlertEventStore.findAcknowledgedBy`, implemented for MongoDB and PostgreSQL and covered by `AlertStoreContract`. Docs: [Alerts → Privacy](../alerts.md#privacy), [GDPR Compliance](../gdpr-compliance.md).
- **A digest could stay stuck.** When the digest's insert hit its dedupe key — another runner sent it, or this one crashed between the insert and marking the held events — the held events stayed `DIGEST_PENDING` and every pass retried a digest that never inserted. `AlertMaintenanceService.sendDigest` now marks them on a dedupe hit too, still without delivering.
- **`actions exists` fired on every clean turn**: an empty action list is now absent (`OutputMatcher.resolve`); `contains` is unchanged.
- **A cache miss copied the turn even when nothing would run it**: when submitting the output-needs loader fails (alerts off, queue full), `OutputMatchHook` returns before building a snapshot.
- **Bulk acknowledge:** a present `ids` list is the selector. An empty list or an empty entry is a 400 `INVALID_PARAMETER` instead of falling through to "everything matching the filter"; the filter applies only when `ids` is left out. The MCP tool acknowledges one event by id and the Manager sends `{}` when acknowledging by filter, so both stay valid.
- **Wording:** `OUTPUT_MATCH` added to the `source` tag of `eddi_alerts_fired_total` ([Metrics](../metrics.md#alert-metrics)), to `AlertEventQueryParams.source` and to `list_alert_events`' description and `source` argument. "No conversation text" is corrected wherever it was repeated (REST Javadoc, MCP description, [Alerts](../alerts.md), [MCP Server](../mcp-server.md), the `eddi-admin` skill): a rating comment and, only with `includeValue` and conversation review on, an `OUTPUT_MATCH` value appear, each cut to 280 characters.

### Design decisions

- **On a digest dedupe hit every held event is marked**, including any held after the existing digest was written. They stay in the inbox but are not listed in a digest message. The alternative, reading the existing digest to mark only what it covered, needs the event ids on the digest, which it does not store.
- The export re-checks each row against the user id, so a store returning a wider set cannot leak another person's rule or acknowledgement into it.

### Tests

448 unit tests across the alert, output-match, REST, MCP, step-runner and GDPR classes pass, among them the new `AlertGdprParticipantTest`, `RestAlertEventsTest.bulkEmptyIdsRefused`, `AlertMaintenanceServiceTest.digestCrashBetweenInsertAndMark`, `OutputMatchHookTest.cacheMissLoaderRefused`, `OutputMatcherTest.emptyActionsAreAbsent` and `AlertSendersTest.httpsOnly`. Store ITs: 32/32 (16 MongoDB + 16 PostgreSQL, with the two new contract cases). The doc and style guards pass. Mutation check: 9 of 9 mutants killed — http accepted at save, an http vault URL delivered to, an empty export, another person's acknowledgement exported, the lastEditor role lost, a dedupe hit leaving events pending, a refused loader submit still snapshotting, an empty ids list falling back to the filter, a blank id entry accepted.

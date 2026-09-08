# Tenant Quotas

EDDI meters usage per tenant and can refuse work once a tenant passes its limits. The limits
themselves are stored per tenant and are editable at runtime through the admin API below — you do
not need a restart to raise one tenant's ceiling.

The deployment-wide defaults that seed a new tenant live in
[configuration-reference.md](configuration-reference.md) under `eddi.tenant.quota.*`. A tenant row
is created from those defaults the first time the tenant is seen; editing the properties afterwards
does not rewrite rows that already exist, and a mismatch between a stored row and the configured
default is reported in a startup WARN.

## What is metered

| Limit | Meaning | Unlimited |
|---|---|---|
| `maxConversationsPerDay` | New conversations started in the current calendar day | `-1` |
| `maxAgentsPerTenant` | Agents the tenant may own | `-1` |
| `maxApiCallsPerMinute` | API calls in the current calendar minute | `-1` |
| `maxMonthlyCostUsd` | Dollar spend in the current calendar month | `-1` |
| `enabled` | Whether enforcement is active for this tenant at all | — |

Windows are **calendar-aligned, not sliding**. The daily counter resets at the start of the day in
the store's clock, the per-minute counter at the start of the minute, and the cost counter at the
start of the month.

> **`maxMonthlyCostUsd` is not enforced today.** The limit is stored and returned, and the gate
> that would read it exists, but nothing records cost against it yet, so the counter stays at zero
> and the gate never trips. Writing a non-negative value logs a WARN saying so. Treat it as a
> declaration of intent, not a control.

## Failing closed

If the quota store cannot be read — the database is down, a query fails — the gate **denies** the
request rather than letting it through. Both the MongoDB and the PostgreSQL store answer with the
same message, `Quota accounting unavailable — denying request for safety`, surfaced as `503`, so a
caller cannot tell which backend refused and cannot get a different answer by retrying against
another node.

## Admin API

All five operations sit under `/administration/quotas` and require the `eddi-admin` role.

| Method | Path | Description |
|---|---|---|
| `GET` | `/administration/quotas` | List every configured tenant quota |
| `GET` | `/administration/quotas/{tenantId}` | Read one tenant's quota |
| `PUT` | `/administration/quotas/{tenantId}` | Create or replace one tenant's quota |
| `GET` | `/administration/quotas/{tenantId}/usage` | Read the tenant's current counters |
| `POST` | `/administration/quotas/{tenantId}/usage/reset` | Zero the tenant's counters |

### Quota shape

```json
{
  "tenantId": "acme",
  "maxConversationsPerDay": 5000,
  "maxAgentsPerTenant": 50,
  "maxApiCallsPerMinute": 600,
  "maxMonthlyCostUsd": -1,
  "enabled": true
}
```

`-1` means unlimited for any of the four numeric limits. A `PUT` rejects a negative value other
than `-1` with a `400`.

### Raising one tenant's ceiling

```bash
curl -X PUT "http://localhost:7070/administration/quotas/acme" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "tenantId": "acme",
    "maxConversationsPerDay": 20000,
    "maxAgentsPerTenant": 50,
    "maxApiCallsPerMinute": 600,
    "maxMonthlyCostUsd": -1,
    "enabled": true
  }'
```

The write takes effect immediately: the service caches quotas but drops the tenant's entry on
every write, so the next check reads the new row.

### Usage shape

```json
{
  "tenantId": "acme",
  "conversationsToday": 143,
  "apiCallsThisMinute": 7,
  "monthlyCostUsd": 0.0,
  "minuteWindowStart": "2026-09-07T14:32:00Z",
  "dayStart": "2026-09-07T00:00:00Z",
  "costMonth": "2026-09"
}
```

Counters from an expired window read as zero rather than as their stale value, so a `conversationsToday`
of `0` on a tenant that was busy yesterday is correct, not a lost write.

Resetting is an operator escape hatch — it zeroes the stored counters for the current windows and
does not change the limits.

## See also

- [Configuration Reference](configuration-reference.md) — the `eddi.tenant.quota.*` defaults
- [Metrics & Monitoring](metrics.md) — the quota counters exposed at `/q/metrics`

# Log Administration API

> **Base path:** `/administration/logs`  
> **Security:** `@RolesAllowed("eddi-admin")` — requires `eddi-admin` role when OIDC is enabled (`QUARKUS_OIDC_TENANT_ENABLED=true`). Bypassed in dev mode (OIDC disabled by default).

EDDI provides a built-in log management API for platform-wide observability. It captures all application log records into an in-memory ring buffer and optionally persists them to the database for cross-restart history.

> **Note:** This API provides _system-level application logs_ (JUL/JBoss log records). For conversation message history (user/assistant messages), use the [Conversation Log endpoint](#conversation-log) instead.

---

## Endpoints

### Recent Logs (Ring Buffer)

```
GET /administration/logs
```

Returns recent log entries from the in-memory ring buffer. These are fast to query but do not survive restarts.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `agentId` | string | — | Filter by agent ID |
| `conversationId` | string | — | Filter by conversation ID |
| `level` | string | — | Minimum log level (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`). Returns entries **at or above** this level |
| `limit` | integer | `200` | Maximum number of entries to return |

**Example:**

```bash
# Get the 50 most recent WARN+ entries for a specific agent
curl "http://localhost:7070/administration/logs?agentId=my-agent&level=WARN&limit=50"
```

**Response:**

```json
[
  {
    "timestamp": 1711900800000,
    "level": "WARN",
    "loggerName": "ai.labs.eddi.modules.llm.impl.LlmTask",
    "message": "LLM response was not valid JSON, storing as plain string",
    "environment": "production",
    "agentId": "my-agent",
    "agentVersion": 3,
    "conversationId": "conv-abc123",
    "userId": "user-42",
    "instanceId": "eddi-host-a1b2"
  }
]
```

---

### Historical Logs (Database)

```
GET /administration/logs/history
```

Returns historical logs from the database. These survive restarts and work across instances. Only logs at or above the configured `eddi.logs.db-persist-min-level` are persisted.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `environment` | enum | — | Filter by deployment environment (`production`, `test`, etc.) |
| `agentId` | string | — | Filter by agent ID |
| `agentVersion` | integer | — | Filter by agent version |
| `conversationId` | string | — | Filter by conversation ID |
| `userId` | string | — | Filter by user ID |
| `instanceId` | string | — | Filter by EDDI instance ID (useful in multi-instance deployments) |
| `skip` | integer | `0` | Number of entries to skip (pagination) |
| `limit` | integer | `50` | Maximum entries to return |

**Example:**

```bash
# Get historical errors for a specific conversation
curl "http://localhost:7070/administration/logs/history?conversationId=conv-abc123&limit=100"
```

---

### Live Log Stream (SSE)

```
GET /administration/logs/stream
```

Opens a Server-Sent Events (SSE) connection for real-time log tailing. Supports the same filters as the recent logs endpoint.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `agentId` | string | — | Filter by agent ID |
| `conversationId` | string | — | Filter by conversation ID |
| `level` | string | — | Minimum log level (same semantics as recent logs) |

**Example:**

```bash
# Live-tail all WARN+ logs
curl -N -H "Accept: text/event-stream" \
  "http://localhost:7070/administration/logs/stream?level=WARN"
```

Each SSE event contains a JSON-serialized `LogEntry`:

```
data: {"timestamp":1711900800000,"level":"ERROR","loggerName":"...","message":"..."}
```

---

### Instance ID

```
GET /administration/logs/instance-id
```

Returns the unique identifier for this EDDI instance. Useful for correlating logs in multi-instance deployments.

**Response:**

```json
{
  "instanceId": "eddi-host-a1b2c3d4"
}
```

---

## Configuration

All logging configuration lives in `application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `eddi.logs.buffer-size` | `10000` | Ring buffer capacity (in-memory entries) |
| `eddi.logs.db-enabled` | `true` | Enable/disable database persistence |
| `eddi.logs.db-flush-interval-seconds` | `5` | How often the async writer flushes to the database |
| `eddi.logs.db-persist-min-level` | `WARN` | Minimum level to persist to the database (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`) |

---

## Architecture

```
┌──────────────────┐     ┌─────────────────────────────────────┐
│ Quarkus Logger   │────→│ LogCaptureFilter (@LoggingFilter)   │
│ (all log records)│     │ Intercepts every JBoss LogRecord    │
└──────────────────┘     └───────────┬─────────────────────────┘
                                     │
                                     ▼
                         ┌───────────────────────┐
                         │   BoundedLogStore      │
                         │                       │
                         │  ┌─────────────────┐  │
                         │  │  Ring Buffer     │──┼──→ GET /administration/logs
                         │  │  (ArrayDeque)    │  │
                         │  └─────────────────┘  │
                         │                       │
                         │  ┌─────────────────┐  │
                         │  │  SSE Listeners   │──┼──→ GET /administration/logs/stream
                         │  └─────────────────┘  │
                         │                       │
                         │  ┌─────────────────┐  │     ┌──────────────┐
                         │  │  Async DB Queue  │──┼────→│ IDatabaseLogs│
                         │  │  (batch flush)   │  │     │ (Mongo / PG) │──→ GET .../history
                         │  └─────────────────┘  │     └──────────────┘
                         └───────────────────────┘
```

The `LogCaptureFilter` captures **every** log record (all levels) into the ring buffer for instant query. Only entries meeting the `db-persist-min-level` threshold are enqueued for async batch persistence to the database.

---

## Related APIs

| API | Path | Purpose |
|-----|------|---------|
| **Log Admin** (this page) | `/administration/logs` | System-wide application logs |
| **[Conversation Log](conversations.md)** | `/agents/{conversationId}/log` | Per-conversation message history (user/assistant turns) |
| **[Audit Ledger](audit-ledger.md)** | `/auditstore` | EU AI Act compliance audit trail |

These three APIs serve distinct purposes and are not redundant.

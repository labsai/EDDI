# Metrics

E.D.D.I exposes all kinds of internal JVM metrics as prometheus export, including comprehensive tool management metrics for declarative agents.

These metrics are viewable here:

```
http://<eddi-instance>/q/metrics
```

In order to visualize these metrics, you can use this predefined dashboard for E.D.D.I:

[E.D.D.I dashboard for Grafana](https://grafana.com/grafana/dashboards/11179)

---

## Tool Management Metrics

**Version: ≥5.6.0**

EDDI provides comprehensive metrics for monitoring tool usage, performance, caching, costs, and rate limits.

### Cache Metrics

Monitor tool caching performance with Infinispan-based smart caching:

```
eddi.tool.cache.hits                    # Total cache hits
eddi.tool.cache.misses                  # Total cache misses
eddi.tool.cache.hits{tool="weather"}    # Hits per tool
eddi.tool.cache.misses{tool="weather"}  # Misses per tool
eddi.tool.cache.puts{tool="weather"}    # Cache puts per tool
eddi.tool.cache.get.duration            # Cache get duration (timer)
eddi.tool.cache.put.duration            # Cache put duration (timer)
eddi.tool.cache.size                    # Current cache size (gauge)
```

**Example Prometheus Query - Cache Hit Rate:**
```promql
rate(eddi_tool_cache_hits_total[5m]) / 
  (rate(eddi_tool_cache_hits_total[5m]) + 
   rate(eddi_tool_cache_misses_total[5m])) * 100
```

### Rate Limiting Metrics

Monitor rate limiting and abuse prevention:

```
eddi.tool.ratelimit.allowed               # Total allowed calls
eddi.tool.ratelimit.denied                # Total denied calls
eddi.tool.ratelimit.allowed{tool="X"}     # Allowed per tool
eddi.tool.ratelimit.denied{tool="X"}      # Denied per tool
eddi.tool.ratelimit.remaining             # Remaining calls (gauge)
```

**Example Prometheus Query - Rate Limit Violations:**
```promql
rate(eddi_tool_ratelimit_denied_total[5m])
```

### Cost Tracking Metrics

Monitor API costs and budget usage:

```
eddi.tool.calls.total                   # Total tool calls
eddi.tool.calls{tool="X"}               # Calls per tool
eddi.tool.costs{tool="X"}               # Cost per tool (cumulative)
eddi.tool.costs.total                   # Total cumulative cost (gauge)
eddi.tool.budget.exceeded               # Budget exceeded events
```

**Example Prometheus Query - Cost Per Hour:**
```promql
rate(eddi_tool_costs_total[1h]) * 3600
```

### Tool Execution Metrics

Monitor tool performance and reliability:

```
eddi.tool.execution.success               # Successful executions
eddi.tool.execution.failure               # Failed executions
eddi.tool.execution.success{tool="X"}     # Success per tool
eddi.tool.execution.failure{tool="X"}     # Failures per tool
eddi.tool.execution.cached{tool="X"}      # Cache hits per tool
eddi.tool.execution.ratelimited{tool="X"} # Rate limited per tool
eddi.tool.execution.duration              # Execution duration (timer)
eddi.tool.execution.duration{tool="X"}    # Duration per tool (timer)
eddi.tool.execution.parallel              # Parallel execution count
eddi.tool.execution.parallel.count        # Number of parallel tools
eddi.tool.execution.parallel.duration     # Parallel execution time (timer)
eddi.tool.execution.parallel.timeout      # Parallel timeouts
eddi.tool.execution.parallel.error        # Parallel errors
```

**Example Prometheus Query - Success Rate:**
```promql
rate(eddi_tool_execution_success_total[5m]) /
  (rate(eddi_tool_execution_success_total[5m]) +
   rate(eddi_tool_execution_failure_total[5m])) * 100
```

**Example Prometheus Query - P95 Latency:**
```promql
histogram_quantile(0.95, 
  rate(eddi_tool_execution_duration_bucket[5m]))
```

---

## Sample Grafana Dashboard

### Tool System Overview
```json
{
  "title": "Tool System Health",
  "panels": [
    {
      "title": "Tool Call Rate",
      "targets": [{
        "expr": "rate(eddi_tool_calls_total[5m])",
        "legendFormat": "Calls/sec"
      }]
    },
    {
      "title": "Cache Hit Rate",
      "targets": [{
        "expr": "rate(eddi_tool_cache_hits_total[5m]) / (rate(eddi_tool_cache_hits_total[5m]) + rate(eddi_tool_cache_misses_total[5m])) * 100",
        "legendFormat": "Hit Rate %"
      }]
    },
    {
      "title": "Cost Rate",
      "targets": [{
        "expr": "rate(eddi_tool_costs_total[1h]) * 3600",
        "legendFormat": "$/hour"
      }]
    },
    {
      "title": "Success Rate",
      "targets": [{
        "expr": "rate(eddi_tool_execution_success_total[5m]) / (rate(eddi_tool_execution_success_total[5m]) + rate(eddi_tool_execution_failure_total[5m])) * 100",
        "legendFormat": "Success %"
      }]
    }
  ]
}
```

---

## Prometheus Alerts

### Sample Alert Rules

```yaml
groups:
  - name: eddi_tool_alerts
    rules:
      # Critical Alerts
      - alert: ToolSystemDown
        expr: rate(eddi_tool_execution_success_total[5m]) == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "No successful tool executions in 2 minutes"
      
      - alert: BudgetExceeded
        expr: eddi_tool_costs_total > 10
        labels:
          severity: critical
        annotations:
          summary: "Total tool costs exceeded $10"
      
      # Warning Alerts
      - alert: HighToolFailureRate
        expr: rate(eddi_tool_execution_failure_total[5m]) / rate(eddi_tool_execution_success_total[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tool failure rate above 5%"
      
      - alert: CacheDegraded
        expr: |
          (rate(eddi_tool_cache_hits_total[5m]) /
           (rate(eddi_tool_cache_hits_total[5m]) +
            rate(eddi_tool_cache_misses_total[5m]))) < 0.5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Cache hit rate below 50%"
      
      - alert: RateLimitHigh
        expr: rate(eddi_tool_ratelimit_denied_total[5m]) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High rate limit denials: {{$value}}/5min"
```

---

## Accessing Metrics

### Via Prometheus
```
http://localhost:7070/q/metrics
```

### Via REST API
EDDI also provides REST endpoints for tool metrics:

```bash
# Cache stats
GET /langchain/tools/cache/stats

# Rate limit info
GET /langchain/tools/ratelimit/{toolName}

# Cost tracking
GET /langchain/tools/costs
GET /langchain/tools/costs/conversation/{conversationId}
GET /langchain/tools/costs/tool/{toolName}

# Tool history
GET /langchain/tools/history/{conversationId}
```

---

## Monitoring Best Practices

### Key Metrics to Monitor

1. **Cache Hit Rate** - Target: >70%
```promql
rate(eddi_tool_cache_hits_total[5m]) / 
  (rate(eddi_tool_cache_hits_total[5m]) + 
   rate(eddi_tool_cache_misses_total[5m]))
```

2. **Tool Success Rate** - Target: >95%
```promql
rate(eddi_tool_execution_success_total[5m]) /
  (rate(eddi_tool_execution_success_total[5m]) +
   rate(eddi_tool_execution_failure_total[5m]))
```

3. **P95 Latency** - Target: <2 seconds
```promql
histogram_quantile(0.95, 
  rate(eddi_tool_execution_duration_bucket[5m]))
```

4. **Cost Per Request** - Target: <$0.001
```promql
rate(eddi_tool_costs_total[1h]) /
  rate(eddi_tool_calls_total[1h])
```

---

## Additional Resources

- **[LangChain Integration Guide](langchain.md)** - Full LangChain and agent documentation
- **[Prometheus Documentation](https://prometheus.io/docs/)** - Prometheus setup
- **[Grafana Documentation](https://grafana.com/docs/)** - Dashboard creation
- **[Micrometer Documentation](https://micrometer.io/docs)** - Metrics framework


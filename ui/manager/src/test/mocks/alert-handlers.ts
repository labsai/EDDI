import { http, HttpResponse } from "msw";
import { MASKED_URL_SUFFIX, type AlertEvent, type AlertRule, type DeliveryTarget } from "@/lib/api/alerts";

/**
 * Alerts — `/alerts/rules` and `/alerts/events` against an in-memory store shaped
 * like the backend's `AlertRule` / `AlertEvent`.
 *
 * Follows the backend closely enough for the Alerts page: the inbox filters on
 * agent, source and acknowledged; acknowledging records a caller; a retry puts
 * failed deliveries back to pending; a rule without a name answers 400
 * `INVALID_RULE`; a test send of a target whose channel is
 * {@link FAILING_CHANNEL} reports a failure.
 */

export const FAILING_CHANNEL = "C0FAILING";

const minute = 60_000;

function labelOf(target: DeliveryTarget): string {
  if (target.type === "SLACK") return `Slack ${target.channelId}`;
  try {
    return `Webhook ${new URL(target.url ?? "").host}`;
  } catch {
    return "Webhook ?";
  }
}

/** The full webhook URL the mock "server" keeps; reads only ever show it masked. */
export const STORED_HOOK_URL = "https://hooks.example.com/services/T000/B000/s3cr3t";

/**
 * The rules as the mock stores them: full webhook URLs, every target with a
 * server-assigned `targetId`. Reads go through {@link readForm}.
 */
function storedRuleFixtures(): AlertRule[] {
  return [
    {
      id: "rule-ratings",
      name: "Unhappy users",
      enabled: true,
      scope: { allAgents: false, agentIds: ["agent1"] },
      source: "RATING",
      condition: { maxNormalizedScore: 0.25, onComment: true },
      delivery: [{ type: "SLACK", channelId: "C0123ABC", targetId: "t-slack-1" }],
      throttle: { windowMinutes: 60, maxPerWindow: 10 },
      digest: "HOURLY",
      createdBy: "admin",
    },
    {
      id: "rule-errors",
      name: "Broken turns",
      enabled: true,
      scope: { allAgents: true, agentIds: [] },
      source: "CONVERSATION_ERROR",
      condition: { errorKinds: ["TURN_ERROR", "TURN_DEADLINE"] },
      delivery: [{ type: "WEBHOOK", url: STORED_HOOK_URL, signingSecret: "${vault:alert-hook}", targetId: "t-hook-1" }],
      throttle: { windowMinutes: 60, maxPerWindow: 10 },
      digest: "DAILY",
      createdBy: "admin",
    },
  ];
}

/** A target as a read shows it: a literal webhook URL masked to scheme + host. */
function maskTarget(target: DeliveryTarget): DeliveryTarget {
  if (target.type !== "WEBHOOK" || !target.url) return { ...target, urlConfigured: null };
  if (/^\$\{vault:[^}]+\}$/.test(target.url)) return { ...target, urlConfigured: true };
  let origin = "https://?";
  try {
    origin = new URL(target.url).origin;
  } catch {
    /* keep the placeholder */
  }
  return { ...target, url: `${origin}${MASKED_URL_SUFFIX}`, urlConfigured: true };
}

function readForm(rule: AlertRule): AlertRule {
  return { ...rule, delivery: rule.delivery.map(maskTarget) };
}

/** The rules as the backend answers a read (masked URLs, ids). */
export function alertRuleFixtures(): AlertRule[] {
  return storedRuleFixtures().map(readForm);
}

let nextTargetId = 1;

/**
 * Mirrors the server: a masked or empty URL is restored only from the stored
 * target with the same `targetId`; ids the stored rule lacks are dropped and
 * missing ones assigned. Returns an error response for a masked or empty URL
 * nothing restores.
 */
function withStoredUrls(rule: AlertRule, current: AlertRule | null): AlertRule | Response {
  const stored = new Map((current?.delivery ?? []).map((t) => [t.targetId, t]));
  const seen = new Set<string>();
  const delivery: DeliveryTarget[] = [];
  for (const raw of rule.delivery) {
    const target: DeliveryTarget = { ...raw, urlConfigured: undefined };
    const needsStored = target.type === "WEBHOOK" && (!target.url || target.url.endsWith(MASKED_URL_SUFFIX));
    const same = target.targetId ? stored.get(target.targetId) : undefined;
    if (needsStored) {
      if (!same?.url) {
        return HttpResponse.json(
          { error: "INVALID_RULE", message: "delivery url is the masked value a read returns, and its targetId matches no stored target" },
          { status: 400 },
        );
      }
      target.url = same.url;
    }
    const keepId = !!target.targetId && stored.has(target.targetId) && !seen.has(target.targetId);
    if (keepId) seen.add(target.targetId!);
    delivery.push({ ...target, targetId: keepId ? target.targetId : `t-new-${nextTargetId++}` });
  }
  return { ...rule, delivery };
}

export function alertEventFixtures(): AlertEvent[] {
  const ago = (minutes: number) => new Date(Date.now() - minutes * minute).toISOString();
  return [
    {
      id: "alert-1",
      ruleId: "rule-ratings",
      ruleName: "Unhappy users",
      source: "RATING",
      kind: "ALERT",
      agentId: "agent1",
      agentVersion: 3,
      conversationId: "conv-1",
      firedAt: ago(5),
      summary: "Unhappy users: conversation rating 2 (FIVE_STAR) — normalizedScore <= 0.25, comment (agent agent1 v3)",
      match: { matched: ["normalizedScore <= 0.25", "comment"], ratingId: "r1", stepIndex: -1, scale: "FIVE_STAR", score: 2, normalizedScore: 0.25, tags: ["Wrong answer"], commentExcerpt: "It kept looping on my order number." },
      deliveries: [{ targetKey: "k-slack", type: "SLACK", label: "Slack C0123ABC", status: "DELIVERED", attempts: 1, lastAttemptAt: ago(5) }],
      state: "DELIVERED",
      duplicates: 0,
    },
    {
      id: "alert-2",
      ruleId: "rule-errors",
      ruleName: "Broken turns",
      source: "CONVERSATION_ERROR",
      kind: "ALERT",
      agentId: "agent1",
      agentVersion: 3,
      conversationId: "conv-7",
      firedAt: ago(30),
      summary: "Broken turns: a turn failed (agent agent1 v3)",
      match: { matched: ["errorKind: TURN_ERROR"], errorKind: "TURN_ERROR" },
      deliveries: [
        { targetKey: "k-hook", type: "WEBHOOK", label: "Webhook hooks.example.com", status: "FAILED", attempts: 5, lastAttemptAt: ago(10), lastError: "The webhook answered HTTP 503" },
      ],
      state: "FAILED",
      duplicates: 2,
    },
    {
      id: "alert-3",
      ruleId: "rule-errors",
      ruleName: "Broken turns",
      source: "CONVERSATION_ERROR",
      kind: "ALERT",
      agentId: "agent2",
      agentVersion: 1,
      conversationId: "conv-9",
      firedAt: ago(120),
      summary: "Broken turns: a turn exceeded its deadline (agent agent2 v1)",
      match: { matched: ["errorKind: TURN_DEADLINE"], errorKind: "TURN_DEADLINE" },
      deliveries: [],
      state: "INBOX_ONLY",
      duplicates: 0,
      acknowledgedBy: "maintainer@example.com",
      acknowledgedAt: ago(60),
    },
  ];
}

let rules: AlertRule[] = storedRuleFixtures();
let events: AlertEvent[] = alertEventFixtures();
let nextId = 1;

/** Back to the fixtures — called between tests (see setup). */
export function resetAlertStore() {
  rules = storedRuleFixtures();
  nextTargetId = 1;
  events = alertEventFixtures();
  nextId = 1;
}

function filteredEvents(url: URL): AlertEvent[] {
  const p = url.searchParams;
  return events.filter((e) => {
    if (p.get("agentId") && e.agentId !== p.get("agentId")) return false;
    if (p.get("ruleId") && e.ruleId !== p.get("ruleId")) return false;
    if (p.get("source") && e.source !== p.get("source")) return false;
    if (p.get("state") && e.state !== p.get("state")) return false;
    if (p.get("conversationId") && e.conversationId !== p.get("conversationId")) return false;
    if (p.get("acknowledged") === "false" && e.acknowledgedAt) return false;
    if (p.get("acknowledged") === "true" && !e.acknowledgedAt) return false;
    return true;
  });
}

function acknowledge(event: AlertEvent): boolean {
  if (event.acknowledgedAt) return false;
  event.acknowledgedBy = "admin";
  event.acknowledgedAt = new Date().toISOString();
  return true;
}

function invalid(rule: AlertRule) {
  return !rule.name?.trim()
    ? HttpResponse.json({ error: "INVALID_RULE", message: "name is required" }, { status: 400 })
    : null;
}

export const alertHandlers = [
  http.get("*/alerts/rules", ({ request }) => {
    const agentId = new URL(request.url).searchParams.get("agentId");
    const list = agentId ? rules.filter((r) => r.scope.agentIds.includes(agentId)) : rules;
    return HttpResponse.json({ total: list.length, index: 0, limit: 50, rules: list.map(readForm) });
  }),

  http.post("*/alerts/rules/test", async ({ request }) => {
    const body = (await request.json()) as AlertRule;
    const refused = invalid(body);
    if (refused) return refused;
    // A saved rule's masked URLs are restored by targetId, as on update.
    const current = body.id ? (rules.find((r) => r.id === body.id) ?? null) : null;
    const rule = withStoredUrls(body, current);
    if (rule instanceof Response) return rule;
    return HttpResponse.json({
      deliveries: rule.delivery.map((target) => ({
        type: target.type,
        label: labelOf(target),
        delivered: target.channelId !== FAILING_CHANNEL,
        error: target.channelId === FAILING_CHANNEL ? "Slack refused the message (see the server log for the API error)" : null,
      })),
    });
  }),

  http.post("*/alerts/rules", async ({ request }) => {
    const body = (await request.json()) as AlertRule;
    const refused = invalid(body);
    if (refused) return refused;
    const rule = withStoredUrls(body, null);
    if (rule instanceof Response) return rule;
    const stored = { ...rule, id: `rule-new-${nextId++}`, createdBy: "admin", createdAt: new Date().toISOString() };
    rules = [stored, ...rules];
    return HttpResponse.json(readForm(stored), { status: 201 });
  }),

  http.put("*/alerts/rules/:ruleId", async ({ params, request }) => {
    const body = (await request.json()) as AlertRule;
    const refused = invalid(body);
    if (refused) return refused;
    const index = rules.findIndex((r) => r.id === params.ruleId);
    if (index < 0) return HttpResponse.json({ error: "RULE_NOT_FOUND", message: "Alert rule not found" }, { status: 404 });
    const rule = withStoredUrls(body, rules[index] ?? null);
    if (rule instanceof Response) return rule;
    rules[index] = { ...rule, id: String(params.ruleId) };
    return HttpResponse.json(readForm(rules[index]));
  }),

  http.delete("*/alerts/rules/:ruleId", ({ params }) => {
    const before = rules.length;
    rules = rules.filter((r) => r.id !== params.ruleId);
    return before === rules.length
      ? HttpResponse.json({ error: "RULE_NOT_FOUND", message: "Alert rule not found" }, { status: 404 })
      : new HttpResponse(null, { status: 204 });
  }),

  http.get("*/alerts/events/unacknowledged-count", ({ request }) => {
    const agentId = new URL(request.url).searchParams.get("agentId");
    return HttpResponse.json({ count: events.filter((e) => !e.acknowledgedAt && (!agentId || e.agentId === agentId)).length });
  }),

  http.post("*/alerts/events/acknowledge", async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as { ids?: (string | null)[] };
    // As on the server: a present ids list is the selector — empty, or with an empty entry, is a 400.
    if (body.ids !== undefined && (body.ids === null || body.ids.length === 0 || body.ids.some((id) => !id || !id.trim()))) {
      return HttpResponse.json({ error: "INVALID_PARAMETER", message: "ids must name at least one event" }, { status: 400 });
    }
    const targets = body.ids ? events.filter((e) => body.ids!.includes(e.id)) : filteredEvents(new URL(request.url));
    return HttpResponse.json({ acknowledged: targets.filter(acknowledge).length });
  }),

  http.get("*/alerts/events", ({ request }) => {
    const url = new URL(request.url);
    const index = Number(url.searchParams.get("index") ?? 0);
    const limit = Number(url.searchParams.get("limit") ?? 25);
    const list = filteredEvents(url);
    return HttpResponse.json({ total: list.length, index, limit, events: list.slice(index, index + limit) });
  }),

  http.get("*/alerts/events/:eventId", ({ params }) => {
    const event = events.find((e) => e.id === params.eventId);
    return event ? HttpResponse.json(event) : HttpResponse.json({ error: "EVENT_NOT_FOUND", message: "Alert event not found" }, { status: 404 });
  }),

  http.post("*/alerts/events/:eventId/acknowledge", ({ params }) => {
    const event = events.find((e) => e.id === params.eventId);
    if (!event) return HttpResponse.json({ error: "EVENT_NOT_FOUND", message: "Alert event not found" }, { status: 404 });
    acknowledge(event);
    return HttpResponse.json(event);
  }),

  http.post("*/alerts/events/:eventId/retry", ({ params }) => {
    const event = events.find((e) => e.id === params.eventId);
    if (!event) return HttpResponse.json({ error: "EVENT_NOT_FOUND", message: "Alert event not found" }, { status: 404 });
    if (!event.deliveries.some((d) => d.status === "FAILED")) {
      return HttpResponse.json({ error: "NOTHING_TO_RETRY", message: "No delivery of this alert has failed" }, { status: 409 });
    }
    event.deliveries = event.deliveries.map((d) => (d.status === "FAILED" ? { ...d, status: "PENDING", attempts: 0 } : d));
    event.state = "PENDING";
    return HttpResponse.json(event);
  }),
];

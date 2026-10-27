import { http, HttpResponse } from "msw";
import type { OwnRating, RatingRow, RatingSummary } from "@/lib/api/ratings";

/**
 * Conversation ratings — the maintainers' `/ratings` endpoints and the owner's
 * `/agents/{conversationId}/rating` endpoints, against an in-memory store shaped
 * like the backend's `RatingRow` (IRestConversationRatingAdmin).
 *
 * Filtering, paging, summaries and triage follow the backend's rules closely
 * enough for the Feedback page to behave as it does live: `needsAttention` is
 * NEW and (normalized ≤ 0.5 or a comment); a re-rating resets triage; a PATCH
 * of the rating whose id is {@link CHANGED_RATING_ID} answers 409
 * `RATING_CHANGED`, the way a re-rating mid-review does.
 */

export const CHANGED_RATING_ID = "rating-changed";

const day = 86_400_000;

function row(partial: Partial<RatingRow> & Pick<RatingRow, "id" | "conversationId" | "score">): RatingRow {
  const scale = partial.scale ?? "FIVE_STAR";
  const normalized = scale === "FIVE_STAR" ? (partial.score - 1) / 4 : partial.score;
  const created = partial.createdAt ?? new Date(Date.now() - day).toISOString();
  return {
    stepIndex: -1,
    kind: (partial.stepIndex ?? -1) === -1 ? "conversation" : "message",
    agentId: "agent1",
    agentVersion: 3,
    environment: "production",
    channel: "chat-ui",
    scale,
    normalizedScore: normalized,
    tags: [],
    comment: null,
    createdAt: created,
    updatedAt: created,
    reviewStatus: "NEW",
    reviewedBy: null,
    reviewedAt: null,
    reviewNote: null,
    userId: "user-42",
    canOpenTranscript: true,
    ...partial,
  };
}

export function ratingFixtures(): RatingRow[] {
  const ago = (days: number) => new Date(Date.now() - days * day).toISOString();
  return [
    row({ id: "r1", conversationId: "conv-1", score: 2, tags: ["Wrong answer"], comment: "It kept looping on my order number.", createdAt: ago(1) }),
    row({ id: "r2", conversationId: "conv-2", score: 5, tags: ["Helpful"], createdAt: ago(2), agentVersion: 3 }),
    row({ id: "r3", conversationId: "conv-3", score: 4, comment: "Quick and friendly =HYPERLINK(\"x\")", createdAt: ago(3), agentVersion: 2, canOpenTranscript: false }),
    row({ id: "r4", conversationId: "conv-3", stepIndex: 2, scale: "THUMBS", score: 0, tags: ["Too slow"], createdAt: ago(3), agentVersion: 2 }),
    row({ id: "r5", conversationId: "conv-4", score: 1, createdAt: ago(5), reviewStatus: "REVIEWED", reviewedBy: "maintainer@example.com", reviewedAt: ago(4), reviewNote: "Escalated to support." }),
    row({ id: "r6", conversationId: "conv-5", score: 3, createdAt: ago(9), agentId: "agent2", agentVersion: 2, channel: "api" }),
  ];
}

let store: RatingRow[] = ratingFixtures();
let ownRatings = new Map<string, Map<number, OwnRating>>();

/** Back to the fixtures — called between tests (see `resetRatingStore` in setup). */
export function resetRatingStore() {
  store = ratingFixtures();
  ownRatings = new Map();
}

const attention = (r: RatingRow) => r.reviewStatus === "NEW" && (r.normalizedScore <= 0.5 || !!r.comment);

function filtered(url: URL): RatingRow[] {
  const p = url.searchParams;
  const num = (key: string) => (p.has(key) ? Number(p.get(key)) : undefined);
  return store.filter((r) => {
    if (p.get("agentId") && r.agentId !== p.get("agentId")) return false;
    if (p.get("conversationId") && r.conversationId !== p.get("conversationId")) return false;
    if (num("agentVersion") !== undefined && r.agentVersion !== num("agentVersion")) return false;
    if (p.get("environment") && r.environment !== p.get("environment")) return false;
    if (p.get("kind") && r.kind !== p.get("kind")) return false;
    if (p.get("tag") && !r.tags.includes(p.get("tag")!)) return false;
    if (p.get("hasComment") === "true" && !r.comment) return false;
    if (p.get("hasComment") === "false" && r.comment) return false;
    if (p.get("reviewStatus") && r.reviewStatus !== p.get("reviewStatus")) return false;
    if (p.get("needsAttention") === "true" && !attention(r)) return false;
    if (num("minScore") !== undefined && r.normalizedScore < num("minScore")!) return false;
    if (num("maxScore") !== undefined && r.normalizedScore > num("maxScore")!) return false;
    if (p.get("from") && (r.createdAt ?? "") < new Date(p.get("from")!).toISOString()) return false;
    if (p.get("to") && (r.createdAt ?? "") >= new Date(p.get("to")!).toISOString()) return false;
    return true;
  });
}

function summarize(rows: RatingRow[], bucket: string): RatingSummary {
  const stars: Record<string, number> = { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0 };
  let up = 0;
  let down = 0;
  const tags = new Map<string, number>();
  for (const r of rows) {
    if (r.scale === "FIVE_STAR") stars[String(r.score)] = (stars[String(r.score)] ?? 0) + 1;
    else if (r.score === 1) up += 1;
    else down += 1;
    r.tags.forEach((t) => tags.set(t, (tags.get(t) ?? 0) + 1));
  }
  const starTotal = Object.values(stars).reduce((a, b) => a + b, 0);
  return {
    bucket,
    count: rows.length,
    avgNormalized: rows.length ? rows.reduce((s, r) => s + r.normalizedScore, 0) / rows.length : null,
    distribution: { thumbsUp: up, thumbsDown: down, stars },
    csat: starTotal ? ((stars["4"] ?? 0) + (stars["5"] ?? 0)) / starTotal : null,
    thumbsUpRate: up + down ? up / (up + down) : null,
    commentCount: rows.filter((r) => r.comment).length,
    topTags: [...tags.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10).map(([tag, count]) => ({ tag, count })),
  };
}

function bucketOf(r: RatingRow, groupBy: string): string {
  switch (groupBy) {
    case "day":
      return (r.createdAt ?? "").slice(0, 10);
    case "agentVersion":
      return String(r.agentVersion ?? "unknown");
    case "channel":
      return r.channel ?? "other";
    default:
      return "all";
  }
}

const csvCell = (value: unknown) => {
  if (value === null || value === undefined || value === "") return "";
  const text = String(value);
  const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text;
  return `"${safe.replace(/"/g, '""')}"`;
};

export const ratingHandlers = [
  http.get("*/ratings/summary", ({ request }) => {
    const url = new URL(request.url);
    const groupBy = url.searchParams.get("groupBy") ?? "none";
    const rows = filtered(url);
    if (groupBy === "none") return HttpResponse.json({ groupBy, buckets: [summarize(rows, "all")] });
    const groups = new Map<string, RatingRow[]>();
    rows.forEach((r) => groups.set(bucketOf(r, groupBy), [...(groups.get(bucketOf(r, groupBy)) ?? []), r]));
    const buckets = [...groups.entries()]
      .sort(([a], [b]) => (groupBy === "agentVersion" ? Number(a) - Number(b) : a.localeCompare(b)))
      .map(([key, members]) => summarize(members, key));
    return HttpResponse.json({ groupBy, buckets });
  }),

  http.get("*/ratings/attention-count", ({ request }) => {
    const agentId = new URL(request.url).searchParams.get("agentId");
    return HttpResponse.json({ count: store.filter((r) => attention(r) && (!agentId || r.agentId === agentId)).length });
  }),

  http.get("*/ratings/export", ({ request }) => {
    const rows = filtered(new URL(request.url));
    const header = "id,conversationId,stepIndex,kind,agentId,agentVersion,environment,channel,scale,score,normalizedScore,tags,comment,createdAt,updatedAt,reviewStatus,reviewedBy,reviewedAt,reviewNote,userId";
    const lines = rows.map((r) =>
      [csvCell(r.id), csvCell(r.conversationId), r.stepIndex, csvCell(r.kind), csvCell(r.agentId), r.agentVersion ?? "",
        csvCell(r.environment), csvCell(r.channel), csvCell(r.scale), r.score, r.normalizedScore, csvCell(r.tags.join("|")),
        csvCell(r.comment), csvCell(r.createdAt), csvCell(r.updatedAt), csvCell(r.reviewStatus), csvCell(r.reviewedBy),
        csvCell(r.reviewedAt), csvCell(r.reviewNote), csvCell(r.userId)].join(","),
    );
    return new HttpResponse([header, ...lines].join("\r\n") + "\r\n", {
      headers: { "Content-Type": "text/csv; charset=UTF-8", "Content-Disposition": 'attachment; filename="conversation-ratings.csv"' },
    });
  }),

  http.post("*/ratings/review", ({ request }) => {
    const matching = filtered(new URL(request.url)).filter((r) => r.reviewStatus === "NEW");
    const now = new Date().toISOString();
    matching.forEach((r) => Object.assign(r, { reviewStatus: "REVIEWED", reviewedBy: "admin", reviewedAt: now }));
    return HttpResponse.json({ reviewed: matching.length });
  }),

  http.get("*/ratings", ({ request }) => {
    const url = new URL(request.url);
    const sort = url.searchParams.get("sort") ?? "newest";
    const index = Number(url.searchParams.get("index") ?? 0);
    const limit = Math.min(100, Number(url.searchParams.get("limit") ?? 20));
    const rows = [...filtered(url)].sort((a, b) =>
      sort === "lowest"
        ? a.normalizedScore - b.normalizedScore || (b.createdAt ?? "").localeCompare(a.createdAt ?? "")
        : (b.createdAt ?? "").localeCompare(a.createdAt ?? ""),
    );
    return HttpResponse.json({ total: rows.length, index, limit, ratings: rows.slice(index, index + limit) });
  }),

  http.patch("*/ratings/:ratingId", async ({ params, request }) => {
    const id = String(params.ratingId);
    if (id === CHANGED_RATING_ID) {
      return HttpResponse.json(
        { error: "RATING_CHANGED", message: "The rating was changed or withdrawn meanwhile; reload it and review again" },
        { status: 409 },
      );
    }
    const target = store.find((r) => r.id === id);
    if (!target) return HttpResponse.json({ error: "RATING_NOT_FOUND", message: "Rating not found" }, { status: 404 });
    const body = (await request.json()) as { reviewStatus?: "NEW" | "REVIEWED"; reviewNote?: string };
    if (body.reviewNote !== undefined && body.reviewNote.length > 2000) {
      return HttpResponse.json({ error: "NOTE_TOO_LONG", message: "reviewNote may be at most 2000 characters" }, { status: 400 });
    }
    if (body.reviewStatus === "REVIEWED") {
      Object.assign(target, { reviewStatus: "REVIEWED", reviewedBy: "admin", reviewedAt: new Date().toISOString() });
    } else if (body.reviewStatus === "NEW") {
      Object.assign(target, { reviewStatus: "NEW", reviewedBy: null, reviewedAt: null });
    }
    if (body.reviewNote !== undefined) target.reviewNote = body.reviewNote.trim() || null;
    return HttpResponse.json(target);
  }),

  // ── Owner endpoints (the Manager's chat) ──
  http.get("*/agents/:conversationId/rating", ({ params }) => {
    const ratings = [...(ownRatings.get(String(params.conversationId))?.values() ?? [])];
    return HttpResponse.json({
      conversation: ratings.find((r) => r.stepIndex === -1) ?? null,
      steps: ratings.filter((r) => r.stepIndex !== -1),
    });
  }),
  http.put("*/agents/:conversationId/rating", async ({ params, request }) => {
    const body = (await request.json()) as { score: number; tags?: string[]; comment?: string };
    const rating: OwnRating = { stepIndex: -1, score: body.score, tags: body.tags ?? [], comment: body.comment ?? null };
    const id = String(params.conversationId);
    ownRatings.set(id, (ownRatings.get(id) ?? new Map()).set(-1, rating));
    return HttpResponse.json(rating);
  }),
  http.delete("*/agents/:conversationId/rating", ({ params }) => {
    ownRatings.get(String(params.conversationId))?.delete(-1);
    return new HttpResponse(null, { status: 204 });
  }),
  http.put("*/agents/:conversationId/steps/:stepIndex/rating", async ({ params, request }) => {
    const body = (await request.json()) as { score: number; tags?: string[]; comment?: string };
    const step = Number(params.stepIndex);
    const rating: OwnRating = { stepIndex: step, score: body.score, tags: body.tags ?? [], comment: body.comment ?? null };
    const id = String(params.conversationId);
    ownRatings.set(id, (ownRatings.get(id) ?? new Map()).set(step, rating));
    return HttpResponse.json(rating);
  }),
  http.delete("*/agents/:conversationId/steps/:stepIndex/rating", ({ params }) => {
    ownRatings.get(String(params.conversationId))?.delete(Number(params.stepIndex));
    return new HttpResponse(null, { status: 204 });
  }),
];

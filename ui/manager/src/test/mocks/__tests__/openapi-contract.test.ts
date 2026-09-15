import { describe, it, expect } from "vitest";
import fs from "node:fs";
import path from "node:path";
import type { RequestHandler } from "msw";
import * as handlerModule from "../handlers";

/**
 * Does every mocked endpoint actually exist on the backend?
 *
 * 5,481 unit tests and 198 UI E2E tests validate the Manager against
 * `handlers.ts` — 5,000 lines of hand-written mocks. Nothing checked that those
 * mocks resemble EDDI. A mock that invents an endpoint, or keeps one the backend
 * has dropped, makes the whole suite prove the Manager is consistent with a
 * fiction the same repo wrote.
 *
 * This closes that in the direction that a snapshot can close cheaply: **no
 * handler may mock an operation the backend does not expose.** The other
 * direction — the backend gaining something the mocks lack — is what the
 * `integration` tier is for, since it needs a live instance to see.
 *
 * The snapshot is `openapi-operations.json`, method + path only, refreshed with
 * `npm run openapi:refresh`. See that script for why it is a snapshot rather
 * than a live fetch.
 *
 * ## What this does NOT cover, stated plainly
 *
 * `handlers.ts` only. Individual test files add roughly 640 more `server.use()`
 * overrides inline, ~2.6× this surface, and they are not checked — several are
 * known to mock endpoints EDDI no longer has, including the pre-rename
 * `GET /agents/production/:agentId/:conversationId` (MSW writes it with a
 * leading wildcard, spelled out here because that sequence would close this
 * comment) which this branch fixed in `e2e/`. Extending the check there is not
 * simply a matter of widening the
 * glob: some inline mocks are *deliberately* wrong, like the decoy in
 * `lib/api/__tests__/logs.test.ts` that mocks `/administration/logs/instance`
 * to prove the app calls `/instance-id`. Worth doing, separately, with a way to
 * mark intent.
 *
 * ## What this found when it was first run
 *
 * 301 handlers, 8 unmatched, of which three were real:
 * `parserstore/parsers/jsonSchema` and `snippetstore/snippets/jsonSchema` do not
 * exist (the backend serves `jsonSchema` for eleven stores, not those two) while
 * `schemas.ts` builds that URL generically for *every* resource type; and
 * `POST /snippetstore/snippets/{id}` does not exist either. All three are
 * exempted below with that reasoning rather than quietly deleted, because the
 * app-side fix is a separate change.
 */

const SNAPSHOT = path.join(__dirname, "..", "openapi-operations.json");

/**
 * Handlers that legitimately have no matching backend operation.
 *
 * Every entry carries a reason. An entry is a promise that the mismatch is
 * understood — not a place to silence a failure. Adding one without a reason
 * defeats the check.
 */
const EXEMPT: Record<string, string> = {
  "GET */openapi":
    "The document that defines this check. It does not describe itself, so it can never appear in its own path list.",

  "GET https://api.github.com/repos/labsai/EDDI/releases/latest":
    "Third party, by design — the update check. The single off-origin host the Manager contacts; update-check-card.test.tsx pins that invariant.",

  "GET */parserstore/parsers/jsonSchema":
    "DRIFT, tracked: EDDI serves jsonSchema for eleven stores and not parser. schemas.ts:20 builds the URL for every RESOURCE_TYPE, so this 404s in production while the mock answers 200. Fixing the app side is a separate change.",

  "GET */snippetstore/snippets/jsonSchema":
    "DRIFT, tracked: same as parserstore — no jsonSchema operation exists for this store.",

  "POST */snippetstore/snippets/:id":
    "DRIFT, tracked: the backend exposes put/get/delete on {id} for snippets, not post. parserstore does have post, which is probably where this was copied from.",

  "POST */secretstore/secrets/:tenantId/:keyName/rotate":
    "Anticipated absence, not drift: secrets.ts falls back to a plain PUT on 404/405, and says so. Worth knowing that the mock answering 200 means the fallback — the live path against a 6.3.0 backend — is never exercised by a test.",

  "GET */logs/recent":
    "Dead mock: production reads /administration/logs (logs.ts BASE + query string), which the snapshot does contain. Nothing calls this.",
};

/**
 * Endpoints registered by more than one handler, frozen as they were found.
 *
 * MSW resolves in registration order, so for each of these the *later* handler
 * — usually the more detailed one — is unreachable. `backupSyncHandlers`'
 * versions of the backup endpoints, for instance, never run: `handlers` claims
 * those paths first.
 *
 * Keyed by the NORMALISED path, because that is what MSW matches on.
 * path-to-regexp ignores parameter names, so `/ratelimit/:tool` and
 * `/ratelimit/:toolName` are one route to it and two strings to us — keying on
 * the raw pattern counted 52 duplicates and missed 6, including that pair
 * (handlers.ts:4166 and :4363, whose 60s and 45s reset windows differ and only
 * the first of which ever runs). Two people naming the same id differently is
 * the most likely way a duplicate gets introduced, and it was the one way this
 * ratchet could not see.
 *
 * Frozen rather than fixed here for the reason `check-i18n.mjs` freezes its
 * COLLIDING set: the list can shrink but never grow, so the debt is visible and
 * bounded while untangling 58 shadowed handlers stays a separate change with
 * its own test run. A NEW duplicate fails immediately — including one that
 * merely swaps for a removed entry, since the set is compared and not counted.
 */
const KNOWN_DUPLICATE_ROUTES: readonly string[] = [
  "DELETE */administration/coordinator/dead-letters",
  "DELETE */administration/coordinator/dead-letters/{}",
  "DELETE */schedulestore/schedules/{}",
  "GET */administration/coordinator/dead-letters",
  "GET */administration/coordinator/status",
  "GET */administration/quotas/{}",
  "GET */administration/quotas/{}/usage",
  "GET */agents/{}",
  "GET */apicallstore/apicalls/descriptors",
  "GET */apicallstore/apicalls/{}",
  "GET */auditstore/agent/{}",
  "GET */auditstore/{}",
  "GET */auditstore/{}/count",
  "GET */backup/export/{}",
  "GET */dictionarystore/dictionaries/descriptors",
  "GET */dictionarystore/dictionaries/{}",
  "GET */groups/{}/conversations",
  "GET */llm/tools/cache/stats",
  "GET */llm/tools/costs",
  "GET */llm/tools/costs/conversation/{}",
  "GET */llm/tools/history/{}",
  "GET */llm/tools/ratelimit/{}",
  "GET */llmstore/llms/descriptors",
  "GET */llmstore/llms/{}",
  "GET */mcpcallsstore/mcpcalls/descriptors",
  "GET */mcpcallsstore/mcpcalls/{}",
  "GET */outputstore/outputsets/descriptors",
  "GET */outputstore/outputsets/{}",
  "GET */parserstore/parsers/descriptors",
  "GET */parserstore/parsers/{}",
  "GET */propertysetterstore/propertysetters/descriptors",
  "GET */propertysetterstore/propertysetters/{}",
  "GET */ragstore/rags/descriptors",
  "GET */ragstore/rags/{}",
  "GET */rulestore/rulesets/descriptors",
  "GET */rulestore/rulesets/{}",
  "GET */schedulestore/schedules",
  "GET */schedulestore/schedules/admin/failed",
  "GET */schedulestore/schedules/{}",
  "GET */schedulestore/schedules/{}/fires",
  "GET */secretstore/secrets/health",
  "GET */snippetstore/snippets/descriptors",
  "GET */snippetstore/snippets/{}",
  "POST */administration/agents/setup",
  "POST */administration/coordinator/dead-letters/{}/replay",
  "POST */administration/quotas/{}/usage/reset",
  "POST */backup/export/{}",
  "POST */backup/import",
  "POST */backup/import/preview",
  "POST */groups/{}/conversations",
  "POST */schedulestore/schedules",
  "POST */schedulestore/schedules/{}/disable",
  "POST */schedulestore/schedules/{}/dismiss",
  "POST */schedulestore/schedules/{}/enable",
  "POST */schedulestore/schedules/{}/fire",
  "POST */schedulestore/schedules/{}/retry",
  "PUT */administration/quotas/{}",
  "PUT */schedulestore/schedules/{}",
];

/**
 * Every handler array the module exports, discovered rather than listed.
 *
 * This was a hand-copied list of 15 imports duplicating `server.ts` and
 * `browser-handlers.ts`. A 16th group added to `handlers.ts` and registered in
 * `server.ts` would have been mocked by the entire suite and invisible to the
 * check that exists to police it — the same silent-drift shape this file is
 * about, occurring in this file.
 */
const ALL_HANDLERS: RequestHandler[] = Object.values(handlerModule).flatMap((v) =>
  Array.isArray(v) ? (v as RequestHandler[]) : [],
);

/** `:id` and `{id}` both become `{}` so MSW and OpenAPI paths can be compared. */
function normalisePath(p: string): string {
  const withoutQuery = p.split("?")[0] ?? "";
  const collapsed = withoutQuery
    .split("/")
    .map((seg) =>
      seg.startsWith(":") || (seg.startsWith("{") && seg.endsWith("}")) ? "{}" : seg,
    )
    .join("/");
  return collapsed.replace(/\/+$/, "");
}

interface MockRoute {
  method: string;
  /** The pattern exactly as written, for exemption keys and failure messages. */
  raw: string;
  /** MSW's leading `*` spans path segments, so the pattern is a suffix. */
  wildcard: boolean;
  tail: string;
  /**
   * A pattern whose store segment is itself a parameter — a leading wildcard
   * followed by `:store/:plural/…` — is a catch-all standing in for many
   * concrete stores, so comparing it to one concrete spec path is meaningless.
   * It is matched segment-wise instead (see `matchesShape`), which still fails
   * if the shape is wrong.
   */
  genericStore: boolean;
  /**
   * A pattern that would answer every request — see `toRoute`. Reported on its
   * own rather than matched, because `endsWith("")` is true of every string.
   */
  matchesEverything: boolean;
}

function toRoute(handler: RequestHandler): MockRoute | null {
  const info = (handler as unknown as { info: { path: unknown; method: unknown } }).info;
  if (typeof info?.path !== "string" || typeof info?.method !== "string") return null;

  const raw = info.path;
  const wildcard = raw.startsWith("*");
  const stripped = raw.replace(/^\*/, "");
  const tail = normalisePath(stripped.startsWith("/") ? stripped : `/${stripped}`);
  const firstSegment = tail.split("/").filter(Boolean)[0];

  return {
    method: info.method.toUpperCase(),
    raw,
    wildcard,
    tail,
    genericStore: firstSegment === "{}",
    // `http.get("*")` or `http.get("*/")` normalises to an empty tail, and
    // `"anything".endsWith("")` is true — so the single most dangerous mock
    // possible, one that answers every request, is the one shape this check
    // would wave through unconditionally. Flagged rather than matched.
    matchesEverything: tail === "",
  };
}

const snapshot = JSON.parse(fs.readFileSync(SNAPSHOT, "utf8")) as {
  eddiVersion: string;
  operations: string[];
};

const SPEC_OPERATIONS = snapshot.operations.map((op) => {
  const [method, ...rest] = op.split(" ");
  return { method: method!, path: normalisePath(rest.join(" ")) };
});

/**
 * Segment-wise match, with the mock's `{}` standing for any single segment.
 *
 * This is what makes a catch-all — a leading wildcard, then `{store}/{plural}/descriptors` —
 * checkable instead of skippable: it still has to line up with a real operation
 * of the same shape and the same trailing segments, so a typo in
 * `descriptors` — or a method the backend does not offer there — fails.
 */
function matchesShape(route: MockRoute, specPath: string): boolean {
  const routeSegs = route.tail.split("/").filter(Boolean);
  const specSegs = specPath.split("/").filter(Boolean);

  // A leading `*` spans segments, so the mock describes the tail of the path.
  if (route.wildcard) {
    if (specSegs.length < routeSegs.length) return false;
  } else if (specSegs.length !== routeSegs.length) {
    return false;
  }

  const offset = specSegs.length - routeSegs.length;
  return routeSegs.every((seg, i) => seg === "{}" || seg === specSegs[offset + i]);
}

/**
 * Exact match: every segment equal, and a `{}` on one side only satisfied by a
 * `{}` on the other. Stricter than `matchesShape` — a mock that puts a
 * parameter where the backend has a literal segment is a mismatch worth
 * knowing about, so ordinary routes are held to this.
 */
function matchesExactly(route: MockRoute, specPath: string): boolean {
  return route.wildcard
    ? specPath === route.tail || specPath.endsWith(route.tail)
    : specPath === route.tail;
}

function isInSpec(route: MockRoute): boolean {
  // Catch-alls cannot be compared exactly — their store segment is a parameter
  // standing in for any store — so only those fall back to the shape match.
  const matches = route.genericStore ? matchesShape : matchesExactly;
  return SPEC_OPERATIONS.some(
    (op) => op.method === route.method && matches(route, op.path),
  );
}

describe(`MSW handlers against EDDI ${snapshot.eddiVersion}'s API surface`, () => {
  const routes = ALL_HANDLERS.map(toRoute).filter((r): r is MockRoute => r !== null);

  it("reads a snapshot that actually has operations in it", () => {
    // Guards the whole file: an empty or malformed snapshot would make every
    // assertion below vacuous rather than failing.
    expect(SPEC_OPERATIONS.length).toBeGreaterThan(100);
    expect(routes.length).toBeGreaterThan(100);
    // And a real version — `refresh` falls back to "unknown" if the document
    // omits `info.version`, which would silently degrade the describe title to
    // "EDDI unknown's API surface" and leave nobody able to say what this was
    // checked against.
    expect(snapshot.eddiVersion).toMatch(/^\d+\.\d+\.\d+/);
  });

  /** Routes with no matching backend operation, exemptions included. */
  const unmatched = routes.filter((r) => r.matchesEverything || !isInSpec(r));
  const unmatchedKeys = new Set(unmatched.map((r) => `${r.method} ${r.raw}`));

  it("registers no handler that would answer every request", () => {
    // `endsWith("")` is true of every string, so a bare `*` pattern is the one
    // shape the suffix match cannot fail on — and the most dangerous mock
    // possible. Named separately so the failure says what it is.
    const catchAlls = routes.filter((r) => r.matchesEverything).map((r) => `${r.method} ${r.raw}`);
    expect(
      catchAlls,
      "a bare `*` handler answers everything and makes every other check meaningless",
    ).toEqual([]);
  });

  it("mocks no endpoint the backend does not expose", () => {
    const unexplained = unmatched
      .filter((r) => !(`${r.method} ${r.raw}` in EXEMPT))
      .map((r) => `${r.method} ${r.raw}`);

    expect(
      unexplained,
      [
        "These MSW handlers mock endpoints that EDDI does not expose, so every test",
        "using them is validating against a fiction. Either fix the handler, or add it",
        `to EXEMPT in this file with a reason. If the backend gained the endpoint, run:`,
        "  npm run openapi:refresh",
      ].join("\n"),
    ).toEqual([]);
  });

  it("keeps every exemption still needed", () => {
    // Checked against the *unmatched* set, not against every route. Comparing
    // to every route only catches an exemption whose handler was deleted — it
    // leaves one whose drift the backend has since fixed sitting there as a
    // permanent whitelist for that method+path, so if the operation were later
    // removed again the check would stay green forever.
    const stale = Object.keys(EXEMPT).filter((key) => !unmatchedKeys.has(key));

    expect(
      stale,
      "EXEMPT entries that are no longer needed — the handler is gone, or the backend now exposes it. Delete them.",
    ).toEqual([]);
  });

  it("gives every exemption a reason", () => {
    const unreasoned = Object.entries(EXEMPT)
      .filter(([, reason]) => reason.trim().length < 20)
      .map(([key]) => key);

    expect(unreasoned, "an exemption without a reason is just a silenced failure").toEqual([]);
  });

  it("contacts exactly one off-origin host", () => {
    // The update check is the only outbound call the Manager makes, and
    // updates.ts is built around that being true. A second absolute URL
    // appearing in the mocks means a second one appeared in the app.
    const hosts = [
      ...new Set(
        routes
          .filter((r) => /^https?:\/\//.test(r.raw))
          .map((r) => new URL(r.raw).host),
      ),
    ];
    expect(hosts).toEqual(["api.github.com"]);
  });

  it("registers each endpoint once, or no more often than it already did", () => {
    const counts = new Map<string, number>();
    for (const r of routes) {
      const key = `${r.method} ${normalisePath(r.raw)}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const duplicated = [...counts.entries()]
      .filter(([, n]) => n > 1)
      .map(([key]) => key)
      .sort();

    const unexpected = duplicated.filter((k) => !KNOWN_DUPLICATE_ROUTES.includes(k));
    expect(
      unexpected,
      "a second handler for this endpoint shadows the first — MSW resolves in registration order, so the later one never runs",
    ).toEqual([]);

    // Exact, not `length <=`. A length comparison lets a *fixed* duplicate
    // linger in the frozen list, and a stale entry there is a standing licence
    // for that route to be duplicated again — the ratchet would have quietly
    // stopped ratcheting. Fixing one now requires deleting its line, which is
    // the only way the list actually shrinks.
    expect(
      duplicated,
      "KNOWN_DUPLICATE_ROUTES no longer matches reality — delete the entries you fixed",
    ).toEqual([...KNOWN_DUPLICATE_ROUTES].sort());
  });
});

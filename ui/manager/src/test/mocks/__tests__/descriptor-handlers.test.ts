import { describe, it, expect } from "vitest";
import { api } from "@/lib/api-client";

/**
 * MSW resolves handlers in registration order, and `handlers.ts` registers two
 * wildcards early that swallow routes declared later:
 *
 *   `*./:store/:plural/descriptors`  — the generic list stub
 *   `*./<store>/<plural>/:id`        — the per-store detail handlers
 *
 * Both have opt-outs, and both opt-outs were incomplete. The generic stub named
 * only `channelstore`, so `groupstore` was answered by it and the eight groups
 * defined 700 lines below were dead fixture — every Workforce and Groups test
 * ran against one synthetic row. The `:id` handlers stood aside for
 * `/descriptors` but not `/jsonSchema`, so ten stores answered a schema request
 * with a config document and `RESOURCE_SCHEMAS` was unreachable.
 *
 * Neither failed anything. A shadowed handler is not an error — it is simply a
 * different, plausible-looking response, which is the hardest kind of mock bug
 * to notice. These tests exist so the next one fails loudly.
 *
 * (The route-duplication ratchet in `openapi-contract.test.ts` cannot catch
 * this: it compares normalised paths, and `*./:store/:plural/descriptors` and
 * `*./groupstore/groups/descriptors` are different strings to it.)
 */

/**
 * Through `ApiClient`, not a bare fetch against a hardcoded host.
 *
 * It is what the app uses, so these probe the handlers by the same URL the
 * real callers build — a mock whose pattern no longer matches what the client
 * sends would show up here rather than only in a page test. It also throws on
 * a non-2xx, so a missing handler fails as itself instead of as a confusing
 * shape assertion further down.
 */
const getJson = <T,>(path: string): Promise<T> => api.get<T>(path);

interface Descriptor {
  resource?: string;
  name?: string;
}

/** Stores whose `/descriptors` must come from their own handler, not the stub. */
const DEDICATED = [
  { path: "/agentstore/agents/descriptors", contains: "Support Agent" },
  { path: "/workflowstore/workflows/descriptors", contains: "Pipeline" },
  { path: "/groupstore/groups/descriptors", contains: "Product Review Panel" },
  { path: "/channelstore/channels/descriptors", contains: "Slack" },
];

/** Stores served by the generic stub, which have no dedicated handler. */
const GENERIC = [
  "/rulestore/rulesets",
  "/apicallstore/apicalls",
  "/outputstore/outputsets",
  "/dictionarystore/dictionaries",
  "/llmstore/llms",
];

describe("descriptor handlers are not shadowed", () => {
  it.each(DEDICATED)("$path answers from its own fixture", async ({ path, contains }) => {
    const descriptors = await getJson<Descriptor[]>(path);

    // The stub's giveaway is a single row named `Mock <something>`.
    expect(
      descriptors.length,
      `${path} returned one row — the generic stub has swallowed its handler`,
    ).toBeGreaterThan(1);
    expect(descriptors.map((d) => d.name ?? "").join(" ")).toContain(contains);
  });

  it.each(GENERIC)("%s is served by the stub, with more than one row", async (base) => {
    const descriptors = await getJson<Descriptor[]>(`${base}/descriptors`);

    // Three, not one. A single row makes list ordering, pagination and every
    // "more than one of these" assertion impossible to write.
    expect(descriptors).toHaveLength(3);
  });

  it("honours limit and index, so pagination is exercisable", async () => {
    const firstTwo = await getJson<Descriptor[]>(
      "/rulestore/rulesets/descriptors?limit=2&index=0",
    );
    expect(firstTwo).toHaveLength(2);

    const lastOne = await getJson<Descriptor[]>(
      "/rulestore/rulesets/descriptors?limit=2&index=2",
    );
    expect(lastOne).toHaveLength(1);
  });

  it("still answers a filter as an id lookup", async () => {
    // `getResourceVersions` asks `descriptors?filter=<id>` for ids this mock has
    // never heard of. Returning [] there would break every version picker, so
    // the stub echoes — deliberately, and this pins it.
    const descriptors = await getJson<Descriptor[]>(
      "/rulestore/rulesets/descriptors?filter=some-unknown-id",
    );
    expect(descriptors).toHaveLength(1);
    expect(descriptors[0]?.resource).toContain("some-unknown-id");
  });
});

describe("jsonSchema routes are not shadowed by the :id handlers", () => {
  const SCHEMA_ROUTES = [
    "/agentstore/agents/jsonSchema",
    "/rulestore/rulesets/jsonSchema",
    "/apicallstore/apicalls/jsonSchema",
    "/outputstore/outputsets/jsonSchema",
    "/dictionarystore/dictionaries/jsonSchema",
  ];

  it.each(SCHEMA_ROUTES)("%s returns a schema, not a config document", async (path) => {
    const body = await getJson<Record<string, unknown>>(path);

    // A ruleset has `behaviorGroups`; an apicall has `httpCalls`. Either coming
    // back here means `:id` matched "jsonSchema" and answered as if it were one.
    expect(
      body,
      `${path} returned a config document — a :id handler has swallowed it`,
    ).not.toHaveProperty("behaviorGroups");
    expect(body).not.toHaveProperty("httpCalls");

    expect(Object.keys(body)).toEqual(
      expect.arrayContaining(["type", "properties"]),
    );
  });
});

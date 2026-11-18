import { http, HttpResponse } from "msw";
import type { ModelPriceCatalog, ModelPriceCatalogView, ModelPriceEntry, UnpricedSighting } from "../../lib/api/model-prices";

/**
 * The deployment price catalog (`/administration/model-prices`), shaped like
 * the backend's `RestModelPriceCatalog`: the GET view wraps the stored
 * `ModelPriceCatalog`, a PUT is compare-and-set on `If-Match` (409 with
 * `currentRevision` on a stale revision), `/resolve` matches exact names and
 * a leading/trailing `*`.
 */

const seedEntries: ModelPriceEntry[] = [
  {
    provider: "openai",
    model: "gpt-4o",
    inputPricePer1M: 2.5,
    outputPricePer1M: 10,
    cacheReadPricePer1M: 1.25,
    asOf: "2026-11-02",
    source: "https://developers.openai.com/api/docs/pricing",
  },
  { provider: "ollama", model: "*", inputPricePer1M: 0, outputPricePer1M: 0 },
];

let catalog: ModelPriceCatalog = { revision: 1, unpricedPolicy: "ALLOW", entries: seedEntries };

/** Resets the in-memory catalog (tests that save). */
export function resetModelPriceMock(next?: Partial<ModelPriceCatalog>) {
  catalog = { revision: 1, unpricedPolicy: "ALLOW", entries: seedEntries, ...next };
}

const reference: ModelPriceCatalog = {
  revision: 0,
  unpricedPolicy: "ALLOW",
  updatedAt: "2026-11-02",
  updatedBy: "EDDI reference prices",
  entries: [
    {
      provider: "openai",
      model: "gpt-4o",
      inputPricePer1M: 2.5,
      outputPricePer1M: 10,
      cacheReadPricePer1M: 1.25,
      asOf: "2026-11-02",
      source: "https://developers.openai.com/api/docs/pricing",
    },
    {
      provider: "anthropic",
      model: "claude-sonnet-4-6*",
      inputPricePer1M: 3,
      outputPricePer1M: 15,
      cacheReadPricePer1M: 0.3,
      cacheWritePricePer1M: 3.75,
      asOf: "2026-11-02",
      source: "https://platform.claude.com/docs/en/about-claude/pricing",
    },
  ],
};

const sightings: UnpricedSighting[] = [
  { provider: "mistral", model: "mistral-large-latest", site: "llm_task", count: 4, lastSeen: "2026-11-02T10:00:00Z" },
];

function matches(pattern: string, name: string): boolean {
  const p = pattern.toLowerCase();
  const n = name.toLowerCase();
  const lead = p.startsWith("*");
  const trail = p.length > 1 && p.endsWith("*");
  const lit = p.slice(lead ? 1 : 0, trail ? -1 : undefined);
  if (lead && trail) return n.includes(lit);
  if (lead) return n.endsWith(lit);
  if (trail) return n.startsWith(lit);
  return n === p;
}

export const modelPriceHandlers = [
  http.get("*/administration/model-prices/resolve", ({ request }) => {
    const url = new URL(request.url);
    const provider = url.searchParams.get("provider") ?? "";
    const model = url.searchParams.get("model") ?? "";
    const entry =
      catalog.entries.find((e) => e.provider === provider && matches(e.model, model)) ??
      catalog.entries.find((e) => e.provider === "*" && matches(e.model, model)) ??
      null;
    return HttpResponse.json({ provider, model, matched: entry !== null, entry });
  }),
  http.get("*/administration/model-prices/reference", () => HttpResponse.json(reference)),
  http.get("*/administration/model-prices/unpriced", () => HttpResponse.json({ node: "local", sightings })),
  http.get("*/administration/model-prices", () => {
    const view: ModelPriceCatalogView = {
      catalog,
      effectivePolicy: catalog.unpricedPolicy,
      floorPolicy: "ALLOW",
      knownProviders: ["anthropic", "gemini", "ollama", "openai"],
      staleAfterDays: 120,
      referenceAsOf: reference.updatedAt ?? null,
    };
    return HttpResponse.json(view);
  }),
  http.put("*/administration/model-prices", async ({ request }) => {
    const body = (await request.json()) as ModelPriceCatalog;
    const expected = Number(request.headers.get("If-Match") ?? body.revision);
    if (expected !== catalog.revision) {
      return HttpResponse.json(
        { error: "The price catalog changed since revision " + expected, currentRevision: catalog.revision },
        { status: 409 },
      );
    }
    catalog = { ...body, revision: catalog.revision + 1, updatedAt: new Date().toISOString(), updatedBy: "admin" };
    return HttpResponse.json(catalog);
  }),
];

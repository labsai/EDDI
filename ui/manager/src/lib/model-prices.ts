import type { ModelPriceCatalog, ModelPriceEntry } from "@/lib/api/model-prices";

/**
 * Client-side mirror of ModelPriceCatalogValidator — the server is the
 * authority; this only lets the page point at the field before a save.
 * Each problem is an i18n key plus its English fallback.
 */
export interface EntryProblem {
  field: "provider" | "model" | "prices" | "price" | "threshold" | "source" | "asOf" | "duplicate";
  key: string;
  fallback: string;
}

export const MAX_ENTRIES = 500;
export const MAX_PRICE = 10_000;
const MODEL = /^\*?[A-Za-z0-9._:/@+-]*\*?$/;
const PROVIDER = /^[a-z0-9._-]{1,64}$/;
const AS_OF = /^\d{4}-\d{2}-\d{2}$/;

function badPrice(v: number | null | undefined): boolean {
  return v != null && (!Number.isFinite(v) || v < 0 || v > MAX_PRICE);
}

export function entryKey(e: Pick<ModelPriceEntry, "provider" | "model">): string {
  return `${e.provider.trim().toLowerCase()}\u0000${e.model.trim().toLowerCase()}`;
}

/** Problems of one entry; `others` are the rest of the catalog, for duplicates. */
export function entryProblems(entry: ModelPriceEntry, others: readonly ModelPriceEntry[] = []): EntryProblem[] {
  const out: EntryProblem[] = [];
  const provider = entry.provider?.trim() ?? "";
  if (!provider || (provider !== "*" && !PROVIDER.test(provider))) {
    out.push({ field: "provider", key: "modelPrices.problemProvider", fallback: "Provider must be a model type such as openai, or *." });
  }
  const model = entry.model ?? "";
  if (!model || model.length > 128 || !MODEL.test(model) || model === "**") {
    out.push({
      field: "model",
      key: "modelPrices.problemModel",
      fallback: "Model: 1-128 characters of letters, digits and ._:/@+-, with * only at the start or end.",
    });
  }
  if (entry.inputPricePer1M == null && entry.outputPricePer1M == null) {
    out.push({ field: "prices", key: "modelPrices.problemNoPrice", fallback: "Set an input or an output price (0 marks a free model)." });
  }
  const tier = entry.longContext;
  if (
    [entry.inputPricePer1M, entry.outputPricePer1M, entry.cacheReadPricePer1M, entry.cacheWritePricePer1M].some(badPrice) ||
    (tier && [tier.inputPricePer1M, tier.outputPricePer1M, tier.cacheReadPricePer1M, tier.cacheWritePricePer1M].some(badPrice))
  ) {
    out.push({ field: "price", key: "modelPrices.problemPrice", fallback: "Prices must be numbers between 0 and 10000." });
  }
  if (tier && (tier.thresholdTokens == null || tier.thresholdTokens < 1000 || tier.thresholdTokens > 10_000_000)) {
    out.push({ field: "threshold", key: "modelPrices.problemThreshold", fallback: "The long-context threshold must be 1,000 to 10,000,000 tokens." });
  }
  if (entry.source && !/^https?:\/\/[^\s/]+/i.test(entry.source.trim())) {
    out.push({ field: "source", key: "modelPrices.problemSource", fallback: "Source must be an http(s) link." });
  }
  if (entry.asOf && !AS_OF.test(entry.asOf.trim())) {
    out.push({ field: "asOf", key: "modelPrices.problemAsOf", fallback: "As-of must be a date (YYYY-MM-DD)." });
  }
  if (provider && model && others.some((o) => o !== entry && entryKey(o) === entryKey(entry))) {
    out.push({ field: "duplicate", key: "modelPrices.problemDuplicate", fallback: "Another entry has the same provider and model." });
  }
  return out;
}

/** Whether `asOf` is older than `staleAfterDays` days before `now`. */
export function isStale(asOf: string | null | undefined, staleAfterDays: number, now: Date = new Date()): boolean {
  if (!asOf || !AS_OF.test(asOf)) return false;
  const then = Date.parse(`${asOf}T00:00:00Z`);
  if (Number.isNaN(then)) return false;
  return now.getTime() - then > staleAfterDays * 86_400_000;
}

export interface ReferenceMerge {
  entries: ModelPriceEntry[];
  added: ModelPriceEntry[];
  changed: ModelPriceEntry[];
  unchanged: number;
}

const n = (v: number | null | undefined) => v ?? null;

function sameTier(a: ModelPriceEntry["longContext"], b: ModelPriceEntry["longContext"]): boolean {
  if (!a || !b) return !a && !b;
  return (
    n(a.thresholdTokens) === n(b.thresholdTokens) &&
    n(a.inputPricePer1M) === n(b.inputPricePer1M) &&
    n(a.outputPricePer1M) === n(b.outputPricePer1M) &&
    n(a.cacheReadPricePer1M) === n(b.cacheReadPricePer1M) &&
    n(a.cacheWritePricePer1M) === n(b.cacheWritePricePer1M)
  );
}

/** Field by field, so key order and null-versus-absent do not read as a change. */
function samePrices(a: ModelPriceEntry, b: ModelPriceEntry): boolean {
  return (
    n(a.inputPricePer1M) === n(b.inputPricePer1M) &&
    n(a.outputPricePer1M) === n(b.outputPricePer1M) &&
    n(a.cacheReadPricePer1M) === n(b.cacheReadPricePer1M) &&
    n(a.cacheWritePricePer1M) === n(b.cacheWritePricePer1M) &&
    sameTier(a.longContext, b.longContext)
  );
}

/**
 * Merges the bundled reference prices into `current`: a reference entry with
 * the same provider and model replaces the current one, a new one is appended,
 * and entries the reference does not list are kept. Nothing is saved — the
 * page shows the diff and the admin saves.
 */
export function mergeReference(current: readonly ModelPriceEntry[], reference: readonly ModelPriceEntry[]): ReferenceMerge {
  const byKey = new Map(current.map((e) => [entryKey(e), e] as const));
  const entries = [...current];
  const added: ModelPriceEntry[] = [];
  const changed: ModelPriceEntry[] = [];
  let unchanged = 0;
  for (const ref of reference) {
    const existing = byKey.get(entryKey(ref));
    if (!existing) {
      entries.push(ref);
      added.push(ref);
    } else if (samePrices(existing, ref) && (existing.asOf ?? null) === (ref.asOf ?? null)) {
      unchanged++;
    } else {
      entries[entries.indexOf(existing)] = ref;
      changed.push(ref);
    }
  }
  return { entries, added, changed, unchanged };
}

/** Parses an imported catalog file; throws on anything that is not one. */
export function parseCatalogImport(text: string): Pick<ModelPriceCatalog, "unpricedPolicy" | "entries"> {
  const parsed = JSON.parse(text) as Partial<ModelPriceCatalog> | null;
  if (!parsed || typeof parsed !== "object" || !Array.isArray(parsed.entries)) {
    throw new Error("not a price catalog");
  }
  const wellFormed = parsed.entries.every(
    (e) => e != null && typeof e === "object" && typeof e.provider === "string" && typeof e.model === "string" && (e.asOf == null || typeof e.asOf === "string"),
  );
  if (!wellFormed) {
    throw new Error("not a price catalog");
  }
  return { unpricedPolicy: parsed.unpricedPolicy ?? "ALLOW", entries: parsed.entries };
}

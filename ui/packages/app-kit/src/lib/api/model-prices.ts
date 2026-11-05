import { api } from "../api-client";

/* ─── Types (mirror ai.labs.eddi.engine.pricing.model) ─── */

export type UnpricedPolicy = "ALLOW" | "REFUSE_WHEN_BUDGETED" | "REFUSE";

export const UNPRICED_POLICIES: readonly UnpricedPolicy[] = ["ALLOW", "REFUSE_WHEN_BUDGETED", "REFUSE"];

export interface LongContextTier {
  thresholdTokens?: number | null;
  inputPricePer1M?: number | null;
  outputPricePer1M?: number | null;
  cacheReadPricePer1M?: number | null;
  cacheWritePricePer1M?: number | null;
}

export interface ModelPriceEntry {
  provider: string;
  model: string;
  inputPricePer1M?: number | null;
  outputPricePer1M?: number | null;
  cacheReadPricePer1M?: number | null;
  cacheWritePricePer1M?: number | null;
  longContext?: LongContextTier | null;
  asOf?: string | null;
  source?: string | null;
  note?: string | null;
}

export interface ModelPriceCatalog {
  revision: number;
  unpricedPolicy: UnpricedPolicy;
  updatedAt?: string | null;
  updatedBy?: string | null;
  entries: ModelPriceEntry[];
}

/** `GET /administration/model-prices`. */
export interface ModelPriceCatalogView {
  catalog: ModelPriceCatalog;
  effectivePolicy: UnpricedPolicy;
  floorPolicy: UnpricedPolicy;
  knownProviders: string[] | null;
  staleAfterDays: number;
  referenceAsOf: string | null;
}

export interface ResolvedModelPrice {
  provider: string;
  model: string;
  matched: boolean;
  entry: ModelPriceEntry | null;
}

export interface UnpricedSighting {
  provider: string;
  model: string;
  site: string;
  count: number;
  lastSeen: string;
}

/* ─── API ─── */

const BASE = "/administration/model-prices";

export function getModelPrices(): Promise<ModelPriceCatalogView> {
  return api.get<ModelPriceCatalogView>(BASE);
}

/**
 * Replaces the catalog. `catalog.revision` must be the revision the edit was
 * based on — a different stored revision answers 409.
 */
export function putModelPrices(catalog: ModelPriceCatalog): Promise<ModelPriceCatalog> {
  return api.put<ModelPriceCatalog>(BASE, catalog, { "If-Match": String(catalog.revision) });
}

export function resolveModelPrice(provider: string, model: string): Promise<ResolvedModelPrice> {
  const q = new URLSearchParams({ provider, model });
  return api.get<ResolvedModelPrice>(`${BASE}/resolve?${q.toString()}`);
}

export function getReferencePrices(): Promise<ModelPriceCatalog> {
  return api.get<ModelPriceCatalog>(`${BASE}/reference`);
}

export async function getUnpricedSightings(): Promise<UnpricedSighting[]> {
  const body = await api.get<{ sightings?: UnpricedSighting[] }>(`${BASE}/unpriced`);
  return body.sightings ?? [];
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getModelPrices,
  getReferencePrices,
  getUnpricedSightings,
  putModelPrices,
  resolveModelPrice,
  type ModelPriceCatalog,
} from "@/lib/api/model-prices";

const modelPriceKeys = {
  all: ["model-prices"] as const,
  catalog: ["model-prices", "catalog"] as const,
  reference: ["model-prices", "reference"] as const,
  unpriced: ["model-prices", "unpriced"] as const,
  resolve: (provider: string, model: string) => ["model-prices", "resolve", provider, model] as const,
};

export function useModelPrices() {
  return useQuery({ queryKey: modelPriceKeys.catalog, queryFn: getModelPrices });
}

export function useUnpricedSightings() {
  return useQuery({ queryKey: modelPriceKeys.unpriced, queryFn: getUnpricedSightings, refetchInterval: 30_000 });
}

/** The reference prices, fetched only when the admin asks for them. */
export function useReferencePrices(enabled: boolean) {
  return useQuery({ queryKey: modelPriceKeys.reference, queryFn: getReferencePrices, enabled, staleTime: Infinity });
}

/** Which catalog entry prices a model; off until both are known. */
export function useResolvedModelPrice(provider: string | undefined, model: string | undefined) {
  const enabled = !!provider && !!model && !model.includes("{");
  return useQuery({
    queryKey: modelPriceKeys.resolve(provider ?? "", model ?? ""),
    queryFn: () => resolveModelPrice(provider ?? "", model ?? ""),
    enabled,
    retry: false,
    staleTime: 60_000,
  });
}

export function useSaveModelPrices() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (catalog: ModelPriceCatalog) => putModelPrices(catalog),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: modelPriceKeys.all });
    },
  });
}

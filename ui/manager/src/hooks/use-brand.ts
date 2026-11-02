import { useSyncExternalStore } from "react";
import { getBrand, subscribeBrand, type Brand } from "@/lib/brand";

/**
 * The deployment's brand, re-rendering when it changes (`setBrand`, after the
 * settings page saved one). See `src/lib/brand.ts`.
 */
export function useBrand(): Brand {
  return useSyncExternalStore(subscribeBrand, getBrand, getBrand);
}

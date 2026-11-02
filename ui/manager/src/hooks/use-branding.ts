import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  deleteBrandAsset,
  getBranding,
  resetBranding,
  updateBranding,
  uploadBrandAsset,
  type BrandingView,
  type BrandUpdate,
} from "@/lib/api/branding";
import type { BrandAssetSlot } from "@/lib/brand";
import { applyBrandRevision } from "@/lib/brand-live";

/** Read and invalidated only here. */
export const brandingKeys = {
  all: ["branding"] as const,
};

/** The stored brand, the brand in effect, `locked` and `source` (eddi-admin). */
export function useBranding(enabled = true) {
  return useQuery({
    queryKey: brandingKeys.all,
    queryFn: getBranding,
    enabled,
    // Another admin's save should not be overwritten by a stale form; the
    // revision check catches it either way, but a focus refetch keeps the
    // "someone else saved" case rare.
    refetchOnWindowFocus: false,
  });
}

/**
 * Every successful write answers the new view: cache it, then apply the new
 * revision to this tab (`applyBrandRevision`). A failed script reload does not
 * fail the write — it is stored — so it is reported separately.
 */
function useBrandingWrite<TArgs>(write: (args: TArgs) => Promise<BrandingView>, onApplyError?: (e: unknown) => void) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: write,
    // The page renders every refusal in place (field violations, the conflict
    // notice, per-slot image errors), so the global error toast would repeat it.
    meta: { silent: true },
    onSuccess: (view) => {
      qc.setQueryData(brandingKeys.all, view);
      // Not awaited: the write is done, and the mutation should not stay
      // pending on a script load.
      applyBrandRevision(view.effective.revision).catch((e: unknown) => onApplyError?.(e));
    },
  });
}

export function useUpdateBranding(onApplyError?: (e: unknown) => void) {
  return useBrandingWrite((update: BrandUpdate) => updateBranding(update), onApplyError);
}

export function useResetBranding(onApplyError?: (e: unknown) => void) {
  return useBrandingWrite(() => resetBranding(), onApplyError);
}

export function useUploadBrandAsset(onApplyError?: (e: unknown) => void) {
  return useBrandingWrite(
    ({ slot, file }: { slot: BrandAssetSlot; file: Blob }) => uploadBrandAsset(slot, file),
    onApplyError,
  );
}

export function useDeleteBrandAsset(onApplyError?: (e: unknown) => void) {
  return useBrandingWrite((slot: BrandAssetSlot) => deleteBrandAsset(slot), onApplyError);
}

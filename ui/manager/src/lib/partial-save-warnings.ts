import type { TFunction } from "i18next";
import { toast } from "sonner";
import { cascadePartialResult } from "@eddi/app-kit/lib/api/cascade-save";

/**
 * The warnings a resource save answered with when a LATER hop of its cascade
 * failed. The resource version exists, so what the server said about it still
 * applies; it used to be dropped with the failed workflow or agent update.
 * Shown beside the cascade's own error, and returned for a page that lists them.
 */
export function reportPartialSaveWarnings(err: unknown, t: TFunction): string[] | undefined {
  const warnings = cascadePartialResult(err)?.warnings;
  if (!warnings || warnings.length === 0) return undefined;
  toast.warning(t("editor.savedWithWarnings", "Saved, with warnings"), { description: warnings.join("\n") });
  return warnings;
}

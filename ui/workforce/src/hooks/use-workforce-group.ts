import { useMemo } from "react";
import { useGroup, useResolvedGroupVersion } from "@eddi/app-kit/hooks/use-groups";
import { useCatalogGroup } from "@/hooks/use-catalog";
import { useWorkforceAccess } from "@eddi/app-kit/hooks/use-workforce-access";
import { catalogGroupAsConfig } from "@eddi/app-kit/lib/api/catalog";
import type { AgentGroupConfiguration } from "@eddi/app-kit/lib/api/groups";

/**
 * The group a Workforce board, thread or history page shows, read where the
 * caller may read it.
 *
 * An author reads the group store — the full configuration at the version the
 * URL names, or the current one (`useResolvedGroupVersion`). An end user may not
 * read `/groupstore` at all, so they get the catalog entry instead: name,
 * description, style and members, which is what those pages display. A group the
 * catalog does not offer them resolves to no configuration; running it is still
 * decided by the backend's USE check, not by this.
 *
 * `version` is `undefined` while an author's current-version lookup is in flight,
 * so callers keep their version-dependent queries disabled meanwhile.
 */
export function useWorkforceGroup(
  boardId: string | undefined,
  urlVersion: string | null,
): { version: number | undefined; data: AgentGroupConfiguration | undefined; isLoading: boolean } {
  const { canAuthor } = useWorkforceAccess();

  const authorVersion = useResolvedGroupVersion(canAuthor ? boardId : undefined, urlVersion);
  const authorQuery = useGroup(canAuthor && authorVersion ? (boardId ?? "") : "", authorVersion);

  const catalog = useCatalogGroup(boardId, !canAuthor);
  const catalogConfig = useMemo(
    () => (catalog.group ? catalogGroupAsConfig(catalog.group) : undefined),
    [catalog.group],
  );

  if (canAuthor) {
    return {
      version: authorVersion,
      data: authorQuery.data,
      // A disabled query reports isLoading false, so the version lookup counts too.
      isLoading: authorQuery.isLoading || authorVersion === undefined,
    };
  }
  const explicit = urlVersion != null && /^[1-9]\d*$/.test(urlVersion) ? Number(urlVersion) : undefined;
  return {
    version: explicit ?? catalog.group?.version ?? (catalog.isLoading ? undefined : 1),
    data: catalogConfig,
    isLoading: catalog.isLoading,
  };
}

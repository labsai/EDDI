import { useEffect } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { pageTitleLabel } from "@/lib/route-registry";

/**
 * Dynamically updates document.title based on the current route.
 * WCAG 2.4.2 — Page Titled: each page must have a descriptive title.
 *
 * Labels come from the shared route registry, the same table the breadcrumb and
 * the command palette read. `entityName` (the open agent's name, say) replaces
 * the section label on a detail route once it is known.
 */
export function useDocumentTitle(entityName?: string) {
  const location = useLocation();
  const { t } = useTranslation();

  useEffect(() => {
    document.title = `${pageTitleLabel(t, location.pathname, entityName)} — EDDI Manager`;
  }, [location.pathname, t, entityName]);
}

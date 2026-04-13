import { useEffect } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";

/**
 * Dynamically updates document.title based on the current route.
 * WCAG 2.4.2 — Page Titled: each page must have a descriptive title.
 */
export function useDocumentTitle() {
  const location = useLocation();
  const { t } = useTranslation();

  useEffect(() => {
    const pathSegments = location.pathname
      .replace(/^\/manage\/?/, "")
      .split("/")
      .filter(Boolean);

    const labelMap: Record<string, string> = {
      agents: t("nav.agents"),
      workflows: t("nav.packages"),
      conversations: t("nav.conversations"),
      chat: t("nav.chat"),
      resources: t("nav.resources"),
      groups: t("nav.groups", "Groups"),
      coordinator: t("nav.coordinator", "Coordinator"),
      schedules: t("nav.schedules", "Schedules"),
      logs: t("nav.logs", "Logs"),
      orphans: t("nav.orphans", "Orphans"),
      secrets: t("nav.secrets", "Secrets"),
      audit: t("nav.audit", "Audit Trail"),
      quotas: t("nav.quotas", "Quotas"),
      userdata: t("userData.title", "User Data"),
      triggers: t("nav.triggers", "Triggers"),
      capabilities: t("nav.capabilities", "Capabilities"),
      sync: t("nav.sync", "Sync"),
      gdpr: t("nav.gdpr", "GDPR"),
      wizard: t("wizard.title", "Agent Wizard"),
      studio: t("nav.studio", "Agent Studio"),
      properties: t("nav.properties", "Properties"),
    };

    if (pathSegments.length === 0) {
      document.title = `${t("nav.dashboard")} — EDDI Manager`;
    } else {
      // Use the first recognisable segment for the title
      const firstSegment = pathSegments[0]!.replace(/view$/, "");
      const pageLabel = labelMap[firstSegment] ?? firstSegment;
      document.title = `${pageLabel} — EDDI Manager`;
    }
  }, [location.pathname, t]);
}

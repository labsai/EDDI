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
      // Sections that had no entry and so titled as their raw lowercase path
      // segment ("approvals — EDDI Manager").
      operator: t("nav.operator", "Platform Operator"),
      channels: t("nav.channels", "Channels"),
      approvals: t("nav.approvals", "Approvals"),
      memories: t("nav.memories", "User Memory"),
      variables: t("nav.variables", "Variables"),
      updates: t("nav.updates", "Updates"),
      "user-conversations": t("nav.userConversations", "User Conversations"),
      // Not under /manage, so it arrives here as the first segment verbatim.
      workforce: t("nav.workforce", "Workforce"),
    };

    /**
     * Detail routes worth their own title, keyed by section then by the trailing
     * literal segment. Matching the LAST segment rather than a path prefix is what
     * lets `/manage/groups/:id/workspace` resolve — the id in the middle is
     * dynamic, so a prefix match would never hit. A section whose last segment is
     * an id simply finds nothing here and keeps the section title.
     */
    const detailMap: Record<string, Record<string, string>> = {
      groups: {
        wizard: t("groupWizard.title", "Group Setup Wizard"),
        templates: t("groupTemplates.title", "Group Templates"),
        workspace: t("groupWorkspace.title", "Standing Team Workspace"),
      },
      agents: { wizard: t("wizard.title", "Agent Wizard") },
      conversations: { monitoring: t("nav.monitoring", "Conversation Monitoring") },
      workforce: {
        new: t("workforceWizard.title", "New Team"),
        analytics: t("nav.analytics", "Analytics"),
        chat: t("nav.chat"),
        settings: t("common.settings", "Settings"),
        history: t("common.history", "History"),
      },
    };

    /**
     * `agentview`/`workflowview`/`conversationview` are detail routes whose segment
     * is the singular of their section. Stripping "view" alone yielded "agent",
     * which is not a labelMap key either — so these pages titled as raw lowercase
     * text. Map them onto the section they belong to.
     */
    const DETAIL_VIEW_SECTIONS: Record<string, string> = {
      agentview: "agents",
      workflowview: "workflows",
      conversationview: "conversations",
      agentstore: "agents",
    };

    /**
     * Explicit mapping first, then an exact section match, then the original
     * trailing-"view" strip — kept as a fallback so any `<plural>view` route it
     * already resolved keeps working.
     */
    const resolveSection = (raw: string): string => {
      const mapped = DETAIL_VIEW_SECTIONS[raw];
      if (mapped) return mapped;
      if (labelMap[raw]) return raw;
      const stripped = raw.replace(/view$/, "");
      return labelMap[stripped] ? stripped : raw;
    };

    if (pathSegments.length === 0) {
      document.title = `${t("nav.dashboard")} — EDDI Manager`;
    } else {
      const raw = pathSegments[0]!;
      const section = resolveSection(raw);
      const last = pathSegments[pathSegments.length - 1]!;
      const detail = last === raw ? undefined : detailMap[section]?.[last];
      const pageLabel = detail ?? labelMap[section] ?? section;
      document.title = `${pageLabel} — EDDI Manager`;
    }
  }, [location.pathname, t]);
}

import { describe, expect, it } from "vitest";
import i18n from "@/i18n/config";
import { ALL_PAGES, NAV_SECTIONS, buildCrumbs, entityFromPath, pageTitleLabel } from "@/lib/route-registry";

const t = i18n.t.bind(i18n);

describe("route registry", () => {
  it("labels a nested wizard by the section it sits in", () => {
    // `groups/wizard` crumbed as "Agent Wizard" when the breadcrumb kept its own table.
    expect(buildCrumbs(t, "/manage/groups/wizard").map((c) => c.label)).toEqual([
      "Dashboard",
      "Groups",
      "Group Setup Wizard",
    ]);
    expect(buildCrumbs(t, "/manage/agents/wizard").slice(-1)[0]?.label).toBe("Agent Wizard");
  });

  it.each([
    ["/manage/operator", "Platform Operator"],
    ["/manage/approvals", "Approvals"],
    ["/manage/channels", "Channels"],
    ["/manage/variables", "Variables"],
    ["/manage/workspaces", "Workspaces"],
    ["/manage/conversations/monitoring", "Conversation Monitoring"],
  ])("gives %s the label %s in the breadcrumb, not its raw segment", (path, label) => {
    expect(buildCrumbs(t, path).slice(-1)[0]?.label).toBe(label);
  });

  it("uses the entity name for a detail route when it is known", () => {
    expect(buildCrumbs(t, "/manage/agentview/abc123", "Support Bot").map((c) => c.label)).toEqual([
      "Dashboard",
      "Agents",
      "Support Bot",
    ]);
    expect(pageTitleLabel(t, "/manage/agentview/abc123", "Support Bot")).toBe("Support Bot");
    // Not yet loaded: the section's label, never the raw id.
    expect(pageTitleLabel(t, "/manage/agentview/abc123")).toBe("Agents");
  });

  it("points a detail crumb back at its list route", () => {
    expect(buildCrumbs(t, "/manage/agentview/abc123")[1]?.to).toBe("/manage/agents");
  });

  it("titles an unknown path as not found", () => {
    expect(pageTitleLabel(t, "/manage/definitely-not-a-page")).toBe("Page not found");
  });

  it("recognises the agent a detail path points at", () => {
    expect(entityFromPath("/manage/agentview/a%201")).toEqual({ kind: "agent", id: "a 1" });
    expect(entityFromPath("/manage/agents")).toBeNull();
  });

  it("lists every sidebar page for the command palette, plus the unlisted ones", () => {
    const paths = ALL_PAGES.map((p) => p.path);
    for (const section of NAV_SECTIONS) {
      for (const item of section.items) expect(paths).toContain(item.path);
    }
    // Missing from the palette before it read the registry.
    for (const path of [
      "/manage/operator",
      "/manage/approvals",
      "/manage/secrets",
      "/manage/schedules",
      "/manage/channels",
      "/manage/capabilities",
      "/manage/triggers",
      "/manage/workspaces",
      "/manage/linked-accounts",
    ]) {
      expect(paths).toContain(path);
    }
  });

  it("has unique paths and unique section ids", () => {
    const paths = ALL_PAGES.map((p) => p.path);
    expect(new Set(paths).size).toBe(paths.length);
    const ids = NAV_SECTIONS.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

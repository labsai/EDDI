import { describe, it, expect, afterEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { useDocumentTitle } from "@/hooks/use-document-title";
import { type ReactNode } from "react";

function createWrapper(path: string) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={[path]}>
        {children}
      </MemoryRouter>
    );
  };
}

function titleFor(path: string): string {
  renderHook(() => useDocumentTitle(), { wrapper: createWrapper(path) });
  return document.title;
}

/**
 * These assert the actual title text. The previous versions only checked that the
 * title contained "EDDI Manager", which is true of every path this hook can
 * produce — so they passed while nine route sections rendered their raw lowercase
 * path segment ("approvals — EDDI Manager") and every sub-page of a section
 * inherited the section's own name.
 */
describe("useDocumentTitle", () => {
  afterEach(() => {
    document.title = "";
  });

  it("titles the dashboard at the manage root, with and without a trailing slash", () => {
    expect(titleFor("/manage/")).toBe("Dashboard — EDDI Manager");
    expect(titleFor("/manage")).toBe("Dashboard — EDDI Manager");
  });

  it("titles a section from its first path segment", () => {
    expect(titleFor("/manage/agents")).toBe("Agents — EDDI Manager");
    expect(titleFor("/manage/groups")).toBe("Groups — EDDI Manager");
  });

  // Every one of these rendered its raw lowercase segment before.
  it.each([
    ["/manage/approvals", "Approvals"],
    ["/manage/channels", "Channels"],
    ["/manage/memories", "User Memory"],
    ["/manage/variables", "Variables"],
    ["/manage/operator", "Platform Operator"],
    ["/manage/user-conversations", "User Conversations"],
    ["/workforce", "Workforce"],
  ])("titles %s as %s", (path, label) => {
    expect(titleFor(path)).toBe(`${label} — EDDI Manager`);
  });

  // A sub-page is a different page and needs a different title (WCAG 2.4.2).
  it.each([
    ["/manage/groups/wizard", "Group Setup Wizard"],
    ["/manage/groups/templates", "Group Templates"],
    ["/manage/agents/wizard", "Agent Wizard"],
    ["/manage/conversations/monitoring", "Conversation Monitoring"],
  ])("gives %s its own title, not the section's", (path, label) => {
    expect(titleFor(path)).toBe(`${label} — EDDI Manager`);
  });

  it("resolves a detail segment past a dynamic id", () => {
    // The id in the middle is why this matches on the LAST segment rather than a
    // path prefix — a prefix match could never see "workspace".
    expect(titleFor("/manage/groups/grp-123/workspace")).toBe(
      "Standing Team Workspace — EDDI Manager",
    );
  });

  it("keeps the section title when the trailing segment is an id", () => {
    expect(titleFor("/manage/groups/grp-123")).toBe("Groups — EDDI Manager");
    expect(titleFor("/manage/channels/ch-1")).toBe("Channels — EDDI Manager");
  });

  it("maps singular detail-view routes onto their section", () => {
    // `agentview` stripped of "view" is "agent", which is not a label key either —
    // so these titled as raw lowercase text.
    expect(titleFor("/manage/agentview/a-1")).toBe("Agents — EDDI Manager");
    // The `workflows` section reads `nav.packages`, which renders as "Workflows".
    expect(titleFor("/manage/workflowview/w-1")).toBe("Workflows — EDDI Manager");
    expect(titleFor("/manage/conversationview/c-1")).toBe("Conversations — EDDI Manager");
  });

  it("still resolves a plural <section>view segment via the legacy strip", () => {
    expect(titleFor("/manage/agentsview")).toBe("Agents — EDDI Manager");
  });

  it("falls back to the raw segment for a genuinely unknown path", () => {
    expect(titleFor("/manage/unknown-page")).toBe("unknown-page — EDDI Manager");
  });
});

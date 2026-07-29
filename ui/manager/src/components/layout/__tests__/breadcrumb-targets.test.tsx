import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { TopBar } from "../top-bar";

/**
 * Detail pages are /manage/<thing>view/:id, but there is no /manage/<thing>view
 * route — only the list at /manage/<things>. The breadcrumb built its
 * intermediate crumb by accumulating path segments, so it produced a link to
 * /manage/agentview, which matched nothing and fell through to the catch-all
 * redirect to /welcome.
 */

function crumbHrefs(): string[] {
  const nav = screen.getByRole("navigation", { name: /breadcrumb/i });
  return [...nav.querySelectorAll("a")].map((a) => a.getAttribute("href") ?? "");
}

function render(path: string, pattern: string) {
  return renderPage(path, <TopBar onMenuClick={() => {}} sidebarVisible />, pattern);
}

describe("breadcrumb targets", () => {
  const CASES: Array<[string, string, string, string]> = [
    ["agent detail", "/manage/agentview/abc123", "/manage/agentview/:id", "/manage/agents"],
    ["workflow detail", "/manage/workflowview/wf1", "/manage/workflowview/:id", "/manage/workflows"],
    [
      "conversation detail",
      "/manage/conversationview/c1",
      "/manage/conversationview/:id",
      "/manage/conversations",
    ],
  ];

  for (const [label, path, pattern, expected] of CASES) {
    it(`${label} links the intermediate crumb to ${expected}`, () => {
      render(path, pattern);
      const hrefs = crumbHrefs();
      expect(hrefs).toContain(expected);
      // The dead accumulated path must not appear.
      expect(hrefs).not.toContain(expected.replace(/s$/, "view"));
    });
  }

  it("emits no crumb pointing at a bare *view path", () => {
    render("/manage/agentview/abc123", "/manage/agentview/:id");
    for (const href of crumbHrefs()) {
      expect(href).not.toMatch(/\/manage\/(agent|workflow|conversation)view$/);
    }
  });
});

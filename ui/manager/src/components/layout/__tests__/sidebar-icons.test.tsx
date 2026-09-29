import { describe, it, expect, beforeEach } from "vitest";
import { renderWithProviders } from "@/test/test-utils";
import { Sidebar } from "@/components/layout/sidebar";

/** The lucide-* classes of a link's icon, which name the glyph it draws. */
function glyphOf(link: Element): string {
  const svg = link.querySelector("svg");
  const classes = svg?.getAttribute("class")?.split(/\s+/) ?? [];
  return classes.filter((c) => c.startsWith("lucide-")).sort().join(" ");
}

describe("Sidebar — icons", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("gives every destination its own icon", () => {
    // Active Conversations and Coordinator once shared the Activity pulse, and
    // Groups and User Data would have shared Users. Collapsed, the icon is all
    // the sidebar shows, so two entries with one icon cannot be told apart.
    const { getByTestId } = renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );
    const links = [...getByTestId("sidebar").querySelectorAll("nav a")];
    expect(links.length).toBeGreaterThan(20);

    const seen = new Map<string, string>();
    for (const link of links) {
      const glyph = glyphOf(link);
      const label = link.textContent?.trim() ?? link.getAttribute("href") ?? "";
      expect(glyph, `${label} has no icon`).not.toBe("");
      expect(seen.get(glyph), `${label} reuses the icon of ${seen.get(glyph)}`).toBeUndefined();
      seen.set(glyph, label);
    }
  });
});

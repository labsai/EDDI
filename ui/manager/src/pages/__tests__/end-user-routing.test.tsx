import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderAs } from "@eddi/app-kit/test/test-utils";
import { pageNavigation } from "@eddi/app-kit/lib/app-paths";
import { LandingPage } from "@/pages/landing-page";
import { CatalogListingToggle } from "@eddi/app-kit/components/shared/catalog-listing-toggle";
import { mockRolesOverride } from "@eddi/app-kit/lib/mock-roles";

/**
 * The Manager's part of the end-user story (phase 2 of the frontend-unification
 * plan): the welcome chooser sends an `eddi-user` on to Workforce (D6), and an
 * author lists an agent in the catalog. Workforce's own screens for an end user
 * are tested in ui/workforce (src/components/workforce/__tests__/
 * workforce-end-user.test.tsx).
 */

describe("LandingPage (D6)", () => {
  it("sends an eddi-user straight to Workforce, even with ?choose — a page load into the other app", () => {
    // Workforce is its own app (ui/workforce): a router redirect to /workforce
    // would match nothing in the Manager, so it must be a page navigation.
    const replace = vi.spyOn(pageNavigation, "replace").mockImplementation(() => {});
    try {
      renderAs(["eddi-user"], "/welcome?choose", [["/welcome", <LandingPage />]]);
      expect(replace).toHaveBeenCalledWith("/workforce");
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    } finally {
      replace.mockRestore();
    }
  });

  it("links the Workforce card with a plain href, the Manager card through the router", () => {
    renderAs(["eddi-editor"], "/welcome?choose", [["/welcome", <LandingPage />]]);
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/manage", "/workforce"]);
  });

  it("keeps the chooser for editors and admins", () => {
    for (const roles of [["eddi-editor"], ["eddi-admin"]]) {
      const { unmount } = renderAs(roles, "/welcome?choose", [["/welcome", <LandingPage />]]);
      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
      unmount();
    }
  });
});

describe("CatalogListingToggle", () => {
  it("reads an absent flag as off and reports the flipped value", () => {
    const changes: boolean[] = [];
    renderAs(["eddi-editor"], "/x", [
      ["/x", <CatalogListingToggle kind="agent" listed={undefined} onChange={(v) => changes.push(v)} />],
    ]);
    const toggle = screen.getByTestId("agent-listed-toggle");
    expect(toggle).toHaveAttribute("aria-checked", "false");
    fireEvent.click(toggle);
    expect(changes).toEqual([true]);
  });
});

describe("mockRolesOverride", () => {
  it("ignores the stored roles unless the mock API is active", () => {
    const win = window as unknown as Record<string, unknown>;
    localStorage.setItem("eddi-mock-roles", "eddi-user");
    try {
      expect(mockRolesOverride()).toBeNull();
      win.__EDDI_MOCK_ACTIVE__ = true;
      expect(mockRolesOverride()).toEqual(["eddi-user"]);
    } finally {
      delete win.__EDDI_MOCK_ACTIVE__;
      localStorage.removeItem("eddi-mock-roles");
    }
  });
});

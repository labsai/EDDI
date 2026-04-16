import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { GroupsPage } from "@/pages/groups";

describe("GroupsPage", () => {
  it("renders heading and search input", () => {
    renderWithProviders(<GroupsPage />, {
      initialRoute: "/manage/groups",
    });
    expect(screen.getByTestId("group-search")).toBeInTheDocument();
  });

  it("renders group cards after loading", async () => {
    renderWithProviders(<GroupsPage />, {
      initialRoute: "/manage/groups",
    });

    // MSW handler for */groupstore/groups/:id returns "Product Review Panel"
    // for all IDs, so enriched descriptors all get that name.
    await waitFor(
      () => {
        // The enriched fetch replaces the descriptor name with the config name.
        // The mock GET /groupstore/groups/:id always returns "Product Review Panel".
        const cards = screen.getAllByText("Product Review Panel");
        expect(cards.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );
  });

  it("shows create group button", () => {
    renderWithProviders(<GroupsPage />, {
      initialRoute: "/manage/groups",
    });
    expect(screen.getByTestId("create-group-btn")).toBeInTheDocument();
  });

  it("displays group cards in card view by default", async () => {
    renderWithProviders(<GroupsPage />, {
      initialRoute: "/manage/groups",
    });

    await waitFor(
      () => {
        // GroupCard components have data-testid="group-card-{id}"
        const cards = screen.getAllByTestId(/^group-card-/);
        expect(cards.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );
  });
});

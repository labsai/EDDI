import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { UpdatesPage } from "@/pages/updates";

describe("UpdatesPage", () => {
  it("titles the screen and hosts the check", async () => {
    renderWithProviders(<UpdatesPage />);

    expect(screen.getByRole("heading", { level: 1, name: /EDDI Updates/ })).toBeInTheDocument();
    expect(await screen.findByTestId("update-check-card")).toBeInTheDocument();
  });

  it("states the check's privacy contract on the page itself", async () => {
    // The page is the whole context an operator has before pressing the button,
    // so "nothing is sent until you ask" has to be readable here.
    renderWithProviders(<UpdatesPage />);

    expect(screen.getByText(/Nothing is sent until you ask/)).toBeInTheDocument();
    expect(await screen.findByText("No check run yet.")).toBeInTheDocument();
  });

  it("says the title once — the card no longer repeats it", () => {
    renderWithProviders(<UpdatesPage />);

    expect(screen.getAllByText("EDDI Updates")).toHaveLength(1);
  });
});

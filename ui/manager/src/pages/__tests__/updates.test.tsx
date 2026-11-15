import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { UpdatesPage } from "@/pages/updates";

describe("UpdatesPage", () => {
  it("titles the screen and hosts the check", async () => {
    renderWithProviders(<UpdatesPage />);

    const title = screen.getByTestId("updates-page-title");
    expect(title).toHaveTextContent("EDDI Updates");
    // The page owns the heading now that the card has none — so this has to be
    // the h1, or the screen has no top-level heading at all (WCAG 2.4.6).
    expect(title.tagName).toBe("H1");
    expect(await screen.findByTestId("update-check-card")).toBeInTheDocument();
  });

  it("states the check's privacy contract on the page itself", async () => {
    // The page is the whole context an operator has before pressing the button,
    // so that the check runs by default, and that it can be turned off, has to
    // be readable here. With the server's switch off, nothing has run yet.
    server.use(
      http.get("*/administration/update-check", () =>
        HttpResponse.json({ enabled: false, lockedByConfig: false, uiEnabled: false }),
      ),
    );
    renderWithProviders(<UpdatesPage />);

    expect(screen.getByTestId("updates-page-description")).toHaveTextContent(
      /runs by default/,
    );
    expect(await screen.findByTestId("update-check-result")).toHaveTextContent(
      "No check run yet.",
    );
  });

  it("says the title once — the card no longer repeats it", async () => {
    renderWithProviders(<UpdatesPage />);

    const card = await screen.findByTestId("update-check-card");
    expect(within(card).queryByText("EDDI Updates")).not.toBeInTheDocument();
  });
});

import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { UpdateBanner } from "@/components/layout/update-banner";
import { UpdateCheckCard } from "@/components/shared/update-check-card";
import { AUTO_UPDATE_CHECK_KEY } from "@/hooks/use-update-check";

const LATEST_URL = "https://api.github.com/repos/labsai/EDDI/releases/latest";

describe("UpdateBanner", () => {
  it("stays silent when the automatic check is off", async () => {
    renderWithProviders(<UpdateBanner />);
    // Give the disabled query every chance to fire before asserting it did not.
    await waitFor(() => expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument());
  });

  it("announces a newer release once the automatic check is on", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    renderWithProviders(<UpdateBanner />);

    const banner = await screen.findByTestId("update-banner");
    expect(banner).toHaveTextContent("EDDI 9.9.9 is available");
    expect(banner).toHaveTextContent("You are running 6.0.0-demo.");
  });

  it("stays silent when the deployment is already current", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    server.use(http.get(LATEST_URL, () => HttpResponse.json({ tag_name: "6.0.0-demo" })));

    renderWithProviders(<UpdateBanner />);

    await waitFor(() => expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument());
  });

  it("stays silent when the check fails", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    server.use(http.get(LATEST_URL, () => HttpResponse.error()));

    renderWithProviders(<UpdateBanner />);

    await waitFor(() => expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument());
  });

  it("announces itself, since it appears long after the page has settled", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    renderWithProviders(<UpdateBanner />);

    const banner = await screen.findByTestId("update-banner");
    // Nothing moves focus here, so without a live region a screen reader user
    // is simply never told an update exists.
    expect(banner).toHaveAttribute("role", "status");
    expect(banner).toHaveAttribute("aria-live", "polite");
  });

  it("can be dismissed", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    const user = userEvent.setup();
    renderWithProviders(<UpdateBanner />);

    await screen.findByTestId("update-banner");
    await user.click(screen.getByTestId("update-banner-dismiss"));

    expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument();
  });

  it("appears as soon as the checkbox is ticked, without a reload", async () => {
    const user = userEvent.setup();
    // Banner and card sit in separate subtrees, exactly as in the app layout.
    renderWithProviders(
      <>
        <UpdateBanner />
        <UpdateCheckCard />
      </>,
    );

    expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument();

    await user.click(await screen.findByTestId("update-auto-check"));

    expect(await screen.findByTestId("update-banner")).toBeInTheDocument();
  });

  it.each(["/manage/updates", "/manage/updates/"])(
    "stays out of the way on the page it points at (%s)",
    async (route) => {
      localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
      // The card is rendered alongside so the check's completion is observable:
      // asserting the banner's absence against a still-pending query would pass
      // no matter what the route rule did, since the banner is absent while the
      // status is "unknown" anyway.
      renderWithProviders(
        <>
          <UpdateBanner />
          <UpdateCheckCard />
        </>,
        { initialRoute: route },
      );

      // Same query, same cache: once the card names the release, the banner has
      // everything it needs and is silent only because of where we are.
      expect(await screen.findByText(/EDDI 9\.9\.9 is available/)).toBeInTheDocument();
      expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument();
    },
  );

  it("still announces itself everywhere else, so this is a route rule and not a mute", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    renderWithProviders(<UpdateBanner />, { initialRoute: "/manage/agents" });

    expect(await screen.findByTestId("update-banner")).toBeInTheDocument();
  });

  it("links to the Updates page and to the release notes", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    renderWithProviders(<UpdateBanner />);

    const banner = await screen.findByTestId("update-banner");
    expect(within(banner).getByRole("link", { name: /How to update/ })).toHaveAttribute(
      "href",
      "/manage/updates",
    );
    expect(within(banner).getByTestId("update-banner-notes-link")).toHaveAttribute(
      "href",
      "https://github.com/labsai/EDDI/releases/tag/9.9.9",
    );
  });
});

import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { UpdateCheckCard } from "@/components/shared/update-check-card";
import { AUTO_UPDATE_CHECK_KEY } from "@/hooks/use-update-check";

const LATEST_URL = "https://api.github.com/repos/labsai/EDDI/releases/latest";

/** Count GitHub hits so "nothing is sent by default" can be asserted, not assumed. */
function countGithubCalls(response: () => Response) {
  const calls = vi.fn();
  server.use(
    http.get(LATEST_URL, () => {
      calls();
      return response();
    }),
  );
  return calls;
}

const release = (version: string) =>
  HttpResponse.json({
    tag_name: version,
    name: version,
    html_url: `https://github.com/labsai/EDDI/releases/tag/${version}`,
    published_at: "2026-08-01T10:00:00Z",
  });

describe("UpdateCheckCard", () => {
  it("checks nothing until asked", async () => {
    const calls = countGithubCalls(() => release("9.9.9"));
    renderWithProviders(<UpdateCheckCard />);

    expect(await screen.findByText("No check run yet.")).toBeInTheDocument();
    // The installed version still resolves — that is a same-origin read.
    expect(await screen.findByTestId("update-installed-version")).toHaveTextContent("6.0.0-demo");
    expect(screen.getByTestId("update-latest-version")).toHaveTextContent("—");
    expect(calls).not.toHaveBeenCalled();
  });

  it("reports an available update and how to get it when the button is pressed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    expect(await screen.findByText("EDDI 9.9.9 is available")).toBeInTheDocument();
    expect(screen.getByTestId("update-latest-version")).toHaveTextContent("9.9.9");

    const instructions = screen.getByTestId("update-instructions");
    expect(instructions).toHaveTextContent("eddi update");
    expect(instructions).toHaveTextContent(
      "docker compose --env-file .env -f docker-compose.yml pull",
    );
  });

  it("says so when the installed version is already the latest", async () => {
    server.use(http.get(LATEST_URL, () => release("6.0.0-demo")));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    expect(
      await screen.findByText("You are running the latest release (6.0.0-demo)."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("update-instructions")).not.toBeInTheDocument();
  });

  it("flags a deployment that is ahead of the latest release", async () => {
    server.use(http.get(LATEST_URL, () => release("1.0.0")));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    expect(
      await screen.findByText(/newer than the latest release \(1\.0\.0\)/),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("update-instructions")).not.toBeInTheDocument();
  });

  it("distinguishes a rate limit from other failures", async () => {
    server.use(
      http.get(LATEST_URL, () =>
        HttpResponse.json({}, { status: 403, headers: { "x-ratelimit-remaining": "0" } }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    expect(await screen.findByText(/rate limit/i)).toBeInTheDocument();
  });

  it("surfaces an unreachable GitHub without claiming the deployment is current", async () => {
    server.use(http.get(LATEST_URL, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    expect(await screen.findByText(/Could not reach api\.github\.com/)).toBeInTheDocument();
    expect(screen.queryByText(/latest release/i)).not.toHaveTextContent("You are running");
  });

  it("starts with the automatic check off", async () => {
    renderWithProviders(<UpdateCheckCard />);
    expect(await screen.findByTestId("update-auto-check")).not.toBeChecked();
    expect(localStorage.getItem(AUTO_UPDATE_CHECK_KEY)).toBeNull();
  });

  it("persists the automatic check preference and checks immediately when enabled", async () => {
    const calls = countGithubCalls(() => release("9.9.9"));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-auto-check"));

    expect(screen.getByTestId("update-auto-check")).toBeChecked();
    expect(localStorage.getItem(AUTO_UPDATE_CHECK_KEY)).toBe("true");
    await waitFor(() => expect(calls).toHaveBeenCalledTimes(1));
  });

  it("checks once when the preference is already on, not once per render", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    const calls = countGithubCalls(() => release("9.9.9"));
    const { rerender } = renderWithProviders(<UpdateCheckCard />);

    expect(await screen.findByText("EDDI 9.9.9 is available")).toBeInTheDocument();
    rerender(<UpdateCheckCard />);

    await waitFor(() => expect(calls).toHaveBeenCalledTimes(1));
  });

  it("turning the preference off stops later automatic checks", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    const checkbox = await screen.findByTestId("update-auto-check");
    await waitFor(() => expect(checkbox).toBeChecked());

    await user.click(checkbox);
    expect(checkbox).not.toBeChecked();
    expect(localStorage.getItem(AUTO_UPDATE_CHECK_KEY)).toBe("false");
  });

  it("links to the release when one is known, and to all releases otherwise", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    const link = await screen.findByTestId("update-release-notes-link");
    expect(link).toHaveAttribute("href", "https://github.com/labsai/EDDI/releases");

    await user.click(screen.getByTestId("update-check-now"));

    await waitFor(() =>
      expect(screen.getByTestId("update-release-notes-link")).toHaveAttribute(
        "href",
        "https://github.com/labsai/EDDI/releases/tag/9.9.9",
      ),
    );
  });
});

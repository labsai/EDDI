import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { UpdateCheckCard } from "@/components/shared/update-check-card";
import { AUTO_UPDATE_CHECK_KEY } from "@/hooks/use-update-check";

const LATEST_URL = "https://api.github.com/repos/labsai/EDDI/releases/latest";

/** Count outbound hits so "nothing is sent by default" can be asserted, not assumed. */
function countCalls(url: string, response: () => Response) {
  const calls = vi.fn();
  server.use(
    http.get(url, () => {
      calls();
      return response();
    }),
  );
  return calls;
}

const release = (version: string, body = "## Highlights\n\n- Mock release note one") =>
  HttpResponse.json({
    tag_name: version,
    name: version,
    html_url: `https://github.com/labsai/EDDI/releases/tag/${version}`,
    published_at: "2026-08-01T10:00:00Z",
    body,
  });


describe("UpdateCheckCard", () => {
  it("checks nothing until asked", async () => {
    const github = countCalls(LATEST_URL, () => release("9.9.9"));
    renderWithProviders(<UpdateCheckCard />);

    expect(await screen.findByText("No check run yet.")).toBeInTheDocument();
    // The installed version still resolves — that is a same-origin read.
    expect(await screen.findByTestId("update-installed-version")).toHaveTextContent("6.0.0-demo");
    expect(screen.getByTestId("update-latest-version")).toHaveTextContent("—");
    expect(screen.getByTestId("update-image-version")).toHaveTextContent("—");
    expect(github).not.toHaveBeenCalled();
  });

  it("contacts GitHub and nothing else", async () => {
    // The guard that keeps a relay from creeping back in. MSW runs with
    // `onUnhandledRequest: "error"`, so a request to any other host fails the
    // test on its own — this asserts the positive half: one host, one call.
    const seen: string[] = [];
    server.events.on("request:start", ({ request }) => {
      const { host } = new URL(request.url);
      if (host !== "localhost:3000") seen.push(host);
    });

    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);
    await user.click(await screen.findByTestId("update-check-now"));
    await screen.findByText("EDDI 9.9.9 is available");

    expect([...new Set(seen)]).toEqual(["api.github.com"]);
    server.events.removeAllListeners();
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

  it("shows the Docker image separately, derived from the release, with its own link", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    const cell = await screen.findByTestId("update-image-version");
    await waitFor(() => expect(cell).toHaveTextContent("9.9.9"));

    const link = within(cell).getByRole("link");
    expect(link).toHaveAttribute("href", "https://hub.docker.com/r/labsai/eddi/tags?name=9.9.9");
    // The full pullable reference, so the claim is inspectable without a click.
    expect(link).toHaveAttribute("title", "labsai/eddi:9.9.9");
  });

  it("shows no image until a release is known", async () => {
    renderWithProviders(<UpdateCheckCard />);

    const cell = await screen.findByTestId("update-image-version");
    expect(cell).toHaveTextContent("—");
    expect(within(cell).queryByRole("link")).not.toBeInTheDocument();
  });

  it("claims no image when the release lookup failed", async () => {
    server.use(http.get(LATEST_URL, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));
    await screen.findByText(/Could not reach api\.github\.com/);

    const cell = screen.getByTestId("update-image-version");
    expect(cell).toHaveTextContent("—");
    expect(within(cell).queryByRole("link")).not.toBeInTheDocument();
  });

  it("renders the publication date unambiguously, on its own line", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    // A named month, because this app ships both month-first and day-first
    // locales and a numeric date means different days in each.
    const published = await screen.findByText(/^Published /);
    expect(published).toHaveTextContent("Published Jan 15, 2026");
    expect(published).not.toHaveTextContent("You are running");
  });

  it("copies a command to the clipboard and confirms it did", async () => {
    // user-event installs its own clipboard stub in setup(), so this asserts
    // through the real API rather than a hand-rolled spy it would overwrite.
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));
    await screen.findByTestId("update-instructions");

    const [cliCopy, manualCopy] = screen.getAllByTestId("update-copy-command");
    expect(cliCopy).toHaveAccessibleName("Copy");

    await user.click(cliCopy!);
    expect(await navigator.clipboard.readText()).toBe("eddi update");
    await waitFor(() => expect(cliCopy).toHaveAccessibleName("Copied"));

    // The manual block copies all three lines, not just the one under the cursor.
    await user.click(manualCopy!);
    expect(await navigator.clipboard.readText()).toBe(
      [
        "cd ~/.eddi",
        "docker compose --env-file .env -f docker-compose.yml pull",
        "docker compose --env-file .env -f docker-compose.yml up -d",
      ].join("\n"),
    );
  });

  it("shows only the opening of very long release notes, and links out for the rest", async () => {
    const long = ["The summary paragraph.", ...Array.from({ length: 200 }, (_, i) => `Section ${i} body text that goes on.`)].join("\n\n");
    server.use(http.get(LATEST_URL, () => release("9.9.9", long)));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));
    await user.click(await screen.findByTestId("update-release-notes-toggle"));

    const notes = screen.getByTestId("update-release-notes");
    expect(notes).toHaveTextContent("The summary paragraph.");
    expect(notes).not.toHaveTextContent("Section 199");
    expect(screen.getByTestId("update-full-notes-link")).toBeInTheDocument();
  });

  it("keeps release notes collapsed until asked, then renders them as markdown", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));

    const toggle = await screen.findByTestId("update-release-notes-toggle");
    expect(toggle).toHaveTextContent("What's new in 9.9.9");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Mock release note one")).not.toBeInTheDocument();

    await user.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    // Rendered, not printed raw: the "## Highlights" line becomes a heading.
    expect(screen.getByRole("heading", { name: "Highlights" })).toBeInTheDocument();
    expect(screen.getByText("Mock release note one")).toBeInTheDocument();
    expect(screen.getByTestId("update-full-notes-link")).toHaveAttribute(
      "href",
      "https://github.com/labsai/EDDI/releases/tag/9.9.9",
    );
  });

  it("says so when a release carries no notes", async () => {
    server.use(http.get(LATEST_URL, () => release("9.9.9", "")));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));
    await user.click(await screen.findByTestId("update-release-notes-toggle"));

    expect(screen.getByText("This release has no notes.")).toBeInTheDocument();
  });

  it("escapes raw HTML in release notes rather than injecting it", async () => {
    server.use(
      http.get(LATEST_URL, () =>
        release("9.9.9", 'Careful: <img src=x onerror="alert(1)"> and <b>bold</b>'),
      ),
    );
    const user = userEvent.setup();
    const { container } = renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-check-now"));
    await user.click(await screen.findByTestId("update-release-notes-toggle"));

    const notes = screen.getByTestId("update-release-notes");
    expect(within(notes).queryByRole("img")).not.toBeInTheDocument();
    expect(container.querySelector("b")).toBeNull();
    expect(notes).toHaveTextContent("<b>bold</b>");
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
    // A failed check is not evidence of anything — no verdict, no instructions.
    expect(screen.queryByText(/You are running the latest release/)).not.toBeInTheDocument();
    expect(screen.queryByText(/is available$/)).not.toBeInTheDocument();
    expect(screen.queryByTestId("update-instructions")).not.toBeInTheDocument();
  });

  it("starts with the automatic check off", async () => {
    renderWithProviders(<UpdateCheckCard />);
    expect(await screen.findByTestId("update-auto-check")).not.toBeChecked();
    expect(localStorage.getItem(AUTO_UPDATE_CHECK_KEY)).toBeNull();
  });

  it("persists the automatic check preference and checks immediately when enabled", async () => {
    const github = countCalls(LATEST_URL, () => release("9.9.9"));
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    await user.click(await screen.findByTestId("update-auto-check"));

    expect(screen.getByTestId("update-auto-check")).toBeChecked();
    expect(localStorage.getItem(AUTO_UPDATE_CHECK_KEY)).toBe("true");
    await waitFor(() => expect(github).toHaveBeenCalledTimes(1));
  });

  it("checks once when the preference is already on, not once per render", async () => {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    const github = countCalls(LATEST_URL, () => release("9.9.9"));
    const { rerender } = renderWithProviders(<UpdateCheckCard />);

    expect(await screen.findByText("EDDI 9.9.9 is available")).toBeInTheDocument();
    rerender(<UpdateCheckCard />);

    await waitFor(() => expect(github).toHaveBeenCalledTimes(1));
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

  it("links to both hosts before a check, and narrows to the exact release and tag after", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateCheckCard />);

    expect(await screen.findByTestId("update-release-notes-link")).toHaveAttribute(
      "href",
      "https://github.com/labsai/EDDI/releases",
    );
    expect(screen.getByTestId("update-docker-hub-link")).toHaveAttribute(
      "href",
      "https://hub.docker.com/r/labsai/eddi/tags",
    );

    await user.click(screen.getByTestId("update-check-now"));

    await waitFor(() =>
      expect(screen.getByTestId("update-release-notes-link")).toHaveAttribute(
        "href",
        "https://github.com/labsai/EDDI/releases/tag/9.9.9",
      ),
    );
    await waitFor(() =>
      expect(screen.getByTestId("update-docker-hub-link")).toHaveAttribute(
        "href",
        "https://hub.docker.com/r/labsai/eddi/tags?name=9.9.9",
      ),
    );
  });
});

import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { OrphansPage } from "@/pages/orphans";

vi.mock("@/hooks/use-onboarding", () => ({
  useOnboarding: () => vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));

describe("OrphansPage — failures", () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear();
  });

  // Regression: a failed scan fell back to the pre-scan prompt, as if nothing
  // had been asked.
  it("shows a failed scan with the backend's reason", async () => {
    server.use(
      http.get("*/administration/orphans", () =>
        HttpResponse.json({ message: "Workflow store unavailable" }, { status: 500 }),
      ),
    );
    renderWithProviders(<OrphansPage />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    const error = await screen.findByTestId("orphans-scan-error");
    expect(error).toHaveTextContent("Workflow store unavailable");
    expect(screen.queryByTestId("pre-scan-state")).not.toBeInTheDocument();
  });

  // Regression: the backend's 409 `incomplete_scan` was reported as a bare
  // "Error".
  it("explains a purge refused because the reference scan was incomplete", async () => {
    server.use(
      http.delete("*/administration/orphans", () =>
        HttpResponse.json(
          {
            error: "incomplete_scan",
            message: "Refusing to purge orphans: the reference scan was incomplete (store timeout).",
          },
          { status: 409 },
        ),
      ),
    );
    renderWithProviders(<OrphansPage />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));
    await user.click(await screen.findByTestId("purge-button"));
    await user.click(screen.getByTestId("confirm-purge-button"));

    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1));
    const [title, options] = vi.mocked(toast.error).mock.calls[0]!;
    expect(title).toMatch(/could not finish checking/i);
    expect((options as { description?: string }).description).toContain("store timeout");
  });
});
